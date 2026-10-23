package com.yildiz.notasistani;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Kayan düğmelerden açılan mini not alanı: uygulamayı açmadan elle not / görev yaz. */
public class QuickNoteActivity extends Activity {
    EditText ed;

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> finish());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(Color.WHITE, 22));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setElevation(dp(12));
        card.setOnClickListener(v -> {
        });
        TextView t = new TextView(this);
        t.setText("✏️  Hızlı not");
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF0F172A);
        card.addView(t);
        TextView h = new TextView(this);
        h.setText("Not, görev ya da hatırlatma yaz — AI kendisi ayırır");
        h.setTextSize(12.5f);
        h.setTextColor(0xFF64748B);
        h.setPadding(0, dp(2), 0, dp(10));
        card.addView(h);
        ed = new EditText(this);
        ed.setHint("Örn: Yarın 10'da Hipermak'ı ara");
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ed.setMinLines(3);
        ed.setMaxLines(8);
        ed.setGravity(Gravity.TOP | Gravity.START);
        ed.setTextSize(16);
        ed.setTextColor(0xFF0F172A);
        ed.setBackground(round(0xFFF1F5F9, 14));
        ed.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(ed, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(12), 0, 0);
        Button cancel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        cancel.setText("Vazgeç");
        cancel.setTextColor(0xFF64748B);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> finish());
        Button ok = new Button(this, null, android.R.attr.borderlessButtonStyle);
        ok.setText("Kaydet");
        ok.setAllCaps(false);
        ok.setTextColor(Color.WHITE);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2563EB, 0xFF06B6D4});
        g.setCornerRadius(dp(14));
        ok.setBackground(g);
        ok.setPadding(dp(22), 0, dp(22), 0);
        ok.setOnClickListener(v -> save());
        row.addView(cancel);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, dp(46));
        olp.leftMargin = dp(8);
        row.addView(ok, olp);
        card.addView(row);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        clp.leftMargin = clp.rightMargin = dp(16);
        root.addView(card, clp);
        setContentView(root);
        ed.requestFocus();
        ed.postDelayed(() -> {
            try {
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(ed, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) {
            }
        }, 150);
    }

    void save() {
        final String text = ed.getText().toString().trim();
        if (text.isEmpty()) {
            finish();
            return;
        }
        final Context app = getApplicationContext();
        final long ts = System.currentTimeMillis();
        final File d = new File(app.getFilesDir(), "bubble");
        d.mkdirs();
        final File f = new File(d, ts + ".json");
        final JSONObject out = new JSONObject();
        try {
            out.put("ts", ts).put("transcript", text).put("pending", true).put("engine", "text").put("kind", "text");
            Summary.write(f, out.toString());
        } catch (Exception e) {
            Toast.makeText(this, "Kaydedilemedi", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "✔ Not kaydedildi", Toast.LENGTH_SHORT).show();
        finish();
        // görev / hatırlatmaya ayır (internet yoksa uygulama açılınca ayrılır)
        new Thread(() -> {
            try {
                SharedPreferences pr = app.getSharedPreferences("app", Context.MODE_PRIVATE);
                String code = pr.getString("qCode", ""), model = pr.getString("qModel", "gemini-flash-latest");
                String sys = pr.getString("qPrompt", "");
                if (sys.isEmpty()) sys = "Kullanıcının metnini not ve görevlere ayır. {{NOW}} Sadece JSON: {\"notes\":[{\"title\":\"\",\"body\":\"\",\"tags\":[]}],\"tasks\":[{\"title\":\"\",\"note\":\"\",\"due\":null,\"remind\":null,\"priority\":\"orta\",\"repeat\":\"\"}]}";
                JSONObject r = QuickRec.askStatic(app, sys, new JSONArray().put(new JSONObject().put("text", text)), model, code);
                if (f.exists()) {
                    String sum = QuickRec.applyStatic(app, out, r, ts);
                    Summary.write(f, out.toString());
                    try {
                        Notif.show(app, 4243, "Hızlı not", sum.replace("✔ Kaydedildi\n", ""));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }, "hizli-not").start();
    }
}
