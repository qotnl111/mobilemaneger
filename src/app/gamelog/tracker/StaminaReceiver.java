package app.gamelog.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Map;
import java.util.Set;

/** Posts a silent notification when a game's estimated stamina is full. */
public class StaminaReceiver extends BroadcastReceiver {

    static final String ACTION_FULL = "app.gamelog.tracker.STAMINA_FULL";
    static final String CHANNEL = "stamina";
    static final int IMMUTABLE = 0x04000000;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gamelog", Context.MODE_PRIVATE);
    }

    static PendingIntent alarmIntent(Context c, String pkg) {
        Intent i = new Intent(c, StaminaReceiver.class);
        i.setAction(ACTION_FULL);
        i.setData(Uri.parse("gamelog://full/" + pkg));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | IMMUTABLE);
    }

    static void setAlarm(Context c, String pkg, long when) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, alarmIntent(c, pkg));
        } catch (Exception e) {
            // without the alarm there is simply no notification
        }
    }

    /** Remember and arm the "full" moment for one game. */
    static void schedule(Context c, String pkg, String label, long when, long anchor) {
        SharedPreferences p = prefs(c);
        if (p.getLong("na:" + pkg, 0L) != when || p.getLong("nt:" + pkg, 0L) != anchor) {
            p.edit().putLong("na:" + pkg, when).putLong("nt:" + pkg, anchor)
                    .putString("nl:" + pkg, label).apply();
        }
        setAlarm(c, pkg, when);
    }

    static void cancel(Context c, String pkg) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarmIntent(c, pkg));
        } catch (Exception e) {
            // nothing was armed
        }
        SharedPreferences p = prefs(c);
        if (p.contains("na:" + pkg)) {
            p.edit().remove("na:" + pkg).remove("nt:" + pkg).remove("nl:" + pkg).apply();
        }
    }

    @Override
    public void onReceive(Context c, Intent in) {
        if (in == null) return;
        String a = in.getAction();
        try {
            if (ACTION_FULL.equals(a)) {
                Uri u = in.getData();
                if (u != null && u.getLastPathSegment() != null) handle(c, u.getLastPathSegment());
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                // alarms do not survive a reboot: arm them again
                long now = System.currentTimeMillis();
                for (Map.Entry<String, ?> en : prefs(c).getAll().entrySet()) {
                    String k = en.getKey();
                    if (!k.startsWith("na:") || !(en.getValue() instanceof Long)) continue;
                    String pkg = k.substring(3);
                    long when = ((Long) en.getValue()).longValue();
                    if (when <= now) handle(c, pkg);
                    else setAlarm(c, pkg, when);
                }
            }
        } catch (Exception e) {
            // a failed notification must never crash the process
        }
    }

    /** Foreground time and last close of one game between two moments. */
    static long[] played(Context c, String pkg, long from, long to) {
        long total = 0, last = 0, open = -1;
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = usm.queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev != null && ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (!pkg.equals(e.getPackageName())) continue;
                int type = e.getEventType();
                if (type == 1) {
                    if (open < 0) open = e.getTimeStamp();
                } else if (type == 2 && open >= 0) {
                    total += e.getTimeStamp() - open;
                    last = e.getTimeStamp();
                    open = -1;
                }
            }
            if (open >= 0) {
                total += to - open;
                last = to;
            }
        } catch (Exception e) {
            // no usage access: fall back to the stored estimate
        }
        return new long[]{total, last};
    }

    static void handle(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        if (!p.contains("na:" + pkg)) return;
        Set<String> tracked = p.getStringSet("pkgs", null);
        int cap = p.getInt("sc:" + pkg, 0);
        if (!p.getBoolean("notif", true) || tracked == null || !tracked.contains(pkg) || cap <= 0) {
            cancel(c, pkg);
            return;
        }
        long now = System.currentTimeMillis();
        long when = p.getLong("na:" + pkg, 0L);
        if (when > now + 60000L) {          // woke up early: wait for the real moment
            setAlarm(c, pkg, when);
            return;
        }
        long anchor = p.getLong("nt:" + pkg, 0L);
        String label = p.getString("nl:" + pkg, pkg);

        // played again since the estimate was made? then the stamina was spent again
        long minMs = p.getInt("minMin", 5) * 60000L;
        long[] r = played(c, pkg, anchor + 1000L, now);
        if (r[0] >= minMs && r[1] > anchor) {
            MainActivity.Item it = new MainActivity.Item();
            it.pkg = pkg;
            it.cap = cap;
            it.regen = p.getFloat("sr:" + pkg, 0f);
            it.daily = p.getInt("sd:" + pkg, -1);
            it.aTime = r[1];
            it.aVal = 0;
            long w = MainActivity.fullAt(it);
            if (w != Long.MAX_VALUE && w > now) {
                schedule(c, pkg, label, w, r[1]);
                return;
            }
        }

        p.edit().remove("na:" + pkg).remove("nt:" + pkg).remove("nl:" + pkg).apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "스테미나 가득 알림",
                NotificationManager.IMPORTANCE_LOW);     // low = no sound, no vibration
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);

        Intent open = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (open == null) open = new Intent(c, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(c, pkg.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | IMMUTABLE);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                .setContentTitle(label + " 스테미나 가득")
                .setContentText(cap + " / " + cap + " · 누르면 게임 실행")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        nm.notify(pkg.hashCode(), n);
    }
}
