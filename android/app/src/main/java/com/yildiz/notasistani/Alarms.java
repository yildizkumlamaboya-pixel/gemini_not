package com.yildiz.notasistani;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;

class Alarms {
    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("alarms", Context.MODE_PRIVATE);
    }

    static PendingIntent pi(Context c, String id, String title, String body) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("NA_ALARM_" + id);
        i.putExtra("id", id);
        i.putExtra("title", title);
        i.putExtra("body", body);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void set(Context c, String id, long when, String title, String body) {
        try {
            JSONObject o = new JSONObject();
            o.put("when", when);
            o.put("title", title);
            o.put("body", body);
            sp(c).edit().putString(id, o.toString()).apply();
        } catch (Exception ignored) {
        }
        arm(c, id, when, title, body);
    }

    static void arm(Context c, String id, long when, String title, String body) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent p = pi(c, id, title, body);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        }
    }

    static void cancel(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c, id, "", ""));
        sp(c).edit().remove(id).apply();
    }

    static void done(Context c, String id) {
        sp(c).edit().remove(id).apply();
    }

    static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis();
        for (Iterator<String> it = sp(c).getAll().keySet().iterator(); it.hasNext(); ) {
            String id = it.next();
            try {
                JSONObject o = new JSONObject(sp(c).getString(id, "{}"));
                long when = o.getLong("when");
                String t = o.optString("title"), b = o.optString("body");
                if (when > now) arm(c, id, when, t, b);
                else if (now - when < 6 * 3600 * 1000L) {
                    Notif.show(c, id.hashCode(), t, b);
                    done(c, id);
                } else done(c, id);
            } catch (Exception ignored) {
            }
        }
    }
}
