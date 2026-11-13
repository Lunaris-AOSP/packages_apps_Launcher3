/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.ArrayMap;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

final class CosWorkspacePageTransition {
    private static final float TRANSITION_SCALE_FACTOR = 0.74f;
    private static final float FOCAL_LENGTH = 0.5f;
    private static final int CARD = 0;
    private static final int ROLL = 1;
    private static final int SLIDE = 2;
    private static final int CUBE = 3;
    private static final int FLIP = 4;
    private static final int TILT = 5;

    private final Workspace<?> mWorkspace;
    private final AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(0.9f);
    private final ArrayMap<View, Transformation> mTransformations = new ArrayMap<>();
    private final int[] mVisiblePages = new int[2];
    private int mEffect = CARD;
    private Camera mTiltCamera;
    private Matrix mTiltMatrix;
    private final float[] mTiltPoint = new float[2];
    private CosWorkspaceRollTransition mRollTransition;
    private boolean mDrawingBackfaces;

    CosWorkspacePageTransition(Workspace<?> workspace) {
        mWorkspace = workspace;
    }

    boolean isActive() {
        return !mTransformations.isEmpty();
    }

    boolean isCard() {
        return isActive() && mEffect == CARD;
    }

    boolean isRoll() {
        return isActive() && mEffect == ROLL;
    }

    void setEffect(String effect) {
        int selected = CARD;
        if (effect != null) {
            switch (effect) {
                case "roll": selected = ROLL; break;
                case "slide": selected = SLIDE; break;
                case "cube": selected = CUBE; break;
                case "flip": selected = FLIP; break;
                case "tilt": selected = TILT; break;
            }
        }
        if (mEffect != selected) {
            reset();
            mEffect = selected;
        }
    }

    void setDrawingBackfaces(boolean drawingBackfaces) {
        mDrawingBackfaces = drawingBackfaces;
    }

    boolean drawRollChild(Canvas canvas, View page, long drawingTime) {
        return mRollTransition.drawChild(canvas, page, drawingTime, mDrawingBackfaces);
    }

    void apply(float edgeTranslation) {
        if (mEffect == ROLL) {
            if (mRollTransition == null) {
                mRollTransition = new CosWorkspaceRollTransition(mWorkspace);
            }
            mRollTransition.setEdgeTranslation(edgeTranslation);
        }
        int pageCount = mWorkspace.getPageCount();
        int panelCount = mWorkspace.getPanelCount();
        int screenCenter = mWorkspace.getScrollX() + mWorkspace.getMeasuredWidth() / 2;
        for (int i = 0; i < pageCount; i++) {
            View page = mWorkspace.getPageAt(i);
            if (!mTransformations.containsKey(page)) {
                mTransformations.put(page, new Transformation(page));
            }
        }
        for (int group = 0; group < pageCount; group += panelCount) {
            int end = Math.min(group + panelCount, pageCount);
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            for (int i = group; i < end; i++) {
                View page = mWorkspace.getPageAt(i);
                left = Math.min(left, page.getLeft());
                right = Math.max(right, page.getRight());
            }
            View firstPage = mWorkspace.getPageAt(group);
            float progress = mWorkspace.getScrollProgress(screenCenter, firstPage, group);
            float width = right - left;
            float scale = 1f;
            float alpha = 1f;
            float translation = 0f;
            float rotation = 0f;
            float pivot = (left + right) / 2f;
            float cameraDistance = 0f;
            switch (mEffect) {
                case CARD:
                    float depth = Math.abs(Math.min(progress, 0f));
                    float z = (1f - FOCAL_LENGTH / (FOCAL_LENGTH + depth))
                            / (1f - FOCAL_LENGTH / (FOCAL_LENGTH + 1f));
                    scale = 1f - z * (1f - TRANSITION_SCALE_FACTOR);
                    alpha = progress < 0f
                            ? mAlphaInterpolator.getInterpolation(1f - Math.abs(progress)) : 1f;
                    translation = Math.min(progress, 0f) * width;
                    break;
                case CUBE:
                    rotation = -90f * progress;
                    pivot = progress > 0f ? right : left;
                    cameraDistance = 4200f;
                    break;
                case FLIP:
                    rotation = -180f * progress;
                    scale = 1f - 0.65f * Math.abs(progress);
                    alpha = Math.abs(progress) < 0.5f ? 1f : 0f;
                    translation = mWorkspace.getScrollX() - mWorkspace.getScrollForPage(group);
                    cameraDistance = 3500f;
                    break;
                case TILT:
                    rotation = 16.7f * progress;
                    translation = getTiltTranslation(rotation, width, firstPage.getHeight());
                    break;
            }
            int pageScroll = mWorkspace.getScrollForPage(group);
            boolean atEdge = (edgeTranslation > 0f && pageScroll == mWorkspace.mMinScroll)
                    || (edgeTranslation < 0f && pageScroll == mWorkspace.mMaxScroll);
            if (atEdge) {
                translation = edgeTranslation;
                scale = 1f;
                alpha = 1f;
                rotation = 0f;
                pivot = (left + right) / 2f;
            }
            for (int i = group; i < end; i++) {
                View page = mWorkspace.getPageAt(i);
                Transformation original = mTransformations.get(page);
                page.setPivotX(pivot - page.getLeft());
                page.setPivotY(page.getHeight() / 2f);
                page.setTranslationX(original.translationX + translation);
                page.setScaleX(original.scaleX * scale);
                page.setScaleY(original.scaleY * scale);
                page.setAlpha(original.alpha * alpha);
                page.setRotationY(original.rotationY + rotation);
                page.setCameraDistance(cameraDistance > 0f
                        ? cameraDistance * mWorkspace.getResources().getDisplayMetrics().density
                        : original.cameraDistance);
            }
        }
    }

