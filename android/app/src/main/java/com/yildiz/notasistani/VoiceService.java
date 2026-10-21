package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Sesli not: kaydı ve Gemini işlemini arka planda yapar; ekran kapalıyken de sürer, bitince sonucu dosyaya yazar. */
public class VoiceService extends Service {
    static final String STOP = "com.yildiz.notasistani.VOICE_STOP";
    static final String CANCEL = "com.yildiz.notasistani.VOICE_CANCEL";
    static final String CH = "sesli_not";
    static final long MAX = 180000;
    static volatile boolean recording = false, busy = false;
    static volatile long since = 0;
    static volatile int level = 0;

    MediaRecorder rec;
    File cur;
    Handler h;
    PowerManager.WakeLock wl;
    String req, prompt, system, code, model;
    boolean json;

    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (rec != null) level = Math.min(100, rec.getMaxAmplitude() / 200);
            } catch (Exception ignored) {
            }
            if (recording) h.postDelayed(this, 150);
        }
    };
    final Runnable auto = () -> stopRec(true);

    static File resDir(android.content.Context c) {
        File d = new File(c.getFilesDir(), "voice");
        d.mkdirs();
        return d;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (h == null) h = new Handler(Looper.getMainLooper());
        String a = i == null ? null : i.getAction();
        if (STOP.equals(a)) {
            stopRec(true);
            return START_NOT_STICKY;
        }
        if (CANCEL.equals(a)) {
            stopRec(false);
            return START_NOT_STICKY;
        }
        if (recording || busy || i == null) return START_NOT_STICKY;
        req = i.getStringExtra("req");
        prompt = i.getStringExtra("prompt");
        system = i.getStringExtra("system");
        code = i.getStringExtra("code");
        model = i.getStringExtra("model");
        json = i.getBooleanExtra("json", false);
        fg("Sesli not kaydediliyor", true);
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notasistani:sesli");
            wl.acquire(10 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        try {
            cur = new File(getCacheDir(), "voice_" + req + ".aac");
            // Konuşma tanıma için ayarlanmış mikrofon modu; desteklenmezse normal mikrofon
            try {
                rec = newRec(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            } catch (Exception e1) {
                try {
                    if (rec != null) rec.release();
                } catch (Exception ignored) {
                }
                rec = newRec(MediaRecorder.AudioSource.MIC);
            }
        } catch (Exception e) {
            try {
                if (rec != null) rec.release();
            } catch (Exception ignored) {
            }
            rec = null;
            result(false, "", "Mikrofon açılamadı");
            finish();
            return START_NOT_STICKY;
        }
        recording = true;
        since = System.currentTimeMillis();
        h.postDelayed(auto, MAX);
        h.post(poll);
        return START_NOT_STICKY;
    }

    MediaRecorder newRec(int source) throws Exception {
        MediaRecorder r = new MediaRecorder();
        rec = r;
        r.setAudioSource(source);
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        r.setAudioSamplingRate(32000);
        r.setAudioChannels(1);
        r.setAudioEncodingBitRate(96000);
        r.setOutputFile(cur.getAbsolutePath());
        r.prepare();
        r.start();
        return r;
    }

    void fg(String title, boolean withStop) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "Sesli not", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 21, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setOngoing(true).setColor(0xFF2563EB).setContentIntent(open);
        if (withStop) {
            PendingIntent stop = PendingIntent.getService(this, 22, new Intent(this, VoiceService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Bitir", stop).build());
            b.setContentText("Bitirince arka planda yazıya dökülür");
        } else b.setContentText("Gemini işliyor, ekranı kapatabilirsin");
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(8, n);
    }

    void stopRec(boolean process) {
        if (!recording) return;
        recording = false;
        level = 0;
        h.removeCallbacks(auto);
        h.removeCallbacks(poll);
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
        if (!process || !ok || cur == null || cur.length() < 800) {
            if (cur != null) cur.delete();
            if (process) result(false, "", "Kayıt çok kısa");
            finish();
            return;
        }
        busy = true;
        fg("Sesli not hazırlanıyor", false);
        new Thread(() -> {
            try {
                byte[] audio;
                try (InputStream is = new FileInputStream(cur)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    audio = bo.toByteArray();
                }
                JSONObject gc = new JSONObject().put("temperature", 0).put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
                if (json) gc.put("responseMimeType", "application/json");
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                                .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/aac").put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
                                .put(new JSONObject().put("text", prompt == null ? "" : prompt)))))
                        .put("generationConfig", gc);
                if (system != null && !system.isEmpty())
                    body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
                String text = null, err = null;
                for (int attempt = 0; attempt < 3 && text == null; attempt++) {
                    try {
                        text = Summary.post(this, new JSONObject().put("model", model == null || model.isEmpty() ? "gemini-flash-latest" : model).put("body", body), code);
                    } catch (Exception e) {
                        err = e.getMessage();
                        Thread.sleep(3000L * (attempt + 1));
                    }
                }
                result(text != null, text == null ? "" : text, text == null ? err : "");
            } catch (Exception e) {
                result(false, "", e.getMessage());
            }
            cur.delete();
            busy = false;
            if (!MainActivity.visible) Notif.show(this, 9100, "🎤 Sesli not hazır", "Kontrol etmek için dokun");
            h.post(this::finish);
        }).start();
    }

    void result(boolean ok, String text, String err) {
        try {
            JSONObject o = new JSONObject().put("id", req).put("ok", ok).put("text", text).put("error", err == null ? "" : err).put("ts", System.currentTimeMillis());
            Summary.write(new File(resDir(this), req + ".json"), o.toString());
        } catch (Exception ignored) {
        }
    }

    void finish() {
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
