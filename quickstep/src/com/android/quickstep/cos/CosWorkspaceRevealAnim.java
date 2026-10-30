/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import static com.android.launcher3.BaseActivity.EVENT_DESTROYED;
import static com.android.launcher3.LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherAnimUtils.SCALE_INDEX_WORKSPACE_STATE;
import static com.android.launcher3.LauncherAnimUtils.SCRIM_COLORS;
import static com.android.launcher3.LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.FLAG_HAS_SYS_UI_SCRIM;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.graphics.Scrim.SCRIM_PROGRESS;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_DEPTH_CONTROLLER;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_OVERVIEW;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_SCRIM;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.View;

import com.android.app.animation.Animations;
import com.android.internal.util.lunaris.Luminous;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.anim.PropertySetter;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.views.ScrimColorsEvaluator;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.views.RecentsView;

public final class CosWorkspaceRevealAnim {
    private final AnimatorSet mAnimators = new AnimatorSet();
    private boolean mPrepared;

    public CosWorkspaceRevealAnim(QuickstepLauncher launcher, RectFSpringAnim sibling,
            RectF target, boolean fade, boolean blur) {
        View workspace = launcher.getWorkspace();
        View hotseat = launcher.getHotseat();
        float startScale = workspace.getScaleX() == 1f
                ? CosAppAnimation.HOME_SCALE : workspace.getScaleX();
        Animations.Companion.cancelOngoingAnimation(workspace);
        Animations.Companion.cancelOngoingAnimation(hotseat);

        StateAnimationConfig setup = new StateAnimationConfig();
        setup.animFlags = SKIP_OVERVIEW | SKIP_DEPTH_CONTROLLER | SKIP_SCRIM;
        setup.duration = 0;

        PendingAnimation content = new PendingAnimation(CosAppAnimation.REVEAL_DURATION);
        ValueAnimator prepare = ValueAnimator.ofFloat(0f, 1f).setDuration(0);
        content.addFloat(launcher.getWorkspace(),
                WORKSPACE_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE),
                startScale, 1f, CosAppAnimation.REVEAL);
        content.addFloat(launcher.getHotseat(),
                HOTSEAT_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE),
                startScale, 1f, CosAppAnimation.REVEAL);

        RectF original = target == null ? null : new RectF(target);
        RectF transformed = new RectF();
        Matrix matrix = new Matrix();
        content.addOnFrameCallback(() -> {
            if (original == null || sibling == null || !sibling.isRunning()) return;
            transformed.set(original);
            // Workspace and hotseat share a pivot, so dock icons follow this transform too.
            matrix.setScale(workspace.getScaleX(), workspace.getScaleY(),
                    workspace.getPivotX(), workspace.getPivotY());
            matrix.mapRect(transformed);
            transformed.inset((transformed.width() - original.width()) / 2f,
                    (transformed.height() - original.height()) / 2f);
            if (!transformed.equals(target)) {
                target.set(transformed);
                sibling.onTargetPositionChanged();
            }
        });

        DepthController depth = launcher.getDepthController();
        boolean contentBlur = blur && LauncherPrefs.APP_LAUNCH_BLUR_ENABLED.get(launcher);
        int blurRadius = contentBlur ? launcher.getResources().getDimensionPixelSize(
                R.dimen.max_depth_blur_radius_enhanced) : 0;
        if (blur) {
            content.addFloat(depth.stateDepth, MULTI_PROPERTY_VALUE,
                    BACKGROUND_APP.getDepth(launcher), NORMAL.getDepth(launcher),
                    CosAppAnimation.REVEAL);
            if (contentBlur) {
                ValueAnimator blurAnimator = ValueAnimator.ofFloat(blurRadius, 0f);
                blurAnimator.setInterpolator(CosAppAnimation.REVEAL);
                blurAnimator.addUpdateListener(value ->
                        depth.setAppAnimationBlur((Float) value.getAnimatedValue()));
                content.add(blurAnimator);
            }
            mAnimators.play(depth.animateAppWallpaperDepth(BACKGROUND_APP.getDepth(launcher),
                    NORMAL.getDepth(launcher), CosAppAnimation.WALLPAPER_CLOSE_DURATION,
                    CosAppAnimation.WALLPAPER_CLOSE)).after(prepare);
        }
        content.addFloat(launcher.getDragLayer().getWorkspaceDragScrim(), SCRIM_PROGRESS,
                BACKGROUND_APP.getWorkspaceBackgroundAlpha(launcher),
                NORMAL.getWorkspaceBackgroundAlpha(launcher), CosAppAnimation.REVEAL);
        content.addFloat(launcher.getRootView().getSysUiScrim().getSysUIProgress(),
                AnimatedFloat.VALUE, BACKGROUND_APP.hasFlag(FLAG_HAS_SYS_UI_SCRIM) ? 1f : 0f,
                NORMAL.hasFlag(FLAG_HAS_SYS_UI_SCRIM) ? 1f : 0f, CosAppAnimation.REVEAL);
        ObjectAnimator scrim = ObjectAnimator.ofObject(launcher.getScrimView(), SCRIM_COLORS,
                ScrimColorsEvaluator.INSTANCE, BACKGROUND_APP.getWorkspaceScrimColor(launcher),
                NORMAL.getWorkspaceScrimColor(launcher));
        scrim.setInterpolator(CosAppAnimation.REVEAL);
        content.add(scrim);
        mAnimators.play(content.buildAnim()).after(prepare);
        Runnable destroyCallback = mAnimators::cancel;
        StateListener<LauncherState> stateListener = new StateListener<>() {
            @Override
            public void onStateTransitionStart(LauncherState state) {
                if (state != NORMAL) mAnimators.cancel();
            }
        };
        prepare.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                Luminous.notifyAnimationStart(workspace);
                mPrepared = true;
                launcher.getStateManager().createAtomicAnimation(BACKGROUND_APP, NORMAL, setup)
                        .start();
                launcher.<RecentsView>getOverviewPanel().forceFinishScroller();
                launcher.getWorkspace().getStateTransitionAnimation().setScrim(
                        PropertySetter.NO_ANIM_PROPERTY_SETTER, BACKGROUND_APP, setup);
                launcher.getWorkspace().setPivotToScaleWithSelf(hotseat);
                if (blur) {
                    depth.pauseBlursOnWindows(true);
                    depth.stateDepth.setValue(BACKGROUND_APP.getDepth(launcher));
                }
                if (contentBlur) depth.setAppAnimationBlur(blurRadius);
                launcher.addEventCallback(EVENT_DESTROYED, destroyCallback);
                launcher.getStateManager().addStateListener(stateListener);
                Animations.Companion.setOngoingAnimation(workspace, mAnimators);
                Animations.Companion.setOngoingAnimation(hotseat, mAnimators);
                if (fade) {
                    workspace.setAlpha(0f);
                    hotseat.setAlpha(0f);
                    Luminous.spring(workspace, Luminous.ALPHA, 1f, 900f, 1f);
                    Luminous.spring(hotseat, Luminous.ALPHA, 1f, 900f, 1f);
                }
            }
        });
        mAnimators.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mPrepared) return;
                mPrepared = false;
                launcher.removeEventCallback(EVENT_DESTROYED, destroyCallback);
                launcher.getStateManager().removeStateListener(stateListener);
                Luminous.cancel(workspace, Luminous.ALPHA);
                Luminous.cancel(hotseat, Luminous.ALPHA);
                workspace.setAlpha(1f);
                hotseat.setAlpha(1f);
                WORKSPACE_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE)
                        .setValue(launcher.getWorkspace(), 1f);
                HOTSEAT_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE)
                        .setValue(launcher.getHotseat(), 1f);
                if (original != null) {
                    target.set(original);
                    if (sibling != null) sibling.onTargetPositionChanged();
                }
                Animations.Companion.setOngoingAnimation(workspace, null);
                Animations.Companion.setOngoingAnimation(hotseat, null);
                depth.setAppAnimationBlur(-1f);
                depth.pauseBlursOnWindows(false);
                depth.stateDepth.setValue(launcher.getStateManager().getState().getDepth(launcher));
            }
        });
    }

    public AnimatorSet getAnimators() {
        return mAnimators;
    }

    public void start() {
        mAnimators.start();
    }
}
