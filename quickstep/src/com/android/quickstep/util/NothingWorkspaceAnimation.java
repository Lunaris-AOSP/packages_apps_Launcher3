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
package com.android.quickstep.util;

import static com.android.launcher3.LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherAnimUtils.SCALE_INDEX_WORKSPACE_STATE;
import static com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA;
import static com.android.launcher3.LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_DEPTH_CONTROLLER;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_OVERVIEW;
import static com.android.launcher3.states.StateAnimationConfig.SKIP_SCRIM;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.FloatProperty;
import android.view.SurfaceControl;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.app.animation.Animations;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.PropertySetter;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.InteractionBoost;
import com.android.quickstep.cos.CosAppAnimation;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.system.BlurUtils;

import java.util.ArrayList;

public final class NothingWorkspaceAnimation {
    private final QuickstepLauncher mLauncher;
    private final ViewMotion mWorkspace;
    private final ViewMotion mHotseat;
    private final SpringAnimation mDepth;
    private final SpringAnimation mSysUi;
    private final Matrix mTargetMatrix = new Matrix();
    private final RectF mOriginalTarget = new RectF();
    private final RectF mTransformedTarget = new RectF();
    private final SurfaceTransactionApplier mApplier;
    private final ArrayList<RectFSpringAnim> mClosingAnimations = new ArrayList<>();
    private RectFSpringAnim mSibling;
    private RectF mTarget;
    private SurfaceControl mBlurLayer;
    private boolean mOwnsBlurPause;
    private int mLastBlur = -1;
    private float mDepthValue;
    private float mSysUiValue;
    private final StateListener<LauncherState> mStateListener = new StateListener<>() {
        @Override
        public void onStateTransitionStart(LauncherState state) {
            if (state != NORMAL && state != BACKGROUND_APP) reset();
        }

        @Override
        public void onStateTransitionComplete(LauncherState state) {
            if (state != NORMAL && state != BACKGROUND_APP) return;
            if (mWorkspace.isRunning()) mWorkspace.restoreValues();
            if (mHotseat.isRunning()) mHotseat.restoreValues();
            if (mDepth.isRunning()) mLauncher.getDepthController().stateDepth.setValue(mDepthValue);
            if (mSysUi.isRunning()) {
                mLauncher.getRootView().getSysUiScrim().getSysUIProgress().updateValue(mSysUiValue);
            }
        }
    };

