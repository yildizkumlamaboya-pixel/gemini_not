package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Ana ekran widget'ındaki mikrofon: uygulamayı açmadan kaydeder; sessizlikte ya da tekrar basınca kaydeder. */
public class QuickRecService extends Service {
    static final String TOGGLE = "com.yildiz.notasistani.QREC_TOGGLE", CANCEL = "com.yildiz.notasistani.QREC_CANCEL", CH = "sesli_not";
    static volatile boolean recording = false;
    long lastNote = 0;

    QuickRec rec;

    static PendingIntent toggle(Context c) {
        Intent i = new Intent(c, QuickRecService.class).setAction(TOGGLE);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 51, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(c, 51, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    Notification note(String text, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Sesli not (widget)", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(ongoing ? "🎤 Sesli not" : "Sesli not").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(ongoing).setColor(0xFFEF4444).setOnlyAlertOnce(true);
        if (ongoing) {
            PendingIntent cancel = PendingIntent.getService(this, 52, new Intent(this, QuickRecService.class).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "■ Kaydet", toggle(this)).build());
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "✕ İptal", cancel).build());
        }
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? TOGGLE : intent.getAction();
        if (rec == null) rec = new QuickRec(this, new QuickRec.L() {
            public void state(boolean on) {
                recording = on;
                QuickWidget.refresh(QuickRecService.this);
            }

            public void text(String t) {
                long now = System.currentTimeMillis();
                if (now - lastNote < 700) return;
                lastNote = now;
                try {
                    getSystemService(NotificationManager.class).notify(8, note(t, true));
                } catch (Exception ignored) {
                }
            }

            public void done(String summary, boolean ok) {
                recording = false;
                QuickWidget.refresh(QuickRecService.this);
                stopForeground(true);
                try {
                    Notif.show(QuickRecService.this, 4242, "Sesli not", summary.replace("✔ Kaydedildi\n", "✔ "));
                } catch (Exception ignored) {
                }
                stopSelf();
            }
        });
        if (CANCEL.equals(a)) {
            if (rec.isOn()) rec.finish(true);
            else stopSelf();
            return START_NOT_STICKY;
        }
        // TOGGLE
        if (rec.isOn()) {
            rec.finish(false);
            return START_NOT_STICKY;
        }
        if (rec.busy) return START_NOT_STICKY; // önceki not işleniyor
        try {
            Notification n = note("Dinliyorum… konuş; susunca kendiliğinden kaydedilir", true);
            if (Build.VERSION.SDK_INT >= 30) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(8, n);
        } catch (Exception e) {
            openApp();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!rec.start()) { // mikrofon açılamadı: uygulamada dene
            stopForeground(true);
            openApp();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void openApp() {
        try {
            QuickWidget.act(this, "voice", 31).send();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (rec != null && rec.isOn()) rec.finish(true);
        recording = false;
        QuickWidget.refresh(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
