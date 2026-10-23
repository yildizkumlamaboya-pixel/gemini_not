package com.yildiz.notasistani;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Hatırlatma bildirimindeki "Tamamlandı" ve "Ertele" düğmeleri: uygulamayı açmadan çalışır. */
public class NotifAction extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String id = i.getStringExtra("id"), act = i.getStringExtra("act");
        if (id == null || act == null) return;
        try {
            c.getSystemService(NotificationManager.class).cancel(id.hashCode());
        } catch (Exception ignored) {
        }
        long until = 0;
        if ("snooze".equals(act)) {
            int min = i.getIntExtra("min", 15);
            until = System.currentTimeMillis() + min * 60000L;
            Alarms.set(c, id, until, i.getStringExtra("title"), i.getStringExtra("body"));
            Toast.makeText(c, "⏰ " + (min >= 60 ? (min / 60) + " saat" : min + " dk") + " sonra tekrar hatırlatılacak", Toast.LENGTH_SHORT).show();
        } else {
            Alarms.cancel(c, id);
            Toast.makeText(c, "✓ Tamamlandı", Toast.LENGTH_SHORT).show();
        }
        // Uygulama açılınca görevlere işlenir (veriler uygulamanın içinde)
        try {
            SharedPreferences sp = c.getSharedPreferences("app", Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(sp.getString("notifActs", "[]"));
            a.put(new JSONObject().put("id", id).put("act", act).put("until", until).put("at", System.currentTimeMillis()));
            sp.edit().putString("notifActs", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
