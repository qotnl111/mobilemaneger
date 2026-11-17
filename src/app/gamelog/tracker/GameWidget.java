package app.gamelog.tracker;

import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Home screen widget: games not played yet come first, with their stamina. */
public class GameWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "app.gamelog.tracker.WIDGET_REFRESH";
    static final int IMMUTABLE = 0x04000000;
    static final int TODO = 0xFFFFB547;
    static final int DONE = 0xFF4ADE80;
    static final int SUB = 0xFF9AA0AE;

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int i = 0; i < ids.length; i++) update(c, mgr, ids[i]);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle opts) {
        update(c, mgr, id);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        if (in != null && ACTION_REFRESH.equals(in.getAction())) {
            updateAll(c);
            return;
        }
        super.onReceive(c, in);
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, GameWidget.class));
            for (int i = 0; i < ids.length; i++) update(c, mgr, ids[i]);
        } catch (Exception e) {
            // no widget placed, or the launcher is not ready
        }
    }

    static boolean hasAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static void update(Context c, AppWidgetManager mgr, int id) {
        try {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
            Intent app = new Intent(c, MainActivity.class);
            rv.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(c, 1, app,
                    PendingIntent.FLAG_UPDATE_CURRENT | IMMUTABLE));
            Intent re = new Intent(c, GameWidget.class);
            re.setAction(ACTION_REFRESH);
            rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(c, 2, re,
                    PendingIntent.FLAG_UPDATE_CURRENT | IMMUTABLE));
            rv.removeAllViews(R.id.w_list);

            boolean access = hasAccess(c);
            List<MainActivity.Item> items = compute(c, access);
            int done = 0;
            for (int i = 0; i < items.size(); i++) if (items.get(i).played) done++;

            String empty = null;
            if (!access) empty = "앱을 열어 사용 기록 접근을 허용해 주세요";
            else if (items.isEmpty()) empty = "앱에서 관리할 게임을 추가해 주세요";
            rv.setTextViewText(R.id.w_title, items.isEmpty() ? "게임 관리"
                    : "게임 관리 · " + done + " / " + items.size() + " 완료");
            rv.setViewVisibility(R.id.w_empty, empty == null ? View.GONE : View.VISIBLE);
            if (empty != null) rv.setTextViewText(R.id.w_empty, empty);

            // as many rows as the widget is tall
            int h = 110;
            try {
                Bundle o = mgr.getAppWidgetOptions(id);
                h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
            } catch (Exception e) {
                // keep the default height
            }
            int rows = Math.max(1, Math.min(12, (h - 40) / 46));
            if (empty != null && !items.isEmpty()) rows = Math.max(1, rows - 1);
            float den = c.getResources().getDisplayMetrics().density;
            int px = Math.max(48, Math.min(120, (int) (34 * den)));
            long now = System.currentTimeMillis();

            for (int i = 0; i < items.size() && i < rows; i++) {
                MainActivity.Item it = items.get(i);
                RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.w_name, it.label);
                if (it.icon != null) row.setImageViewBitmap(R.id.w_icon, bitmap(it.icon, px));

                String sub;
                boolean full = false;
                if (it.cap > 0) {
                    int sv = MainActivity.stamNow(it);
                    full = sv >= it.cap;
                    if (sv < 0) sub = "⚡ ? / " + it.cap;
                    else if (full) sub = "⚡ 가득 " + it.cap + " / " + it.cap;
                    else sub = "⚡ " + sv + " / " + it.cap + " · " + MainActivity.fmt(MainActivity.fullAt(it) - now) + " 뒤 가득";
                } else {
                    sub = "오늘 " + MainActivity.fmt(it.days[0]);
                }
                row.setTextViewText(R.id.w_sub, sub);
                row.setTextColor(R.id.w_sub, full ? TODO : SUB);
                row.setTextViewText(R.id.w_stat, it.played ? "완료" : "안 함");
                row.setTextColor(R.id.w_stat, it.played ? DONE : TODO);

                Intent launch = c.getPackageManager().getLaunchIntentForPackage(it.pkg);
                if (launch == null) launch = app;
                row.setOnClickPendingIntent(R.id.w_row, PendingIntent.getActivity(c, it.pkg.hashCode(),
                        launch, PendingIntent.FLAG_UPDATE_CURRENT | IMMUTABLE));
                rv.addView(R.id.w_list, row);
            }
            mgr.updateAppWidget(id, rv);
        } catch (Exception e) {
            // a broken widget update must not crash the app process
        }
    }

    static Bitmap bitmap(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(cv);
        return b;
    }

    /** Today's state of every tracked game, read-only, in the same order as the app. */
    static List<MainActivity.Item> compute(Context c, boolean access) {
        SharedPreferences prefs = c.getSharedPreferences("gamelog", Context.MODE_PRIVATE);
        PackageManager pm = c.getPackageManager();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        Set<String> saved = prefs.getStringSet("pkgs", null);
        Set<String> pkgs = saved == null ? new HashSet<String>() : new HashSet<String>(saved);
        int global = prefs.getInt("resetHour", 4);
        long minMs = prefs.getInt("minMin", 5) * 60000L;
        Map<String, ?> stored = prefs.getAll();
        long now = System.currentTimeMillis();

        Map<String, MainActivity.Item> map = new HashMap<String, MainActivity.Item>();
        Map<Integer, Set<String>> byHour = new HashMap<Integer, Set<String>>();
        for (String p : pkgs) {
            MainActivity.Item it = new MainActivity.Item();
            it.pkg = p;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                it.label = String.valueOf(pm.getApplicationLabel(ai));
                it.icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                it.label = p;
            }
            it.ownHour = prefs.getInt("rh:" + p, -1);
            it.dayHour = it.ownHour >= 0 && it.ownHour <= 23 ? it.ownHour : global;
            map.put(p, it);
            Integer h = Integer.valueOf(it.dayHour);
            Set<String> g = byHour.get(h);
            if (g == null) {
                g = new HashSet<String>();
                byHour.put(h, g);
            }
            g.add(p);
        }

        for (Map.Entry<Integer, Set<String>> grp : byHour.entrySet()) {
            String[] key = new String[1];
            long start = MainActivity.dayStart(0, key, grp.getKey().intValue());
            Map<String, Long> u = new HashMap<String, Long>();
            Map<String, Long> le = new HashMap<String, Long>();
            if (access) {
                try {
                    u = MainActivity.usage(usm, start, now, grp.getValue(), le);
                } catch (Exception e) {
                    // fall back to what the app stored
                }
            }
            for (String p : grp.getValue()) {
                MainActivity.Item it = map.get(p);
                long live = u.containsKey(p) ? u.get(p).longValue() : 0L;
                Object old = stored.get("t:" + key[0] + ":" + p);
                long was = (old instanceof Long) ? ((Long) old).longValue() : 0L;
                boolean man = Boolean.TRUE.equals(stored.get("m:" + key[0] + ":" + p));
                boolean no = Boolean.TRUE.equals(stored.get("x:" + key[0] + ":" + p));
                it.days[0] = Math.max(live, was);
                it.manual = man;
                it.played = !no && (it.days[0] >= minMs || man);
                it.done[0] = it.played;

                it.autoAnchor = prefs.getLong("aa:" + p, 0L);
                if (live >= minMs && !no && le.containsKey(p)) {
                    it.autoAnchor = Math.max(it.autoAnchor, le.get(p).longValue());
                }
                it.cap = prefs.getInt("sc:" + p, 0);
                it.regen = prefs.getFloat("sr:" + p, 0f);
                it.daily = prefs.getInt("sd:" + p, -1);
                long manT = prefs.getLong("at:" + p, 0L);
                if (manT >= it.autoAnchor && manT > 0) {
                    it.aTime = manT;
                    it.aVal = prefs.getInt("av:" + p, 0);
                } else {
                    it.aTime = it.autoAnchor;
                    it.aVal = 0;
                }
            }
        }

        List<MainActivity.Item> list = new ArrayList<MainActivity.Item>(map.values());
        final int sortMode = prefs.getInt("sortMode", 1);
        final long sortNow = now;
        Collections.sort(list, new Comparator<MainActivity.Item>() {
            public int compare(MainActivity.Item a, MainActivity.Item b) {
                if (a.played != b.played) return a.played ? 1 : -1;
                if (sortMode == 1) {
                    long ta = MainActivity.untilFull(a, sortNow), tb = MainActivity.untilFull(b, sortNow);
                    if (ta != tb) return ta < tb ? -1 : 1;
                } else {
                    double fa = MainActivity.fill(a), fb = MainActivity.fill(b);
                    if (fa != fb) return fa > fb ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }
}
