package cn.personal.worktimesync;
import org.json.*;
import java.util.*;

public class FeishuClientTest {
    static class Cloud implements FeishuClient.Transport {
        Map<String,JSONObject> records=new LinkedHashMap<>();int writes;boolean failAfterWrite, corrupt, omitEmptyItems;
        public JSONObject call(String method,String path,JSONObject body) throws Exception {
            if(method.equals("GET")) {
                if(omitEmptyItems&&records.isEmpty())return new JSONObject().put("data",new JSONObject().put("total",0).put("has_more",false));
                JSONArray items=new JSONArray();for(JSONObject row:records.values())items.put(new JSONObject(row.toString()));
                if(corrupt&&items.length()>0)items.getJSONObject(0).getJSONObject("fields").put("时长小时",999);
                return new JSONObject().put("data",new JSONObject().put("items",items).put("has_more",false));
            }
            writes++;JSONObject item=body.getJSONArray("records").getJSONObject(0);
            if(path.endsWith("batch_create")) {
                String id="rec"+records.size();records.put(id,new JSONObject().put("record_id",id).put("fields",item.getJSONObject("fields")));
            } else {
                JSONObject fields=records.get(item.getString("record_id")).getJSONObject("fields");
                Iterator<String> keys=item.getJSONObject("fields").keys();while(keys.hasNext()){String k=keys.next();fields.put(k,item.getJSONObject("fields").get(k));}
            }
            if(failAfterWrite){failAfterWrite=false;throw new Exception("connection lost after commit");}
            return new JSONObject();
        }
    }
    static JSONObject summary(long millis) throws Exception {
        JSONObject row=new JSONObject().put("week","2026-10-05").put("category","开会").put("sync_key","2026-10-05 / 开会")
            .put("duration_ms",millis).put("duration_hours",millis/3600000.0).put("work_total_hours",millis/3600000.0).put("work_share",1.0);
        return new JSONObject().put("period_end_ms",1000).put("rows",new JSONArray().put(row));
    }
    static void require(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        Cloud cloud=new Cloud();FeishuClient client=new FeishuClient(cloud);JSONObject first=summary(3600000);
        Cloud initiallyEmpty=new Cloud();initiallyEmpty.omitEmptyItems=true;
        FeishuClient initiallyEmptyClient=new FeishuClient(initiallyEmpty);
        initiallyEmptyClient.sync(first,100,200);initiallyEmptyClient.sync(first,100,201);
        require(initiallyEmpty.records.size()==1);
        Cloud incomplete=new Cloud(){public JSONObject call(String method,String path,JSONObject body) throws Exception {
            if(method.equals("GET"))return new JSONObject().put("data",new JSONObject().put("total",1).put("has_more",false));
            return super.call(method,path,body);
        }};
        try{new FeishuClient(incomplete).sync(first,100,200);throw new AssertionError();}catch(Exception expected){}require(incomplete.writes==0);
        client.sync(first,100,200);client.sync(first,100,201);require(cloud.records.size()==1);
        client.sync(summary(7200000),101,202);require(cloud.records.values().iterator().next().getJSONObject("fields").getDouble("时长小时")==2);
        int before=cloud.writes;try{client.sync(first,100,203);throw new AssertionError();}catch(Exception expected){}require(before==cloud.writes);
        Cloud lost=new Cloud();lost.failAfterWrite=true;FeishuClient retry=new FeishuClient(lost);
        try{retry.sync(first,100,200);throw new AssertionError();}catch(Exception expected){}
        retry.sync(first,100,201);require(lost.records.size()==1);
        JSONObject duplicate=new JSONObject(first.toString());duplicate.getJSONArray("rows").put(first.getJSONArray("rows").getJSONObject(0));
        before=cloud.writes;try{client.sync(duplicate,102,203);throw new AssertionError();}catch(Exception expected){}require(before==cloud.writes);
        Cloud broken=new Cloud();broken.corrupt=true;
        try{new FeishuClient(broken).sync(first,100,200);throw new AssertionError();}catch(Exception expected){}
        JSONObject earlier=summary(3600000).put("period_end_ms",999);before=cloud.writes;
        try{client.sync(earlier,102,204);throw new AssertionError();}catch(FeishuClient.StaleSummaryException expected){}require(before==cloud.writes);
        // An empty current week must not clear another week's history.
        Cloud emptyWeek=new Cloud();FeishuClient emptyClient=new FeishuClient(emptyWeek);
        emptyClient.sync(first,100,200);
        JSONObject prior=summary(7200000);JSONObject priorRow=prior.getJSONArray("rows").getJSONObject(0);
        priorRow.put("week","2026-09-28").put("sync_key","2026-09-28 / 开会");emptyClient.sync(prior,100,201);
        JSONObject empty=new JSONObject().put("period_start_ms",java.time.Instant.parse("2026-10-04T16:00:00Z").toEpochMilli())
            .put("period_end_ms",2000).put("rows",new JSONArray());
        require(emptyClient.sync(empty,101,202)==0);
        for(JSONObject row:emptyWeek.records.values()) {
            JSONObject fields=row.getJSONObject("fields");
            require(fields.getDouble("时长小时")==(fields.getString("周期起始日期").equals("2026-10-05")?0:2));
        }
        // Week and month may begin on the same day; they must never overwrite each other.
        Cloud shared=new Cloud();FeishuClient weekly=new FeishuClient(shared),monthly=new FeishuClient(shared,true);
        weekly.sync(first,100,200);monthly.sync(summary(7200000),100,201);
        require(shared.records.size()==2);
        weekly.sync(empty,101,202);
        for(JSONObject row:shared.records.values()) {
            JSONObject fields=row.getJSONObject("fields");
            require(fields.getDouble("时长小时")== (fields.getString("周期类型").equals("周")?0:2));
        }
        monthly.sync(summary(10800000),102,203);monthly.sync(summary(10800000),102,204);
        require(shared.records.size()==2);
        System.out.println("PASS: shared table separates week/month keys, clearing and retries even for identical period dates");
        Cloud replacement=new Cloud();FeishuClient replace=new FeishuClient(replacement);
        JSONObject two=summary(3600000);JSONObject meeting=two.getJSONArray("rows").getJSONObject(0);
        meeting.put("work_total_hours",2).put("work_share",0.5);
        two.getJSONArray("rows").put(new JSONObject(meeting.toString()).put("category","测试基建").put("sync_key","2026-10-05 / 测试基建"));
        replace.sync(two,100,200);replace.sync(summary(7200000),101,201);replace.sync(summary(7200000),101,202);
        require(replacement.records.size()==2);
        for(JSONObject record:replacement.records.values()) {
            JSONObject f=record.getJSONObject("fields");boolean kept=f.getString("工作板块").equals("开会");
            require(f.getDouble("时长小时")== (kept?2:0));require(f.getDouble("占工作时间百分比")== (kept?1:0));require(f.getDouble("周期总时长小时")==2);
        }
        int previousWrites=replacement.writes;replace.validateConnection();require(previousWrites==replacement.writes);
        System.out.println("PASS: nonempty weekly replacement zeroes removed categories, recalculates shares, retry never accumulates; connection validation is read-only");
        replace.sync(prior,200,300);new FeishuClient(replacement,true).sync(first,200,300);
        JSONObject real=summary(3600000).put("period_end_ms",999);int writes=replacement.writes;
        try{replace.sync(real,50,400);throw new AssertionError();}catch(FeishuClient.StaleSummaryException expected){}require(replacement.writes==writes);
        replace.sync(real,50,400,true);replace.sync(real,50,401);require(replacement.records.size()==4);
        for(JSONObject record:replacement.records.values()){
            JSONObject f=record.getJSONObject("fields");
            if(f.getString("周期类型").equals("月")){require(f.getDouble("时长小时")==1&&f.getLong("备份时间")==200);continue;}
            if(f.getString("周期起始日期").equals("2026-09-28")){require(f.getDouble("时长小时")==2&&f.getLong("备份时间")==200);continue;}
            require(f.getLong("备份时间")==50);require(f.getDouble("时长小时")== (f.getString("工作板块").equals("开会")?1:0));
        }
        System.out.println("PASS: explicit device-transfer replacement accepts older timestamps only for the approved period; default guard and later normal retries remain active");
        System.out.println("PASS: duplicate retry, updates, stale rejection, uncertain write recovery, invalid summary rejection, readback mismatch");
        System.out.println("PASS: empty current week clears only its own period; prior-week history stays unchanged");
    }
}
