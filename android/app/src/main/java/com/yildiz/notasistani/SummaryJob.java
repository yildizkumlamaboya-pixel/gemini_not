package com.yildiz.notasistani;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class SummaryJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                String t = Summary.generate(SummaryJob.this);
                if (t != null) Notif.show(SummaryJob.this, 9001, "📋 Günün özeti", t);
            } catch (Exception ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
