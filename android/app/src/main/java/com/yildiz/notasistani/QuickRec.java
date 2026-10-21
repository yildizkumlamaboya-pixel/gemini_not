package com.yildiz.notasistani;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Uygulamayı açmadan sesli not: kaydeder, Google tanımayla yazıya döker (yoksa Gemini),
 * AI ile not/görev/hatırlatmaya ayırır, alarmı kurar ve sonucu uygulamaya bırakır.
 * Konuşma bitince (sessizlik) ya da tekrar basınca kendiliğinden kaydeder.
 */
class QuickRec {
    interface L {
        void state(boolean rec);

        void text(String t);

        void done(String summary, boolean ok);

        /** Not kaydedildikten sonra AI ayrımı bitince (görev/hatırlatma özeti). */
        default void after(String summary) {
        }
    }

    static final int RATE = 16000;
    static final long MAX_MS = 180000, SILENCE_MS = 2500, NOVOICE_MS = 9000;
    final Service svc;
    final L l;
    final Handler h = new Handler(Looper.getMainLooper());
    volatile boolean recOn = false, canceled = false, busy = false;
    AudioRecord ar;
    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    volatile boolean heard = false;
    volatile long recStart, lastVoice;
    double floor = 800;
    SpeechRecognizer sr;
    ParcelFileDescriptor sttW;
    final LinkedBlockingQueue<byte[]> sttQ = new LinkedBlockingQueue<>(600);
    final StringBuilder said = new StringBuilder();
    volatile String partial = "";
    volatile boolean sttEnded = true;

    QuickRec(Service s, L l) {
        this.svc = s;
        this.l = l;
    }

    boolean isOn() {
        return recOn;
    }

