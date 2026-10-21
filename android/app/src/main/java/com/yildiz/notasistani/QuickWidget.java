package com.yildiz.notasistani;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/** Ana ekran widget'ı: tek dokunuşla sesli not ve fotoğraflı not. */
public class QuickWidget extends AppWidgetProvider {
    static PendingIntent act(Context c, String a, int rc) {
        Intent i = new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("notasistani://" + a))
                .putExtra("act", a)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static RemoteViews views(Context c) {
        boolean rec = QuickRecService.recording;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        // mikrofon: uygulamayı açmadan kayıt (kırmızı = dinliyor; tekrar basınca kaydeder)
        v.setOnClickPendingIntent(R.id.wMic, QuickRecService.toggle(c));
        v.setInt(R.id.wMic, "setBackgroundResource", rec ? R.drawable.bg_rec : R.drawable.bg_mic);
        v.setImageViewResource(R.id.wMic, rec ? R.drawable.ic_stop : R.drawable.ic_mic);
        v.setOnClickPendingIntent(R.id.wCam, act(c, "camera", 32));
        return v;
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new android.content.ComponentName(c, QuickWidget.class));
            if (ids != null && ids.length > 0) m.updateAppWidget(ids, views(c));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c));
    }
}
