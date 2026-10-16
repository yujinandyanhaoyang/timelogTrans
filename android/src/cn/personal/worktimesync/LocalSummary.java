package cn.personal.worktimesync;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;

final class LocalSummary {
    static final Object LOCK=new Object();
    static Uri latest(Context c,Uri tree) throws Exception {
        BackupLocator.Entry latest=BackupLocator.latest(DocumentsContract.getTreeDocumentId(tree),id->{
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
            String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE};
            java.util.List<BackupLocator.Entry> entries=new java.util.ArrayList<>();
            try(android.database.Cursor cursor=c.getContentResolver().query(children,columns,null,null,null)) {
                if(cursor==null)throw new Exception("无法读取授权目录，请重新授权");
                while(cursor.moveToNext()){
                    if(entries.size()>=1000)throw new Exception("目录内容过多，请授权TimeLogger里的账号目录");
                    entries.add(new BackupLocator.Entry(cursor.getString(0),cursor.getString(1),DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(3)),cursor.getLong(2)));
                }
            }
            return entries;
        });
        return DocumentsContract.buildDocumentUriUsingTree(tree,latest.id);
    }
    static long modified(Context c,Uri uri) throws Exception {
        try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()&&cursor.getLong(0)>0)return cursor.getLong(0);
        }
        throw new Exception("无法确认备份更新时间，请检查目录授权");
    }
    static JSONObject current(Context c,long now) throws Exception {
        android.content.SharedPreferences prefs=c.getSharedPreferences("MainActivity",0);
        String source=prefs.getString("source","");
        if(source.isEmpty()||!prefs.getBoolean("tree",false))throw new Exception("请授权自动备份目录");
        Uri uri=latest(c,Uri.parse(source));long stamp=modified(c,uri);
        File copy=new File(c.getCacheDir(),"current-snapshot.bak");
        try {
            try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(copy)) {
                if(in==null)throw new Exception("备份无法读取，请重新授权");
                byte[] buffer=new byte[65536];int n;long size=0;
                while((n=in.read(buffer))!=-1){size+=n;if(size>128*1024*1024)throw new Exception("备份超过大小限制");out.write(buffer,0,n);}
            }
            LocalDate today=Instant.ofEpochMilli(now).atZone(BackupReader.ZONE).toLocalDate();
            long weekStart=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(BackupReader.ZONE).toInstant().toEpochMilli();
            long monthStart=today.withDayOfMonth(1).atStartOfDay(BackupReader.ZONE).toInstant().toEpochMilli();
            JSONObject week=BackupReader.read(copy,weekStart,now),month=BackupReader.read(copy,monthStart,now);
            if(modified(c,uri)!=stamp)throw new Exception("备份正在更新，请再点击一次上传");
            JSONObject weekly=week.getJSONObject("weekly_summary").put("backup_time_ms",stamp);
            JSONObject monthly=MonthlySummary.from(month).put("backup_time_ms",stamp);
            long recorded=0;org.json.JSONArray records=month.getJSONArray("records");
            for(int i=0;i<records.length();i++)recorded=Math.max(recorded,records.getJSONObject(i).getLong("end_ms"));
            return new JSONObject().put("weekly",weekly).put("monthly",monthly).put("backup_time_ms",stamp)
                .put("week_hours",week.getLong("total_ms")/3600000.0).put("month_hours",month.getLong("total_ms")/3600000.0)
                .put("data_display",recorded>0?Instant.ofEpochMilli(recorded).atZone(BackupReader.ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")):"本月暂无已结束记录")
                .put("backup_display",Instant.ofEpochMilli(stamp).atZone(BackupReader.ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        } finally {copy.delete();}
    }
    static JSONObject read(File file) throws Exception {
        try(InputStream in=new AtomicFile(file).openRead()){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>8*1024*1024)throw new Exception("汇总文件过大");bytes.write(buffer,0,n);}
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }
    static void write(File file,JSONObject value) throws Exception {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(value.toString().getBytes("UTF-8"));atomic.finishWrite(out);}
        catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}
    }
}
