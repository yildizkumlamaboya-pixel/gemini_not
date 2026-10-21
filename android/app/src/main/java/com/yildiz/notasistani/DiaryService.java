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
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Base64;

/** Ekran kapalıyken de süren sesli günlük kaydı: 45 sn'lik AAC parçaları dosyaya yazar, uygulama bunları yazıya döker. */
public class DiaryService extends Service {
    static final String STOP = "com.yildiz.notasistani.DIARY_STOP";
    static final String CH = "gunluk";
    // Konuşmadaki duraklamada kes: en az 4 sn, en çok 25 sn; 1 sn sessizlik = cümle sonu
    static final long MIN_CHUNK = 5000, MAX_CHUNK = 25000, SILENCE = 1000;
    static final AtomicInteger pending = new AtomicInteger(0);
    ExecutorService pool, refinePool;
    String prompt, code, model, refinePrompt, refineModel;
    static final AtomicInteger refining = new AtomicInteger(0);
    // İyileştirme tamponu: taslağı çıkan parçalar ~100 sn birikince güçlü modelle birlikte yeniden yazıya dökülür
    static final long REFINE_MS = 100000;
    final java.util.List<long[]> qbuf = new java.util.ArrayList<>();
    final java.util.Map<Long, String> qtext = new java.util.HashMap<>();
    volatile String lastRefined = "";
    volatile String lastText = "";
    static final int VOICE_AMP = 1500;
    long lastVoice = 0;
    boolean hadVoice = false;
    static volatile boolean running = false;
    static volatile long since = 0;
    static volatile int level = 0;
    static volatile String error = "";