    boolean start() {
        if (recOn || busy) return recOn;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord a = null;
            for (int src : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}) {
                try {
                    a = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
                    if (a.getState() == AudioRecord.STATE_INITIALIZED) break;
                    a.release();
                    a = null;
                } catch (Exception ignored) {
                    a = null;
                }
            }
            if (a == null) return false;
            a.startRecording();
            if (a.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                a.release();
                return false;
            }
            ar = a;
        } catch (Exception e) {
            return false;
        }
        pcm.reset();
        said.setLength(0);
        partial = "";
        heard = false;
        floor = 800;
        lastVoice = 0;
        canceled = false;
        recOn = true;
        recStart = System.currentTimeMillis();
        l.state(true);
        l.text("🎤 Dinliyorum… konuşunca yazıya geçer");
        sttStart();
        new Thread(this::recLoop, "kayan-kayit").start();
        return true;
    }

    void recLoop() {
        byte[] f = new byte[RATE / 10 * 2];
        long lastUi = 0;
        while (recOn && ar != null) {
            int n;
            try {
                n = ar.read(f, 0, f.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            int peak = 0;
            for (int i = 0; i + 1 < n; i += 2) {
                int v = Math.abs((short) ((f[i] & 0xff) | (f[i + 1] << 8)));
                if (v > peak) peak = v;
            }
            long tnow = System.currentTimeMillis();
            if (peak < floor * 2) floor = floor * 0.95 + peak * 0.05; // ortam gürültüsü
            if (peak > Math.max(1500, floor * 3)) {
                heard = true;
                lastVoice = tnow;
            }
            // konuşma bitince (sessizlik) ya da hiç konuşulmazsa kendiliğinden kaydet
            if ((heard && tnow - lastVoice > SILENCE_MS) || (!heard && tnow - recStart > NOVOICE_MS)) {
                h.post(() -> finish(false));
                break;
            }
            pcm.write(f, 0, n);
            if (sttW != null) {
                byte[] c = new byte[n];
                System.arraycopy(f, 0, c, 0, n);
                if (!sttQ.offer(c)) sttQ.poll();
            }
            long now = System.currentTimeMillis();
            if (now - lastUi > 400) {
                lastUi = now;
                long s = (now - recStart) / 1000;
                String t = (said + " " + partial).trim();
                l.text((t.isEmpty() ? "🎤 Dinliyorum… " : "🎤 " + t + "  ") + String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60));
            }
            if (now - recStart > MAX_MS) {
                h.post(() -> finish(false));
                break;
            }
        }
    }

    void sttStart() {
        if (Build.VERSION.SDK_INT < 33) return; // sesi tanımaya vermek Android 13+ ister; değilse Gemini yazar
        h.post(() -> {
            try {
                boolean hasG;
                try {
                    svc.getPackageManager().getServiceInfo(MainActivity.GOOGLE_STT, 0);
                    hasG = true;
                } catch (Exception e) {
                    hasG = false;
                }
                sr = hasG ? SpeechRecognizer.createSpeechRecognizer(svc, MainActivity.GOOGLE_STT) : SpeechRecognizer.createSpeechRecognizer(svc);
                sr.setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle b) {}
                    public void onBeginningOfSpeech() {}
                    public void onRmsChanged(float v) {}
                    public void onBufferReceived(byte[] b) {}
                    public void onEndOfSpeech() {}
                    public void onEvent(int t, Bundle b) {}

                    void add(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (r != null && !r.isEmpty() && r.get(0) != null && !r.get(0).trim().isEmpty()) said.append(said.length() > 0 ? " " : "").append(r.get(0).trim());
                        partial = "";
                    }

                    public void onPartialResults(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (r != null && !r.isEmpty() && r.get(0) != null) partial = r.get(0);
                    }

                    public void onSegmentResults(Bundle b) { add(b); }

                    public void onEndOfSegmentedSession() { sttEnded = true; }

                    public void onResults(Bundle b) {
                        add(b);
                        sttEnded = true;
                    }

                    public void onError(int e) { sttEnded = true; }
                });
                ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                sttW = p[1];
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, svc.getPackageName());
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0]);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
                i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                sttQ.clear();
                sttEnded = false;
                final ParcelFileDescriptor w = p[1];
                new Thread(() -> {
                    try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(w)) {
                        while (recOn && w == sttW) {
                            byte[] b = sttQ.poll(300, TimeUnit.MILLISECONDS);
                            if (b != null) os.write(b);
                        }
                        byte[] b;
                        while ((b = sttQ.poll()) != null) os.write(b); // kalanı da ver
                    } catch (Exception ignored) {
                    }
                }, "kayan-tanima").start();
                sr.startListening(i);
                try {
                    p[0].close();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                sttEnded = true;
                sttW = null;
            }
        });
    }

    void finish(boolean cancel) {
        if (!recOn) return;
        recOn = false;
        canceled = cancel;
        l.state(false);
        try {
            ar.stop();
            ar.release();
        } catch (Exception ignored) {
        }
        ar = null;
        if (cancel) {
            stopStt();
            l.done("✕ İptal edildi", false);
            return;
        }
        l.text("⏳ Not kaydediliyor…");
        busy = true;
        new Thread(() -> {
            try {
                process();
            } finally {
                busy = false;
            }
        }, "kayan-isle").start();
    }

    void stopStt() {
        h.post(() -> {
            try {
                if (sr != null) sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
            sttW = null;
        });
    }

    static String nowCtx() {
        Calendar c = Calendar.getInstance();
        String[] g = {"Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi"};
        return String.format(java.util.Locale.ROOT, "Şu an: %s, %04d-%02d-%02d %02d:%02d (Türkiye saati). Tarihleri \"YYYY-MM-DDTHH:mm\" formatında yerel saatle ver.",
                g[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    static long parseLocal(String s) {
        try {
            if (s == null || s.length() < 16) return 0;
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)), Integer.parseInt(s.substring(14, 16)), 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    static String jtext(String t) {
        String s = t == null ? "" : t.trim();
        if (s.startsWith("```")) s = s.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }

    /** AI sonucu: görevleri/notları dosyaya yazar, alarmları kurar, özet döndürür. */
    String apply(JSONObject out, JSONObject r, long ts) throws Exception {
        JSONArray tasks = r.optJSONArray("tasks"), notes = r.optJSONArray("notes");
        if (tasks == null) tasks = new JSONArray();
        if (notes == null) notes = new JSONArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.getJSONObject(i);
            String id = "b" + ts + "_" + i;
            t.put("id", id);
            long w = parseLocal(t.optString("remind", null));
            if (w > System.currentTimeMillis()) Alarms.set(svc, id, w, "⏰ " + t.optString("title"), t.optString("note", ""));
            sb.append("✅ ").append(t.optString("title")).append(t.optString("remind", "").length() >= 16 ? " ⏰ " + t.optString("remind").substring(11, 16) : "").append("\n");
        }
        for (int i = 0; i < notes.length(); i++) sb.append("📝 ").append(notes.getJSONObject(i).optString("title")).append("\n");
        out.remove("pending");
        out.put("tasks", tasks).put("notes", notes);
        return sb.length() > 0 ? "✔ Kaydedildi\n" + sb.toString().trim() : "✔ Not kaydedildi";
    }

    JSONObject ask(String sys, JSONArray parts, String model, String code) throws Exception {
        JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys.replace("{{NOW}}", nowCtx())))))
                .put("generationConfig", new JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json").put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
        return new JSONObject(jtext(Summary.post(svc, new JSONObject().put("model", model).put("body", body), code)));
    }

    /**
     * Hızlı kayıt: Google tanıma metni varsa not HEMEN kaydedilir (bekletmez), görev/hatırlatma ayrımı arkada yapılır.
     * Metin yoksa ses tek istekte hem yazıya dökülür hem ayrılır.
     */
    void process() {
        for (int i = 0; i < 12 && !sttEnded; i++) { // son cümle için en çok 1,2 sn bekle
            try {
                Thread.sleep(100);
            } catch (Exception ignored) {
            }
        }
        stopStt();
        String text = (said + " " + partial).trim();
        SharedPreferences pr = svc.getSharedPreferences("app", Context.MODE_PRIVATE);
        String code = pr.getString("qCode", ""), model = pr.getString("qModel", "gemini-flash-latest");
        String sys = pr.getString("qPrompt", "");
        if (sys.isEmpty()) sys = "Kullanıcının metnini not ve görevlere ayır. {{NOW}} Sadece JSON: {\"notes\":[{\"title\":\"\",\"body\":\"\",\"tags\":[]}],\"tasks\":[{\"title\":\"\",\"note\":\"\",\"due\":null,\"remind\":null,\"priority\":\"orta\",\"repeat\":\"\"}]}";
        long ts = System.currentTimeMillis();
        File d = new File(svc.getFilesDir(), "bubble");
        d.mkdirs();
        File f = new File(d, ts + ".json");
        JSONObject out = new JSONObject();
        try {
            out.put("ts", ts);
            if (!text.isEmpty()) {
                // 1) hemen kaydet
                out.put("transcript", text).put("pending", true);
                Summary.write(f, out.toString());
                l.done("✔ Kaydedildi\n“" + (text.length() > 120 ? text.substring(0, 120) + "…" : text) + "”", true);
                // 2) arkada görev/hatırlatmaya ayır
                try {
                    JSONObject r = ask(sys, new JSONArray().put(new JSONObject().put("text", text)), model, code);
                    if (f.exists()) { // uygulama henüz almadıysa sonucu ekle
                        String sum = apply(out, r, ts);
                        Summary.write(f, out.toString());
                        l.after(sum);
                    }
                } catch (Exception ignored) { // internet yok: uygulama sonra ayırır
                }
                return;
            }
            if (!heard || pcm.size() < RATE) {
                l.done("🤫 Ses duyulmadı", false);
                return;
            }
            // Google tanıma yok: ses tek istekte yazıya dökülür ve ayrılır
            l.text("⏳ Yazıya dökülüyor…");
            byte[] audio = pcm.toByteArray();
            byte[] wav = new byte[44 + audio.length];
            System.arraycopy(DiaryService.wavHeader(audio.length), 0, wav, 0, 44);
            System.arraycopy(audio, 0, wav, 44, audio.length);
            JSONArray parts = new JSONArray().put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                    .put(new JSONObject().put("text", "Bu ses kaydını kelimesi kelimesine Türkçe yazıya dök (transcript), sonra not ve görevlere ayır."));
            JSONObject r;
            try {
                r = ask(sys + "\nJSON'un İLK alanı \"transcript\" olsun: kaydın kelimesi kelimesine Türkçe metni. Konuşma yoksa transcript boş olsun.", parts, model, code);
            } catch (Exception e) {
                l.done("⚠️ Yazıya dökülemedi (internet?)", false);
                return;
            }
            String tr = r.optString("transcript", "").trim();
            if (tr.isEmpty()) {
                l.done("🤫 Konuşma anlaşılmadı", false);
                return;
            }
            out.put("transcript", tr);
            String sum = apply(out, r, ts);
            Summary.write(f, out.toString());
            l.done(sum, true);
        } catch (Exception e) {
            l.done("⚠️ Kaydedilemedi", false);
        }
    }
}
