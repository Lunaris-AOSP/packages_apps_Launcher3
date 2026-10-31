/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.hardware.power.Boost;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Singleton;
import android.view.View;

import com.android.internal.util.lunaris.Luminous;

public final class InteractionBoost {
    private static final String TAG = "InteractionBoost";
    private static final int ANIMATION_BOOST_DURATION_MS = 500;
    private static final int MIN_BOOST_INTERVAL_MS = 100;

    private static final Singleton<IPowerManager> POWER_MANAGER = new Singleton<>() {
        @Override
        protected IPowerManager create() {
            return IPowerManager.Stub.asInterface(
                    ServiceManager.getService(Context.POWER_SERVICE));
        }
    };

    private static long sLastBoostTime = -MIN_BOOST_INTERVAL_MS;
    private static int sLastBoostDuration;

    private InteractionBoost() { }

    public static void requestForAnimation(View view) {
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow()) {
            return;
        }
        Luminous.notifyAnimationStart(view);
        request(ANIMATION_BOOST_DURATION_MS);
    }

    public static synchronized void request(int durationMs) {
        if (durationMs <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long elapsed = now - sLastBoostTime;
        if (elapsed < MIN_BOOST_INTERVAL_MS && elapsed < sLastBoostDuration
                && durationMs <= sLastBoostDuration) {
            return;
        }
        IPowerManager powerManager = POWER_MANAGER.get();
        if (powerManager == null) {
            return;
        }
        try {
            powerManager.setPowerBoost(Boost.INTERACTION, durationMs);
            sLastBoostTime = now;
            sLastBoostDuration = durationMs;
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Unable to request interaction boost", e);
        }
    }
}
