package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ekran kapalıyken de süren sesli günlük.
 * Sesi kendimiz kaydederiz (16 kHz PCM) ve aynı sesi anlık olarak Google konuşma tanımaya akıtırız.
 * Google tanıma bir parçayı yazıya dökemediyse (ya da telefonda yoksa) o parça Gemini'ye gönderilir.
 */
public class DiaryService extends Service {
    static final String STOP = "com.yildiz.notasistani.DIARY_STOP";
    static final String CH = "gunluk";
    static final int RATE = 16000;
    // Konuşmadaki duraklamada kes: en az 5 sn, en çok 25 sn; 1 sn sessizlik = cümle sonu
    static final long MIN_CHUNK = 5000, MAX_CHUNK = 25000, SILENCE = 1000;
    static final int VOICE_AMP = 1500;
    // Google tanıma bir parçayı bu süre içinde yazıya dökmezse parça Gemini'ye gider
    static final long STT_WAIT = 20000;
    static final long BATCH_MS = 80000, REFINE_MS = 70000;

    static final AtomicInteger pending = new AtomicInteger(0);
    static final AtomicInteger refining = new AtomicInteger(0);
    static volatile boolean running = false;
    static volatile long since = 0;
    static volatile int level = 0;
    static volatile String error = "";
    static volatile String sttMode = "";
    static volatile int waitN = 0;
    static volatile DiaryService inst;
    static volatile int gen = 0; // "yazmayı iptal et" ile artar: önceki işlerin sonucu yazılmaz // Google tanımayı bekleyen parça sayısı // "google", "cihaz", "" (yok)

    ExecutorService pool, refinePool;
    String prompt, code, model, refinePrompt, refineModel;
    final java.util.List<long[]> qbuf = new java.util.ArrayList<>();
    final java.util.Map<Long, String> qtext = new java.util.HashMap<>();
    final java.util.ArrayDeque<long[]> wq = new java.util.ArrayDeque<>();
    // Google tanımanın yazmasını bekleyen parçalar: dosya -> {başlangıç, bitiş, süre}
    final java.util.concurrent.ConcurrentHashMap<File, long[]> waiting = new java.util.concurrent.ConcurrentHashMap<>();
    volatile String lastRefined = "", lastText = "";

    Handler h;
    PowerManager.WakeLock wl;
    AudioRecord ar;
    Thread recThread;

    // geçerli parça
    ByteArrayOutputStream chunk;
    long curStart, lastVoice;
    boolean hadVoice;

    // Google tanıma
    SpeechRecognizer sr;
    ParcelFileDescriptor sttWrite;
    final LinkedBlockingQueue<byte[]> sttQ = new LinkedBlockingQueue<>(400);
    Thread sttThread;
    volatile boolean sttActive = false, sttHealthy = false;
    volatile long lastSttAt = 0, voiceSince = 0;
    int sttFails = 0, sttKind = 0; // 0: Google, 1: cihaz içi, 2: yok

