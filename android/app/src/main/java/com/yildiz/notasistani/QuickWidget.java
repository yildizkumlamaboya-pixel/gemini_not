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

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
            v.setOnClickPendingIntent(R.id.wMic, act(c, "voice", 31));
            v.setOnClickPendingIntent(R.id.wCam, act(c, "camera", 32));
            m.updateAppWidget(id, v);
        }
    }
}