    MediaRecorder rec;
    File cur;
    long curStart;
    Handler h;
    PowerManager.WakeLock wl;

    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                if (rec != null) {
                    int amp = rec.getMaxAmplitude();
                    level = Math.min(100, amp / 200);
                    long now = System.currentTimeMillis(), len = now - curStart;
                    if (amp > VOICE_AMP) {
                        lastVoice = now;
                        hadVoice = true;
                    }
                    boolean pause = hadVoice && len >= MIN_CHUNK && now - lastVoice >= SILENCE;
                    if (pause || len >= MAX_CHUNK) {
                        finishChunk(hadVoice); // hiç konuşma yoksa parçayı atla
                        hadVoice = false;
                        if (running && !startChunk()) running = false;
                    }
                }
            } catch (Exception ignored) {
            }
            if (running) h.postDelayed(this, 100);
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
            if (running) stopAll();
            else if (pool == null || pool.isTerminated()) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra("prompt")) {
            prompt = intent.getStringExtra("prompt");
            refinePrompt = intent.getStringExtra("refinePrompt");
            refineModel = intent.getStringExtra("refineModel");
            code = intent.getStringExtra("code");
            model = intent.getStringExtra("model");
        }
        if (!running) {
            if (pool == null || pool.isShutdown()) pool = Executors.newFixedThreadPool(2);
            if (refinePool == null || refinePool.isShutdown()) refinePool = Executors.newSingleThreadExecutor();
            synchronized (qbuf) {
                qbuf.clear();
                qtext.clear();
            }
            lastRefined = "";
            lastText = "";
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
            lastVoice = 0;
            hadVoice = false;
            cur = new File(dir(this), "c_" + curStart + ".part");
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(prompt != null ? MediaRecorder.OutputFormat.AAC_ADTS : MediaRecorder.OutputFormat.MPEG_4);
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
        finishChunk(true);
    }

    void finishChunk(boolean keep) {
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
            if (keep && ok && cur.length() > 0) {
                if (prompt != null) {
                    // Parçayı hemen arka planda yazıya dök (ekran kapalıyken de)
                    File f = new File(cur.getParentFile(), "w_" + curStart + "_" + dur + ".aac");
                    cur.renameTo(f);
                    final long st = curStart, du = dur;
                    pending.incrementAndGet();
                    pool.submit(() -> transcribe(f, st, du));
                } else cur.renameTo(new File(cur.getParentFile(), "c_" + curStart + "_" + dur + ".m4a"));
            } else cur.delete();
            cur = null;
        }
    }

    void stopAll() {
        running = false;
        level = 0;
        if (h != null) h.removeCallbacksAndMessages(null);
        finishChunk();
        // Kalan parçalar bitene kadar servisi açık tut (en fazla 5 dk)
        final ExecutorService p = pool, rp = refinePool;
        new Thread(() -> {
            try {
                if (p != null) {
                    p.shutdown();
                    p.awaitTermination(5, TimeUnit.MINUTES);
                }
                flushRefine(); // kalan taslakları da iyileştir
                if (rp != null) {
                    rp.shutdown();
                    rp.awaitTermination(6, TimeUnit.MINUTES);
                }
            } catch (Exception ignored) {
            }
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Exception ignored) {
            }
            if (!running && h != null) h.post(() -> {
                stopForeground(true);
                stopSelf();
            });
        }).start();
    }

    void flushRefine() {
        final java.util.List<long[]> items;
        final java.util.Map<Long, String> texts;
        synchronized (qbuf) {
            if (qbuf.isEmpty()) return;
            items = new java.util.ArrayList<>(qbuf);
            texts = new java.util.HashMap<>(qtext);
            qbuf.clear();
            qtext.clear();
        }
        items.sort((a, b) -> Long.compare(a[0], b[0]));
        refining.incrementAndGet();
        try {
            refinePool.submit(() -> refine(items, texts));
        } catch (Exception e) {
            refining.decrementAndGet();
            for (long[] q : items) new File(dir(this), "q_" + q[0] + "_" + q[1] + ".aac").delete();
        }
    }

    /** Birkaç parçanın sesini birleştirip güçlü modelle bağlamıyla birlikte yeniden yazıya döker. */
    void refine(java.util.List<long[]> items, java.util.Map<Long, String> texts) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            StringBuilder draft = new StringBuilder();
            JSONArray rep = new JSONArray();
            long total = 0;
            for (long[] q : items) {
                File f = new File(dir(this), "q_" + q[0] + "_" + q[1] + ".aac");
                if (!f.exists()) continue;
                try (InputStream is = new FileInputStream(f)) { // ADTS çerçeveleri art arda eklenebilir
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                }
                draft.append(texts.getOrDefault(q[0], "")).append("\n");
                rep.put(q[0]);
                total += q[1];
            }
            if (rep.length() == 0) return;
            String pr = refinePrompt + "\n\nTASLAK (hızlı yapılmış döküm; yanlış duyulmuş kelimeler içerebilir, taslağa değil SESE güven):\n" + draft
                    + (lastRefined.isEmpty() ? "" : "\nBir önceki bölümün sonu (bağlam için, tekrar yazma): \"" + lastRefined + "\"");
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                            .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/aac").put("data", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))))
                            .put(new JSONObject().put("text", pr)))))
                    .put("generationConfig", new JSONObject().put("temperature", 0));
            JSONObject payload = new JSONObject().put("model", refineModel == null || refineModel.isEmpty() ? "gemini-pro-latest" : refineModel).put("body", body);
            String text = null;
            for (int a = 0; a < 2 && text == null; a++) {
                try {
                    text = Summary.post(this, payload, code);
                } catch (Exception e) {
                    Thread.sleep(4000);
                }
            }
            if (text != null) {
                text = text.replaceAll("(?i)<\\s*/?\\s*(noise|silence|music|inaudible)\\s*>", "").trim();
                if (!text.isEmpty() && !text.matches("(?i)^BOŞ\\.?$")) {
                    lastRefined = text.length() > 200 ? text.substring(text.length() - 200) : text;
                    Summary.write(new File(dir(this), "rf_" + items.get(0)[0] + ".json"), new JSONObject()
                            .put("ts", items.get(0)[0]).put("dur", total).put("text", text).put("replaces", rep).toString());
                }
            }
        } catch (Exception ignored) {
        } finally {
            for (long[] q : items) new File(dir(this), "q_" + q[0] + "_" + q[1] + ".aac").delete();
            refining.decrementAndGet();
        }
    }

    void transcribe(File f, long start, long dur) {
        String text = null, err = null;
        try {
            byte[] audio;
            try (InputStream is = new FileInputStream(f)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                audio = bo.toByteArray();
            }
            String prev = lastText;
            String pr = prompt + (prev.isEmpty() ? "" : "\nBir önceki parçanın sonu (bağlam için, tekrar yazma): \"" + prev + "\"");
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                            .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/aac").put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
                            .put(new JSONObject().put("text", pr)))))
                    .put("generationConfig", new JSONObject().put("temperature", 0).put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
            JSONObject payload = new JSONObject().put("model", model == null || model.isEmpty() ? "gemini-flash-latest" : model).put("body", body);
            for (int a = 0; a < 3 && text == null; a++) {
                try {
                    text = Summary.post(this, payload, code);
                } catch (Exception e) {
                    err = e.getMessage();
                    Thread.sleep(2500L * (a + 1));
                }
            }
        } catch (Exception e) {
            err = e.getMessage();
        }
        try {
            if (text != null) {
                text = text.replaceAll("(?i)<\\s*/?\\s*(noise|silence|music|inaudible)\\s*>", "").trim();
                if (text.matches("(?i)^BOŞ\\.?$") || text.matches("^[\\s.…,-]*$")) text = "";
                if (!text.isEmpty()) lastText = text.length() > 200 ? text.substring(text.length() - 200) : text;
                if (!text.isEmpty()) {
                    Summary.write(new File(dir(this), "r_" + start + ".json"), new JSONObject().put("ts", start).put("dur", dur).put("text", text).toString());
                    if (refinePrompt != null && !refinePrompt.isEmpty()) {
                        f.renameTo(new File(dir(this), "q_" + start + "_" + dur + ".aac"));
                        long total = 0;
                        synchronized (qbuf) {
                            qbuf.add(new long[]{start, dur});
                            qtext.put(start, text);
                            for (long[] q : qbuf) total += q[1];
                        }
                        if (total >= REFINE_MS) flushRefine();
                    } else f.delete();
                } else f.delete();
            } else {
                File keep = new File(dir(this), "f_" + start + "_" + dur + ".aac");
                f.renameTo(keep);
                Summary.write(new File(dir(this), "r_" + start + ".json"), new JSONObject().put("ts", start).put("dur", dur).put("text", "").put("err", err == null ? "Yazıya dökülemedi" : err).put("file", keep.getName()).toString());
            }
        } catch (Exception ignored) {
        }
        pending.decrementAndGet();
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