    private float getTiltTranslation(float rotation, float width, float height) {
        if (mTiltCamera == null) {
            mTiltCamera = new Camera();
            mTiltMatrix = new Matrix();
        }
        mTiltMatrix.reset();
        mTiltCamera.save();
        mTiltCamera.rotateY(Math.abs(rotation));
        mTiltCamera.getMatrix(mTiltMatrix);
        mTiltCamera.restore();
        mTiltMatrix.preTranslate(-width / 2f, -height / 2f);
        mTiltMatrix.postTranslate(width / 2f, height / 2f);
        mTiltPoint[0] = width;
        mTiltPoint[1] = height;
        mTiltMatrix.mapPoints(mTiltPoint);
        return (width - mTiltPoint[0]) * Math.signum(rotation);
    }

    float getOriginalTranslationX(View page) {
        Transformation original = mTransformations.get(page);
        return original == null ? page.getTranslationX() : original.translationX;
    }

    int[] getVisibleChildrenRange() {
        float visibleLeft = 0f;
        float visibleRight = mWorkspace.getMeasuredWidth();
        float scale = mWorkspace.getScaleX();
        if (scale > 0f && scale < 1f) {
            float center = visibleRight / 2f;
            visibleLeft = center - center / scale;
            visibleRight = center + center / scale;
        }
        mVisiblePages[0] = -1;
        mVisiblePages[1] = -1;
        for (int i = 0; i < mWorkspace.getPageCount(); i++) {
            View page = mWorkspace.getPageAt(i);
            float left = page.getLeft() + getOriginalTranslationX(page) - mWorkspace.getScrollX();
            if (left <= visibleRight && left + page.getMeasuredWidth() >= visibleLeft) {
                if (mVisiblePages[0] == -1) {
                    mVisiblePages[0] = i;
                }
                mVisiblePages[1] = i;
            }
        }
        return mVisiblePages;
    }

    void reset() {
        for (int i = 0; i < mTransformations.size(); i++) {
            View page = mTransformations.keyAt(i);
            Transformation original = mTransformations.valueAt(i);
            page.setTranslationX(original.translationX);
            page.setScaleX(original.scaleX);
            page.setScaleY(original.scaleY);
            page.setAlpha(original.alpha);
            page.setRotationY(original.rotationY);
            page.setCameraDistance(original.cameraDistance);
            if (original.pivotSet) {
                page.setPivotX(original.pivotX);
                page.setPivotY(original.pivotY);
            } else {
                page.resetPivot();
            }
        }
        mTransformations.clear();
        mDrawingBackfaces = false;
        if (mRollTransition != null) {
            mRollTransition.reset();
        }
    }

    private static final class Transformation {
        final float translationX;
        final float scaleX;
        final float scaleY;
        final float alpha;
        final float rotationY;
        final float cameraDistance;
        final float pivotX;
        final float pivotY;
        final boolean pivotSet;

        Transformation(View page) {
            translationX = page.getTranslationX();
            scaleX = page.getScaleX();
            scaleY = page.getScaleY();
            alpha = page.getAlpha();
            rotationY = page.getRotationY();
            cameraDistance = page.getCameraDistance();
            pivotX = page.getPivotX();
            pivotY = page.getPivotY();
            pivotSet = page.isPivotSet();
        }
    }
}
