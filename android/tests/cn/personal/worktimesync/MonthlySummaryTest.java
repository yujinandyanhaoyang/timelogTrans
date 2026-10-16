package cn.personal.worktimesync;
import org.json.*;
import java.time.Instant;
public class MonthlySummaryTest {
    public static void main(String[] args) throws Exception {
        JSONObject local=new JSONObject().put("period_start_ms",Instant.parse("2026-09-30T16:00:00Z").toEpochMilli()).put("period_end_ms",Instant.parse("2026-10-11T15:00:00Z").toEpochMilli())
            .put("category_totals_ms",new JSONObject().put("2026-09-28 / 开会",3600000).put("2026-10-05 / 开会",7200000).put("2026-10-05 / 基建",3600000));
        JSONObject result=MonthlySummary.from(local);JSONArray rows=result.getJSONArray("rows");
        if(rows.length()!=2)throw new AssertionError();
        double sum=0,shares=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.getString("week").equals("2026-10-01")||row.getDouble("work_total_hours")!=4)throw new AssertionError();sum+=row.getDouble("duration_hours");shares+=row.getDouble("work_share");}
        if(sum!=4||shares!=1||result.has("records"))throw new AssertionError();
        System.out.println("PASS: month merges week/category buckets, calendar-month key, total/share and no raw records");
    }
}
