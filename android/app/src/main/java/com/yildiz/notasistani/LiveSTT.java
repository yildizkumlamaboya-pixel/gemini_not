package com.yildiz.notasistani;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Gemini Live ile canlı yazıya dökme (gemini-3.5-transcribe-live).
 * Ses 16 kHz PCM olarak doğrudan Gemini'ye akar; kısa ömürlü anahtarı sunucumuz verir (gerçek anahtar telefona gelmez).
 */
class LiveSTT {
    interface CB {
        void text(String full);
    }

    static final String MODEL = "models/gemini-3.5-transcribe-live";
    static final String WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=";
    static final OkHttpClient HTTP = new OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();

    final CB cb;
    WebSocket ws;
    volatile boolean ready = false, closed = false;
    final StringBuilder fin = new StringBuilder();
    volatile String interim = "";
    volatile long lastText = 0;

    LiveSTT(CB cb) {
        this.cb = cb;
    }

    static String token(Context c) throws Exception {
        String code = c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("qCode", "");
        HttpURLConnection con = (HttpURLConnection) new URL(Summary.API).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        if (code != null && !code.isEmpty()) con.setRequestProperty("x-app-code", code);
        try (OutputStream os = con.getOutputStream()) {
            os.write("{\"op\":\"livetoken\"}".getBytes("UTF-8"));
        }
        int st = con.getResponseCode();
        String resp = Summary.readAll(st < 400 ? con.getInputStream() : con.getErrorStream());
        JSONObject o = new JSONObject(resp);
        if (st >= 400 || !o.has("token")) throw new Exception(o.optString("error", "Canlı ses anahtarı alınamadı"));
        return o.getString("token");
    }

    String full() {
        return (fin + " " + interim).replaceAll("\\s+", " ").trim();
    }

    /** Arka planda çağrılır: bağlanır ve kurulum onayını bekler. */
    boolean start(Context c) {
        try {
            String tok = token(c);
            final JSONArray names = new JSONArray(c.getSharedPreferences("app", Context.MODE_PRIVATE).getString("names", "[]"));
            final CountDownLatch up = new CountDownLatch(1);
            ws = HTTP.newWebSocket(new Request.Builder().url(WS + java.net.URLEncoder.encode(tok, "UTF-8")).build(), new WebSocketListener() {
                @Override
                public void onOpen(WebSocket w, Response r) {
                    try {
                        JSONObject tr = new JSONObject().put("languageCodes", new JSONArray().put("tr-TR")).put("mode", "SMART");
                        if (names.length() > 0) tr.put("customVocabulary", names); // Ayarlar'daki firma/kişi isimleri
                        JSONObject setup = new JSONObject().put("setup", new JSONObject()
                                .put("model", MODEL)
                                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("TEXT")))
                                .put("inputAudioTranscription", tr));
                        w.send(setup.toString());
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onMessage(WebSocket w, String s) {
                    handle(s, up);
                }

                @Override
                public void onMessage(WebSocket w, okio.ByteString b) {
                    handle(b.utf8(), up);
                }

                @Override
                public void onClosed(WebSocket w, int code, String reason) {
                    closed = true;
                    up.countDown();
                }

                @Override
                public void onFailure(WebSocket w, Throwable t, Response r) {
                    closed = true;
                    up.countDown();
                }
            });
            up.await(8, TimeUnit.SECONDS);
            return ready && !closed;
        } catch (Exception e) {
            return false;
        }
    }

    void handle(String s, CountDownLatch up) {
        try {
            JSONObject m = new JSONObject(s);
            if (m.has("setupComplete")) {
                ready = true;
                up.countDown();
            }
            JSONObject sc = m.optJSONObject("serverContent");
            if (sc != null) {
                JSONObject f = sc.optJSONObject("inputTranscription"), t = sc.optJSONObject("interimInputTranscription");
                if (f != null && !f.optString("text").isEmpty()) {
                    if (fin.length() > 0 && !Character.isWhitespace(fin.charAt(fin.length() - 1))) fin.append(' ');
                    fin.append(f.optString("text"));
                    interim = "";
                    lastText = System.currentTimeMillis();
                } else if (t != null) {
                    interim = t.optString("text", "");
                    lastText = System.currentTimeMillis();
                }
                if (f != null || t != null) cb.text(full());
            }
            if (m.has("error") || m.has("goAway")) closed = true;
        } catch (Exception ignored) {
        }
    }

    void send(byte[] pcm, int off, int len) {
        if (!ready || closed || ws == null) return;
        try {
            String b = Base64.encodeToString(pcm, off, len, Base64.NO_WRAP);
            ws.send("{\"realtimeInput\":{\"audio\":{\"data\":\"" + b + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}");
        } catch (Exception ignored) {
        }
    }

    /** Kaydı bitir: son cümlenin kesin yazısını bekle (en çok 5 sn) ve metni döndür. */
    String finish() {
        if (ws != null && ready && !closed) {
            try {
                ws.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
            } catch (Exception ignored) {
            }
            long t = System.currentTimeMillis();
            while (System.currentTimeMillis() - t < 5000 && !closed) {
                long now = System.currentTimeMillis();
                if (now - t > 1200 && interim.isEmpty() && now - lastText > 1500) break;
                try {
                    Thread.sleep(120);
                } catch (Exception ignored) {
                }
            }
        }
        cancel();
        return full();
    }

    void cancel() {
        try {
            if (ws != null) ws.close(1000, "bitti");
        } catch (Exception ignored) {
        }
        closed = true;
    }
}
