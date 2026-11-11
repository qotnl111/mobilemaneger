package app.gamelog.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

public class MainActivity extends Activity {

    static final int[] MIN_OPTIONS = {1, 2, 3, 5, 10, 15, 30};
    static final long DAY_MS = 24L * 60L * 60L * 1000L;
    static final int DAYS = 7;
    static final int[] RESET_HOURS = {0, 4, 5, 6};
    static final String RAW = "https://raw.githubusercontent.com/qotnl111/mobilemaneger/main/";
    static final String ACTION_INSTALL = "app.gamelog.tracker.INSTALL_STATUS";

    static final int BG = 0xFF12141A;
    static final int CARD = 0xFF1D2029;
    static final int LINE = 0xFF2C303C;
    static final int TEXT = 0xFFF2F3F7;
    static final int SUB = 0xFF9AA0AE;
    static final int TODO = 0xFFFFB547;
    static final int DONE = 0xFF4ADE80;

    SharedPreferences prefs;
    UsageStatsManager usm;
    PackageManager pm;
    Handler ui = new Handler(Looper.getMainLooper());
    int generation = 0;

    LinearLayout strip;
    LinearLayout detail;
    String selected;
    List<Item> lastItems = new ArrayList<Item>();
    final Map<String, List<String[]>> newsCache = new HashMap<String, List<String[]>>();
    final Map<String, Long> newsTime = new HashMap<String, Long>();
    LinearLayout permBox;
    TextView summary;
    volatile String todayKey = "";

    static class Item {
        String pkg;
        String label;
        Drawable icon;
        long[] days = new long[DAYS];
        long week;
        long total;
        boolean played;
        boolean manual;
        int cap;            // stamina cap, 0 = not tracked
        double regen;       // minutes per point, 0 = no gradual refill
        int daily = -1;     // hour of a full daily refill, -1 = none
        long aTime;         // anchor: stamina was aVal at aTime
        int aVal;
        long autoAnchor;
        long upd;           // next game update day (local midnight), 0 = none
        int updCycle;       // repeat every N days, 0 = one-off
        boolean updTime;    // upd carries a time of day
        boolean[] done = new boolean[DAYS];
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("gamelog", MODE_PRIVATE);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        pm = getPackageManager();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------- UI ----------

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    GradientDrawable box(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView button(String s, int fill, int textColor) {
        TextView t = text(s, 14, textColor, true);
        t.setBackground(box(fill, 0, 12));
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        return t;
    }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(24), dp(20), dp(8));
        head.addView(text("게임 관리", 26, TEXT, true));
        summary = text("", 14, SUB, false);
        summary.setPadding(0, dp(4), 0, 0);
        head.addView(summary);
        root.addView(head);

        // permission box
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setBackground(box(CARD, TODO, 16));
        permBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        permBox.addView(text("플레이 시간을 읽으려면 권한이 필요해요", 15, TEXT, true));
        TextView why = text("설정에서 '게임 관리'의 사용 기록 접근을 허용해 주세요.", 13, SUB, false);
        why.setPadding(0, dp(4), 0, dp(12));
        permBox.addView(why);
        TextView grant = button("사용 기록 접근 허용하기", TODO, 0xFF1A1300);
        grant.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "설정 > 앱 > 특별한 접근 > 사용 기록 접근에서 허용해 주세요",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        permBox.addView(grant);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(20), dp(8), dp(20), dp(8));
        root.addView(permBox, plp);

