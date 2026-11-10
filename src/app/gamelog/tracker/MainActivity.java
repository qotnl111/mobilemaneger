package app.gamelog.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.PendingIntent;
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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

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

        TextView hint = text("카드를 누르면 게임 실행 · 안 한 게임이 왼쪽부터 나와요\n여러 게임 빼기와 완료 기준은 설정에서 바꿔요", 12, SUB, false);
        hint.setPadding(dp(20), dp(4), dp(20), dp(16));
        hint.setLineSpacing(dp(3), 1f);
        root.addView(hint);

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), 0, dp(20), dp(20));
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
        c.setBackground(box(CARD, it.played ? LINE : TODO, 18));

        ImageView icon = new ImageView(this);
        if (it.icon != null) icon.setImageDrawable(it.icon);
        if (it.played) icon.setAlpha(0.55f);
        c.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView name = text(it.label, 15, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, dp(6));
        c.addView(name);

        TextView pill = text(it.played ? (it.days[0] < minMs() ? "✓ 완료 (직접)" : "✓ 플레이 완료") : "아직 안 함", 12,
                it.played ? DONE : 0xFF1A1300, true);
        pill.setBackground(box(it.played ? 0x224ADE80 : TODO, 0, 20));
        pill.setPadding(dp(9), dp(3), dp(9), dp(3));
        c.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        if (it.days[0] < minMs()) {
            TextView mark = button(it.manual ? "완료 취소" : "완료 처리",
                    it.manual ? LINE : 0x224ADE80, it.manual ? SUB : DONE);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mark.setPadding(dp(8), dp(9), dp(8), dp(9));
            mark.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String k = "m:" + todayKey + ":" + it.pkg;
                    if (it.manual) prefs.edit().remove(k).apply();
                    else prefs.edit().putBoolean(k, true).apply();
                    refresh();
                }
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMargins(0, dp(12), 0, 0);
            c.addView(mark, mlp);
        }

        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent li = pm.getLaunchIntentForPackage(it.pkg);
                    if (li != null) {
                        startActivity(li);
                    } else {
                        Toast.makeText(MainActivity.this, "이 게임을 실행할 수 없어요", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "이 게임을 실행할 수 없어요", Toast.LENGTH_SHORT).show();
                }
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

    // ---------- data ----------

    int minMin() {
        return prefs.getInt("minMin", 5);
    }

    long minMs() {
        return minMin() * 60L * 1000L;
    }

    int resetHour() {
        return prefs.getInt("resetHour", 0);
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
    Map<String, Long> usage(long start, long end, Set<String> pkgs) {
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
            }
        }
        long stop = Math.min(end, System.currentTimeMillis());
        for (Map.Entry<String, Long> en : open.entrySet()) {
            long from = en.getValue().longValue();
            String pkg = openPkg.get(en.getKey());
            if (pkg != null && stop > from) add(total, pkg, stop - from);
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

        Map<String, ?> stored = prefs.getAll();
        SharedPreferences.Editor ed = prefs.edit();
        Set<String> shownKeys = new HashSet<String>();
        long now = System.currentTimeMillis();
        for (int d = 0; d < DAYS; d++) {
            String[] key = new String[1];
            long start = dayStart(d, key);
            long end = Math.min(start + DAY_MS, now);
            Map<String, Long> u = new HashMap<String, Long>();
            if (access) {
                try {
                    u = usage(start, end, pkgs);
                } catch (Exception e) {
                    // keep what was stored
                }
            }
            for (String p : pkgs) {
                String k = "t:" + key[0] + ":" + p;
                shownKeys.add(k);
                long v = u.containsKey(p) ? u.get(p).longValue() : 0L;
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
