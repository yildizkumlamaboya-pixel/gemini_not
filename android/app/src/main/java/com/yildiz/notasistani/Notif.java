package com.yildiz.notasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

class Notif {
    static final String CH = "hatirlatma";

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.setDescription("Görev ve not hatırlatmaları");
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    static void show(Context c, int id, String title, String body) {
        show(c, id, title, body, null);
    }

    static PendingIntent act(Context c, String taskId, String act, int min, String title, String body, int rc) {
        Intent i = new Intent(c, NotifAction.class).setAction("NA_ACT_" + act + "_" + min + "_" + taskId)
                .putExtra("id", taskId).putExtra("act", act).putExtra("min", min).putExtra("title", title).putExtra("body", body);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, int id, String title, String body, String taskId) {
        channel(c);
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setColor(0xFF2563EB)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (taskId != null && !taskId.isEmpty()) {
            android.graphics.drawable.Icon ic = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat);
            b.addAction(new Notification.Action.Builder(ic, "✓ Tamamlandı", act(c, taskId, "done", 0, title, body, id * 4 + 1)).build());
            b.addAction(new Notification.Action.Builder(ic, "15 dk ertele", act(c, taskId, "snooze", 15, title, body, id * 4 + 2)).build());
            b.addAction(new Notification.Action.Builder(ic, "1 saat ertele", act(c, taskId, "snooze", 60, title, body, id * 4 + 3)).build());
        }
        try {
            c.getSystemService(NotificationManager.class).notify(id, b.build());
        } catch (SecurityException ignored) {
        }
    }
}
