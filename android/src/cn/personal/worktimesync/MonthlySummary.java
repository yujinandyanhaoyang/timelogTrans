package cn.personal.worktimesync;
import org.json.*;
import java.time.*;
import java.util.*;

final class MonthlySummary {
    static JSONObject from(JSONObject local) throws Exception {
        String period=Instant.ofEpochMilli(local.getLong("period_start_ms")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        JSONObject groups=local.getJSONObject("category_totals_ms");TreeMap<String,Long> categories=new TreeMap<>();
        Iterator<String> keys=groups.keys();long total=0;
        while(keys.hasNext()){String key=keys.next(),category=key.substring(13);long duration=groups.getLong(key);categories.put(category,categories.getOrDefault(category,0L)+duration);total+=duration;}
        JSONArray rows=new JSONArray();
        for(Map.Entry<String,Long> entry:categories.entrySet()){
            long duration=entry.getValue();rows.put(new JSONObject().put("week",period).put("category",entry.getKey()).put("sync_key",period+" / "+entry.getKey())
                .put("duration_ms",duration).put("duration_hours",duration/3600000.0).put("work_total_hours",total/3600000.0).put("work_share",total>0?(double)duration/total:0));
        }
        return new JSONObject().put("schema_version",1).put("timezone","Asia/Shanghai").put("period_start_ms",local.getLong("period_start_ms")).put("period_end_ms",local.getLong("period_end_ms")).put("rows",rows);
    }
}
