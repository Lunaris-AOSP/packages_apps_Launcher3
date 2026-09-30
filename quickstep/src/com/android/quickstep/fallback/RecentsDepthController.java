/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.quickstep.fallback;

import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_DEPTH;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_DEPTH_CONTROLLER;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;

import android.os.SystemProperties;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;

import com.android.launcher3.Flags;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.statehandlers.DesktopVisibilityController;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.quickstep.RecentsActivity;
import com.android.quickstep.util.BaseDepthController;

import java.io.PrintWriter;

public final class RecentsDepthController extends BaseDepthController
        implements StateHandler<RecentsState> {
    private final RecentsActivity mActivity;
    private boolean mStarted;
    private boolean mDrawListenerAdded;
    private final Runnable mRemoveDrawListener = this::removeOnDrawListener;
    private final ViewTreeObserver.OnDrawListener mOnDrawListener = this::onDraw;
    private final Runnable mOpaquenessListener = () -> {
        if (mStarted) applyDepthAndBlur();
    };
    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    addOnDrawListener();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    removeOnDrawListener();
                    setBaseSurfaceOverride(null, false);
                    setBaseSurface(null);
                    setEarlyWakeup(false);
                    onWallpaperWindowDetached();
                }
            };

    public RecentsDepthController(RecentsActivity activity, boolean blurEnabled) {
        super(activity);
        mActivity = activity;
        mCrossWindowBlursEnabled = blurEnabled && Flags.enableOverviewBackgroundWallpaperBlur();
    }

    public void initialize() {
        setSurfaceTransactionApplier(mActivity.getRootView());
        mActivity.getRootView().addOnAttachStateChangeListener(mAttachListener);
        mActivity.getScrimView().addOpaquenessListener(mOpaquenessListener);
    }

    public float getDepth(RecentsState state) {
        if (state == RecentsState.BACKGROUND_APP) {
            return getBackgroundAppDepth();
        }
        return SystemProperties.getBoolean("ro.launcher.depth.overview", true)
                ? DEPTH_70_PERCENT : DEPTH_0_PERCENT;
    }

    @Override
    public float getBackgroundAppDepth() {
        return DesktopVisibilityController.INSTANCE.get(mActivity)
                .isInDesktopMode(mActivity.getDisplayId()) ? DEPTH_0_PERCENT : DEPTH_70_PERCENT;
    }

    @Override
    protected float getTargetStateDepth() {
        return getDepth(mActivity.getStateManager().getState());
    }

    @Override
    public void setState(RecentsState state) {
        stateDepth.setValue(getDepth(state));
    }

    @Override
    public void setStateWithAnimation(RecentsState state, StateAnimationConfig config,
            PendingAnimation animation) {
        if (!config.hasAnimationFlag(SKIP_DEPTH_CONTROLLER)) {
            animation.setFloat(stateDepth, MULTI_PROPERTY_VALUE, getDepth(state),
                    config.getInterpolator(ANIM_DEPTH, LINEAR));
        }
    }

    public void setActivityStarted(boolean started) {
        if (isDisposed()) return;
        mStarted = started;
        pauseBlursOnWindows(!started);
        if (started) {
            addOnDrawListener();
        } else {
            removeOnDrawListener();
            setBaseSurfaceOverride(null, false);
            setBaseSurface(null);
            setEarlyWakeup(false);
            onWallpaperWindowDetached();
        }
    }

    private void onDraw() {
        ViewRootImpl viewRoot = mActivity.getRootView().getViewRootImpl();
        setBaseSurface(viewRoot == null ? null : viewRoot.getSurfaceControl());
        if (mBaseSurface != null && mBaseSurface.isValid()) {
            mActivity.getRootView().post(mRemoveDrawListener);
        }
    }

    @Override
    protected void onInvalidSurface() {
        addOnDrawListener();
    }

    private void addOnDrawListener() {
        mActivity.getRootView().removeCallbacks(mRemoveDrawListener);
        if (!mStarted || isDisposed() || mDrawListenerAdded) return;
        mActivity.getRootView().getViewTreeObserver().addOnDrawListener(mOnDrawListener);
        mDrawListenerAdded = true;
    }

    private void removeOnDrawListener() {
        mActivity.getRootView().removeCallbacks(mRemoveDrawListener);
        if (!mDrawListenerAdded) return;
        mActivity.getRootView().getViewTreeObserver().removeOnDrawListener(mOnDrawListener);
        mDrawListenerAdded = false;
    }

    @Override
    public void dispose() {
        setActivityStarted(false);
        mActivity.getRootView().removeOnAttachStateChangeListener(mAttachListener);
        mActivity.getScrimView().removeOpaquenessListener(mOpaquenessListener);
        super.dispose();
    }

    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "RecentsDepthController: depth=" + stateDepth.getValue()
                + " blur=" + mCurrentBlur + " started=" + mStarted);
    }
}
