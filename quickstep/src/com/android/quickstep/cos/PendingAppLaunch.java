/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

public final class PendingAppLaunch {
    private Runnable mLaunch;
    private Runnable mOnSuperseded;

    public boolean hasPendingLaunch() {
        return mLaunch != null;
    }

    public void set(Runnable launch, Runnable onSuperseded) {
        Runnable previous = mOnSuperseded;
        mLaunch = launch;
        mOnSuperseded = onSuperseded;
        if (previous != null) previous.run();
    }

    public void execute() {
        Runnable launch = mLaunch;
        mLaunch = null;
        mOnSuperseded = null;
        if (launch != null) launch.run();
    }
}
