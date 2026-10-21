package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

/** Uygulama kapalıyken de ekranda duran kayan mikrofon ve kamera düğmeleri. */
public class BubbleService extends Service {
    static final String CH = "kisayol", STOP = "com.yildiz.notasistani.BUBBLE_STOP";
    static volatile BubbleService inst;
    WindowManager wm;
    LinearLayout box;
    WindowManager.LayoutParams lp;
    ImageView micV, camV;
    TextView panel;
    boolean micReady = false;
    final Handler h = new Handler(Looper.getMainLooper());

    // kayıt durumu
    volatile boolean recOn = false, canceled = false;
    AudioRecord ar;
    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    boolean heard = false;
    long recStart;
    SpeechRecognizer sr;
    ParcelFileDescriptor sttW;
    final LinkedBlockingQueue<byte[]> sttQ = new LinkedBlockingQueue<>(600);
    final StringBuilder said = new StringBuilder();
    volatile String partial = "";
    volatile boolean sttEnded = true;
    static final int RATE = 16000;
    static final long MAX_MS = 180000;

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void start(Context c) {
        if (!canDraw(c)) return;
        Intent i = new Intent(c, BubbleService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BubbleService.class));
    }

    /** Uygulama ekrandayken düğmeleri gizle (uygulamanın kendi düğmeleri var). */
    static void appVisible(boolean v) {
        BubbleService s = inst;
        if (s != null && s.box != null) s.box.post(() -> s.box.setVisibility(v ? View.GONE : View.VISIBLE));
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("bubble", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();
        if (box == null) {
            if (!canDraw(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            show();
        }
        inst = this;
        appVisible(MainActivity.visible);
        return START_STICKY;
    }

    void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Kayan kısayollar", NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 41, new Intent(this, BubbleService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("Kayan düğmeler açık").setContentText("Mikrofon ve kamera her ekranda")
                .setOngoing(true).setColor(0xFF2563EB)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "Kapat", stop).build());
        Notification n = b.build();
        // Uygulama ekrandayken başlatıldıysa mikrofon türünü de ekle: sonra uygulama kapalıyken de doğrudan kayıt yapılabilir
        boolean mic = MainActivity.visible && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | (mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
            else if (Build.VERSION.SDK_INT >= 30) startForeground(9, n, mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
            else startForeground(9, n);
            if (mic || Build.VERSION.SDK_INT < 30) micReady = true;
        } catch (Exception e) {
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(9, n);
            } catch (Exception ignored) {
            }
        }
    }

    /** Uygulama açıldığında çağrılır: mikrofon izni türünü ekler. */
    static void upgrade(Context c) {
        BubbleService s = inst;
        if (s != null && !s.micReady) s.h.post(s::startFg);
    }

    ImageView btn(int icon, int bg, String act) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setBackgroundResource(bg);
        int p = dp(13);
        v.setPadding(p, p, p, p);
        v.setElevation(dp(6));
        v.setContentDescription(act.equals("voice") ? "Sesli not" : "Fotoğraflı not");
        v.setTag(act);
        return v;
    }

    void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView mic = btn(R.drawable.ic_mic, R.drawable.bg_mic, "voice"), cam = btn(R.drawable.ic_cam, R.drawable.bg_cam, "camera");
        micV = mic;
        camV = cam;
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(52), dp(52));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(dp(52), dp(52));
        b2.topMargin = dp(10);
        box.addView(mic, a);
        box.addView(cam, b2);
        SharedPreferences pr = getSharedPreferences("app", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = pr.getInt("bx", dm.widthPixels - dp(68));
        lp.y = pr.getInt("by", dm.heightPixels / 2);
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;
            long down;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        moved = false;
                        down = System.currentTimeMillis();
                        v.setAlpha(.8f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        if (moved) {
                            lp.x = ox + Math.round(dx);
                            lp.y = oy + Math.round(dy);
                            try {
                                wm.updateViewLayout(box, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        if (moved) {
                            // kenara yapıştır
                            int w = getResources().getDisplayMetrics().widthPixels;
                            lp.x = lp.x + box.getWidth() / 2 < w / 2 ? 0 : w - box.getWidth();
                            try {
                                wm.updateViewLayout(box, lp);
                            } catch (Exception ignored) {
                            }
                            pr.edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (System.currentTimeMillis() - down > 700) { // uzun basış: kapat
                                Toast.makeText(BubbleService.this, "Kayan düğmeler kapatıldı (Ayarlar'dan açabilirsin)", Toast.LENGTH_LONG).show();
                                pr.edit().putBoolean("bubble", false).apply();
                                stopSelf();
                            } else tap((String) v.getTag());
                        }
                        return true;
                }
                return false;
            }
        };
        mic.setOnTouchListener(drag);
        cam.setOnTouchListener(drag);
        wm.addView(box, lp);
    }

    void tap(String act) {
        if (recOn) { // kayıt sırasında: mikrofon = bitir, ✕ = iptal
            if ("voice".equals(act)) finishRec(false);
            else finishRec(true);
            return;
        }
        if ("voice".equals(act) && micReady && startRec()) return;
        open(act);
    }

    // ---------------- Uygulamayı açmadan sesli not ----------------
    void showPanel(String t) {
        h.post(() -> {
            try {
                if (panel == null) {
                    panel = new TextView(this);
                    panel.setBackgroundResource(R.drawable.bg_panel);
                    panel.setTextColor(0xFFFFFFFF);
                    panel.setTextSize(15);
                    panel.setPadding(dp(16), dp(12), dp(16), dp(12));
                    panel.setMaxLines(6);
                    panel.setEllipsize(android.text.TextUtils.TruncateAt.START);
                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(getResources().getDisplayMetrics().widthPixels - dp(32), WindowManager.LayoutParams.WRAP_CONTENT,
                            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
                    p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    p.y = dp(48);
                    wm.addView(panel, p);
                }
                panel.setText(t);
            } catch (Exception ignored) {
            }
        });
    }

    void hidePanel(long delay) {
        h.postDelayed(() -> {
            if (recOn) return;
            try {
                if (panel != null) wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }, delay);
    }

    void recUI(boolean on) {
        h.post(() -> {
            if (micV == null) return;
            micV.setBackgroundResource(on ? R.drawable.bg_rec : R.drawable.bg_mic);
            micV.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_mic);
            camV.setBackgroundResource(on ? R.drawable.bg_gray : R.drawable.bg_cam);
            camV.setImageResource(on ? R.drawable.ic_close : R.drawable.ic_cam);
        });
    }

    boolean startRec() {
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
        canceled = false;
        recOn = true;
        recStart = System.currentTimeMillis();
        recUI(true);
        showPanel("🎤 Dinliyorum… konuşunca yazıya geçer\n■ bitir · ✕ iptal");
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
            if (peak > 1500) heard = true;
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
                showPanel((t.isEmpty() ? "🎤 Dinliyorum… " : "🎤 " + t + "  ") + String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60));
            }
            if (now - recStart > MAX_MS) {
                h.post(() -> finishRec(false));
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
                    getPackageManager().getServiceInfo(MainActivity.GOOGLE_STT, 0);
                    hasG = true;
                } catch (Exception e) {
                    hasG = false;
                }
                sr = hasG ? SpeechRecognizer.createSpeechRecognizer(this, MainActivity.GOOGLE_STT) : SpeechRecognizer.createSpeechRecognizer(this);
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
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
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

    void finishRec(boolean cancel) {
        if (!recOn) return;
        recOn = false;
        canceled = cancel;
        recUI(false);
        try {
            ar.stop();
            ar.release();
        } catch (Exception ignored) {
        }
        ar = null;
        if (cancel) {
            stopStt();
            showPanel("✕ İptal edildi");
            hidePanel(1200);
            return;
        }
        showPanel("⏳ Not kaydediliyor…");
        new Thread(this::process, "kayan-isle").start();
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

    /** Yazıya dök → AI ile not/görev/hatırlatmaya ayır → kaydet (uygulama açılınca listelere eklenir). */
    void process() {
        // Google tanımanın son cümleyi bitirmesini bekle
        for (int i = 0; i < 30 && !sttEnded; i++) {
            try {
                Thread.sleep(100);
            } catch (Exception ignored) {
            }
        }
        stopStt();
        String text = (said + " " + partial).trim();
        SharedPreferences pr = getSharedPreferences("app", MODE_PRIVATE);
        String code = pr.getString("qCode", ""), model = pr.getString("qModel", "gemini-flash-latest");
        byte[] audio = pcm.toByteArray();
        if (text.isEmpty() && heard && audio.length > RATE) { // Google tanıma yoksa sesi Gemini yazsın
            try {
                byte[] wav = new byte[44 + audio.length];
                System.arraycopy(DiaryService.wavHeader(audio.length), 0, wav, 0, 44);
                System.arraycopy(audio, 0, wav, 44, audio.length);
                JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                                .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                                .put(new JSONObject().put("text", "Bu ses kaydını Türkçe olarak kelimesi kelimesine yazıya dök. Sadece metni ver. Konuşma yoksa yalnızca BOŞ yaz.")))))
                        .put("generationConfig", new JSONObject().put("temperature", 0).put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
                text = Summary.post(this, new JSONObject().put("model", model).put("body", body), code).trim();
                if (text.matches("(?i)^BOŞ\\.?$")) text = "";
            } catch (Exception ignored) {
            }
        }
        if (text.isEmpty()) {
            showPanel(heard ? "⚠️ Ses yazıya dökülemedi (internet?)" : "🤫 Ses duyulmadı");
            hidePanel(2500);
            return;
        }
        long ts = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        String summary;
        try {
            out.put("ts", ts).put("transcript", text);
            String sys = pr.getString("qPrompt", "");
            JSONObject r = null;
            if (!sys.isEmpty()) {
                try {
                    JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)))))
                            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys.replace("{{NOW}}", nowCtx())))))
                            .put("generationConfig", new JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json").put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));
                    r = new JSONObject(jtext(Summary.post(this, new JSONObject().put("model", model).put("body", body), code)));
                } catch (Exception ignored) {
                }
            }
            if (r == null) { // internet yok: not olarak sakla, uygulama sonra AI ile ayırır
                out.put("pending", true);
                summary = "📝 Not kaydedildi (internet gelince görevlere ayrılacak)";
            } else {
                JSONArray tasks = r.optJSONArray("tasks"), notes = r.optJSONArray("notes");
                if (tasks == null) tasks = new JSONArray();
                if (notes == null) notes = new JSONArray();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject t = tasks.getJSONObject(i);
                    String id = "b" + ts + "_" + i;
                    t.put("id", id);
                    long w = parseLocal(t.optString("remind", null));
                    if (w > System.currentTimeMillis()) Alarms.set(this, id, w, "⏰ " + t.optString("title"), t.optString("note", ""));
                    sb.append("✅ ").append(t.optString("title")).append("\n");
                }
                for (int i = 0; i < notes.length(); i++) sb.append("📝 ").append(notes.getJSONObject(i).optString("title")).append("\n");
                out.put("tasks", tasks).put("notes", notes);
                summary = sb.length() > 0 ? "✔ Kaydedildi\n" + sb.toString().trim() : "✔ Not kaydedildi";
            }
            File d = new File(getFilesDir(), "bubble");
            d.mkdirs();
            Summary.write(new File(d, ts + ".json"), out.toString());
        } catch (Exception e) {
            summary = "⚠️ Kaydedilemedi";
        }
        showPanel(summary);
        hidePanel(3500);
        try {
            Notif.show(this, 4242, "Sesli not", summary.replace("✔ Kaydedildi\n", ""));
        } catch (Exception ignored) {
        }
    }

    void open(String act) {
        Intent i = new Intent(this, MainActivity.class).putExtra("act", act)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                QuickWidget.act(this, act, act.equals("voice") ? 33 : 34).send();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        inst = null;
        if (recOn) finishRec(true);
        try {
            if (panel != null) wm.removeView(panel);
        } catch (Exception ignored) {
        }
        try {
            if (box != null) wm.removeView(box);
        } catch (Exception ignored) {
        }
        box = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
