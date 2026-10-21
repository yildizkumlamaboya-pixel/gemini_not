package com.yildiz.notasistani;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class SummaryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Summary.schedule(c); // ertesi gün için
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            js.schedule(new JobInfo.Builder(4242, new ComponentName(c, SummaryJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(15 * 60 * 1000L)
                    .build());
        } catch (Exception ignored) {
        }
    }
}
