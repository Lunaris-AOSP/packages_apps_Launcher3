/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.animation.AnimationUtils;

import com.android.launcher3.util.EdgeEffectCompat;

final class CosWorkspacePageEdgeEffect extends EdgeEffectCompat {
    private final View mWorkspace;
    private final boolean mLeft;
    private double mNaturalFrequency;

    private float mDistance;
    private float mVelocity;
    private float mStartDistance;
    private float mStartVelocity;
    private long mStartTime;
    private boolean mReleasing;

    CosWorkspacePageEdgeEffect(Context context, View workspace, boolean left) {
        super(context);
        mWorkspace = workspace;
        mLeft = left;
    }

    @Override
    public void onPull(float deltaDistance) {
        onPullDistance(deltaDistance, 0.5f);
    }

    @Override
    public void onPull(float deltaDistance, float displacement) {
        onPullDistance(deltaDistance, displacement);
    }

    @Override
    public float onPullDistance(float deltaDistance, float displacement) {
        updateDistance();
        mReleasing = false;
        mVelocity = 0f;
        float previous = mDistance;
        mDistance = Utilities.boundToRange(mDistance + deltaDistance, 0f, 1f);
        return mDistance - previous;
    }

    @Override
    public void onFlingVelocity(int velocity) {
        mVelocity = (mLeft ? velocity : -velocity) / (float) Math.max(1, mWorkspace.getWidth());
    }

    @Override
    public void onRelease() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish();
        } else if (!mReleasing && mDistance > 0f) {
            mNaturalFrequency = Math.sqrt(mWorkspace.getResources().getFloat(
                    R.dimen.workspace_page_back_spring_stiffness));
            mStartDistance = mDistance;
            mStartVelocity = mVelocity;
            mStartTime = AnimationUtils.currentAnimationTimeMillis();
            mReleasing = true;
            mWorkspace.postInvalidateOnAnimation();
        }
    }

    @Override
    public void onAbsorb(int velocity) {
        mDistance = Math.max(mDistance, 0.5f / Math.max(1, mWorkspace.getWidth()));
        mVelocity = Math.abs(velocity) / (float) Math.max(1, mWorkspace.getWidth());
        mReleasing = false;
        onRelease();
    }

    @Override
    public float getDistance() {
        updateDistance();
        return mDistance;
    }

    @Override
    public boolean isFinished() {
        updateDistance();
        return mDistance == 0f;
    }

    @Override
    public void finish() {
        mDistance = 0f;
        mVelocity = 0f;
        mReleasing = false;
    }

    @Override
    public boolean draw(Canvas canvas) {
        updateDistance();
        return mReleasing;
    }

    private void updateDistance() {
        if (!mReleasing) {
            return;
        }
        double seconds = (AnimationUtils.currentAnimationTimeMillis() - mStartTime) / 1000.0;
        double coefficient = mStartVelocity + mNaturalFrequency * mStartDistance;
        double decay = Math.exp(-mNaturalFrequency * seconds);
        mDistance = Utilities.boundToRange(
                (float) ((mStartDistance + coefficient * seconds) * decay), 0f, 1f);
        mVelocity = (float) ((mStartVelocity - mNaturalFrequency * coefficient * seconds) * decay);
        float threshold = 0.375f / Math.max(1, mWorkspace.getWidth());
        if (mDistance <= 0f || (mDistance < threshold && Math.abs(mVelocity) < threshold * 62.5f)) {
            finish();
        }
    }
}