    public NothingWorkspaceAnimation(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mWorkspace = new ViewMotion(launcher.getWorkspace(),
                WORKSPACE_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE));
        mHotseat = new ViewMotion(launcher.getHotseat(),
                HOTSEAT_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE));
        mWorkspace.scale.addUpdateListener((animation, value, velocity) -> updateTarget());
        mDepth = spring(launcher.getDepthController().stateDepth, MULTI_PROPERTY_VALUE,
                NothingAppAnimation.DEPTH_STIFFNESS, 0.001f);
        mDepthValue = launcher.getDepthController().stateDepth.getValue();
        mDepth.addUpdateListener((animation, value, velocity) -> {
            mDepthValue = value;
            updateBlur(value);
        });
        mDepth.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) removeBlur();
        });
        mSysUi = spring(launcher.getRootView().getSysUiScrim().getSysUIProgress(),
                AnimatedFloat.VALUE, NothingAppAnimation.SYS_UI_STIFFNESS, 0.001f);
        mSysUiValue = launcher.getRootView().getSysUiScrim().getSysUIProgress().value;
        mSysUi.addUpdateListener((animation, value, velocity) -> mSysUiValue = value);
        mApplier = new SurfaceTransactionApplier(launcher.getDragLayer());
        launcher.getStateManager().addStateListener(mStateListener);
    }

    public AnimatorSet createRevealAnimation(RectFSpringAnim sibling, RectF target,
            boolean fade, boolean blur) {
        return animation(() -> reveal(sibling, target, fade, blur));
    }

    public AnimatorSet createOpeningAnimation(boolean scrimHandlesBlur) {
        return animation(() -> open(scrimHandlesBlur));
    }

    public void trackClosingAnimation(RectFSpringAnim spring) {
        spring.addAnimatorListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mClosingAnimations.add(spring);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mClosingAnimations.remove(spring);
            }
        });
    }

    private AnimatorSet animation(Runnable start) {
        AnimatorSet set = new AnimatorSet();
        set.play(ValueAnimator.ofFloat(0f, 1f)
                .setDuration(NothingAppAnimation.LAUNCH_DURATION_MS));
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (!CosAppAnimation.isEnabled(mLauncher)) start.run();
            }
        });
        return set;
    }

    public void open(boolean scrimHandlesBlur) {
        if (CosAppAnimation.isEnabled(mLauncher)) return;
        mSibling = null;
        mTarget = null;
        prepareViews();
        mLauncher.getWorkspace().setPivotToScaleWithSelf(mLauncher.getHotseat());
        mWorkspace.animate(NothingAppAnimation.WORKSPACE_MIN_SCALE,
                NothingAppAnimation.WORKSPACE_MIN_ALPHA);
        mHotseat.animate(NothingAppAnimation.WORKSPACE_MIN_SCALE,
                NothingAppAnimation.WORKSPACE_MIN_ALPHA);
        animateDepth(true, scrimHandlesBlur);
        mLauncher.getRootView().getSysUiScrim().getSysUIMultiplier().updateValue(1f);
        if (!mSysUi.isRunning()) {
            mLauncher.getRootView().getSysUiScrim().getSysUIProgress().updateValue(1f);
        }
        retarget(mSysUi, NothingAppAnimation.SYS_UI_STIFFNESS, 0f);
    }

    public void reveal(RectFSpringAnim sibling, RectF target, boolean fade, boolean blur) {
        if (CosAppAnimation.isEnabled(mLauncher)) return;
        float scale = mWorkspace.currentScale();
        float alpha = mWorkspace.currentAlpha();
        float depth = mDepth.isRunning() ? mDepthValue
                : mLauncher.getDepthController().stateDepth.getValue();
        float sysUi = mSysUi.isRunning() ? mSysUiValue : 0f;
        boolean running = mWorkspace.isRunning();
        prepareViews();
        StateAnimationConfig setup = new StateAnimationConfig();
        setup.animFlags = SKIP_OVERVIEW | SKIP_DEPTH_CONTROLLER | SKIP_SCRIM;
        setup.duration = 0;
        mLauncher.getStateManager().createAtomicAnimation(BACKGROUND_APP, NORMAL, setup).start();
        mLauncher.<RecentsView>getOverviewPanel().forceFinishScroller();
        mLauncher.getWorkspace().getStateTransitionAnimation().setScrim(
                PropertySetter.NO_ANIM_PROPERTY_SETTER, BACKGROUND_APP, setup);
        mWorkspace.setValues(scale != 1f || running
                        ? scale : NothingAppAnimation.WORKSPACE_MIN_SCALE,
                !fade ? alpha : alpha != 1f || running
                        ? alpha : NothingAppAnimation.WORKSPACE_MIN_ALPHA);
        mHotseat.setValues(mWorkspace.view.getScaleX(), mWorkspace.view.getAlpha());
        mLauncher.getDepthController().stateDepth.setValue(depth);
        mLauncher.getWorkspace().setPivotToScaleWithSelf(mLauncher.getHotseat());
        mSibling = sibling;
        mTarget = target;
        if (target != null) mOriginalTarget.set(target);
        mWorkspace.animate(1f, 1f);
        mHotseat.animate(1f, 1f);
        if (blur) animateDepth(false, false);
        mLauncher.getRootView().getSysUiScrim().getSysUIMultiplier().updateValue(1f);
        mLauncher.getRootView().getSysUiScrim().getSysUIProgress().updateValue(sysUi);
        retarget(mSysUi, NothingAppAnimation.SYS_UI_STIFFNESS, 1f);
        updateTarget();
    }

    private void prepareViews() {
        float scale = mWorkspace.currentScale();
        float alpha = mWorkspace.currentAlpha();
        float hotseatScale = mHotseat.currentScale();
        float hotseatAlpha = mHotseat.currentAlpha();
        Animations.Companion.cancelOngoingAnimation(mLauncher.getWorkspace());
        Animations.Companion.cancelOngoingAnimation(mLauncher.getHotseat());
        mWorkspace.setValues(scale, alpha);
        mHotseat.setValues(hotseatScale, hotseatAlpha);
        InteractionBoost.requestForAnimation(mLauncher.getWorkspace());
    }

    private void animateDepth(boolean opening, boolean scrimHandlesBlur) {
        boolean blur = LauncherPrefs.APP_LAUNCH_BLUR_ENABLED.get(mLauncher);
        if (!opening && blur && BlurUtils.supportsBlursOnWindows() && mBlurLayer == null) {
            var root = mLauncher.getDragLayer().getViewRootImpl();
            SurfaceControl parent = root == null ? null : root.getSurfaceControl();
            if (parent != null && parent.isValid()) {
                mBlurLayer = new SurfaceControl.Builder().setName("Nothing workspace blur")
                        .setParent(parent).setEffectLayer().setOpaque(false).setHidden(false)
                        .build();
                mLastBlur = -1;
            }
        }
        boolean pause = scrimHandlesBlur || !blur || mBlurLayer != null;
        if (pause && !mLauncher.getDepthController().isBlursPaused()) {
            mLauncher.getDepthController().pauseBlursOnWindows(true);
            mOwnsBlurPause = true;
        }
        float target = opening ? BACKGROUND_APP.getDepth(mLauncher) : NORMAL.getDepth(mLauncher);
        if (!opening && !mDepth.isRunning()
                && mLauncher.getDepthController().stateDepth.getValue() == target) {
            mLauncher.getDepthController().stateDepth.setValue(BACKGROUND_APP.getDepth(mLauncher));
        }
        retarget(mDepth, NothingAppAnimation.DEPTH_STIFFNESS, target);
    }

    private void updateBlur(float depth) {
        if (mBlurLayer == null || !mBlurLayer.isValid()) return;
        float progress = Math.max(0f, Math.min(1f,
                depth / Math.max(0.001f, BACKGROUND_APP.getDepth(mLauncher))));
        int radius = Math.round(progress * mLauncher.getResources().getDimensionPixelSize(
                R.dimen.max_depth_blur_radius_enhanced));
        if (Math.abs(radius - mLastBlur) < 4 && radius != 0) return;
        SurfaceTransaction transaction = new SurfaceTransaction();
        transaction.forSurface(mBlurLayer).setBackgroundBlurRadius(radius)
                .setAlpha(progress).setShow();
        mApplier.scheduleApply(transaction);
        mLastBlur = radius;
    }

    private void updateTarget() {
        if (mTarget == null || mSibling == null || !mSibling.isRunning()
                || mSibling.isReverse()) return;
        View workspace = mLauncher.getWorkspace();
        mTransformedTarget.set(mOriginalTarget);
        mTargetMatrix.setScale(workspace.getScaleX(), workspace.getScaleY(),
                workspace.getPivotX(), workspace.getPivotY());
        mTargetMatrix.mapRect(mTransformedTarget);
        mTransformedTarget.inset((mTransformedTarget.width() - mOriginalTarget.width()) / 2f,
                (mTransformedTarget.height() - mOriginalTarget.height()) / 2f);
        if (!mTransformedTarget.equals(mTarget)) {
            mTarget.set(mTransformedTarget);
            mSibling.onTargetPositionChanged();
        }
    }

    private void removeBlur() {
        if (mBlurLayer != null) {
            if (mBlurLayer.isValid()) {
                SurfaceTransaction transaction = new SurfaceTransaction();
                transaction.forSurface(mBlurLayer).setRemove();
                mApplier.scheduleApply(transaction);
            }
            mBlurLayer = null;
        }
        if (mOwnsBlurPause) {
            mLauncher.getDepthController().pauseBlursOnWindows(false);
            mOwnsBlurPause = false;
        }
    }

    public void reset() {
        mSibling = null;
        mTarget = null;
        ArrayList<RectFSpringAnim> closingAnimations = new ArrayList<>(mClosingAnimations);
        mClosingAnimations.clear();
        for (RectFSpringAnim spring : closingAnimations) {
            if (spring.isRunning()) spring.end();
        }
        mWorkspace.cancel();
        mHotseat.cancel();
        mDepth.cancel();
        mSysUi.cancel();
        removeBlur();
        mWorkspace.setValues(1f, 1f);
        mHotseat.setValues(1f, 1f);
        mLauncher.getDepthController().setState(mLauncher.getStateManager().getState());
    }

    public void destroy() {
        mLauncher.getStateManager().removeStateListener(mStateListener);
        reset();
    }

    private static SpringAnimation spring(Object target, FloatProperty property,
            float stiffness, float minimumChange) {
        return new SpringAnimation(target, FloatPropertyCompat.createFloatPropertyCompat(property))
                .setSpring(new SpringForce().setStiffness(stiffness)
                        .setDampingRatio(NothingAppAnimation.DAMPING_RATIO))
                .setMinimumVisibleChange(minimumChange);
    }

    private static void retarget(SpringAnimation spring, float stiffness, float target) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            spring.animateToFinalPosition(target);
            spring.skipToEnd();
            return;
        }
        spring.getSpring().setStiffness(stiffness / ValueAnimator.getDurationScale());
        spring.animateToFinalPosition(target);
    }

    private static final class ViewMotion {
        final View view;
        final FloatProperty scaleProperty;
        final SpringAnimation scale;
        final SpringAnimation alpha;
        private int layerType;
        private boolean layered;
        private float scaleValue;
        private float alphaValue;

        ViewMotion(View target, FloatProperty property) {
            view = target;
            scaleProperty = property;
            scale = spring(target, property, NothingAppAnimation.WORKSPACE_SCALE_STIFFNESS, 0.001f);
            alpha = spring(target, VIEW_ALPHA, NothingAppAnimation.WORKSPACE_ALPHA_STIFFNESS, 0.002f);
            scaleValue = target.getScaleX();
            alphaValue = target.getAlpha();
            scale.addUpdateListener((animation, value, velocity) -> scaleValue = value);
            alpha.addUpdateListener((animation, value, velocity) -> alphaValue = value);
            scale.addEndListener((animation, canceled, value, velocity) -> restoreLayer());
            alpha.addEndListener((animation, canceled, value, velocity) -> restoreLayer());
        }

        void animate(float size, float opacity) {
            if (!layered) {
                layerType = view.getLayerType();
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                layered = true;
            }
            retarget(scale, NothingAppAnimation.WORKSPACE_SCALE_STIFFNESS, size);
            retarget(alpha, NothingAppAnimation.WORKSPACE_ALPHA_STIFFNESS, opacity);
        }

        float currentScale() {
            return scale.isRunning() ? scaleValue : view.getScaleX();
        }

        float currentAlpha() {
            return alpha.isRunning() ? alphaValue : view.getAlpha();
        }

        void restoreValues() {
            scaleProperty.setValue(view, scaleValue);
            view.setAlpha(alphaValue);
        }

        void setValues(float size, float opacity) {
            scaleValue = size;
            alphaValue = opacity;
            scaleProperty.setValue(view, size);
            view.setAlpha(opacity);
        }

        boolean isRunning() {
            return scale.isRunning() || alpha.isRunning();
        }

        void restoreLayer() {
            if (layered && !isRunning()) {
                view.setLayerType(layerType, null);
                layered = false;
            }
        }

        void cancel() {
            scale.cancel();
            alpha.cancel();
            restoreLayer();
        }
    }
}