    static File dir(android.content.Context c) {
        File d = new File(c.getFilesDir(), "diary");
        d.mkdirs();
        return d;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (h == null) h = new Handler(Looper.getMainLooper());
        inst = this;
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
        if (prompt == null || prompt.isEmpty()) prompt = "Bu ses kaydını Türkçe olarak kelimesi kelimesine yazıya dök. Konuşma yoksa yalnızca BOŞ yaz.";
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
            if (!startRecording()) {
                stopAll();
                return START_NOT_STICKY;
            }
            sttFails = 0;
            sttKind = 0;
            sttHealthy = false;
            sttStart();
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
                .setContentText("Konuşmalar anında yazıya dökülüyor")
                .setOngoing(true)
                .setColor(0xFFEF4444)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "Durdur", stop).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    // ---------------- Kayıt ----------------
    boolean startRecording() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, RATE * 2); // 1 sn
        for (int src : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}) {
            try {
                AudioRecord a = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (a.getState() != AudioRecord.STATE_INITIALIZED) {
                    a.release();
                    continue;
                }
                a.startRecording();
                ar = a;
                break;
            } catch (Exception ignored) {
            }
        }
        if (ar == null) {
            error = "Mikrofon açılamadı";
            return false;
        }
        newChunk();
        recThread = new Thread(this::recLoop, "gunluk-kayit");
        recThread.start();
        return true;
    }

    void newChunk() {
        chunk = new ByteArrayOutputStream(RATE * 2 * 30);
        curStart = System.currentTimeMillis();
        lastVoice = 0;
        hadVoice = false;
    }

    void recLoop() {
        byte[] frame = new byte[RATE / 10 * 2]; // 100 ms
        while (running && ar != null) {
            int n;
            try {
                n = ar.read(frame, 0, frame.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            int peak = 0;
            for (int i = 0; i + 1 < n; i += 2) {
                int v = Math.abs((short) ((frame[i] & 0xff) | (frame[i + 1] << 8)));
                if (v > peak) peak = v;
            }
            level = Math.min(100, peak / 200);
            long now = System.currentTimeMillis();
            if (peak > VOICE_AMP) {
                lastVoice = now;
                hadVoice = true;
                if (voiceSince == 0) voiceSince = now;
            }
            chunk.write(frame, 0, n);
            if (sttActive) {
                byte[] c = new byte[n];
                System.arraycopy(frame, 0, c, 0, n);
                if (!sttQ.offer(c)) sttQ.poll(); // tanıma geride kalırsa en eskiyi at
            }
            long len = now - curStart;
            boolean pause = hadVoice && len >= MIN_CHUNK && now - lastVoice >= SILENCE;
            if (pause || len >= MAX_CHUNK) {
                closeChunk(hadVoice);
                newChunk();
            }
        }
    }

    /** Parça bitti: konuşma varsa dosyaya yaz; Google tanıma yazıya dökmezse Gemini'ye gönder. */
    void closeChunk(boolean voice) {
        final long start = curStart, end = System.currentTimeMillis(), dur = end - start;
        final byte[] pcm = chunk.toByteArray();
        if (!voice || pcm.length < RATE) return; // sessiz parça
        final File f = new File(dir(this), "w_" + start + "_" + dur + ".wav");
        try {
            writeWav(f, pcm);
        } catch (Exception e) {
            return;
        }
        pending.incrementAndGet();
        waiting.put(f, new long[]{start, end, dur});
        waitN = waiting.size();
        checkLater(f, 0);
    }

    void checkLater(File f, int tries) {
        h.postDelayed(() -> {
            long[] w = waiting.get(f);
            if (w == null) return;
            if (sttActive && sttHealthy && lastSttAt < w[1] && tries < 3) { // tanıma çalışıyor ama cümle henüz bitmedi
                checkLater(f, tries + 1);
                return;
            }
            resolve(f);
        }, sttActive ? STT_WAIT / 3 : 0);
    }

    /** Google tanıma yazdıysa sesi sil, yazmadıysa Gemini kuyruğuna ver. */
    void resolve(File f) {
        long[] w = waiting.remove(f);
        waitN = waiting.size();
        if (w == null) return;
        if (sttHealthy && lastSttAt >= w[1]) {
            f.delete();
            pending.decrementAndGet();
            return;
        }
        synchronized (wq) {
            wq.add(new long[]{w[0], w[2]});
        }
        try {
            pool.submit(this::drain);
        } catch (Exception e) {
            pending.decrementAndGet();
        }
    }

    static void writeWav(File f, byte[] pcm) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(wavHeader(pcm.length));
            os.write(pcm);
        }
    }

    static byte[] wavHeader(int len) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(len);
        return b.array();
    }

    static byte[] readPcm(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            byte[] all = bo.toByteArray();
            if (all.length <= 44) return new byte[0];
            byte[] p = new byte[all.length - 44];
            System.arraycopy(all, 44, p, 0, p.length);
            return p;
        }
    }

    // ---------------- Google konuşma tanıma (kendi kaydımızdan beslenir) ----------------
    void sttStart() {
        if (!running) return;
        if (Build.VERSION.SDK_INT < 33 || sttKind > 1) { // ses kaynağını vermek Android 13+ ister
            sttMode = "";
            sttActive = false;
            return;
        }
        h.post(() -> {
            try {
                sttStop(false);
                if (sttKind == 0) {
                    android.content.ComponentName g = MainActivity.GOOGLE_STT;
                    boolean hasG;
                    try {
                        getPackageManager().getServiceInfo(g, 0);
                        hasG = true;
                    } catch (Exception e) {
                        hasG = false;
                    }
                    sr = hasG ? SpeechRecognizer.createSpeechRecognizer(this, g) : SpeechRecognizer.createSpeechRecognizer(this);
                } else {
                    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                        sttKind = 2;
                        sttMode = "";
                        return;
                    }
                    sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                }
                sr.setRecognitionListener(listener);
                ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                sttWrite = p[1];
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0]);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
                i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                Summary.bias(this, i);
                sttQ.clear();
                sttActive = true;
                sttMode = sttKind == 0 ? "google" : "cihaz";
                final ParcelFileDescriptor w = p[1];
                sttThread = new Thread(() -> feed(w), "gunluk-tanima");
                sttThread.start();
                sr.startListening(i);
                try {
                    p[0].close(); // tanıma servisi kendi kopyasını tutar
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                sttFailed();
            }
        });
    }

    void feed(ParcelFileDescriptor w) {
        try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(w)) {
            while (sttActive && running && w == sttWrite) {
                byte[] b = sttQ.poll(500, TimeUnit.MILLISECONDS);
                if (b != null) os.write(b);
            }
        } catch (Exception ignored) {
        }
    }

    void sttStop(boolean finish) {
        sttActive = false;
        ParcelFileDescriptor w = sttWrite;
        sttWrite = null;
        try {
            if (w != null) w.close();
        } catch (Exception ignored) {
        }
        try {
            if (sr != null) {
                if (finish) sr.stopListening();
                else sr.cancel();
                if (!finish) sr.destroy();
            }
        } catch (Exception ignored) {
        }
        if (!finish) sr = null;
    }

    void sttFailed() {
        sttActive = false;
        sttFails++;
        if (!sttHealthy && sttFails >= 2) { // bu tanıyıcı çalışmıyor: sıradakini dene
            sttKind++;
            sttFails = 0;
        }
        if (sttKind > 1) {
            sttMode = "";
            h.post(() -> sttStop(false));
            return;
        }
        if (running) h.postDelayed(this::sttStart, sttHealthy ? 1000 : 300);
    }

    void onSttText(ArrayList<String> r) {
        if (r == null || r.isEmpty()) return;
        String t = r.get(0) == null ? "" : r.get(0).trim();
        long now = System.currentTimeMillis();
        sttHealthy = true;
        sttFails = 0;
        lastSttAt = now;
        if (t.isEmpty()) return;
        long ts = voiceSince > 0 ? voiceSince : now - 3000;
        voiceSince = 0;
        try {
            File f = new File(dir(this), "r_" + ts + ".json");
            if (f.exists()) f = new File(dir(this), "r_" + (ts + 1) + ".json");
            Summary.write(f, new JSONObject().put("ts", ts).put("dur", Math.max(1000, now - ts)).put("text", t).put("stt", true).toString());
        } catch (Exception ignored) {
        }
    }

    final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle b) {}
        public void onBeginningOfSpeech() {}
        public void onRmsChanged(float v) {}
        public void onBufferReceived(byte[] b) {}
        public void onEndOfSpeech() {}
        public void onEvent(int t, Bundle b) {}
        public void onPartialResults(Bundle b) {}

        public void onSegmentResults(Bundle b) {
            onSttText(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        }

        public void onEndOfSegmentedSession() {
            if (running) sttStart();
        }

        public void onResults(Bundle b) { // bölümlü oturumu desteklemeyen tanıyıcılar
            onSttText(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
            if (running) sttStart();
        }

        public void onError(int e) {
            if (!running) return;
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { // tanıma çalışıyor, sadece konuşma duymadı
                sttActive = false;
                h.postDelayed(DiaryService.this::sttStart, 300);
                return;
            }
            sttFailed();
        }
    };

    // ---------------- Gemini (yedek yol) ----------------
    /** Kuyruktaki ardışık parçaları (en çok ~80 sn) tek dosyada birleştirip tek istekle yazıya döker. */
    void drain() {
        java.util.List<long[]> batch = new java.util.ArrayList<>();
        long tot = 0;
        synchronized (wq) {
            while (!wq.isEmpty() && (batch.isEmpty() || tot + wq.peek()[1] <= BATCH_MS)) {
                long[] q = wq.poll();
                batch.add(q);
                tot += q[1];
            }
        }
        if (batch.isEmpty()) return;
        long start = batch.get(0)[0];
        if (batch.size() == 1) {
            transcribe(new File(dir(this), "w_" + start + "_" + tot + ".wav"), start, tot);
            return;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (long[] q : batch) {
            File f = new File(dir(this), "w_" + q[0] + "_" + q[1] + ".wav");
            try {
                all.write(readPcm(f));
            } catch (Exception ignored) {
            }
            f.delete();
        }
        File out = new File(dir(this), "w_" + start + "_" + tot + ".wav");
        try {
            writeWav(out, all.toByteArray());
        } catch (Exception ignored) {
        }
        pending.addAndGet(-(batch.size() - 1));
        transcribe(out, start, tot);
    }

    String b64(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        }
    }

    /** Bekleyen tüm yazıya dökme işlerini iptal eder, sesleri siler. */
    static void cancelPending(android.content.Context c) {
        gen++;
        DiaryService s = inst;
        if (s != null) {
            synchronized (s.wq) {
                s.wq.clear();
            }
            s.waiting.clear();
            synchronized (s.qbuf) {
                s.qbuf.clear();
                s.qtext.clear();
            }
        }
        waitN = 0;
        pending.set(0);
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().matches("[wq]_\\d+_\\d+\\.(wav|aac)")) f.delete();
    }

    void transcribe(File f, long start, long dur) {
        final int g = gen;
        String text = null, err = null;
        try {
            String prev = lastText;
            String pr = prompt + (prev.isEmpty() ? "" : "\nBir önceki parçanın sonu (bağlam için, tekrar yazma): \"" + prev + "\"");
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                            .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/wav").put("data", b64(f))))
                            .put(new JSONObject().put("text", pr)))))
                    .put("generationConfig", new JSONObject().put("temperature", 0).put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
            JSONObject payload = new JSONObject().put("model", model == null || model.isEmpty() ? "gemini-flash-latest" : model).put("body", body);
            for (int a = 0; a < 3 && text == null; a++) {
                try {
                    text = Summary.post(this, payload, code);
                } catch (Exception e) {
                    err = e.getMessage();
                    boolean quota = err != null && err.matches("(?is).*(kota|yoğun|429|quota).*");
                    Thread.sleep(quota ? 15000L * (a + 1) : 2500L * (a + 1));
                }
            }
        } catch (Exception e) {
            err = e.getMessage();
        }
        if (g != gen) { // iptal edildi
            f.delete();
            return;
        }
        try {
            if (text != null) {
                text = text.replaceAll("(?i)<\\s*/?\\s*(noise|silence|music|inaudible)\\s*>", "").trim();
                if (text.matches("(?i)^BOŞ\\.?$") || text.matches("^[\\s.…,-]*$")) text = "";
                if (!text.isEmpty()) {
                    lastText = text.length() > 200 ? text.substring(text.length() - 200) : text;
                    Summary.write(new File(dir(this), "r_" + start + ".json"), new JSONObject().put("ts", start).put("dur", dur).put("text", text).toString());
                    if (refinePrompt != null && !refinePrompt.isEmpty()) {
                        f.renameTo(new File(dir(this), "q_" + start + "_" + dur + ".wav"));
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
                File keep = new File(dir(this), "f_" + start + "_" + dur + ".wav");
                f.renameTo(keep);
                Summary.write(new File(dir(this), "r_" + start + ".json"), new JSONObject().put("ts", start).put("dur", dur).put("text", "").put("err", err == null ? "Yazıya dökülemedi" : err).put("file", keep.getName()).toString());
            }
        } catch (Exception ignored) {
        }
        pending.decrementAndGet();
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
            for (long[] q : items) new File(dir(this), "q_" + q[0] + "_" + q[1] + ".wav").delete();
        }
    }

    /** Gemini ile yazılan parçaları birleştirip güçlü modelle bağlamıyla yeniden yazıya döker. */
    void refine(java.util.List<long[]> items, java.util.Map<Long, String> texts) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            StringBuilder draft = new StringBuilder();
            JSONArray rep = new JSONArray();
            long total = 0;
            for (long[] q : items) {
                File f = new File(dir(this), "q_" + q[0] + "_" + q[1] + ".wav");
                if (!f.exists()) continue;
                bo.write(readPcm(f));
                draft.append(texts.getOrDefault(q[0], "")).append("\n");
                rep.put(q[0]);
                total += q[1];
            }
            if (rep.length() == 0) return;
            byte[] pcm = bo.toByteArray();
            byte[] wav = new byte[44 + pcm.length];
            System.arraycopy(wavHeader(pcm.length), 0, wav, 0, 44);
            System.arraycopy(pcm, 0, wav, 44, pcm.length);
            String pr = refinePrompt + "\n\nTASLAK (hızlı yapılmış döküm; yanlış duyulmuş kelimeler içerebilir, taslağa değil SESE güven):\n" + draft
                    + (lastRefined.isEmpty() ? "" : "\nBir önceki bölümün sonu (bağlam için, tekrar yazma): \"" + lastRefined + "\"");
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                            .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
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
            for (long[] q : items) new File(dir(this), "q_" + q[0] + "_" + q[1] + ".wav").delete();
            refining.decrementAndGet();
        }
    }

    // ---------------- Durdurma ----------------
    void stopAll() {
        running = false;
        level = 0;
        final Thread rt = recThread;
        new Thread(() -> {
            try {
                if (rt != null) rt.join(1500);
            } catch (Exception ignored) {
            }
            try {
                if (ar != null) {
                    ar.stop();
                    ar.release();
                }
            } catch (Exception ignored) {
            }
            ar = null;
            if (chunk != null) closeChunk(hadVoice); // son parça
            h.post(() -> sttStop(true));
            // Google tanımanın son cümleyi bitirmesi için kısa bekle, kalan parçaları Gemini'ye ver
            try {
                Thread.sleep(4000);
            } catch (Exception ignored) {
            }
            h.post(() -> {
                sttStop(false);
                sttMode = "";
            });
            for (File f : new ArrayList<>(waiting.keySet())) resolve(f); // bekleyenleri hemen karara bağla
            final ExecutorService p = pool, rp = refinePool;
            try {
                if (p != null) {
                    p.shutdown();
                    p.awaitTermination(5, TimeUnit.MINUTES);
                }
                flushRefine();
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
            if (!running) h.post(() -> {
                stopForeground(true);
                stopSelf();
            });
        }).start();
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
