package cn.personal.worktimesync;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class BackupReader {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static class Category {
        String title, parent;
        Category(String title, String parent) { this.title=title; this.parent=parent; }
    }
    public static JSONObject read(File source, long from, long until) throws Exception {
        if (until <= from) throw new Exception("统计时间范围无效");
        SQLiteDatabase db = SQLiteDatabase.openDatabase(source.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try(Cursor check=db.rawQuery("PRAGMA quick_check", null)) {
                if(!check.moveToFirst() || !"ok".equals(check.getString(0))) throw new Exception("备份完整性检查失败");
            }
            Map<String,Category> types=new HashMap<>();
            try(Cursor cur=db.rawQuery("SELECT LOCAL_ID,TITLE,LOCAL_PARENT_ID FROM EVENT_TYPE",null)) {
                while(cur.moveToNext()) types.put(cur.getString(0),new Category(cur.getString(1),cur.getString(2)));
            }
            TreeMap<String,Long> totals=new TreeMap<>();
            JSONArray records=new JSONArray();
            long lastEnd=0, total=0;
            String query="SELECT LOCAL_ID,START_TIME,END_TIME,TYPE_ID,UPDATE_TIME FROM EVENT WHERE START_TIME>0 AND START_TIME<? AND END_TIME>? AND END_TIME>START_TIME ORDER BY START_TIME";
            try(Cursor cur=db.rawQuery(query,new String[]{String.valueOf(until),String.valueOf(from)})) {
                while(cur.moveToNext()) {
                    List<String> path=new ArrayList<>(); Set<String> seen=new HashSet<>(); String id=cur.getString(3);
                    while(types.containsKey(id)) {
                        if(!seen.add(id)) throw new Exception("分类层级存在循环");
                        Category type=types.get(id); path.add(0,type.title); id=type.parent;
                    }
                    if(path.isEmpty() || !"工作".equals(path.get(0))) continue;
                    if(id!=null && !id.isEmpty()) throw new Exception("工作分类存在无法解析的父分类");
                    long start=Math.max(from,cur.getLong(1)), end=Math.min(until,cur.getLong(2));
                    if(start<lastEnd) throw new Exception("工作记录存在重叠，请先核对原记录");
                    lastEnd=end;
                    String category=path.size()>1?path.get(1):"未细分工作";
                    while(start<end) {
                        LocalDate monday=Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                        long partEnd=Math.min(end,monday.plusWeeks(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
                        long duration=partEnd-start; String week=monday.toString();
                        JSONObject record=new JSONObject();
                        record.put("sync_key",cur.getString(0)+":"+week); record.put("source_id",cur.getString(0));
                        record.put("week",week);record.put("category",category);record.put("category_path",String.join(" / ",path));
                        record.put("start_ms",start);record.put("end_ms",partEnd);record.put("duration_ms",duration);
                        record.put("duration_hours",duration/3600000.0);record.put("source_update_ms",cur.getLong(4));
                        records.put(record);String key=week+" / "+category;totals.put(key,totals.getOrDefault(key,0L)+duration);
                        total+=duration;start=partEnd;
                    }
                }
            }
            JSONObject result=new JSONObject();result.put("schema_version",1);result.put("timezone","Asia/Shanghai");
            result.put("period_start_ms",from);result.put("period_end_ms",until);result.put("record_count",records.length());
            result.put("total_ms",total);result.put("category_totals_ms",new JSONObject(totals));result.put("records",records);
            result.put("weekly_summary", summarize(result));
            return result;
        } finally { db.close(); }
    }
    // Upload this summary only. Individual records remain local for validation.
    public static JSONObject summarize(JSONObject local) throws Exception {
        JSONObject groups=local.getJSONObject("category_totals_ms");
        TreeMap<String,Long> weeks=new TreeMap<>();
        Iterator<String> keys=groups.keys();
        while(keys.hasNext()) {
            String key=keys.next(), week=key.substring(0,10);
            weeks.put(week,weeks.getOrDefault(week,0L)+groups.getLong(key));
        }
        JSONArray rows=new JSONArray();
        keys=groups.keys();
        while(keys.hasNext()) {
            String key=keys.next(), week=key.substring(0,10), category=key.substring(13);
            long duration=groups.getLong(key), total=weeks.get(week);
            JSONObject row=new JSONObject();
            row.put("sync_key",week+" / "+category);row.put("week",week);row.put("category",category);
            row.put("duration_ms",duration);row.put("duration_hours",duration/3600000.0);
            row.put("work_total_hours",total/3600000.0);row.put("work_share",total>0?(double)duration/total:0);
            rows.put(row);
        }
        JSONObject summary=new JSONObject();summary.put("schema_version",1);summary.put("timezone","Asia/Shanghai");
        summary.put("period_start_ms",local.getLong("period_start_ms"));summary.put("period_end_ms",local.getLong("period_end_ms"));
        summary.put("rows",rows);
        return summary;
    }
}