        // slide bar
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(20), dp(12), dp(8), dp(12));
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView ds = new ScrollView(this);
        ds.setVerticalScrollBarEnabled(false);
        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(20), dp(4), dp(20), dp(16));
        ds.addView(detail, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(ds, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(10), dp(20), dp(16));
        TextView add = button("+ 게임 추가", TODO, 0xFF1A1300);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAddDialog();
            }
        });
        row.addView(add);
        TextView set = button("설정", CARD, TEXT);
        set.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(10), 0, 0, 0);
        row.addView(set, rlp);
        root.addView(row);

        return root;
    }

    View card(final Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        final boolean sel = it.pkg.equals(selected);
        c.setBackground(box(sel ? 0xFF282C39 : CARD, sel ? TEXT : (it.played ? LINE : TODO), 18));

        ImageView icon = new ImageView(this);
        if (it.icon != null) icon.setImageDrawable(it.icon);
        if (it.played) icon.setAlpha(0.55f);
        c.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView name = text(it.label, 15, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, dp(6));
        c.addView(name);

        TextView pill = text(it.played ? (it.days[0] < minMs() ? "✓ 완료 (직접)" : "✓ 플레이 완료") : "아직 안 함", 12,
                it.played ? DONE : 0xFF1A1300, true);
        pill.setBackground(box(it.played ? 0x224ADE80 : TODO, 0, 20));
        pill.setPadding(dp(9), dp(3), dp(9), dp(3));
        c.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (it.cap > 0) {
            int sv = stamNow(it);
            boolean full = sv >= it.cap;
            TextView s1 = text("⚡ " + (sv < 0 ? "?" : String.valueOf(sv)) + " / " + it.cap, 13,
                    full ? TODO : TEXT, true);
            s1.setPadding(0, dp(8), 0, 0);
            c.addView(s1);
            String when = sv < 0 ? "기록 대기 중" : (full ? "가득 찼어요" : fmt(fullAt(it) - System.currentTimeMillis()) + " 뒤 가득");
            c.addView(text(when, 11, full ? TODO : SUB, false));
        }

        if (it.upd > 0) {
            long du = daysUntil(it.upd);
            TextView ut = text(updText(it.upd, it.updTime), 11, du >= 0 && du <= 1 ? TODO : SUB, du >= 0 && du <= 1);
            ut.setPadding(0, dp(6), 0, 0);
            c.addView(ut);
        }

        TextView lab = text("오늘", 11, SUB, false);
        lab.setPadding(0, dp(12), 0, 0);
        c.addView(lab);
        c.addView(text(fmt(it.days[0]), 22, TEXT, true));

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax((int) (minMs() / 1000L));
        bar.setProgress((int) Math.min(minMs() / 1000L, it.days[0] / 1000L));
        bar.setProgressTintList(ColorStateList.valueOf(it.played ? DONE : TODO));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        blp.setMargins(0, dp(6), 0, dp(12));
        c.addView(bar, blp);

        // last 7 days, oldest on the left, today on the right
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = DAYS - 1; i >= 0; i--) {
            View d = new View(this);
            boolean ok = it.done[i];
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ok ? DONE : LINE);
            d.setBackground(g);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
            dlp.setMargins(0, 0, dp(6), 0);
            dots.addView(d, dlp);
        }
        c.addView(dots);

        TextView w = text("7일  " + fmt(it.week), 12, SUB, false);
        w.setPadding(0, dp(8), 0, 0);
        c.addView(w);
        c.addView(text("누적  " + fmt(it.total), 12, SUB, false));

        TextView run = button("▶ 실행", TODO, 0xFF1A1300);
        run.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        run.setPadding(dp(8), dp(9), dp(8), dp(9));
        run.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launch(it);
            }
        });
        LinearLayout.LayoutParams runlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runlp.setMargins(0, dp(12), 0, 0);
        c.addView(run, runlp);

        if (it.days[0] < minMs()) {
            TextView mark = button(it.manual ? "완료 취소" : "완료 처리",
                    it.manual ? LINE : 0x224ADE80, it.manual ? SUB : DONE);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mark.setPadding(dp(8), dp(9), dp(8), dp(9));
            mark.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String k = "m:" + todayKey + ":" + it.pkg;
                    if (it.manual) prefs.edit().remove(k).apply();
                    else prefs.edit().putBoolean(k, true)
                            .putLong("at:" + it.pkg, System.currentTimeMillis())
                            .putInt("av:" + it.pkg, 0).apply();
                    refresh();
                }
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMargins(0, dp(8), 0, 0);
            c.addView(mark, mlp);
        }

        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selected = it.pkg;
                prefs.edit().putString("sel", it.pkg).apply();
                render(lastItems);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(it.label)
                        .setMessage("목록에서 뺄까요? 기록은 지워지지 않아요.")
                        .setPositiveButton("빼기", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                Set<String> s = tracked();
                                s.remove(it.pkg);
                                saveTracked(s);
                                refresh();
                            }
                        })
                        .setNegativeButton("취소", null)
                        .show();
                return true;
            }
        });
        return c;
    }

    void render(List<Item> items) {
        lastItems = items;
        if (selected == null) selected = prefs.getString("sel", null);
        Item selItem = null;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pkg.equals(selected)) selItem = items.get(i);
        }
        if (selItem == null && !items.isEmpty()) {
            selItem = items.get(0);
            selected = selItem.pkg;
        }
        showDetail(selItem);
        strip.removeAllViews();
        int done = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).played) done++;
        }
        if (items.isEmpty()) {
            summary.setText("추가된 게임이 없어요");
            TextView empty = text("아래 '+ 게임 추가'로\n관리할 게임을 골라 주세요", 14, SUB, false);
            empty.setBackground(box(CARD, LINE, 18));
            empty.setPadding(dp(20), dp(28), dp(20), dp(28));
            strip.addView(empty);
        } else {
            summary.setText("오늘 " + done + " / " + items.size() + " 완료 · " + minMin() + "분 이상 켜면 자동 완료");
        }
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(158), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(12), 0);
            strip.addView(card(items.get(i)), lp);
        }
    }

    // ---------- detail panel ----------

    void launch(Item it) {
        try {
            Intent li = pm.getLaunchIntentForPackage(it.pkg);
            if (li != null) {
                startActivity(li);
                return;
            }
        } catch (Exception e) {
            // fall through to the message
        }
        Toast.makeText(this, "이 게임을 실행할 수 없어요", Toast.LENGTH_SHORT).show();
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "링크를 열 수 없어요", Toast.LENGTH_SHORT).show();
        }
    }

    static String enc(String q) {
        try {
            return URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return q;
        }
    }

    static String shortFmt(long ms) {
        long min = ms / 60000L;
        if (min < 1) return ms > 0 ? "1분↓" : "";
        if (min < 60) return min + "분";
        return (min / 60L) + "시간";
    }

    String dayName(int back) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart(back, null));
        return String.valueOf("일월화수목금토".charAt(c.get(Calendar.DAY_OF_WEEK) - 1));
    }

    LinearLayout linkRow(TextView a, TextView b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        la.setMargins(0, 0, dp(8), dp(8));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lb.setMargins(0, 0, 0, dp(8));
        r.addView(a, la);
        r.addView(b, lb);
        return r;
    }

    void showDetail(final Item it) {
        detail.removeAllViews();
        if (it == null) {
            detail.addView(text("게임을 추가하면 여기에 기록과 뉴스가 나와요", 13, SUB, false));
            return;
        }

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(it.label, 19, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView run = button("▶ 실행", TODO, 0xFF1A1300);
        run.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launch(it);
            }
        });
        top.addView(run);
        detail.addView(top);

        TextView st = text("오늘 " + fmt(it.days[0]) + " · 7일 " + fmt(it.week) + " · 누적 " + fmt(it.total),
                13, SUB, false);
        st.setPadding(0, dp(6), 0, dp(12));
        detail.addView(st);

        // 7-day chart, oldest on the left
        LinearLayout chart = new LinearLayout(this);
        chart.setOrientation(LinearLayout.HORIZONTAL);
        chart.setBackground(box(CARD, 0, 16));
        chart.setPadding(dp(8), dp(12), dp(8), dp(12));
        long max = minMs();
        for (int i = 0; i < DAYS; i++) max = Math.max(max, it.days[i]);
        for (int i = DAYS - 1; i >= 0; i--) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView val = text(shortFmt(it.days[i]), 10, SUB, false);
            val.setGravity(Gravity.CENTER);
            col.addView(val);
            LinearLayout holder = new LinearLayout(this);
            holder.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(box(it.done[i] ? DONE : (it.days[i] > 0 ? TODO : LINE), 0, 4));
            int h = Math.max(dp(3), (int) (dp(52) * ((double) it.days[i] / (double) max)));
            holder.addView(bar, new LinearLayout.LayoutParams(dp(16), h));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            hlp.setMargins(0, dp(2), 0, dp(4));
            col.addView(holder, hlp);
            TextView dl = text(i == 0 ? "오늘" : dayName(i), 11, i == 0 ? TEXT : SUB, i == 0);
            dl.setGravity(Gravity.CENTER);
            col.addView(dl);
            chart.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        detail.addView(chart);

        TextView sh = text("스테미나", 15, TEXT, true);
        sh.setPadding(0, dp(18), 0, dp(8));
        detail.addView(sh);
        if (it.cap <= 0) {
            TextView setup = button("+ 스테미나 설정 (상한 · 회복 속도)", CARD, SUB);
            setup.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    editStamina(it);
                }
            });
            detail.addView(setup);
        } else {
            int sv = stamNow(it);
            String msg;
            if (sv < 0) {
                msg = "아직 기준 기록이 없어요.\n플레이를 완료하거나 현재 값을 입력하면 계산을 시작해요.";
            } else if (sv >= it.cap) {
                msg = "가득 찼어요  " + it.cap + " / " + it.cap;
            } else {
                long fa = fullAt(it);
                msg = "현재 약 " + sv + " / " + it.cap + "\n"
                        + new SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(new Date(fa))
                        + "에 가득 (" + fmt(fa - System.currentTimeMillis()) + " 뒤)";
            }
            TextView sb = text(msg, 14, sv >= it.cap ? TODO : TEXT, false);
            sb.setLineSpacing(dp(4), 1f);
            sb.setBackground(box(CARD, 0, 14));
            sb.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sblp.setMargins(0, 0, 0, dp(8));
            detail.addView(sb, sblp);
            TextView setNow = button("현재 값 입력", CARD, TEXT);
            setNow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    editStaminaNow(it);
                }
            });
            TextView conf = button("상한 · 속도 설정", CARD, TEXT);
            conf.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    editStamina(it);
                }
            });
            detail.addView(linkRow(setNow, conf));
        }

        // game update day
        TextView uh = text("업데이트 날짜", 15, TEXT, true);
        uh.setPadding(0, dp(10), 0, dp(8));
        detail.addView(uh);
        if (it.upd > 0) {
            long du = daysUntil(it.upd);
            String um = new SimpleDateFormat(it.updTime ? "M월 d일 (E) a h:mm" : "M월 d일 (E)", Locale.KOREA)
                    .format(new Date(it.upd))
                    + " · " + updText(it.upd, it.updTime).replace("업데이트 ", "");
            if (it.updCycle > 0) um += "\n" + it.updCycle + "일마다 반복";
            TextView ub = text(um, 14, du >= 0 && du <= 1 ? TODO : TEXT, false);
            ub.setLineSpacing(dp(4), 1f);
            ub.setBackground(box(CARD, 0, 14));
            ub.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams ublp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ublp.setMargins(0, 0, 0, dp(8));
            detail.addView(ub, ublp);
        }
        TextView pick = button(it.upd > 0 ? "날짜 변경" : "+ 날짜 등록", CARD, it.upd > 0 ? TEXT : SUB);
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickUpdateDate(it);
            }
        });
        TextView cyc = button(it.upd > 0 ? "반복 · 지우기" : "반복 주기", CARD, it.upd > 0 ? TEXT : SUB);
        cyc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                editUpdateCycle(it);
            }
        });
        detail.addView(linkRow(pick, cyc));

        // quick links
        TextView lh = text("바로가기", 15, TEXT, true);
        lh.setPadding(0, dp(18), 0, dp(8));
        detail.addView(lh);
        TextView store = button("스토어 페이지", CARD, TEXT);
        store.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + it.pkg)));
                } catch (Exception e) {
                    openUrl("https://play.google.com/store/apps/details?id=" + it.pkg);
                }
            }
        });
        TextView site = button("공식 사이트 찾기", CARD, TEXT);
        site.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openUrl("https://www.google.com/search?q=" + enc(it.label + " 공식 사이트"));
            }
        });
        TextView tube = button("유튜브 영상", CARD, TEXT);
        tube.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openUrl("https://www.youtube.com/results?search_query=" + enc(it.label));
            }
        });
        final String mine = prefs.getString("u:" + it.pkg, null);
        TextView my = button(mine == null ? "+ 내 링크 등록" : "내 링크 열기", CARD, mine == null ? SUB : TODO);
        my.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mine == null) editLink(it);
                else openUrl(mine);
            }
        });
        my.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                editLink(it);
                return true;
            }
        });
        detail.addView(linkRow(store, site));
        detail.addView(linkRow(tube, my));
        if (mine != null) {
            detail.addView(text("내 링크는 길게 눌러 수정", 11, SUB, false));
        }

        // news
        TextView nh = text("최신 뉴스", 15, TEXT, true);
        nh.setPadding(0, dp(14), 0, dp(8));
        detail.addView(nh);
        LinearLayout newsBox = new LinearLayout(this);
        newsBox.setOrientation(LinearLayout.VERTICAL);
        detail.addView(newsBox);
        loadNews(it, newsBox);
    }

    /** First daily refill moment after the anchor, or 0 when the game has none. */
    static long nextDaily(Item it) {
        if (it.daily < 0) return 0L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(it.aTime);
        c.set(Calendar.HOUR_OF_DAY, it.daily);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= it.aTime) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    static int stamNow(Item it) {
        if (it.cap <= 0 || it.aTime <= 0 || (it.regen <= 0 && it.daily < 0)) return -1;
        long now = System.currentTimeMillis();
        long nd = nextDaily(it);
        if (nd > 0 && now >= nd) return it.cap;
        long v = it.aVal;
        if (it.regen > 0) v += Math.max(0L, (long) ((now - it.aTime) / (it.regen * 60000.0)));
        return (int) Math.max(0L, Math.min((long) it.cap, v));
    }

    static long fullAt(Item it) {
        long best = Long.MAX_VALUE;
        if (it.regen > 0) {
            best = it.aTime + (long) (Math.max(0, it.cap - it.aVal) * it.regen * 60000.0);
        }
        long nd = nextDaily(it);
        if (nd > 0) best = Math.min(best, nd);
        return best;
    }

    static double fill(Item it) {
        int v = stamNow(it);
        return v < 0 ? -1.0 : (double) v / (double) it.cap;
    }

    EditText numberField(String hint, String value, boolean decimal) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setHint(hint);
        e.setText(value);
        return e;
    }

    void editStamina(final Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final EditText capIn = numberField("상한 (예: 240)", it.cap > 0 ? String.valueOf(it.cap) : "", false);
        String rv = "";
        if (it.regen > 0) {
            rv = it.regen == Math.floor(it.regen) ? String.valueOf((long) it.regen) : String.valueOf(it.regen);
        }
        final EditText regIn = numberField("1 회복에 걸리는 분 (예: 6)", rv, true);
        box.addView(text("상한", 12, SUB, false));
        box.addView(capIn);
        final EditText dayIn = numberField("0 ~ 23 (예: 4)", it.daily >= 0 ? String.valueOf(it.daily) : "", false);
        box.addView(text("1 회복에 걸리는 시간(분) · 조금씩 안 차면 비우기", 12, SUB, false));
        box.addView(regIn);
        box.addView(text("매일 통째로 회복되는 시각(시) · 없으면 비우기", 12, SUB, false));
        box.addView(dayIn);
        new AlertDialog.Builder(this)
                .setTitle(it.label + " 스테미나")
                .setMessage("플레이 완료 시점에 다 쓴 것으로 보고 계산해요. 조금씩 차는 게임은 회복 시간을, 하루 한 번 통째로 차는 게임은 회복 시각을 넣으세요. 둘 다 넣어도 돼요. 상한을 비우면 표시를 꺼요.")
                .setView(box)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int cap = 0;
                        float reg = 0f;
                        try {
                            cap = Integer.parseInt(capIn.getText().toString().trim());
                        } catch (Exception e) {
                            cap = 0;
                        }
                        try {
                            reg = Float.parseFloat(regIn.getText().toString().trim());
                        } catch (Exception e) {
                            reg = 0f;
                        }
                        int day = -1;
                        try {
                            day = Integer.parseInt(dayIn.getText().toString().trim());
                        } catch (Exception e) {
                            day = -1;
                        }
                        if (day > 23) day = -1;
                        if (reg < 0f) reg = 0f;
                        if (cap <= 0) {
                            prefs.edit().remove("sc:" + it.pkg).remove("sr:" + it.pkg)
                                    .remove("sd:" + it.pkg).apply();
                        } else if (reg <= 0f && day < 0) {
                            Toast.makeText(MainActivity.this, "회복 시간이나 매일 회복 시각 중 하나는 넣어 주세요",
                                    Toast.LENGTH_LONG).show();
                            return;
                        } else {
                            prefs.edit().putInt("sc:" + it.pkg, cap).putFloat("sr:" + it.pkg, reg)
                                    .putInt("sd:" + it.pkg, day).apply();
                        }
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    void editStaminaNow(final Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final EditText in = numberField("0 ~ " + it.cap, "", false);
        box.addView(in, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("지금 스테미나")
                .setMessage("게임에 보이는 현재 값을 넣으면 여기서부터 다시 계산해요.")
                .setView(box)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int v;
                        try {
                            v = Integer.parseInt(in.getText().toString().trim());
                        } catch (Exception e) {
                            return;
                        }
                        v = Math.max(0, Math.min(it.cap, v));
                        prefs.edit().putLong("at:" + it.pkg, System.currentTimeMillis())
                                .putInt("av:" + it.pkg, v).apply();
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    static long todayMidnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long daysUntil(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return Math.round((c.getTimeInMillis() - todayMidnight()) / (double) DAY_MS);
    }

    static String updText(long upd, boolean hasTime) {
        long du = daysUntil(upd);
        long now = System.currentTimeMillis();
        if (hasTime) {
            if (now < upd && upd - now < DAY_MS) return "업데이트 " + fmt(upd - now) + " 뒤";
            if (now >= upd && du == 0) return "업데이트 오늘 완료";
        }
        if (du == 0) return "업데이트 오늘!";
        if (du > 0) return "업데이트 D-" + du;
        return "업데이트 " + (-du) + "일 지남";
    }

    void pickUpdateDate(final Item it) {
        final Calendar c = Calendar.getInstance();
        if (it.upd > 0) c.setTimeInMillis(it.upd);
        final int oldH = it.updTime ? c.get(Calendar.HOUR_OF_DAY) : 12;
        final int oldM = it.updTime ? c.get(Calendar.MINUTE) : 0;
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            public void onDateSet(DatePicker view, final int y, final int m, final int d) {
                Calendar n = Calendar.getInstance();
                n.clear();
                n.set(y, m, d, 0, 0, 0);
                prefs.edit().putLong("ud:" + it.pkg, n.getTimeInMillis())
                        .putBoolean("ut:" + it.pkg, false).apply();
                refresh();
                TimePickerDialog tp = new TimePickerDialog(MainActivity.this,
                        new TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(TimePicker tv, int h, int min) {
                                Calendar t = Calendar.getInstance();
                                t.clear();
                                t.set(y, m, d, h, min, 0);
                                prefs.edit().putLong("ud:" + it.pkg, t.getTimeInMillis())
                                        .putBoolean("ut:" + it.pkg, true).apply();
                                refresh();
                            }
                        }, oldH, oldM, false);
                tp.setTitle("업데이트 시간 (취소하면 날짜만 저장)");
                tp.show();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    void editUpdateCycle(final Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        final EditText in = numberField("예: 42 (6주)", it.updCycle > 0 ? String.valueOf(it.updCycle) : "", false);
        box.addView(in, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(it.label + " 업데이트 반복")
                .setMessage("며칠마다 업데이트되는지 넣으면 날짜가 지났을 때 다음 날짜로 자동으로 넘어가요. 비우면 반복하지 않아요.")
                .setView(box)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int n = 0;
                        try {
                            n = Integer.parseInt(in.getText().toString().trim());
                        } catch (Exception e) {
                            n = 0;
                        }
                        if (n > 0) prefs.edit().putInt("uc:" + it.pkg, n).apply();
                        else prefs.edit().remove("uc:" + it.pkg).apply();
                        refresh();
                    }
                })
                .setNegativeButton("취소", null);
        if (it.upd > 0) {
            b.setNeutralButton("날짜 지우기", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    prefs.edit().remove("ud:" + it.pkg).remove("ut:" + it.pkg).apply();
                    refresh();
                }
            });
        }
        b.show();
    }

    void editLink(final Item it) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://");
        String cur = prefs.getString("u:" + it.pkg, "");
        input.setText(cur);
        new AlertDialog.Builder(this)
                .setTitle(it.label + " 내 링크")
                .setMessage("공식 카페, 쿠폰 입력 페이지처럼 자주 가는 주소를 넣어 두세요. 비우면 삭제돼요.")
                .setView(input)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String u = input.getText().toString().trim();
                        if (u.length() == 0) {
                            prefs.edit().remove("u:" + it.pkg).apply();
                        } else {
                            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                            prefs.edit().putString("u:" + it.pkg, u).apply();
                        }
                        render(lastItems);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    void loadNews(final Item it, final LinearLayout newsBox) {
        Long at = newsTime.get(it.pkg);
        List<String[]> cached = newsCache.get(it.pkg);
        if (cached != null && at != null && System.currentTimeMillis() - at.longValue() < 30L * 60L * 1000L) {
            fillNews(newsBox, cached, it);
            return;
        }
        newsBox.addView(text("불러오는 중…", 13, SUB, false));
        final String pkg = it.pkg;
        final String label = it.label;
        new Thread(new Runnable() {
            public void run() {
                List<String[]> got = null;
                try {
                    got = fetchNews(label);
                } catch (Exception e) {
                    got = null;
                }
                final List<String[]> list = got;
                ui.post(new Runnable() {
                    public void run() {
                        if (list != null) {
                            newsCache.put(pkg, list);
                            newsTime.put(pkg, Long.valueOf(System.currentTimeMillis()));
                        }
                        if (isFinishing() || newsBox.getParent() == null || !pkg.equals(selected)) return;
                        fillNews(newsBox, list, it);
                    }
                });
            }
        }).start();
    }

    /** Headlines from the Google News search feed: {title, link, source, date}. */
    List<String[]> fetchNews(String label) throws Exception {
        HttpURLConnection c = open("https://news.google.com/rss/search?q=" + enc(label)
                + "&hl=ko&gl=KR&ceid=KR:ko");
        List<String[]> list = new ArrayList<String[]>();
        InputStream in = c.getInputStream();
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(in, null);
            String[] cur = null;
            String tag = null;
            int ev;
            while ((ev = xp.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = xp.getName();
                    if ("item".equals(tag)) cur = new String[]{"", "", "", ""};
                } else if (ev == XmlPullParser.TEXT) {
                    if (cur != null && tag != null) {
                        String t = xp.getText();
                        if ("title".equals(tag)) cur[0] += t;
                        else if ("link".equals(tag)) cur[1] += t;
                        else if ("source".equals(tag)) cur[2] += t;
                        else if ("pubDate".equals(tag)) cur[3] += t;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("item".equals(xp.getName()) && cur != null) {
                        cur[0] = cur[0].trim();
                        cur[1] = cur[1].trim();
                        cur[2] = cur[2].trim();
                        String suffix = " - " + cur[2];
                        if (cur[2].length() > 0 && cur[0].endsWith(suffix)) {
                            cur[0] = cur[0].substring(0, cur[0].length() - suffix.length());
                        }
                        try {
                            Date d = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                                    .parse(cur[3].trim());
                            cur[3] = new SimpleDateFormat("M월 d일", Locale.KOREA).format(d);
                        } catch (Exception e) {
                            cur[3] = "";
                        }
                        if (cur[0].length() > 0 && cur[1].length() > 0) list.add(cur);
                        cur = null;
                        if (list.size() >= 8) break;
                    }
                    tag = null;
                }
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // already closed
            }
            c.disconnect();
        }
        return list;
    }

    void fillNews(LinearLayout newsBox, List<String[]> list, final Item it) {
        newsBox.removeAllViews();
        if (list == null) {
            newsBox.addView(text("뉴스를 불러오지 못했어요. 인터넷 연결을 확인해 주세요.", 13, SUB, false));
        } else if (list.isEmpty()) {
            newsBox.addView(text("관련 뉴스가 없어요", 13, SUB, false));
        } else {
            for (int i = 0; i < list.size(); i++) {
                final String[] n = list.get(i);
                LinearLayout b = new LinearLayout(this);
                b.setOrientation(LinearLayout.VERTICAL);
                b.setBackground(box(CARD, 0, 14));
                b.setPadding(dp(14), dp(12), dp(14), dp(12));
                TextView t = text(n[0], 14, TEXT, false);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                b.addView(t);
                String meta = n[2];
                if (n[3].length() > 0) meta = meta.length() > 0 ? meta + " · " + n[3] : n[3];
                TextView m = text(meta, 12, SUB, false);
                m.setPadding(0, dp(4), 0, 0);
                b.addView(m);
                b.setClickable(true);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        openUrl(n[1]);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(8));
                newsBox.addView(b, lp);
            }
        }
        TextView more = button("뉴스 더 보기", CARD, SUB);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openUrl("https://news.google.com/search?q=" + enc(it.label) + "&hl=ko&gl=KR&ceid=KR:ko");
            }
        });
        newsBox.addView(more);
    }

    // ---------- data ----------

    int minMin() {
        return prefs.getInt("minMin", 5);
    }

    long minMs() {
        return minMin() * 60L * 1000L;
    }

    int resetHour() {
        return prefs.getInt("resetHour", 4);
    }

    Set<String> tracked() {
        Set<String> s = prefs.getStringSet("pkgs", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    void saveTracked(Set<String> s) {
        prefs.edit().putStringSet("pkgs", new HashSet<String>(s)).apply();
    }

    boolean hasAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isGame(ApplicationInfo ai) {
        return ai != null && (ai.category == ApplicationInfo.CATEGORY_GAME
                || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0);
    }

    List<ResolveInfo> launchables() {
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = null;
        try {
            l = pm.queryIntentActivities(q, 0);
        } catch (Exception e) {
            // fall through
        }
        return l == null ? new ArrayList<ResolveInfo>() : l;
    }

    /** First start: put every app the system marks as a game on the list. */
    void autoAddOnce() {
        if (prefs.getBoolean("init", false)) return;
        Set<String> s = tracked();
        List<ResolveInfo> l = launchables();
        for (int i = 0; i < l.size(); i++) {
            ResolveInfo r = l.get(i);
            if (r.activityInfo == null) continue;
            if (getPackageName().equals(r.activityInfo.packageName)) continue;
            if (isGame(r.activityInfo.applicationInfo)) s.add(r.activityInfo.packageName);
        }
        prefs.edit().putStringSet("pkgs", s).putBoolean("init", true).apply();
    }

    /** Start of the play day that lies `back` days before today, plus its storage key. */
    long dayStart(int back, String[] keyOut) {
        int h = resetHour();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, -h);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -back);
        if (keyOut != null) {
            keyOut[0] = new SimpleDateFormat("yyyyMMdd", Locale.US).format(c.getTime());
        }
        c.add(Calendar.HOUR_OF_DAY, h);
        return c.getTimeInMillis();
    }

    /** Foreground time per tracked package between start and end. */
    Map<String, Long> usage(long start, long end, Set<String> pkgs, Map<String, Long> lastEnd) {
        Map<String, Long> total = new HashMap<String, Long>();
        if (end <= start) return total;
        UsageEvents ev = usm.queryEvents(start, end);
        if (ev == null) return total;
        Map<String, Long> open = new HashMap<String, Long>();   // "pkg/class" -> resume time
        Map<String, String> openPkg = new HashMap<String, String>();
        Set<String> seen = new HashSet<String>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            String pkg = e.getPackageName();
            if (pkg == null || !pkgs.contains(pkg)) continue;
            int type = e.getEventType();
            if (type != 1 && type != 2) continue;  // 1 = resumed, 2 = paused
            String key = pkg + "/" + e.getClassName();
            long ts = e.getTimeStamp();
            if (type == 1) {
                if (!open.containsKey(key)) {
                    open.put(key, Long.valueOf(ts));
                    openPkg.put(key, pkg);
                }
                seen.add(key);
            } else {
                Long s = open.remove(key);
                openPkg.remove(key);
                long from = -1;
                if (s != null) {
                    from = s.longValue();
                } else if (!seen.contains(key)) {
                    from = start;   // was already on screen when the day began
                }
                seen.add(key);
                if (from >= 0 && ts > from) add(total, pkg, ts - from);
                Long le = lastEnd.get(pkg);
                if (le == null || le.longValue() < ts) lastEnd.put(pkg, Long.valueOf(ts));
            }
        }
        long stop = Math.min(end, System.currentTimeMillis());
        for (Map.Entry<String, Long> en : open.entrySet()) {
            long from = en.getValue().longValue();
            String pkg = openPkg.get(en.getKey());
            if (pkg != null && stop > from) add(total, pkg, stop - from);
            if (pkg != null) lastEnd.put(pkg, Long.valueOf(stop));
        }
        return total;
    }

    static void add(Map<String, Long> m, String k, long v) {
        Long cur = m.get(k);
        m.put(k, Long.valueOf((cur == null ? 0L : cur.longValue()) + v));
    }

    void refresh() {
        final int gen = ++generation;
        final boolean access = hasAccess();
        permBox.setVisibility(access ? View.GONE : View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                final List<Item> items;
                try {
                    items = load(access);
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (gen == generation) summary.setText("기록을 읽지 못했어요: " + ex);
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (gen == generation && !isFinishing()) render(items);
                    }
                });
            }
        }).start();
    }

    List<Item> load(boolean access) {
        autoAddOnce();
        Set<String> pkgs = tracked();
        Map<String, Item> map = new HashMap<String, Item>();
        for (String p : pkgs) {
            Item it = new Item();
            it.pkg = p;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                it.label = p + " (삭제됨)";
            }
            map.put(p, it);
        }

        // the day boundary moved: recount the visible week with the new boundary
        if (access && prefs.getInt("keyHour", 0) != resetHour()) {
            SharedPreferences.Editor clr = prefs.edit();
            for (int d = 0; d < DAYS; d++) {
                String[] key = new String[1];
                dayStart(d, key);
                for (String p : pkgs) clr.remove("t:" + key[0] + ":" + p);
            }
            clr.putInt("keyHour", resetHour()).commit();
        }

        Map<String, ?> stored = prefs.getAll();
        SharedPreferences.Editor ed = prefs.edit();
        Set<String> shownKeys = new HashSet<String>();
        long now = System.currentTimeMillis();
        for (int d = 0; d < DAYS; d++) {
            String[] key = new String[1];
            long start = dayStart(d, key);
            long end = Math.min(start + DAY_MS, now);
            Map<String, Long> u = new HashMap<String, Long>();
            Map<String, Long> le = new HashMap<String, Long>();
            if (access) {
                try {
                    u = usage(start, end, pkgs, le);
                } catch (Exception e) {
                    // keep what was stored
                }
            }
            for (String p : pkgs) {
                String k = "t:" + key[0] + ":" + p;
                shownKeys.add(k);
                long v = u.containsKey(p) ? u.get(p).longValue() : 0L;
                if (v >= minMs() && le.containsKey(p)) {
                    Item ai = map.get(p);
                    ai.autoAnchor = Math.max(ai.autoAnchor, le.get(p).longValue());
                }
                Object old = stored.get(k);
                long was = (old instanceof Long) ? ((Long) old).longValue() : 0L;
                if (v > was) {
                    ed.putLong(k, v);
                } else {
                    v = was;   // system history may have expired; trust our own record
                }
                Item it = map.get(p);
                boolean man = Boolean.TRUE.equals(stored.get("m:" + key[0] + ":" + p));
                it.done[d] = v >= minMs() || man;
                if (d == 0) {
                    it.manual = man;
                    todayKey = key[0];
                }
                it.days[d] = v;
                it.week += v;
                it.total += v;
            }
        }
        // stamina: assume it was spent when a completed play day ended
        for (String p : pkgs) {
            Item it = map.get(p);
            long savedAuto = prefs.getLong("aa:" + p, 0L);
            if (it.autoAnchor > savedAuto) ed.putLong("aa:" + p, it.autoAnchor);
            else it.autoAnchor = savedAuto;
            it.cap = prefs.getInt("sc:" + p, 0);
            it.regen = prefs.getFloat("sr:" + p, 0f);
            it.daily = prefs.getInt("sd:" + p, -1);
            it.upd = prefs.getLong("ud:" + p, 0L);
            it.updCycle = prefs.getInt("uc:" + p, 0);
            it.updTime = prefs.getBoolean("ut:" + p, false);
            if (it.upd > 0 && it.updCycle > 0 && it.upd < todayMidnight()) {
                Calendar uc = Calendar.getInstance();
                uc.setTimeInMillis(it.upd);
                long tm = todayMidnight();
                int guard = 0;
                while (uc.getTimeInMillis() < tm && guard++ < 5000) uc.add(Calendar.DAY_OF_YEAR, it.updCycle);
                it.upd = uc.getTimeInMillis();
                ed.putLong("ud:" + p, it.upd);
            }
            long manT = prefs.getLong("at:" + p, 0L);
            if (manT >= it.autoAnchor && manT > 0) {
                it.aTime = manT;
                it.aVal = prefs.getInt("av:" + p, 0);
            } else {
                it.aTime = it.autoAnchor;
                it.aVal = 0;
            }
        }
        ed.apply();

        // lifetime total: add stored days older than the last week
        for (Map.Entry<String, ?> en : stored.entrySet()) {
            String k = en.getKey();
            if (!k.startsWith("t:") || shownKeys.contains(k)) continue;
            if (!(en.getValue() instanceof Long)) continue;
            int cut = k.indexOf(':', 2);
            if (cut < 0) continue;
            Item it = map.get(k.substring(cut + 1));
            if (it != null) it.total += ((Long) en.getValue()).longValue();
        }

        List<Item> list = new ArrayList<Item>(map.values());
        for (int i = 0; i < list.size(); i++) {
            Item it = list.get(i);
            it.played = it.done[0];
        }
        Collections.sort(list, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (a.played != b.played) return a.played ? 1 : -1;   // not played first
                double fa = fill(a), fb = fill(b);
                if (fa != fb) return fa > fb ? -1 : 1;                // fuller stamina first
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    static String fmt(long ms) {
        long sec = ms / 1000L;
        if (sec < 60) return sec + "초";
        long min = sec / 60L;
        if (min < 60) return min + "분";
        return (min / 60L) + "시간 " + (min % 60L) + "분";
    }

    // ---------- settings ----------

    void showSettings() {
        CharSequence[] items = {
                "게임 추가",
                "게임 여러 개 빼기",
                "자동 완료 기준: " + minMin() + "분 이상",
                "하루 시작: " + resetHour() + "시",
                "업데이트 확인 (현재 v" + myVersionName() + ")"
        };
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) showAddDialog();
                        else if (which == 1) showRemoveDialog();
                        else if (which == 2) showMinDialog();
                        else if (which == 3) showResetDialog();
                        else checkUpdate();
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    void showRemoveDialog() {
        final List<String> pkgList = new ArrayList<String>(tracked());
        if (pkgList.isEmpty()) {
            Toast.makeText(this, "목록에 게임이 없어요", Toast.LENGTH_SHORT).show();
            return;
        }
        final Map<String, String> labels = new HashMap<String, String>();
        for (int i = 0; i < pkgList.size(); i++) {
            String p = pkgList.get(i);
            String name;
            try {
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (Exception e) {
                name = p + " (삭제됨)";
            }
            labels.put(p, name);
        }
        Collections.sort(pkgList, new Comparator<String>() {
            public int compare(String a, String b) {
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });
        CharSequence[] names = new CharSequence[pkgList.size()];
        for (int i = 0; i < pkgList.size(); i++) names[i] = labels.get(pkgList.get(i));
        final Set<String> drop = new HashSet<String>();
        new AlertDialog.Builder(this)
                .setTitle("뺄 게임을 체크하세요")
                .setMultiChoiceItems(names, new boolean[pkgList.size()],
                        new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                String p = pkgList.get(which);
                                if (isChecked) drop.add(p);
                                else drop.remove(p);
                            }
                        })
                .setPositiveButton("빼기", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Set<String> s = tracked();
                        s.removeAll(drop);
                        saveTracked(s);
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    void showMinDialog() {
        CharSequence[] names = new CharSequence[MIN_OPTIONS.length];
        int cur = -1;
        for (int i = 0; i < MIN_OPTIONS.length; i++) {
            names[i] = MIN_OPTIONS[i] + "분 이상";
            if (MIN_OPTIONS[i] == minMin()) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("자동 완료 기준")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putInt("minMin", MIN_OPTIONS[which]).apply();
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    void showResetDialog() {
        CharSequence[] names = new CharSequence[RESET_HOURS.length];
        int cur = -1;
        for (int i = 0; i < RESET_HOURS.length; i++) {
            names[i] = RESET_HOURS[i] == 0 ? "0시 (자정)" : "오전 " + RESET_HOURS[i] + "시";
            if (RESET_HOURS[i] == resetHour()) cur = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("하루 시작 시각")
                .setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putInt("resetHour", RESET_HOURS[which]).apply();
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- update ----------

    int myVersion() {
        try {
            return pm.getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    String myVersionName() {
        try {
            return String.valueOf(pm.getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "?";
        }
    }

    void toast(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GameManager");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    void checkUpdate() {
        Toast.makeText(this, "새 버전 확인 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = open(RAW + "version.json?t=" + System.currentTimeMillis());
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    c.disconnect();
                    JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
                    final int code = o.getInt("versionCode");
                    final String name = o.optString("versionName", String.valueOf(code));
                    final String notes = o.optString("notes", "");
                    final String apk = o.optString("apk", "game-manager.apk");
                    ui.post(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            if (code <= myVersion()) {
                                Toast.makeText(MainActivity.this, "이미 최신 버전이에요 (v" + myVersionName() + ")",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("새 버전 v" + name)
                                    .setMessage(notes.length() > 0 ? notes : "새 버전이 있어요.")
                                    .setPositiveButton("업데이트", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int which) {
                                            downloadAndInstall(apk);
                                        }
                                    })
                                    .setNegativeButton("나중에", null)
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    toast("업데이트 정보를 못 가져왔어요 (" + e.getMessage()
                            + "). 인터넷 연결과 저장소 공개 여부를 확인해 주세요.");
                }
            }
        }).start();
    }

    void downloadAndInstall(final String apk) {
        Toast.makeText(this, "새 버전 받는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                PackageInstaller.Session ses = null;
                try {
                    HttpURLConnection c = open(RAW + apk + "?t=" + System.currentTimeMillis());
                    PackageInstaller pi = pm.getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(getPackageName());
                    int id = pi.createSession(params);
                    ses = pi.openSession(id);
                    InputStream in = c.getInputStream();
                    OutputStream out = ses.openWrite("base.apk", 0, -1);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ses.fsync(out);
                    out.close();
                    in.close();
                    c.disconnect();
                    Intent cb = new Intent(MainActivity.this, MainActivity.class);
                    cb.setAction(ACTION_INSTALL);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0);   // FLAG_MUTABLE
                    PendingIntent pend = PendingIntent.getActivity(MainActivity.this, id, cb, flags);
                    ses.commit(pend.getIntentSender());
                    ses.close();
                } catch (Exception e) {
                    if (ses != null) {
                        try {
                            ses.abandon();
                        } catch (Exception ignored) {
                            // nothing to clean up
                        }
                    }
                    toast("업데이트를 받지 못했어요 (" + e.getMessage() + ")");
                }
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !ACTION_INSTALL.equals(intent.getAction())) return;
        Bundle ex = intent.getExtras();
        if (ex == null) return;
        int status = ex.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Object confirm = ex.get(Intent.EXTRA_INTENT);
            if (confirm instanceof Intent) {
                try {
                    startActivity((Intent) confirm);
                } catch (Exception e) {
                    toast("설치 화면을 열지 못했어요");
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            toast("업데이트 완료");
        } else {
            toast("설치하지 못했어요: " + ex.getString(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    // ---------- add dialog ----------

    void showAddDialog() {
        List<ResolveInfo> l = launchables();
        final List<String> pkgList = new ArrayList<String>();
        final Map<String, String> labels = new HashMap<String, String>();
        final Set<String> games = new HashSet<String>();
        for (int i = 0; i < l.size(); i++) {
            ResolveInfo r = l.get(i);
            if (r.activityInfo == null) continue;
            String p = r.activityInfo.packageName;
            if (p == null || p.equals(getPackageName()) || labels.containsKey(p)) continue;
            String name;
            try {
                name = String.valueOf(r.loadLabel(pm));
            } catch (Exception e) {
                name = p;
            }
            labels.put(p, name);
            pkgList.add(p);
            if (isGame(r.activityInfo.applicationInfo)) games.add(p);
        }
        Collections.sort(pkgList, new Comparator<String>() {
            public int compare(String a, String b) {
                boolean ga = games.contains(a), gb = games.contains(b);
                if (ga != gb) return ga ? -1 : 1;   // games first
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });
        final Set<String> sel = tracked();
        CharSequence[] names = new CharSequence[pkgList.size()];
        boolean[] checked = new boolean[pkgList.size()];
        for (int i = 0; i < pkgList.size(); i++) {
            String p = pkgList.get(i);
            names[i] = (games.contains(p) ? "🎮 " : "") + labels.get(p);
            checked[i] = sel.contains(p);
        }
        new AlertDialog.Builder(this)
                .setTitle("관리할 게임 선택")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        String p = pkgList.get(which);
                        if (isChecked) sel.add(p);
                        else sel.remove(p);
                    }
                })
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        saveTracked(sel);
                        refresh();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }
}
