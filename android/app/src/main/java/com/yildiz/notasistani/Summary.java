package com.yildiz.notasistani;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Akşam özeti: her gün seçilen saatte günün konuşma/görev özetini Gemini'ye yazdırıp bildirim gösterir. */
class Summary {
    static final String API = "https://yildiz-not-asistani.vercel.app/api/gemini";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("summary", Context.MODE_PRIVATE);
    }

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 77777, new Intent(c, SummaryReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void set(Context c, boolean on, int hh, int mm) {
        sp(c).edit().putBoolean("on", on).putInt("h", hh).putInt("m", mm).apply();
        schedule(c);
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c));
        if (!sp(c).getBoolean("on", false)) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, sp(c).getInt("h", 20));
        cal.set(Calendar.MINUTE, sp(c).getInt("m", 0));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30000) cal.add(Calendar.DAY_OF_MONTH, 1);
        long when = cal.getTimeInMillis();
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c));
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c));
        }
    }

    static String read(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            return readAll(is);
        }
    }

    static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    static void write(File f, String s) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes("UTF-8"));
        }
    }

    static String generate(Context c) throws Exception {
        File snap = new File(c.getFilesDir(), "snapshot.json");
        if (!snap.exists()) return null;
        JSONObject s = new JSONObject(read(snap));
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(s.optString("date"))) return null;
        String sys = "Kullanıcının kişisel asistanısın. Türkçe, kısa ve maddeli yaz; bu metin telefon bildiriminde okunacak, en fazla 12 satır. Markdown işareti (*, #) kullanma, madde için • kullan.";
        String prompt = "Tarih: " + today + ". Bugünün özetini çıkar: önce konuşmalarda öne çıkan konular, kararlar, kişi/firma ve rakamlar; sonra bugün tamamlananlar; en sonda yarın ve geciken işler. Veri yoksa o kısmı atla.\n\n" + s.optString("text");
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                .put("generationConfig", new JSONObject().put("temperature", 0.4));
        JSONObject payload = new JSONObject().put("model", s.optString("model", "gemini-flash-latest")).put("body", body);

        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(20000);
        con.setReadTimeout(120000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        String code = s.optString("code", "");
        if (!code.isEmpty()) con.setRequestProperty("x-app-code", code);
        try (OutputStream os = con.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
        }
        int st = con.getResponseCode();
        if (st >= 400) return null;
        JSONObject d = new JSONObject(readAll(con.getInputStream()));
        JSONArray parts = d.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text"));
        String text = sb.toString().trim();
        if (text.isEmpty()) return null;
        write(new File(c.getFilesDir(), "summary.json"), new JSONObject().put("date", today).put("text", text).put("ts", System.currentTimeMillis()).toString());
        return text;
    }
}
