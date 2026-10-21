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
import android.widget.Toast;

/** Uygulama kapalıyken de ekranda duran kayan mikrofon ve kamera düğmeleri. */
public class BubbleService extends Service {
    static final String CH = "kisayol", STOP = "com.yildiz.notasistani.BUBBLE_STOP";
    static volatile BubbleService inst;
    WindowManager wm;
    LinearLayout box;
    WindowManager.LayoutParams lp;

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
        if (Build.VERSION.SDK_INT >= 34) startForeground(9, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(9, b.build());
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
                            } else open((String) v.getTag());
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
