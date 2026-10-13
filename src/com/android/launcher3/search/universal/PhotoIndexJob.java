package com.android.launcher3.search.universal;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class PhotoIndexJob extends JobService {

    private java.util.concurrent.atomic.AtomicBoolean mStopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        mStopped = stopped;
        PhotoIndex.runBulk(this, stopped::get, () -> {
            if (!stopped.get()) {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mStopped != null) {
            mStopped.set(true);
        }
        return true;
    }
}
