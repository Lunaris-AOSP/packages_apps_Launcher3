/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep;

import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Looper;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.Utilities;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.ScreenOnTracker;
import com.android.quickstep.util.HomeUnlockReveal;
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController;
import com.android.systemui.shared.system.smartspace.SmartspaceState;

import java.util.concurrent.ExecutionException;

public final class HomeUnlockRevealController
        extends ILauncherUnlockAnimationController.Stub
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long PREPARE_TIMEOUT_MS = 2000;

    private QuickstepLauncher mLauncher;
    private HomeUnlockReveal mWorkspaceAnimation;
    private ValueAnimator mAnimator;
    private boolean mPrepared;
    private final Runnable mPrepareTimeout = this::cancel;
    private final ScreenOnTracker.ScreenOnListener mScreenOnListener = isOn -> {
        if (!isOn) cancel();
    };
    private final Runnable mSystemUiConnectionListener = () -> {
        if (mLauncher != null && !SystemUiProxy.INSTANCE.get(mLauncher).isActive()) {
            cancel();
        }
    };

    public HomeUnlockRevealController(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mWorkspaceAnimation = new HomeUnlockReveal(launcher);
        ScreenOnTracker.INSTANCE.get(launcher).addListener(mScreenOnListener);
        LauncherPrefs.getPrefs(launcher).registerOnSharedPreferenceChangeListener(this);
        SystemUiProxy proxy = SystemUiProxy.INSTANCE.get(launcher);
        proxy.addOnStateChangeListener(mSystemUiConnectionListener);
        proxy.setLauncherUnlockAnimationController(
                launcher.getComponentName().getClassName(), this);
    }

    @Override
    public void prepareForUnlock(boolean animateSmartspace, Rect lockscreenSmartspaceBounds,
            int selectedPage) {
        Runnable prepare = () -> {
            cancel();
            if (isEnabled()) {
                mPrepared = mWorkspaceAnimation.prepareForUnlock();
                if (mPrepared) {
                    mLauncher.getDragLayer().postDelayed(mPrepareTimeout, PREPARE_TIMEOUT_MS);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepare.run();
            return;
        }
        try {
            MAIN_EXECUTOR.submit(prepare).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing workspace unlock", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to prepare workspace unlock", e.getCause());
        }
    }

    @Override
    public void setUnlockAmount(float amount, boolean forceIfAnimating) {
        MAIN_EXECUTOR.execute(() -> {
            if (!isEnabled()) {
                cancel();
                return;
            }
            if (!Float.isFinite(amount) || (mAnimator != null && !forceIfAnimating)) {
                return;
            }
            mLauncher.getDragLayer().removeCallbacks(mPrepareTimeout);
            stopAnimator();
            if (amount >= 1f) {
                cancel();
            } else if (mPrepared && mWorkspaceAnimation.finishPreparation()) {
                mWorkspaceAnimation.setUnlockAmount(Utilities.boundToRange(amount, 0f, 1f));
                mLauncher.getDragLayer().postDelayed(mPrepareTimeout, PREPARE_TIMEOUT_MS);
            } else {
                cancel();
            }
        });
    }

    @Override
    public void playUnlockAnimation(boolean unlocked, long duration, long startDelay) {
        MAIN_EXECUTOR.execute(() -> {
            if (!isEnabled()) {
                cancel();
                return;
            }
            if (!mPrepared || !mWorkspaceAnimation.isPrepared()) {
                cancel();
                return;
            }
            mLauncher.getDragLayer().removeCallbacks(mPrepareTimeout);
            stopAnimator();
            if (!mWorkspaceAnimation.finishPreparation()) {
                cancel();
                return;
            }
            float from = mWorkspaceAnimation.getUnlockAmount();
            float to = unlocked ? 1f : 0f;
            if (duration <= 0 || from == to) {
                cancel();
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(from, to);
            mAnimator = animator;
            animator.setDuration(Math.round(
                    mWorkspaceAnimation.getDuration() * Math.abs(to - from)));
            long revealStartDelay = Math.max(0L, startDelay);
            animator.setStartDelay(unlocked ? revealStartDelay / 2 : revealStartDelay);
            animator.setInterpolator(LINEAR);
            animator.addUpdateListener(value -> {
                if (!isEnabled() || !mWorkspaceAnimation.isPrepared()) {
                    cancel();
                } else {
                    mWorkspaceAnimation.setUnlockAmount((Float) value.getAnimatedValue());
                    if (unlocked && mWorkspaceAnimation.isFinished()) {
                        cancel();
                    }
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mAnimator == animation) {
                        mAnimator = null;
                        cancel();
                    }
                }
            });
            animator.start();
        });
    }

    @Override
    public void playUnlockAnimationWithWallpaperDepth(boolean unlocked, long duration,
            long startDelay, boolean animateWallpaperDepth) {
        playUnlockAnimation(unlocked, duration, startDelay);
    }

    @Override
    public void setSmartspaceSelectedPage(int selectedPage) { }

    @Override
    public void setSmartspaceVisibility(int visibility) { }

    @Override
    public void dispatchSmartspaceStateToSysui() {
        MAIN_EXECUTOR.execute(() -> {
            if (mLauncher != null) {
                SystemUiProxy.INSTANCE.get(mLauncher)
                        .notifySysuiSmartspaceStateUpdated(new SmartspaceState());
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (key == null || LauncherPrefs.WORKSPACE_UNLOCK_ANIMATION.getSharedPrefKey().equals(key)) {
            MAIN_EXECUTOR.execute(() -> {
                if (!isEnabled()) {
                    cancel();
                }
            });
        }
    }

    private boolean isEnabled() {
        return mLauncher != null && ValueAnimator.areAnimatorsEnabled()
                && LauncherPrefs.WORKSPACE_UNLOCK_ANIMATION.get(mLauncher);
    }

    private void stopAnimator() {
        if (mAnimator != null) {
            ValueAnimator animator = mAnimator;
            mAnimator = null;
            animator.cancel();
        }
    }

    public void cancel() {
        stopAnimator();
        if (mLauncher != null) {
            mLauncher.getDragLayer().removeCallbacks(mPrepareTimeout);
        }
        if (mWorkspaceAnimation != null) {
            mWorkspaceAnimation.reset();
        }
        mPrepared = false;
        if (mLauncher != null && mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().postInvalidate();
        }
    }

    public void destroy() {
        if (mLauncher == null) {
            return;
        }
        cancel();
        ScreenOnTracker.INSTANCE.get(mLauncher).removeListener(mScreenOnListener);
        LauncherPrefs.getPrefs(mLauncher).unregisterOnSharedPreferenceChangeListener(this);
        SystemUiProxy proxy = SystemUiProxy.INSTANCE.get(mLauncher);
        proxy.removeOnStateChangeListener(mSystemUiConnectionListener);
        proxy.clearLauncherUnlockAnimationController(this);
        mWorkspaceAnimation = null;
        mLauncher = null;
    }
}
