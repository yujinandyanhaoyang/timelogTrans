package cn.personal.worktimesync;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView status, setup;
    private Button uploadButton, configButton;
    private RemoteScreen screen;
    private boolean continueUpload,setupFlow;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getSystemService(android.app.job.JobScheduler.class).cancel(1800);
        Intent old=new Intent().setClassName(getPackageName(),getPackageName()+".SyncReceiver").setAction("cn.personal.worktimesync.TICK");
        PendingIntent alarm=PendingIntent.getBroadcast(this,1800,old,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
        if(alarm!=null){getSystemService(AlarmManager.class).cancel(alarm);alarm.cancel();}
        getSharedPreferences("sync_schedule",0).edit().putBoolean("enabled",false).apply();
        screen=new RemoteScreen(this);uploadButton=screen.sync;configButton=screen.settings;status=screen.status;setup=screen.setup;
        uploadButton.setOnClickListener(v->{if(hasFolder()&&hasConnection())upload();else{setupFlow=true;continueUpload=false;advanceSetup();}});
        configButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("设置")
            .setItems(new String[]{"备份目录"+(hasFolder()?" · 已授权":" · 待授权"),"飞书连接"+(hasConnection()?" · 已配置":" · 待配置"),"打开工作时间看板"},(d,which)->{
                setupFlow=false;continueUpload=false;
                if(which==0)chooseFolder();else if(which==1)configure();
                else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(Deployment.DASHBOARD_URL)));
            }).show());
        refreshSetup();
        restoreTotals();
        String saved=getPreferences(0).getString("last_status","");
        status.setText(saved.isEmpty()?"设置只需一次，后续点击同步即可。":saved.startsWith("上传成功并已核对")?"点击「同步本周」，读取最新记录。":saved);
    }
    private boolean hasFolder(){
        String source=getPreferences(0).getString("source","");if(!getPreferences(0).getBoolean("tree",false)||source.isEmpty())return false;
        for(android.content.UriPermission grant:getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&grant.getUri().toString().equals(source))return true;
        return false;
    }
    private boolean hasConnection(){return getSharedPreferences("credentials",0).contains("data");}
    private void refreshSetup(){boolean ready=hasFolder()&&hasConnection();screen.ready(ready);setup.setText(ready?"时间日志已连接 · 飞书已连接":hasFolder()?"还差一步：连接飞书":"离线记录，一键同步到飞书");if(uploadButton.isEnabled())uploadButton.setText(ready?"同步本周":hasFolder()?"连接飞书":"连接时间日志");}
    private void advanceSetup(){
        if(!hasFolder()){status.setText("第1步：授权TimeLogger备份目录，只需一次。");chooseFolder();}
        else if(!hasConnection()){status.setText("第2步：配置飞书连接，只需一次。");configure();}
        else{setupFlow=false;refreshSetup();status.setText("设置完成，点击「同步本周」即可。");}
    }
    private void restoreTotals(){
        java.time.LocalDate now=java.time.LocalDate.now(BackupReader.ZONE);
        String week=now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();
        screen.updatePeriod();
        screen.weekValue.setText(week.equals(getPreferences(0).getString("display_week",""))?getPreferences(0).getString("display_week_hours","—"):"—");
        boolean currentMonth=now.withDayOfMonth(1).toString().equals(getPreferences(0).getString("display_month",""));
        screen.monthValue.setText((currentMonth?getPreferences(0).getString("display_month_hours","—"):"—")+" 小时");
        String meta=getPreferences(0).getString("display_detail","");screen.detail.setText(currentMonth&&!meta.isEmpty()?meta:"以时间日志最新自动备份为准。");
    }
    private void chooseFolder(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        String saved=getPreferences(0).getString("source","");
        Uri initial=saved.isEmpty()?Uri.parse("content://com.android.externalstorage.documents/document/primary:Documents/TimeLogger"):Uri.parse(saved);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI,initial);startActivityForResult(pick,2);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=2||result!=RESULT_OK||data==null||data.getData()==null){continueUpload=false;setupFlow=false;return;}
        Uri selected=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            uploadButton.setEnabled(false);configButton.setEnabled(false);screen.progress("正在检查自动备份目录…");
            worker.execute(()->{
                String error=null;
                try{Uri backup=LocalSummary.latest(this,selected);LocalSummary.modified(this,backup);
                    getPreferences(0).edit().putString("source",selected.toString()).putBoolean("tree",true).commit();
                }catch(Exception e){error="未找到可读取的自动备份。请先在时间日志生成自动备份，再选择 Documents 或下载中的 TimeLogger 目录。";}
                final String failure=error;runOnUiThread(()->{screen.finishProgress();uploadButton.setEnabled(true);configButton.setEnabled(true);refreshSetup();
                    if(failure!=null){setupFlow=false;continueUpload=false;status.setText(failure);return;}
                    status.setText("已找到自动备份，目录以后无需重复授权。");if(setupFlow)advanceSetup();else if(continueUpload)upload();});
            });
        }catch(Exception e){continueUpload=false;status.setText("目录授权未完成，请重试。");}
    }
    private void configure(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(32,0,32,0);
        TextView target=new TextView(this);target.setText("连接「个人工作时间统计」\n只需填写一次专用应用的 App Secret。验证通过后加密保存在本机；已有配置可留空保留。");form.addView(target);
        EditText id=new EditText(this);id.setHint("App ID");
        String storedId=Deployment.APP_ID;try{storedId=CredentialStore.read(this).getString("app_id");}catch(Exception ignored){}
        id.setText(storedId);form.addView(id);id.setVisibility(android.view.View.GONE);
        EditText secret=new EditText(this);secret.setHint("App Secret");secret.setInputType(129);form.addView(secret);
        TextView advanced=new TextView(this);advanced.setText("高级设置：更换应用");advanced.setPadding(0,24,0,12);advanced.setOnClickListener(v->{id.setVisibility(android.view.View.VISIBLE);advanced.setVisibility(android.view.View.GONE);});form.addView(advanced);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接飞书").setView(form).setNegativeButton("取消",(d,w)->{continueUpload=false;setupFlow=false;}).setPositiveButton("验证并连接",null).create();
        dialog.setOnCancelListener(d->{continueUpload=false;setupFlow=false;});
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String appId=id.getText().toString().trim(),appSecret=secret.getText().toString().trim();
                if(appSecret.isEmpty()){
                    JSONObject existing=CredentialStore.read(this);
                    if(!appId.equals(existing.getString("app_id")))throw new Exception("更换App ID时请填写对应密钥");
                    appSecret=existing.getString("app_secret");
                }
                if(!appId.startsWith("cli_")||appSecret.length()<8)throw new Exception("请填写完整的应用凭证");
                final String checkedId=appId,checkedSecret=appSecret;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);id.setEnabled(false);secret.setEnabled(false);advanced.setEnabled(false);target.setText("正在验证飞书连接及表格访问权限…");
                worker.execute(()->{String error=null;try{
                    new FeishuClient(new JSONObject().put("app_id",checkedId).put("app_secret",checkedSecret)).validateConnection();
                    CredentialStore.save(this,checkedId,checkedSecret);
                }catch(Exception e){error=e.getMessage();}
                    final String failure=error;runOnUiThread(()->{
                        if(failure!=null){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.setCancelable(true);id.setEnabled(true);secret.setEnabled(true);advanced.setEnabled(true);target.setText("连接未完成："+failure);return;}
                        secret.setText("");dialog.dismiss();refreshSetup();status.setText("连接成功。点击「同步本周」即可，已有同周结果会更新。");if(setupFlow)advanceSetup();else if(continueUpload)upload();
                    });
                });
            }catch(Exception e){target.setText(e.getMessage());}
        });});dialog.show();
    }
    private void upload(){
        upload(null);
    }
    private void upload(JSONObject approvedSnapshot){
        continueUpload=true;
        if(!hasFolder()){chooseFolder();return;}
        if(!hasConnection()){configure();return;}
        continueUpload=false;uploadButton.setEnabled(false);configButton.setEnabled(false);
        uploadButton.setText("正在同步…");screen.progress("1/3 · 读取最新备份并统计");
        status.setText("正在读取最新备份、统计本周并上传…");
        worker.execute(()->{
            String text;
            JSONObject snapshot=null,conflict=null;
            synchronized(LocalSummary.LOCK){
                try{
                    JSONObject bundle=approvedSnapshot==null?LocalSummary.current(this,System.currentTimeMillis()):approvedSnapshot;
                    snapshot=bundle;
                    LocalSummary.write(new File(getFilesDir(),"last-summary.json"),bundle);
                    JSONObject credentials=CredentialStore.read(this),weekly=bundle.getJSONObject("weekly"),monthly=bundle.getJSONObject("monthly");
                    runOnUiThread(()->screen.progress("2/3 · 上传并核对本周统计"));
                    int weekCount=new FeishuClient(credentials).sync(weekly,bundle.getLong("backup_time_ms"),System.currentTimeMillis(),approvedSnapshot!=null);
                    String week=java.time.Instant.ofEpochMilli(weekly.getLong("period_start_ms")).atZone(BackupReader.ZONE).toLocalDate().toString();
                    getPreferences(0).edit().putString("display_week",week).putString("display_week_hours",String.format(Locale.CHINA,"%.2f",bundle.getDouble("week_hours"))).apply();
                    try{
                        runOnUiThread(()->screen.progress("3/3 · 上传并核对本月统计"));
                        new FeishuClient(credentials,true).sync(monthly,bundle.getLong("backup_time_ms"),System.currentTimeMillis(),approvedSnapshot!=null);
                        String month=java.time.Instant.ofEpochMilli(monthly.getLong("period_start_ms")).atZone(BackupReader.ZONE).toLocalDate().toString();
                        getPreferences(0).edit().putString("display_month",month).putString("display_month_hours",String.format(Locale.CHINA,"%.2f",bundle.getDouble("month_hours")))
                            .putString("display_detail","数据记录到 "+bundle.getString("data_display")+"\n备份更新于 "+bundle.getString("backup_display")).apply();
                        text="同步成功 · "+java.time.ZonedDateTime.now(BackupReader.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+"\n本周 "+weekCount+" 个工作板块，飞书周/月结果已核对。";
                    }catch(Exception e){if(e instanceof FeishuClient.StaleSummaryException)throw e;text="本周已上传；本月未完成："+e.getMessage()+"\n再次点击上传即可重试。";}
                }catch(FeishuClient.StaleSummaryException e){conflict=snapshot;text="云端存在时间更晚的汇总，请核对后选择是否使用本机数据替换。";}
                catch(Exception e){text="上传未完成："+e.getMessage()+"\n再次点击上传即可重试。";}
            }
            final String result=text;getPreferences(0).edit().putString("last_status",result).apply();
            final JSONObject replacement=conflict;
            runOnUiThread(()->{screen.finishProgress();status.setText(result);restoreTotals();uploadButton.setEnabled(true);configButton.setEnabled(true);refreshSetup();if(replacement!=null)confirmReplacement(replacement);});
        });
    }
    private void confirmReplacement(JSONObject snapshot){
        try{
            java.time.LocalDate start=java.time.Instant.ofEpochMilli(snapshot.getJSONObject("weekly").getLong("period_start_ms")).atZone(BackupReader.ZONE).toLocalDate();
            String message="本机备份更新："+snapshot.getString("backup_display")+"\n数据记录到："+snapshot.getString("data_display")
                +"\n本周（"+start+" — "+start.plusDays(6)+"）："+String.format(Locale.CHINA,"%.2f",snapshot.getDouble("week_hours"))+"小时"
                +"\n本月："+String.format(Locale.CHINA,"%.2f",snapshot.getDouble("month_hours"))+"小时\n\n如果这是你要正式使用的数据，可替换飞书同周及同月结果。时长较少或已移除的板块也按本机更新；其他周、月保留。";
            new AlertDialog.Builder(this).setTitle("使用本机数据替换？").setMessage(message)
                .setNegativeButton("暂不替换",(d,w)->status.setText("已保留云端结果。本机备份未修改。"))
                .setPositiveButton("确认替换并同步",(d,w)->upload(snapshot)).show();
        }catch(Exception e){status.setText("无法生成替换预览，请重新点击同步。");}
    }
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
    @Override public void onResume(){super.onResume();if(screen!=null){restoreTotals();refreshSetup();}}
}
