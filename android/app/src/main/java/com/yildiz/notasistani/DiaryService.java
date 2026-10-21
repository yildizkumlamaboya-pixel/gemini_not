package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;

/** Ekran kapalıyken de süren sesli günlük kaydı: 45 sn'lik AAC parçaları dosyaya yazar, uygulama bunları yazıya döker. */
public class DiaryService extends Service {
    static final String STOP = "com.yildiz.notasistani.DIARY_STOP";
    static final String CH = "gunluk";
    static final long CHUNK = 45000;
    static volatile boolean running = false;
    static volatile long since = 0;
    static volatile int level = 0;
    static volatile String error = "";

    MediaRecorder rec;
    File cur;
    long curStart;
    Handler h;
    PowerManager.WakeLock wl;

    final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            finishChunk();
            if (running) {
                startChunk();
                h.postDelayed(this, CHUNK);
            }
        }
    };
    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (rec != null) level = Math.min(100, rec.getMaxAmplitude() / 200);
            } catch (Exception ignored) {
            }
            if (running) h.postDelayed(this, 200);
        }
    };

    static File dir(android.content.Context c) {
        File d = new File(c.getFilesDir(), "diary");
        d.mkdirs();
        return d;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (h == null) h = new Handler(Looper.getMainLooper());
        if (intent != null && STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startFg();
            running = true;
            since = System.currentTimeMillis();
            error = "";
            try {
                PowerManager pm = getSystemService(PowerManager.class);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notasistani:gunluk");
                wl.acquire(12 * 3600 * 1000L);
            } catch (Exception ignored) {
            }
            if (!startChunk()) {
                stopAll();
                stopSelf();
                return START_NOT_STICKY;
            }
            h.postDelayed(rotate, CHUNK);
            h.post(poll);
        }
        return START_NOT_STICKY;
    }

    void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Sesli günlük", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 11, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 12, new Intent(this, DiaryService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Sesli günlük kaydediliyor")
                .setContentText("Konuşmalar yazıya dökülecek")
                .setOngoing(true)
                .setColor(0xFFEF4444)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "Durdur", stop).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    boolean startChunk() {
        try {
            curStart = System.currentTimeMillis();
            cur = new File(dir(this), "c_" + curStart + ".part");
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(32000);
            rec.setAudioChannels(1);
            rec.setAudioEncodingBitRate(80000);
            rec.setOutputFile(cur.getAbsolutePath());
            rec.prepare();
            rec.start();
            return true;
        } catch (Exception e) {
            error = "Mikrofon açılamadı";
            try {
                if (rec != null) rec.release();
            } catch (Exception ignored) {
            }
            rec = null;
            return false;
        }
    }

    void finishChunk() {
        if (rec == null) return;
        boolean ok = true;
        try {
            rec.stop();
        } catch (Exception e) {
            ok = false;
        }
        try {
            rec.release();
        } catch (Exception ignored) {
        }
        rec = null;
        if (cur != null) {
            long dur = System.currentTimeMillis() - curStart;
            if (ok && cur.length() > 0) cur.renameTo(new File(cur.getParentFile(), "c_" + curStart + "_" + dur + ".m4a"));
            else cur.delete();
            cur = null;
        }
    }

    void stopAll() {
        running = false;
        level = 0;
        if (h != null) h.removeCallbacksAndMessages(null);
        finishChunk();
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        if (running) stopAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
