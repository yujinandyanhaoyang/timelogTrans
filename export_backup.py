"""只读提取时间日志备份中的工作记录；不上传、不修改备份。"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import sqlite3

TZ = dt.timezone(dt.timedelta(hours=8))


def stamp(value):
    parsed = dt.datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=TZ)
    return int(parsed.timestamp() * 1000)


def extract(source, start, end):
    if end <= start:
        raise ValueError('结束时间必须晚于开始时间')
    source = Path(source).resolve()
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    connection = sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    try:
        if connection.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('备份完整性检查失败')
        types = {r['LOCAL_ID']: dict(r) for r in connection.execute('SELECT * FROM EVENT_TYPE')}
        records = []
        for event in connection.execute(
            'SELECT * FROM EVENT WHERE START_TIME > 0 AND START_TIME < ? '
            'AND END_TIME > ? AND END_TIME > START_TIME ORDER BY START_TIME', (end, start)
        ):
            path, visited, type_id = [], set(), event['TYPE_ID']
            while type_id in types:
                if type_id in visited:
                    raise ValueError('分类层级存在循环')
                visited.add(type_id)
                category = types[type_id]
                path.insert(0, category['TITLE'])
                type_id = category['LOCAL_PARENT_ID']
            if not path or path[0] != '工作':
                continue
            if type_id:
                raise ValueError('工作分类存在无法解析的父分类')
            cursor, finish = max(start, event['START_TIME']), min(end, event['END_TIME'])
            while cursor < finish:
                date = dt.datetime.fromtimestamp(cursor / 1000, TZ)
                monday = (date - dt.timedelta(days=date.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
                boundary = int((monday + dt.timedelta(days=7)).timestamp() * 1000)
                segment_end = min(finish, boundary)
                week = monday.date().isoformat()
                records.append({
                    'sync_key': event['LOCAL_ID'] + ':' + week,
                    'source_id': event['LOCAL_ID'], 'week': week,
                    'category': path[1] if len(path) > 1 else '未细分工作',
                    'category_path': ' / '.join(path),
                    'start_ms': cursor, 'end_ms': segment_end,
                    'duration_ms': segment_end - cursor,
                    'duration_hours': (segment_end - cursor) / 3600000,
                    'source_update_ms': event['UPDATE_TIME'],
                })
                cursor = segment_end
        latest_end = 0
        for row in sorted(records, key=lambda r: r['start_ms']):
            if row['start_ms'] < latest_end:
                raise ValueError('工作记录存在重叠，需要核对后同步，避免重复计时')
            latest_end = row['end_ms']
        keys = [r['sync_key'] for r in records]
        if len(keys) != len(set(keys)):
            raise ValueError('同步键重复')
        totals = {}
        for row in records:
            key = row['week'] + ' / ' + row['category']
            totals[key] = totals.get(key, 0) + row['duration_ms']
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError('读取期间备份发生变化，请使用稳定副本重试')
        return {'schema_version': 1, 'timezone': 'Asia/Shanghai',
                'period_start_ms': start, 'period_end_ms': end, 'source_sha256': digest,
                'record_count': len(records), 'total_ms': sum(totals.values()),
                'category_totals_ms': totals, 'records': records,
                'limits': ['DATA_STATUS含义尚未核实，保留全部有效已结束工作时段；此状态下禁止自动删除云端记录。',
                           '仅包含备份已有记录；不代表手机尚未备份的数据已同步。']}
    finally:
        connection.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup', type=Path)
    parser.add_argument('--from', dest='start', required=True)
    parser.add_argument('--until', dest='end', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = extract(args.backup, stamp(args.start), stamp(args.end))
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'records': result['record_count'], 'hours': result['total_ms']/3600000,
                      'output': str(args.output.resolve())}, ensure_ascii=False))
