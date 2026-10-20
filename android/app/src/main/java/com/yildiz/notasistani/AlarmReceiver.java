package com.yildiz.notasistani;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String id = i.getStringExtra("id");
        Notif.show(c, id == null ? 0 : id.hashCode(), i.getStringExtra("title"), i.getStringExtra("body"));
        if (id != null) Alarms.done(c, id);
    }
}
