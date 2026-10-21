package com.yildiz.notasistani;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import android.speech.tts.TextToSpeech;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.ComponentName;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String URL = "https://yildiz-not-asistani.vercel.app/";
    static final String HOST = "yildiz-not-asistani.vercel.app";
    static final String AUTH = "com.yildiz.notasistani.files";
    static final int RC_ALL = 1, RC_FILE = 2, RC_WEBPERM = 3, RC_GEO = 4, RC_CAMFILE = 5, RC_TREE = 6, RC_CALLTREE = 7, RC_DRIVE_NEW = 8, RC_DRIVE_OPEN = 9;

    static final String MIC = Manifest.permission.RECORD_AUDIO;
    static final String CAM = Manifest.permission.CAMERA;
    static final String FINE = Manifest.permission.ACCESS_FINE_LOCATION;
    static final String COARSE = Manifest.permission.ACCESS_COARSE_LOCATION;
    static final String NOTIF = "android.permission.POST_NOTIFICATIONS";

    static volatile boolean visible = false;
    WebView web;
    ValueCallback<Uri[]> fileCb;
    Uri camUri;
    PermissionRequest pendingWebPerm;
    GeolocationPermissions.Callback geoCb;
    String geoOrigin;
    SharedPreferences prefs;
    TextToSpeech tts;
    // Google konuşma tanıma (Gemini / Google uygulamasının kullandığı tanıma servisi)
    SpeechRecognizer sr;
    final StringBuilder sttAcc = new StringBuilder();
    volatile boolean sttOn = false, sttDone = true;
    int sttErr = 0;
    boolean ttsReady;
    String ttsPending;
    final Map<String, DocumentFile> dirCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        Notif.channel(this);
        pendingAct = getIntent() == null ? null : getIntent().getStringExtra("act");
        if (prefs.getBoolean("bubble", false)) BubbleService.start(this);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " NotAsistaniApp/2");
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return "GET".equals(r.getMethod()) ? local(r.getUrl()) : null;
            }
        });
        // Service worker'ın istekleri de uygulamanın içindeki dosyalardan karşılanır
        try {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(new android.webkit.ServiceWorkerClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    return "GET".equals(r.getMethod()) ? local(r.getUrl()) : null;
                }
            });
        } catch (Exception ignored) {
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    List<String> need = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(MIC)) need.add(MIC);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(CAM)) need.add(CAM);
                    }
                    if (need.isEmpty()) req.grant(req.getResources());
                    else {
                        pendingWebPerm = req;
                        requestPermissions(need.toArray(new String[0]), RC_WEBPERM);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (has(FINE) || has(COARSE)) cb.invoke(origin, true, true);
                else {
                    geoCb = cb;
                    geoOrigin = origin;
                    requestPermissions(new String[]{FINE, COARSE}, RC_GEO);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                boolean image = false;
                String[] types = p.getAcceptTypes();
                if (types != null) for (String a : types) if (a != null && a.startsWith("image")) image = true;
                if (p.isCaptureEnabled() && image) {
                    if (!has(CAM)) requestPermissions(new String[]{CAM}, RC_CAMFILE);
                    else openCamera();
                    return true;
                }
                try {
                    camUri = null;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(image ? "image/*" : "*/*");
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "Dosya seç"), RC_FILE);
                } catch (Exception e) {
                    fileCb.onReceiveValue(null);
                    fileCb = null;
                }
                return true;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(URL);

        if (!prefs.getBoolean("askedAll", false)) web.postDelayed(this::askAll, 1500);
    }

    /** Sayfa dosyaları (index.html vb.) APK'nın içinden verilir: internet yokken de açılır. Yalnızca /api istekleri internete gider. */
    android.webkit.WebResourceResponse local(Uri u) {
        try {
            if (u == null || !HOST.equals(u.getHost())) return null;
            String p = u.getPath();
            if (p == null || p.isEmpty() || p.equals("/")) p = "/index.html";
            if (p.startsWith("/api/")) return null;
            String name = p.substring(1);
            if (!name.matches("[A-Za-z0-9._-]+")) return null;
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime;
            switch (ext) {
                case "html": mime = "text/html"; break;
                case "js": mime = "application/javascript"; break;
                case "json": mime = "application/json"; break;
                case "png": mime = "image/png"; break;
                case "svg": mime = "image/svg+xml"; break;
                default: return null;
            }
            InputStream is = getAssets().open("www/" + name);
            android.webkit.WebResourceResponse r = new android.webkit.WebResourceResponse(mime, "utf-8", is);
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-cache");
            hd.put("Access-Control-Allow-Origin", "*");
            if (name.equals("sw.js")) hd.put("Service-Worker-Allowed", "/");
            r.setResponseHeaders(hd);
            r.setStatusCodeAndReasonPhrase(200, "OK");
            return r;
        } catch (Exception e) {
            return null; // dosya APK'da yoksa internetten yüklenir
        }
    }

    // ---------- izinler ----------
    boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    String[] wanted() {
        List<String> l = new ArrayList<>();
        l.add(MIC);
        l.add(CAM);
        l.add(FINE);
        l.add(COARSE);
        if (Build.VERSION.SDK_INT >= 33) l.add(NOTIF);
        return l.toArray(new String[0]);
    }

    void askAll() {
        List<String> need = new ArrayList<>();
        for (String p : wanted()) if (!has(p)) need.add(p);
        prefs.edit().putBoolean("askedAll", true).apply();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), RC_ALL);
        else sendPerms();
    }

    String state(String p) {
        if (has(p)) return "granted";
        if (prefs.getBoolean("asked_" + p, false) && !shouldShowRequestPermissionRationale(p)) return "denied";
        return "prompt";
    }

    String permJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("microphone", state(MIC));
            o.put("camera", state(CAM));
            o.put("geolocation", has(FINE) || has(COARSE) ? "granted" : state(FINE));
            String n;
            if (Build.VERSION.SDK_INT >= 33) n = state(NOTIF);
            else n = getSystemService(NotificationManager.class).areNotificationsEnabled() ? "granted" : "denied";
            o.put("notifications", n);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    void sendPerms() {
        final String js = "window.onNativePerms&&window.onNativePerms(" + permJson() + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        SharedPreferences.Editor ed = prefs.edit();
        for (String p : perms) ed.putBoolean("asked_" + p, true);
        ed.apply();
        if (rc == RC_WEBPERM && pendingWebPerm != null) {
            List<String> ok = new ArrayList<>();
            for (String r : pendingWebPerm.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(MIC)) ok.add(r);
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(CAM)) ok.add(r);
            }
            if (ok.isEmpty()) pendingWebPerm.deny();
            else pendingWebPerm.grant(ok.toArray(new String[0]));
            pendingWebPerm = null;
        } else if (rc == RC_GEO && geoCb != null) {
            boolean ok = has(FINE) || has(COARSE);
            geoCb.invoke(geoOrigin, ok, ok);
            geoCb = null;
        } else if (rc == RC_CAMFILE) {
            if (has(CAM)) openCamera();
            else if (fileCb != null) {
                fileCb.onReceiveValue(null);
                fileCb = null;
            }
        }
        sendPerms();
    }

    // ---------- kamera / dosya ----------
    void openCamera() {
        try {
            File f = new File(getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
            camUri = FileProvider.getUriForFile(this, AUTH, f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, RC_FILE);
        } catch (Exception e) {
            camUri = null;
            if (fileCb != null) {
                fileCb.onReceiveValue(null);
                fileCb = null;
            }
            Toast.makeText(this, "Kamera açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == RC_CALLTREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.edit().putString("callTree", u.toString()).apply();
                web.evaluateJavascript("window.onCallFolderPicked&&window.onCallFolderPicked()", null);
            }
            return;
        }
        if (rc == RC_DRIVE_NEW || rc == RC_DRIVE_OPEN) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                final Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.edit().putString("driveDoc", u.toString()).apply();
                if (rc == RC_DRIVE_NEW) web.evaluateJavascript("window.onDrivePicked&&window.onDrivePicked()", null);
                else new Thread(() -> { // geri yükleme: dosyayı önce telefona indir
                    boolean ok = false;
                    try (InputStream is = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(new File(getCacheDir(), "drive_restore.txt"))) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = is.read(b)) > 0) os.write(b, 0, n);
                        ok = true;
                    } catch (Exception ignored) {
                    }
                    final boolean f = ok;
                    runOnUiThread(() -> {
                        closeReader();
                        web.evaluateJavascript("window.onDriveRestore&&window.onDriveRestore(" + f + ")", null);
                    });
                }).start();
            }
            return;
        }
        if (rc == RC_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                prefs.edit().putString("tree", u.toString()).apply();
                dirCache.clear();
                web.evaluateJavascript("window.onFolderPicked&&window.onFolderPicked()", null);
            }
            return;
        }
        if (rc != RC_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            if (camUri != null) out = new Uri[]{camUri};
            else if (data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
        }
        camUri = null;
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    // ---------- geri tuşu ----------
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.nativeBack?window.nativeBack():false", v -> {
            if ("true".equals(v)) return;
            moveTaskToBack(true); // ana sayfadaysa uygulamayı arka plana al (kapatma)
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    // Widget / kayan düğmeden gelen kısayol: "voice" (sesli not) veya "camera" (fotoğraflı not)
    volatile String pendingAct;

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String a = i == null ? null : i.getStringExtra("act");
        if (a != null && web != null) {
            pendingAct = a;
            web.evaluateJavascript("window.nativeAct&&window.nativeAct()", null);
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        BubbleService.appVisible(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        BubbleService.appVisible(true);
        BubbleService.upgrade(this);
        if (prefs.getBoolean("bubbleWant", false) && BubbleService.canDraw(this)) { // izin ekranından dönüldü
            prefs.edit().putBoolean("bubbleWant", false).putBoolean("bubble", true).apply();
            BubbleService.start(this);
            if (web != null) web.evaluateJavascript("window.onBubble&&window.onBubble()", null);
        }
        if (web != null) sendPerms();
    }

    // ---------- klasör (otomatik yedek) ----------
    DocumentFile root() {
        String t = prefs.getString("tree", null);
        if (t == null) return null;
        DocumentFile d = DocumentFile.fromTreeUri(this, Uri.parse(t));
        return (d != null && d.canWrite()) ? d : null;
    }

    DocumentFile folder(String rel, boolean create) {
        DocumentFile c = dirCache.get(rel);
        if (c != null && c.exists()) return c;
        DocumentFile cur = root();
        if (cur == null) return null;
        if (!rel.isEmpty()) for (String seg : rel.split("/")) {
            DocumentFile n = cur.findFile(seg);
            if (n == null) {
                if (!create) return null;
                n = cur.createDirectory(seg);
            }
            if (n == null) return null;
            cur = n;
        }
        dirCache.put(rel, cur);
        return cur;
    }

    boolean writeBytes(String path, byte[] data, String mime, boolean overwrite) {
        try {
            int i = path.lastIndexOf('/');
            String d = i < 0 ? "" : path.substring(0, i), name = path.substring(i + 1);
            DocumentFile dir = folder(d, true);
            if (dir == null) return false;
            DocumentFile f = overwrite ? dir.findFile(name) : null;
            if (f == null) f = dir.createFile(mime, name);
            if (f == null) return false;
            try (OutputStream os = getContentResolver().openOutputStream(f.getUri(), "wt")) {
                os.write(data);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    byte[] readBytes(String path) {
        try {
            int i = path.lastIndexOf('/');
            DocumentFile dir = folder(i < 0 ? "" : path.substring(0, i), false);
            if (dir == null) return null;
            DocumentFile f = dir.findFile(path.substring(i + 1));
            if (f == null) return null;
            try (InputStream is = getContentResolver().openInputStream(f.getUri())) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                return bo.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- sesli okuma ----------
    void speakNow(String text) {
        if (tts == null) {
            ttsPending = text;
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("tr", "TR"));
                    ttsReady = true;
                    if (ttsPending != null) tts.speak(ttsPending, TextToSpeech.QUEUE_FLUSH, null, "na");
                    ttsPending = null;
                }
            });
        } else if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "na");
        else ttsPending = text;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        sttRelease();
        super.onDestroy();
    }


    // ---------- Konuşma tanıma ----------
    static final ComponentName GOOGLE_STT = new ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService");

    boolean hasGoogleStt() {
        try {
            getPackageManager().getServiceInfo(GOOGLE_STT, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void sttEmit(String type, String text) {
        final String js = "window.onStt&&window.onStt(" + JSONObject.quote(type) + "," + JSONObject.quote(text == null ? "" : text) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    String sttJoin(String extra) {
        String a = sttAcc.toString().trim();
        if (extra == null || extra.trim().isEmpty()) return a;
        return a.isEmpty() ? extra.trim() : a + " " + extra.trim();
    }

    void sttFinish() {
        if (sttDone) return;
        sttDone = true;
        sttOn = false;
        sttEmit("done", sttAcc.toString().trim());
        sttRelease();
    }

    void sttRelease() {
        try {
            if (sr != null) sr.destroy();
        } catch (Exception ignored) {
        }
        sr = null;
    }

    void sttListen() {
        if (sr == null) {
            sr = hasGoogleStt() ? SpeechRecognizer.createSpeechRecognizer(this, GOOGLE_STT) : SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { sttEmit("ready", ""); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onEvent(int t, Bundle b) {}

                public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) sttEmit("partial", sttJoin(r.get(0)));
                }

                public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) {
                        String t = sttJoin(r.get(0));
                        sttAcc.setLength(0);
                        sttAcc.append(t);
                        sttEmit("partial", t);
                    }
                    if (sttOn) sttListen(); // duraklamadan sonra dinlemeye devam et
                    else sttFinish();
                }

                public void onError(int e) {
                    // Duraklamada ya da geçici hatada kaydı bitirme: dinlemeye devam (bitişe sayfa sessizliğe göre karar verir)
                    if (sttOn && e != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && (sttAcc.length() > 0 || ++sttErr < 4
                            || e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        boolean hard = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT;
                        if (hard) sttRelease();
                        web.postDelayed(MainActivity.this::sttListen, hard ? 350 : 120);
                        return;
                    }
                    if (sttOn && sttAcc.length() == 0 && e != SpeechRecognizer.ERROR_CLIENT) {
                        sttDone = true;
                        sttOn = false;
                        sttRelease();
                        sttEmit("error", String.valueOf(e));
                        return;
                    }
                    sttFinish();
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L);
        try {
            sr.startListening(i);
        } catch (Exception e) {
            sttDone = true;
            sttOn = false;
            sttRelease();
            sttEmit("error", "start");
        }
    }

    // ---------- Google Drive yedeği (tek dosya, Drive'ın dosya seçicisiyle) ----------
    java.io.Writer driveTmp;
    java.io.BufferedReader driveReader;
    volatile boolean driveBusy = false;

    void closeReader() {
        try {
            if (driveReader != null) driveReader.close();
        } catch (Exception ignored) {
        }
        driveReader = null;
    }

    Uri driveDoc() {
        String s = prefs.getString("driveDoc", null);
        return s == null ? null : Uri.parse(s);
    }

    // ---------- JS köprüsü ----------
    class Bridge {
        @JavascriptInterface
        public String takeAct() {
            String a = pendingAct;
            pendingAct = null;
            return a;
        }

        /** Kayan mikrofonun uygulamayı açmadan notu ayırabilmesi için AI talimatı ({{NOW}} = şu anki zaman). */
        @JavascriptInterface
        public void setQuickPrompt(String prompt, String code, String model) {
            prefs.edit().putString("qPrompt", prompt).putString("qCode", code).putString("qModel", model).apply();
        }

        @JavascriptInterface
        public String bubbleResults() {
            JSONArray a = new JSONArray();
            File[] fs = new File(getFilesDir(), "bubble").listFiles();
            if (fs != null) for (File f : fs) {
                if (!f.getName().matches("\\d+\\.json")) continue;
                try (InputStream is = new java.io.FileInputStream(f)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                    a.put(new JSONObject(bo.toString("UTF-8")).put("name", f.getName()));
                } catch (Exception ignored) {
                }
            }
            return a.toString();
        }

        @JavascriptInterface
        public void bubbleAck(String name) {
            if (name != null && name.matches("(\\d+\\.json|photo_\\d+\\.jpg)")) new File(new File(getFilesDir(), "bubble"), name).delete();
        }

        @JavascriptInterface
        public String bubbleFile(String name) {
            if (name == null || !name.matches("photo_\\d+\\.jpg")) return null;
            try (InputStream is = new java.io.FileInputStream(new File(new File(getFilesDir(), "bubble"), name))) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public String bubbleState() {
            try {
                return new JSONObject().put("on", prefs.getBoolean("bubble", false)).put("perm", BubbleService.canDraw(MainActivity.this)).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void bubbleSet(boolean on) {
            if (!on) {
                prefs.edit().putBoolean("bubble", false).putBoolean("bubbleWant", false).apply();
                BubbleService.stop(MainActivity.this);
                return;
            }
            if (BubbleService.canDraw(MainActivity.this)) {
                prefs.edit().putBoolean("bubble", true).apply();
                BubbleService.start(MainActivity.this);
                return;
            }
            prefs.edit().putBoolean("bubbleWant", true).apply();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Listede Not Asistanı'nı bulup \"Diğer uygulamaların üzerinde göster\" iznini aç", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    openAppSettings();
                }
            });
        }

        /** Ana ekrana widget eklemeyi ister (Android 8+, başlatıcı destekliyorsa). */
        @JavascriptInterface
        public boolean widgetPin() {
            if (Build.VERSION.SDK_INT < 26) return false;
            try {
                android.appwidget.AppWidgetManager m = getSystemService(android.appwidget.AppWidgetManager.class);
                if (m == null || !m.isRequestPinAppWidgetSupported()) return false;
                return m.requestPinAppWidget(new android.content.ComponentName(MainActivity.this, QuickWidget.class), null, null);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void drivePick() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TITLE, "NotAsistani-Drive-yedek.txt");
                    startActivityForResult(i, RC_DRIVE_NEW);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Dosya seçici açılamadı", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void driveOpenPick() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, RC_DRIVE_OPEN);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Dosya seçici açılamadı", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String driveName() {
            Uri u = driveDoc();
            if (u == null) return "";
            try {
                DocumentFile d = DocumentFile.fromSingleUri(MainActivity.this, u);
                String n = d == null ? null : d.getName();
                return n == null ? "Drive dosyası" : n;
            } catch (Exception e) {
                return "Drive dosyası";
            }
        }

        @JavascriptInterface
        public void driveForget() {
            prefs.edit().remove("driveDoc").apply();
        }

        @JavascriptInterface
        public boolean driveBegin() {
            if (driveBusy) return false;
            try {
                if (driveTmp != null) driveTmp.close();
                driveTmp = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new FileOutputStream(new File(getCacheDir(), "drive_tmp.txt")), "UTF-8"), 65536);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean driveAppend(String s) {
            try {
                driveTmp.write(s);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Hazırlanan yedeği arka planda Drive dosyasının üzerine yazar; bitince window.onDriveDone(ok, hata). */
        @JavascriptInterface
        public void driveCommit() {
            try {
                driveTmp.close();
            } catch (Exception ignored) {
            }
            driveTmp = null;
            final Uri u = driveDoc();
            if (u == null) return;
            driveBusy = true;
            new Thread(() -> {
                String err = "";
                try (InputStream is = new java.io.FileInputStream(new File(getCacheDir(), "drive_tmp.txt"));
                     OutputStream os = getContentResolver().openOutputStream(u, "wt")) {
                    if (os == null) throw new Exception("Drive dosyası açılamadı");
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = is.read(b)) > 0) os.write(b, 0, n);
                } catch (SecurityException e) {
                    err = "Drive izni kalktı — yeniden bağla";
                } catch (Exception e) {
                    err = e.getMessage() == null ? "Yazılamadı" : e.getMessage();
                }
                driveBusy = false;
                final String js = "window.onDriveDone&&window.onDriveDone(" + err.isEmpty() + "," + JSONObject.quote(err) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /** Geri yüklenen dosyayı satır satır okur (bitince null). */
        @JavascriptInterface
        public String driveReadLine() {
            try {
                if (driveReader == null) driveReader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(new File(getCacheDir(), "drive_restore.txt")), "UTF-8"), 65536);
                String l = driveReader.readLine();
                if (l == null) closeReader();
                return l;
            } catch (Exception e) {
                closeReader();
                return null;
            }
        }

        @JavascriptInterface
        public boolean sttAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this) || hasGoogleStt();
        }

        @JavascriptInterface
        public boolean sttStart() {
            if (checkSelfPermission(MIC) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{MIC}, 77);
                return false;
            }
            runOnUiThread(() -> {
                sttRelease();
                sttAcc.setLength(0);
                sttErr = 0;
                sttOn = true;
                sttDone = false;
                sttListen();
            });
            return true;
        }

        @JavascriptInterface
        public void sttStop() {
            runOnUiThread(() -> {
                sttOn = false;
                if (sr != null) {
                    try {
                        sr.stopListening();
                    } catch (Exception ignored) {
                    }
                    web.postDelayed(MainActivity.this::sttFinish, 2500); // son sonuç gelmezse yine de bitir
                } else sttFinish();
            });
        }

        @JavascriptInterface
        public void sttCancel() {
            runOnUiThread(() -> {
                sttOn = false;
                sttDone = true;
                try {
                    if (sr != null) sr.cancel();
                } catch (Exception ignored) {
                }
                sttRelease();
            });
        }
        @JavascriptInterface
        public String permStatus() {
            return permJson();
        }

        @JavascriptInterface
        public void requestAll() {
            runOnUiThread(() -> {
                boolean anyDenied = false;
                for (String p : wanted()) if (!has(p) && "denied".equals(state(p))) anyDenied = true;
                boolean anyPrompt = false;
                for (String p : wanted()) if (!has(p) && !"denied".equals(state(p))) anyPrompt = true;
                if (anyPrompt) askAll();
                else if (anyDenied) {
                    Toast.makeText(MainActivity.this, "Kapalı izinleri buradan aç", Toast.LENGTH_LONG).show();
                    openAppSettings();
                } else sendPerms();
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::openAppSettings);
        }

        @JavascriptInterface
        public void notify(String id, String title, String body) {
            Notif.show(MainActivity.this, id.hashCode(), title, body);
        }

        @JavascriptInterface
        public void schedule(String id, double when, String title, String body) {
            Alarms.set(MainActivity.this, id, (long) when, title, body);
        }

        @JavascriptInterface
        public void cancel(String id) {
            Alarms.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }

        @JavascriptInterface
        public boolean saveFile(String b64, String name, String mime) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                boolean isImage = mime != null && mime.startsWith("image/");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, isImage ? Environment.DIRECTORY_PICTURES + "/Not Asistani" : Environment.DIRECTORY_DOWNLOADS);
                    Uri col = isImage ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri u = getContentResolver().insert(col, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(u)) {
                        os.write(bytes);
                    }
                } else {
                    File dir = getExternalFilesDir(isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
                    try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                        os.write(bytes);
                    }
                }
                final String msg = isImage ? "Galeriye kaydedildi: " + name : "İndirilenler'e kaydedildi: " + name;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Kaydedilemedi", Toast.LENGTH_SHORT).show());
                return false;
            }
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Paylaş"));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void share(String b64, String name, String mime, String text) {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                if (b64 != null && b64.length() > 0) {
                    File f = new File(getCacheDir(), name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(Base64.decode(b64, Base64.DEFAULT));
                    }
                    Uri u = FileProvider.getUriForFile(MainActivity.this, AUTH, f);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else i.setType("text/plain");
                if (text != null) i.putExtra(Intent.EXTRA_TEXT, text);
                final Intent ch = Intent.createChooser(i, "Paylaş");
                runOnUiThread(() -> startActivity(ch));
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String version() {
            return "3";
        }

        // --- klasöre otomatik yedek ---
        // --- telefon görüşmesi kayıtları (Samsung'un kaydettiği dosyalar) ---
        @JavascriptInterface
        public void pickCallFolder() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (Build.VERSION.SDK_INT >= 26) {
                        try {
                            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Recordings/Call"));
                        } catch (Exception ignored) {
                        }
                    }
                    startActivityForResult(i, RC_CALLTREE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Klasör seçici açılamadı", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String callFolderName() {
            String t = prefs.getString("callTree", null);
            if (t == null) return "";
            DocumentFile d = DocumentFile.fromTreeUri(MainActivity.this, Uri.parse(t));
            return d == null || !d.canRead() ? "" : String.valueOf(d.getName());
        }

        @JavascriptInterface
        public String listCallFiles(double since) {
            org.json.JSONArray a = new org.json.JSONArray();
            try {
                String t = prefs.getString("callTree", null);
                if (t == null) return "[]";
                DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, Uri.parse(t));
                if (root == null) return "[]";
                java.util.List<DocumentFile> all = new ArrayList<>();
                for (DocumentFile f : root.listFiles()) {
                    if (f.isDirectory()) java.util.Collections.addAll(all, f.listFiles());
                    else all.add(f);
                }
                for (DocumentFile f : all) {
                    String n = String.valueOf(f.getName());
                    if (f.isFile() && n.toLowerCase().matches(".*\\.(m4a|aac|mp3|wav|ogg|opus|3gp|amr|webm)$") && f.lastModified() > since)
                        a.put(new JSONObject().put("name", n).put("uri", f.getUri().toString()).put("mod", f.lastModified()).put("size", f.length()));
                }
            } catch (Exception ignored) {
            }
            return a.toString();
        }

        @JavascriptInterface
        public String readUriB64(String uri) {
            try {
                if (uri == null || !uri.startsWith("content://")) return null;
                try (InputStream is = getContentResolver().openInputStream(Uri.parse(uri))) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    long tot = 0;
                    while ((n = is.read(b)) > 0) {
                        bo.write(b, 0, n);
                        tot += n;
                        if (tot > 60L * 1024 * 1024) return null;
                    }
                    return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                }
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startActivityForResult(i, RC_TREE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Klasör seçici açılamadı", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public String folderName() {
            DocumentFile r = root();
            return r == null ? "" : String.valueOf(r.getName());
        }

        @JavascriptInterface
        public boolean writeText(String path, String text) {
            try {
                return writeBytes(path, text.getBytes("UTF-8"), "application/json", true);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean writeFile(String path, String b64, String mime, boolean overwrite) {
            return writeBytes(path, Base64.decode(b64, Base64.DEFAULT), mime, overwrite);
        }

        @JavascriptInterface
        public String readText(String path) {
            try {
                byte[] b = readBytes(path);
                return b == null ? null : new String(b, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public String readFileB64(String path) {
            byte[] b = readBytes(path);
            return b == null ? null : Base64.encodeToString(b, Base64.NO_WRAP);
        }

        // --- arka planda sesli günlük ---
        @JavascriptInterface
        public boolean diaryStart() {
            return diaryStart(null, null, null);
        }

        @JavascriptInterface
        public boolean diaryStart(String prompt, String code, String model) {
            return diaryStart(prompt, code, model, null, null);
        }

        @JavascriptInterface
        public boolean diaryStart(String prompt, String code, String model, String refinePrompt, String refineModel) {
            if (!has(MIC)) {
                runOnUiThread(() -> requestPermissions(new String[]{MIC}, RC_ALL));
                return false;
            }
            Intent i = new Intent(MainActivity.this, DiaryService.class);
            if (prompt != null) i.putExtra("prompt", prompt).putExtra("code", code).putExtra("model", model)
                    .putExtra("refinePrompt", refinePrompt).putExtra("refineModel", refineModel);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            return true;
        }

        @JavascriptInterface
        public void diaryStop() {
            if (DiaryService.running) startService(new Intent(MainActivity.this, DiaryService.class).setAction(DiaryService.STOP));
        }

        @JavascriptInterface
        public void diaryCancelPending() {
            DiaryService.cancelPending(MainActivity.this);
        }

        @JavascriptInterface
        public String diaryState() {
            try {
                return new JSONObject().put("on", DiaryService.running).put("since", DiaryService.since).put("level", DiaryService.level).put("error", DiaryService.error).put("pending", Math.max(0, DiaryService.pending.get() - DiaryService.waitN)).put("stt", DiaryService.sttMode).put("refining", DiaryService.refining.get()).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String diaryChunks() {
            org.json.JSONArray a = new org.json.JSONArray();
            File[] fs = DiaryService.dir(MainActivity.this).listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (File f : fs) if (f.getName().matches("c_\\d+_\\d+\\.m4a")) a.put(f.getName());
            }
            return a.toString();
        }

        @JavascriptInterface
        public String diaryResults() {
            org.json.JSONArray a = new org.json.JSONArray();
            File[] fs = DiaryService.dir(MainActivity.this).listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (File f : fs)
                    if (f.getName().matches("rf?_\\d+\\.json")) {
                        try {
                            a.put(new JSONObject(Summary.read(f)).put("name", f.getName()));
                        } catch (Exception ignored) {
                        }
                    }
            }
            return a.toString();
        }

        @JavascriptInterface
        public void diaryAck(String name) {
            if (name != null && name.matches("rf?_\\d+\\.json")) new File(DiaryService.dir(MainActivity.this), name).delete();
        }

        @JavascriptInterface
        public String readChunk(String name) {
            if (!name.matches("[cf]_\\d+_\\d+\\.(m4a|aac|wav)")) return null;
            try (InputStream is = new java.io.FileInputStream(new File(DiaryService.dir(MainActivity.this), name))) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void deleteChunk(String name) {
            if (name.matches("[cf]_\\d+_\\d+\\.(m4a|aac|wav)")) new File(DiaryService.dir(MainActivity.this), name).delete();
        }

        // --- sesli not (arka planda kayıt + Gemini) ---
        @JavascriptInterface
        public boolean voiceStart(String req, String prompt, String system, boolean json, String code, String model) {
            if (!has(MIC)) {
                runOnUiThread(() -> requestPermissions(new String[]{MIC}, RC_ALL));
                return false;
            }
            Intent i = new Intent(MainActivity.this, VoiceService.class).putExtra("req", req).putExtra("prompt", prompt).putExtra("system", system)
                    .putExtra("json", json).putExtra("code", code).putExtra("model", model);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            return true;
        }

        @JavascriptInterface
        public void voiceStop() {
            if (VoiceService.recording) startService(new Intent(MainActivity.this, VoiceService.class).setAction(VoiceService.STOP));
        }

        @JavascriptInterface
        public void voiceCancel() {
            if (VoiceService.recording) startService(new Intent(MainActivity.this, VoiceService.class).setAction(VoiceService.CANCEL));
        }

        @JavascriptInterface
        public String voiceState() {
            try {
                return new JSONObject().put("rec", VoiceService.recording).put("busy", VoiceService.busy).put("since", VoiceService.since).put("level", VoiceService.level).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String voiceResults() {
            org.json.JSONArray a = new org.json.JSONArray();
            File[] fs = VoiceService.resDir(MainActivity.this).listFiles();
            if (fs != null) for (File f : fs) {
                try {
                    a.put(new JSONObject(Summary.read(f)));
                } catch (Exception ignored) {
                }
            }
            return a.toString();
        }

        @JavascriptInterface
        public void voiceAck(String id) {
            if (id != null && id.matches("[A-Za-z0-9_]+")) new File(VoiceService.resDir(MainActivity.this), id + ".json").delete();
        }

        // --- akşam özeti ---
        @JavascriptInterface
        public void setSummary(boolean on, int hh, int mm) {
            Summary.set(MainActivity.this, on, hh, mm);
        }

        @JavascriptInterface
        public void saveSnapshot(String json) {
            try {
                Summary.write(new File(getFilesDir(), "snapshot.json"), json);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String lastSummary() {
            try {
                File f = new File(getFilesDir(), "summary.json");
                return f.exists() ? Summary.read(f) : "";
            } catch (Exception e) {
                return "";
            }
        }

        // --- sesli okuma (telefonun sesi) ---
        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> speakNow(text));
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }
    }
}
