package com.yildiz.notasistani;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Kayan kamera düğmesi: uygulamayı açmadan, uygulamanın ŞABLONLU canlı kamerası.
 * Aynı sayfayı yalnızca kamera modunda açar; çekilenler aynı veriye fotoğraflı not olarak kaydedilir.
 */
public class CaptureActivity extends Activity {
    WebView web;
    PermissionRequest pendingPerm;
    GeolocationPermissions.Callback geoCb;
    String geoOrigin;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setGeolocationEnabled(true);
        st.setUserAgentString(st.getUserAgentString() + " NotAsistaniApp/2 NotAsistaniCam");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void done() {
                runOnUiThread(() -> finish());
            }
        }, "CamHost");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return "GET".equals(r.getMethod()) ? MainActivity.localAsset(CaptureActivity.this, r.getUrl()) : null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !MainActivity.HOST.equals(r.getUrl().getHost());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) req.grant(req.getResources());
                    else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION}, 71);
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) cb.invoke(origin, true, false);
                else {
                    geoCb = cb;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 72);
                }
            }
        });
        web.loadUrl(MainActivity.URL + "?cam=1");
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (pendingPerm != null) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
        if (geoCb != null) {
            geoCb.invoke(geoOrigin, checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED, false);
            geoCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        // geri: çekilenleri kaydedip kapat
        web.evaluateJavascript("window.camOnlyDone?camOnlyDone():0", v -> {
            if (!"1".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        try {
            web.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
