"""Generate public deployment coordinates locally. Never accepts app secrets."""
import json
from pathlib import Path


def generate(root: Path, test=False):
    names = {'base_token': 'BASE_TOKEN', 'table_id': 'TABLE_ID', 'app_id': 'APP_ID', 'dashboard_url': 'DASHBOARD_URL'}
    if test:
        config = dict(base_token='test-base', table_id='test-table', app_id='cli_test', dashboard_url='https://example.invalid')
    else:
        path = root.parent / 'deployment.local.json'
        if not path.exists():
            raise SystemExit('Copy deployment.example.json to deployment.local.json and fill the four public coordinates first.')
        config = json.loads(path.read_text(encoding='utf-8-sig'))
        if set(config) != set(names) or any(not isinstance(v, str) or not v or 'YOUR_' in v for v in config.values()):
            raise SystemExit('Deployment must contain exactly four nonempty public coordinates. Do not put app_secret here.')
    target = root / 'build/generated/cn/personal/worktimesync/Deployment.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    fields = '\n'.join(f'    static final String {name} = {json.dumps(config[key], ensure_ascii=True)};' for key, name in names.items())
    target.write_text('package cn.personal.worktimesync;\nfinal class Deployment {\n' + fields + '\n}\n', encoding='utf-8')
    return target
