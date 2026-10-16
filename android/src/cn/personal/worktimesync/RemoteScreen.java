package cn.personal.worktimesync;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/** Native, single-action home screen. Setup actions live in the settings menu. */
final class RemoteScreen {
    final Button sync,settings;
    final TextView status,setup,weekValue,monthValue,detail,range;
    final ProgressBar progress;
    final TextView progressStep;
    private LinearLayout welcome,weekCard,monthCard;
    private final Activity activity;
    private final int ink=Color.rgb(24,35,54),muted=Color.rgb(110,122,141),blue=Color.rgb(48,100,246);
    int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);return v;}
    private void gap(LinearLayout parent,int size){View v=new View(activity);parent.addView(v,new LinearLayout.LayoutParams(1,dp(size)));}
    private LinearLayout card(LinearLayout parent){
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(22),dp(22),dp(22));box.setBackground(background(Color.WHITE,22));
        parent.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
    }
    RemoteScreen(Activity a){
        activity=a;int page=Color.rgb(245,247,251);
        a.getWindow().setStatusBarColor(page);a.getWindow().setNavigationBarColor(page);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(page);
        LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(24),dp(24),dp(28));scroll.addView(root);a.setContentView(scroll);
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        TextView title=text("工作时间",27,ink);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        settings=new Button(a);settings.setText("设置");settings.setTextSize(14);settings.setTextColor(muted);settings.setAllCaps(false);settings.setMinWidth(0);settings.setMinimumWidth(0);settings.setPadding(0,0,0,0);settings.setBackground(background(Color.WHITE,14));header.addView(settings,new LinearLayout.LayoutParams(dp(60),dp(42)));
        gap(root,10);setup=text("一次设置，以后只需点击同步。",13,muted);root.addView(setup);
        gap(root,28);welcome=card(root);welcome.addView(text("连接一次，以后只需同步",21,ink));gap(welcome,18);
        TextView guide=text("1  连接时间日志\n选择 TimeLogger 自动备份目录，授权后自动读取。\n\n2  连接飞书\n填写一次连接密钥，自动检查是否可用。\n\n3  一键同步\n默认更新本周；重复同步覆盖同周旧结果，同时刷新月度统计。",14,muted);guide.setLineSpacing(dp(5),1);welcome.addView(guide);
        LinearLayout week=card(root);weekCard=week;week.addView(text("本周工作",15,muted));gap(week,14);
        LinearLayout amount=new LinearLayout(a);amount.setGravity(Gravity.BOTTOM);week.addView(amount);
        weekValue=text("—",48,ink);weekValue.setTypeface(null,Typeface.BOLD);amount.addView(weekValue);
        TextView unit=text(" 小时",15,muted);unit.setPadding(dp(6),0,0,dp(8));amount.addView(unit);
        gap(week,16);range=text("",13,muted);week.addView(range);updatePeriod();
        gap(root,14);LinearLayout month=card(root);monthCard=month;month.addView(text("本月累计",14,muted));gap(month,8);monthValue=text("— 小时",23,ink);monthValue.setTypeface(null,Typeface.BOLD);month.addView(monthValue);
        gap(root,22);detail=text("以时间日志最新自动备份为准。",12,muted);detail.setLineSpacing(dp(5),1);root.addView(detail);
        gap(root,18);sync=new Button(a);sync.setText("同步本周");sync.setTextSize(18);sync.setTypeface(null,Typeface.BOLD);sync.setTextColor(Color.WHITE);sync.setAllCaps(false);sync.setBackground(background(blue,18));root.addView(sync,new LinearLayout.LayoutParams(-1,dp(58)));
        progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(blue));root.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));progress.setVisibility(View.GONE);
        progressStep=text("",13,blue);progressStep.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(progressStep);progressStep.setVisibility(View.GONE);
        gap(root,14);status=text("同步后可在飞书查看板块时长与占比。",13,muted);status.setLineSpacing(dp(5),1);root.addView(status);
        gap(root,18);root.addView(text("自动读取备份 · 同时更新周/月看板",11,muted));
    }
    void ready(boolean ready){welcome.setVisibility(ready?View.GONE:View.VISIBLE);weekCard.setVisibility(ready?View.VISIBLE:View.GONE);monthCard.setVisibility(ready?View.VISIBLE:View.GONE);}
    void progress(String step){progress.setVisibility(View.VISIBLE);progressStep.setVisibility(View.VISIBLE);progressStep.setText(step);}
    void finishProgress(){progress.setVisibility(View.GONE);progressStep.setVisibility(View.GONE);}
    void updatePeriod(){LocalDate today=LocalDate.now(BackupReader.ZONE),monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));range.setText(monday.format(DateTimeFormatter.ofPattern("M月d日"))+" — "+today.format(DateTimeFormatter.ofPattern("M月d日"))+" · 已记录进展");}
}
