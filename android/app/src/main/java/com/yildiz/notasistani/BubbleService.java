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
    ImageView micV, camV, penV;
    TextView panel;
    boolean micReady = false;
    final Handler h = new Handler(Looper.getMainLooper());

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void start(Context c) {
        if (!canDraw(c)) return;
        BubbleService s = inst;
        if (s != null && s.box != null) return; // zaten ekranda: tekrar başlatıp bildirim gösterme
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
        // "Kayan düğmeler açık" bildirimini hemen kaldır: düğmeler ekranda durdukça servis çalışmaya devam eder
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) {
        }
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
        // kayıt artık ayrı servisle yapılıyor; burada bildirim göstermeye gerek yok
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
        ImageView pen = btn(R.drawable.ic_pen, R.drawable.bg_pen, "text");
        penV = pen;
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(dp(52), dp(52));
        b3.topMargin = dp(10);
        box.addView(pen, b3);
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
        pen.setOnTouchListener(drag);
        wm.addView(box, lp);
    }

    final QuickRec rec = new QuickRec(this, new QuickRec.L() {
        public void state(boolean on) {
            recUI(on);
        }

        public void text(String t) {
            showPanel(t);
        }

        public void after(String summary) {
            showPanel(summary);
            hidePanel(3500);
        }

        public void done(String summary, boolean ok) {
            showPanel(summary);
            hidePanel(ok ? 3500 : 1800);
            if (ok) try {
                Notif.show(BubbleService.this, 4242, "Sesli not", summary.replace("✔ Kaydedildi\n", ""));
            } catch (Exception ignored) {
            }
        }
    });

    void tap(String act) {
        if (rec.isOn()) { // (yedek yol) kayıt sırasında: mikrofon = kaydet, ✕ = iptal
            rec.finish(!"voice".equals(act));
            return;
        }
        if (QuickRecService.recording) { // kayıt sırasında: mikrofon = kaydet, ✕ = iptal
            sendRec("voice".equals(act) ? QuickRecService.TOGGLE : QuickRecService.CANCEL);
            return;
        }
        if ("text".equals(act)) { // mini not alanı (yanında biri varken elle yaz)
            try {
                startActivity(new Intent(this, QuickNoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (Exception e) {
                open("note");
            }
            return;
        }
        if ("voice".equals(act)) { // uygulamayı açmadan kayda başla
            sendRec(QuickRecService.TOGGLE);
            return;
        }
        // kamera: uygulamayı açmadan telefonun kamerası
        try {
            startActivity(new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            open(act);
        }
    }

    void sendRec(String action) {
        Intent i = new Intent(this, QuickRecService.class).setAction(action).putExtra("bubble", true);
        try {
            if (Build.VERSION.SDK_INT >= 26 && QuickRecService.TOGGLE.equals(action) && !QuickRecService.recording) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            if (micReady && rec.start()) return;
            open("voice");
        }
    }

    // QuickRecService'ten gelen durum: kayan düğmede göster
    static void recState(boolean on) {
        BubbleService s = inst;
        if (s != null) s.recUI(on);
    }

    static void recText(String t) {
        BubbleService s = inst;
        if (s != null && s.box != null) s.showPanel(t);
    }

    static void recDone(String summary, boolean ok) {
        BubbleService s = inst;
        if (s != null && s.box != null) {
            s.showPanel(summary);
            s.hidePanel(ok ? 3500 : 1800);
        }
    }

    // ---------------- Uygulamayı açmadan sesli not ----------------
    // Telefon ekranının üstünde duran şık durum kartı: dinliyor (kırmızı, nabız) · kaydediliyor (mavi, dönen) · kaydedildi (yeşil ✓)
    LinearLayout panelBox;
    ImageView pIcon;
    android.widget.ProgressBar pSpin;
    TextView pTitle, pBody;
    android.animation.ObjectAnimator pulse;

    void showPanel(String t) {
        h.post(() -> {
            try {
                if (panel == null) {
                    panelBox = new LinearLayout(this);
                    panelBox.setOrientation(LinearLayout.HORIZONTAL);
                    panelBox.setGravity(Gravity.CENTER_VERTICAL);
                    panelBox.setBackgroundResource(R.drawable.bg_panel2);
                    panelBox.setPadding(dp(14), dp(12), dp(16), dp(12));
                    panelBox.setElevation(dp(10));
                    android.widget.FrameLayout ic = new android.widget.FrameLayout(this);
                    pIcon = new ImageView(this);
                    int p = dp(9);
                    pIcon.setPadding(p, p, p, p);
                    ic.addView(pIcon, new android.widget.FrameLayout.LayoutParams(dp(40), dp(40)));
                    pSpin = new android.widget.ProgressBar(this);
                    pSpin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                    android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
                    ic.addView(pSpin, sp);
                    panelBox.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    LinearLayout col = new LinearLayout(this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    cl.leftMargin = dp(12);
                    pTitle = new TextView(this);
                    pTitle.setTextColor(0xFFFFFFFF);
                    pTitle.setTextSize(15);
                    pTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    pBody = new TextView(this);
                    pBody.setTextColor(0xFFCBD5E1);
                    pBody.setTextSize(14);
                    pBody.setMaxLines(4);
                    pBody.setEllipsize(android.text.TextUtils.TruncateAt.START);
                    pBody.setLineSpacing(0, 1.1f);
                    col.addView(pTitle);
                    col.addView(pBody);
                    panelBox.addView(col, cl);
                    panel = new TextView(this); // yalnızca "açık mı" işareti
                    WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(getResources().getDisplayMetrics().widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
                    lp2.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp2.y = dp(44);
                    lp2.windowAnimations = android.R.style.Animation_Toast;
                    wm.addView(panelBox, lp2);
                }
                // durumu metnin başından anla
                String title, body = t;
                int bg, icon;
                boolean spin = false, live = false;
                if (t.startsWith("🎤")) {
                    title = "Dinliyorum";
                    body = t.substring(2).trim();
                    if (body.startsWith("Dinliyorum…")) body = "Konuş… susunca kendiliğinden kaydedilir" + body.substring(11);
                    bg = R.drawable.bg_rec;
                    icon = R.drawable.ic_mic;
                    live = true;
                } else if (t.startsWith("⏳")) {
                    title = "Kaydediliyor";
                    body = t.substring(1).trim();
                    bg = R.drawable.bg_wait;
                    icon = 0;
                    spin = true;
                } else if (t.startsWith("✔")) {
                    title = "Kaydedildi";
                    body = t.replaceFirst("^✔\\s*(Kaydedildi|Not kaydedildi)?\\s*", "").trim();
                    bg = R.drawable.bg_ok;
                    icon = R.drawable.ic_check;
                } else {
                    title = t.startsWith("✕") ? "İptal edildi" : "Bilgi";
                    body = t.replaceFirst("^(✕|⚠️|🤫)\\s*", "").replaceFirst("^İptal edildi", "").trim();
                    bg = R.drawable.bg_gray;
                    icon = R.drawable.ic_info;
                }
                body = body.replace("\n■ kaydet · ✕ iptal", "");
                if (live && QuickRecService.recording || live && rec.isOn()) body = body + "\n■ kaydet   ✕ iptal";
                pTitle.setText(title);
                pBody.setText(body);
                pBody.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
                pIcon.setBackgroundResource(bg);
                if (icon != 0) pIcon.setImageResource(icon);
                else pIcon.setImageDrawable(null);
                pSpin.setVisibility(spin ? View.VISIBLE : View.GONE);
                if (live) {
                    if (pulse == null) {
                        pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(pIcon,
                                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.14f),
                                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.14f));
                        pulse.setDuration(650);
                        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                        pulse.start();
                    }
                } else if (pulse != null) {
                    pulse.cancel();
                    pulse = null;
                    pIcon.setScaleX(1f);
                    pIcon.setScaleY(1f);
                }
            } catch (Exception ignored) {
            }
        });
    }

    void hidePanel(long delay) {
        h.postDelayed(() -> {
            if (rec.isOn() || QuickRecService.recording) return;
            try {
                if (pulse != null) pulse.cancel();
                pulse = null;
                if (panelBox != null) wm.removeView(panelBox);
            } catch (Exception ignored) {
            }
            panel = null;
            panelBox = null;
        }, delay);
    }

    android.animation.ObjectAnimator micPulse;

    void recUI(boolean on) {
        h.post(() -> {
            if (micV == null) return;
            micV.setBackgroundResource(on ? R.drawable.bg_rec : R.drawable.bg_mic);
            if (on) {
                if (micPulse == null) {
                    micPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(micV,
                            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.12f),
                            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.12f));
                    micPulse.setDuration(600);
                    micPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    micPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    micPulse.start();
                }
            } else if (micPulse != null) {
                micPulse.cancel();
                micPulse = null;
                micV.setScaleX(1f);
                micV.setScaleY(1f);
            }
            micV.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_mic);
            camV.setBackgroundResource(on ? R.drawable.bg_gray : R.drawable.bg_cam);
            camV.setImageResource(on ? R.drawable.ic_close : R.drawable.ic_cam);
            if (penV != null) penV.setVisibility(on ? View.GONE : View.VISIBLE);
        });
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
        if (rec.isOn()) rec.finish(true);
        try {
            if (panelBox != null) wm.removeView(panelBox);
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
