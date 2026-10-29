/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.launcher3.util;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.util.Objects;

public class SpringEdgeEffectFactory extends RecyclerView.EdgeEffectFactory {
    @NonNull
    @Override
    protected EdgeEffect createEdgeEffect(@NonNull RecyclerView view, int direction) {
        return createSpringEdgeEffect(view, direction);
    }

    public static EdgeEffect createSpringEdgeEffect(RecyclerView view, int direction) {
        boolean vertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM;
        float sign = direction == DIRECTION_TOP || direction == DIRECTION_LEFT ? 1f : -1f;
        return new SpringEdgeEffect(offsetFor(view, vertical), sign);
    }

    @SuppressLint({"RestrictedApi", "VisibleForTests"})
    public static void install(NestedScrollView view) {
        if (!(view.mEdgeGlowTop instanceof SpringEdgeEffect)) {
            SpringOffset offset = offsetFor(view, true);
            view.mEdgeGlowTop = new SpringEdgeEffect(offset, 1f);
            view.mEdgeGlowBottom = new SpringEdgeEffect(offset, -1f);
        }
    }

    private static SpringOffset offsetFor(View view, boolean vertical) {
        int key = vertical ? R.id.launcher_spring_vertical : R.id.launcher_spring_horizontal;
        Object existing = view.getTag(key);
        if (existing instanceof SpringOffset) return (SpringOffset) existing;
        SpringOffset offset = new SpringOffset(view, vertical);
        view.setTag(key, offset);
        return offset;
    }

    static final class SpringOffset implements View.OnAttachStateChangeListener {
        final View view;
        final boolean vertical;
        final SpringAnimation animation;
        SpringScrollPhysics physics;
        float activeSign;
        boolean returning;
        boolean returnHeld;

        SpringOffset(View view, boolean vertical) {
            this.view = view;
            this.vertical = vertical;
            physics = LauncherScrollPreferences.get(view.getContext());
            animation = new SpringAnimation(view,
                    vertical ? DynamicAnimation.TRANSLATION_Y : DynamicAnimation.TRANSLATION_X);
            animation.setSpring(new SpringForce(0f));
            animation.addEndListener((spring, canceled, value, velocity) -> {
                if (!canceled) returning = false;
            });
            if (view instanceof RecyclerView) {
                RecyclerView list = (RecyclerView) view;
                list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                    @Override
                    public boolean onInterceptTouchEvent(RecyclerView recyclerView, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                holdReturn();
                                break;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                resumeReturn();
                                break;
                        }
                        return false;
                    }

                    @Override
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                        if (disallowIntercept) resumeReturn();
                    }
                });
                list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) beginDrag();
                    }
                });
            }
            configure();
            view.addOnAttachStateChangeListener(this);
        }

        void configure() {
            Objects.requireNonNull(animation.getSpring()).setStiffness(physics.stiffness)
                    .setDampingRatio(physics.dampingRatio);
        }

        boolean enabled() {
            SpringScrollPhysics latest = LauncherScrollPreferences.get(view.getContext());
            if (physics != latest) {
                reset();
                physics = latest;
                configure();
            }
            if (!physics.enabled || !ValueAnimator.areAnimatorsEnabled()) {
                reset();
                return false;
            }
            return true;
        }

        float extent() { return vertical ? view.getHeight() : view.getWidth(); }
        float value() { return vertical ? view.getTranslationY() : view.getTranslationX(); }

        void setValue(float value) {
            if (vertical) view.setTranslationY(value);
            else view.setTranslationX(value);
        }

        void holdReturn() {
            if (!returning) return;
            returnHeld = true;
            animation.cancel();
        }

        void resumeReturn() {
            if (!returnHeld) return;
            returnHeld = false;
            if (returning) release(0f);
        }

        void beginDrag() {
            returning = false;
            returnHeld = false;
            animation.cancel();
        }

        void release(float velocity) {
            returning = true;
            returnHeld = false;
            animation.setStartVelocity(physics.springVelocity(velocity, extent(), value()));
            animation.animateToFinalPosition(0f);
        }

        void reset() {
            animation.cancel();
            setValue(0f);
            activeSign = 0f;
            returning = false;
            returnHeld = false;
        }

        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) { reset(); }
    }

    static final class SpringEdgeEffect extends EdgeEffect {
        private final SpringOffset mOffset;
        private final float mSign;

        SpringEdgeEffect(SpringOffset offset, float sign) {
            super(offset.view.getContext());
            mOffset = offset;
            mSign = sign;
        }

        @Override
        public void onPull(float deltaDistance) { onPullDistance(deltaDistance, 0.5f); }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            onPullDistance(deltaDistance, displacement);
        }

        @Override
        public float onPullDistance(float deltaDistance, float displacement) {
            if (!mOffset.enabled() || mOffset.extent() <= 0f) return 0f;
            if (mOffset.returning && deltaDistance == 0f) return 0f;
            if (deltaDistance != 0f) mOffset.beginDrag();
            if (deltaDistance == 0f && mOffset.activeSign == mSign) {
                mOffset.animation.cancel();
                return 0f;
            }
            float oldDistance = getDistance();
            if (oldDistance == 0f && deltaDistance <= 0f) return 0f;
            mOffset.animation.cancel();
            mOffset.activeSign = mSign;
            float newDistance = Math.max(0f, Math.min(4f, oldDistance + deltaDistance));
            mOffset.setValue(mSign * mOffset.physics.offset(
                    newDistance * mOffset.extent(), mOffset.extent()));
            return deltaDistance > 0f ? deltaDistance : newDistance - oldDistance;
        }

        @Override
        public float getDistance() {
            if (mOffset.returning || mOffset.activeSign != mSign
                    || mOffset.extent() <= 0f) return 0f;
            return Math.max(0f, mSign * mOffset.physics.distance(
                    mOffset.value(), mOffset.extent()) / mOffset.extent());
        }

        @Override
        public void onRelease() {
            if (mOffset.enabled() && mOffset.activeSign == mSign
                    && !mOffset.returning && !mOffset.animation.isRunning()
                    && mOffset.value() != 0f) {
                if (mSign * mOffset.value() > 0.5f) {
                    ScrollEdgeHaptics.perform(mOffset.view.getContext(), 0f);
                }
                mOffset.release(0f);
            }
        }

        @Override
        public void onAbsorb(int velocity) {
            if (!mOffset.enabled()) return;
            boolean rebounding = mOffset.activeSign == mSign && mOffset.returning;
            mOffset.animation.cancel();
            mOffset.activeSign = mSign;
            if (!rebounding && velocity > 1 && mOffset.extent() > 0f) {
                ScrollEdgeHaptics.perform(mOffset.view.getContext(), velocity);
            }
            mOffset.release(mSign * velocity);
        }

        @Override
        public boolean isFinished() {
            return mOffset.activeSign != mSign
                    || (!mOffset.animation.isRunning() && mOffset.value() == 0f);
        }

        @Override
        public void finish() {
            if (mOffset.activeSign == mSign) mOffset.reset();
        }

        @Override
        public boolean draw(Canvas canvas) { return false; }
    }
}
