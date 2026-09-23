package com.android.launcher3.search.universal;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class PhotoIndexJob extends JobService {

    private volatile boolean mStopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        mStopped = false;
        PhotoIndex.runBulk(this, () -> mStopped, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        mStopped = true;
        return true;
    }
}
