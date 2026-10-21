package com.yildiz.notasistani;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;

/** Kayan kamera düğmesi: uygulamayı açmadan telefonun kamerasıyla çeker, fotoğraflı not olarak bırakır. */
public class CaptureActivity extends Activity {
    static final int RC = 61;
    File out;
    long ts;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            ts = b.getLong("ts");
            String p = b.getString("out");
            if (p != null) out = new File(p);
            return; // kamera dönüşü bekleniyor
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION}, 62);
            return;
        }
        shoot();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot();
        else {
            Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        o.putLong("ts", ts);
        if (out != null) o.putString("out", out.getAbsolutePath());
    }

    void shoot() {
        try {
            ts = System.currentTimeMillis();
            out = new File(getCacheDir(), "cap_" + ts + ".jpg");
            Uri u = FileProvider.getUriForFile(this, "com.yildiz.notasistani.files", out);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, RC);
        } catch (Exception e) {
            Toast.makeText(this, "Kamera açılamadı", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    Location lastLoc() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc != RC) return;
        if (res == RESULT_OK && out != null && out.exists() && out.length() > 0) {
            try {
                File d = new File(getFilesDir(), "bubble");
                d.mkdirs();
                File dst = new File(d, "photo_" + ts + ".jpg");
                if (!out.renameTo(dst)) {
                    try (java.io.InputStream is = new java.io.FileInputStream(out); java.io.OutputStream os = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    out.delete();
                }
                JSONObject o = new JSONObject().put("ts", ts).put("photo", dst.getName());
                Location l = lastLoc();
                if (l != null) o.put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("acc", Math.round(l.getAccuracy())).put("locAge", System.currentTimeMillis() - l.getTime());
                Summary.write(new File(d, ts + ".json"), o.toString());
                Toast.makeText(this, "📷 Fotoğraflı not kaydedildi", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Fotoğraf kaydedilemedi", Toast.LENGTH_LONG).show();
            }
        } else if (out != null) out.delete();
        finish();
    }
}
