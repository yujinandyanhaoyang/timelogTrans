package cn.personal.worktimesync;

import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

final class FeishuClient {
    static class StaleSummaryException extends Exception {StaleSummaryException(String message){super(message);}}
    interface Transport { JSONObject call(String method,String path,JSONObject body) throws Exception; }
    private Transport testTransport;
    private static final String ROOT="https://open.feishu.cn/open-apis";
    private String table="/bitable/v1/apps/"+Deployment.BASE_TOKEN+"/tables/"+Deployment.TABLE_ID+"/records";
    private String periodField="周期起始日期",totalField="周期总时长小时",periodKind="周";
    private String token;
    private JSONObject call(String method,String path,JSONObject body) throws Exception {
        if(testTransport!=null)return testTransport.call(method,path,body);
        HttpURLConnection connection=(HttpURLConnection)new URL(ROOT+path).openConnection();
        connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
            int status=connection.getResponseCode();
            if(status!=200)throw new Exception("飞书接口暂未完成（HTTP "+status+"），可稍后重试");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>8*1024*1024)throw new Exception("飞书返回数据过大");bytes.write(buf,0,n);}}
            JSONObject result=new JSONObject(bytes.toString("UTF-8"));
            if(result.optInt("code",-1)!=0){
                int code=result.optInt("code",-1);String stage=path.startsWith("/auth/")?"应用认证":"表格读写";
                throw new Exception(stage+"失败，错误码 "+code+(code==10014?"，请核对同一应用的App ID和App Secret":"，请检查权限或稍后重试"));
            }
            return result;
        }finally{connection.disconnect();}
    }
    FeishuClient(JSONObject credentials) throws Exception {this(credentials,false);}
    FeishuClient(JSONObject credentials,boolean monthly) throws Exception {
        if(monthly)periodKind="月";
        JSONObject auth=call("POST","/auth/v3/tenant_access_token/internal",credentials);
        token=auth.getString("tenant_access_token");
    }
    FeishuClient(Transport transport){testTransport=transport;}
    FeishuClient(Transport transport,boolean monthly){testTransport=transport;if(monthly)periodKind="月";}
    void validateConnection() throws Exception {records();}
    private static String text(Object value) throws Exception {
        if(value instanceof JSONArray){StringBuilder s=new StringBuilder();JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)s.append(a.getJSONObject(i).optString("text"));return s.toString();}
        return value==null?"":String.valueOf(value);
    }
    private Map<String,JSONObject> records() throws Exception {
        Map<String,JSONObject> rows=new HashMap<>();String cursor="";Set<String> pages=new HashSet<>();
        do {
            if(!pages.add(cursor))throw new Exception("飞书分页异常");
            JSONObject data=call("GET",table+"?page_size=500"+(cursor.isEmpty()?"":"&page_token="+URLEncoder.encode(cursor,"UTF-8")),null).getJSONObject("data");
            JSONArray items=data.optJSONArray("items");
            if(items==null){
                // Empty-table responses may omit items; never treat an incomplete page as empty.
                if((!data.has("items")||data.isNull("items"))&&!data.optBoolean("has_more")&&data.optInt("total",-1)==0)
                    items=new JSONArray();
                else throw new Exception("飞书记录列表不完整，请稍后重试");
            }
            for(int i=0;i<items.length();i++) {
                JSONObject row=items.getJSONObject(i);String key=text(row.getJSONObject("fields").opt("同步键"));
                if(!key.isEmpty()&&rows.put(key,row)!=null)throw new Exception("汇总表有重复同步键，请先核对");
            }
            if(!data.optBoolean("has_more"))break;
            cursor=data.optString("page_token");if(cursor.isEmpty()||pages.size()>100)throw new Exception("飞书分页不完整");
        }while(true);
        return rows;
    }
    // Only one device/process may upload at a time. Always read after an uncertain write before retrying.
    synchronized int sync(JSONObject summary,long backupMs,long syncMs) throws Exception {
        return sync(summary,backupMs,syncMs,false);
    }
    synchronized int sync(JSONObject summary,long backupMs,long syncMs,boolean confirmedReplacement) throws Exception {
        JSONArray input=summary.getJSONArray("rows");
        long cutoff=summary.getLong("period_end_ms");
        Map<String,JSONObject> wanted=new HashMap<>();Map<String,Long> sums=new HashMap<>();
        if(input.length()==0){String period=java.time.Instant.ofEpochMilli(summary.getLong("period_start_ms")).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();sums.put(period,0L);}
        for(int i=0;i<input.length();i++) {
            JSONObject row=input.getJSONObject(i);String week=row.getString("week"),cat=row.getString("category"),localKey=week+" / "+cat,key=periodKind+" / "+localKey;
            if(!localKey.equals(row.getString("sync_key"))||wanted.containsKey(key)||row.getLong("duration_ms")<0)throw new Exception("汇总格式无效");
            sums.put(week,sums.getOrDefault(week,0L)+row.getLong("duration_ms"));wanted.put(key,row);
        }
        for(JSONObject row:wanted.values()) {
            long total=sums.get(row.getString("week"));double share=total>0?(double)row.getLong("duration_ms")/total:0;
            if(Math.abs(row.getDouble("work_share")-share)>1e-9||Math.abs(row.getDouble("work_total_hours")-total/3600000.0)>1e-9||Math.abs(row.getDouble("duration_hours")-row.getLong("duration_ms")/3600000.0)>1e-9)throw new Exception("汇总合计或占比不一致");
        }
        Map<String,JSONObject> old=records();Map<String,JSONObject> expected=new HashMap<>();
        for(Map.Entry<String,JSONObject> entry:wanted.entrySet()) {
            String key=entry.getKey();JSONObject row=entry.getValue(),fields=new JSONObject();
            fields.put("同步键",key);fields.put("周期类型",periodKind);fields.put(periodField,row.getString("week"));fields.put("工作板块",row.getString("category"));
            fields.put("时长小时",row.getDouble("duration_hours"));fields.put("占工作时间百分比",row.getDouble("work_share"));
            fields.put(totalField,row.getDouble("work_total_hours"));fields.put("备份时间",backupMs);fields.put("同步时间",syncMs);
            fields.put("汇总截止时间",cutoff);
            JSONObject existing=old.get(key);
            if(existing!=null&&!confirmedReplacement)checkVersion(existing.getJSONObject("fields"),backupMs,cutoff);
            expected.put(key,fields);
        }
        // A whole-week snapshot also clears previous categories that disappeared, without deleting rows.
        for(Map.Entry<String,JSONObject> entry:old.entrySet()) {
            JSONObject fields=entry.getValue().getJSONObject("fields");String week=text(fields.opt(periodField));
            if(!periodKind.equals(text(fields.opt("周期类型"))))continue;
            if(!sums.containsKey(week))continue;
            if(!confirmedReplacement)checkVersion(fields,backupMs,cutoff);
            if(expected.containsKey(entry.getKey()))continue;
            JSONObject zero=new JSONObject();zero.put("时长小时",0);zero.put("占工作时间百分比",0);
            zero.put(totalField,sums.get(week)/3600000.0);zero.put("备份时间",backupMs);zero.put("同步时间",syncMs);
            zero.put("汇总截止时间",cutoff);
            expected.put(entry.getKey(),zero);
        }
        for(Map.Entry<String,JSONObject> entry:expected.entrySet()) {
            if(Thread.currentThread().isInterrupted())throw new Exception("后台任务已停止，保留汇总等待重试");
            JSONObject item=new JSONObject();item.put("fields",entry.getValue());JSONObject existing=old.get(entry.getKey());
            if(existing!=null)item.put("record_id",existing.getString("record_id"));
            JSONObject body=new JSONObject();body.put("records",new JSONArray().put(item));
            call("POST",table+(existing==null?"/batch_create":"/batch_update"),body);
        }
        Map<String,JSONObject> saved=records();
        for(Map.Entry<String,JSONObject> entry:expected.entrySet()) {
            if(!saved.containsKey(entry.getKey()))throw new Exception("尚未核实写入，请稍后重试");
            JSONObject actual=saved.get(entry.getKey()).getJSONObject("fields"), fields=entry.getValue();
            Iterator<String> names=fields.keys();while(names.hasNext()) {
                String name=names.next();Object value=fields.get(name);
                if(value instanceof Number){double n=actual.optDouble(name,Double.NaN);if(!Double.isFinite(n)||Math.abs(n-((Number)value).doubleValue())>1e-8)throw new Exception("云端数字核对未通过");}
                else if(!text(actual.opt(name)).equals(value))throw new Exception("云端字段核对未通过");
            }
        }
        return wanted.size();
    }
    private static void checkVersion(JSONObject fields,long backupMs,long cutoff) throws Exception {
        if(fields.optLong("备份时间")>backupMs||fields.optLong("汇总截止时间")>cutoff)
            throw new StaleSummaryException("云端已有更新备份或更晚截止时间，本批次不覆盖");
    }
}
