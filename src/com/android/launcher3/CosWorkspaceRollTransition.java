/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class CosWorkspaceRollTransition {
    private final Workspace<?> mWorkspace;
    private final Camera mCamera = new Camera();
    private final Matrix mMatrix = new Matrix();
    private final DecelerateInterpolator mExpansionInterpolator = new DecelerateInterpolator();
    private float mEdgeTranslation;
    private boolean mHasExpanded;
    private int mCurrentGroup = PagedView.INVALID_PAGE;
    private int mLastScroll;
    private float mProgress;
    private float mExpansion;

    CosWorkspaceRollTransition(Workspace<?> workspace) {
        mWorkspace = workspace;
    }

    void setEdgeTranslation(float edgeTranslation) {
        mEdgeTranslation = edgeTranslation;
        int scroll = mWorkspace.getScrollX();
        int panels = mWorkspace.getPanelCount();
        int screenCenter = scroll + mWorkspace.getMeasuredWidth() / 2;
        if (mCurrentGroup == PagedView.INVALID_PAGE) {
            mCurrentGroup = mWorkspace.getLeftmostVisiblePageForIndex(
                    Math.max(0, mWorkspace.getCurrentPage()));
            mLastScroll = mWorkspace.getScrollForPage(mCurrentGroup);
        }
        if (mCurrentGroup >= mWorkspace.getPageCount()
                || Math.abs(mWorkspace.getScrollProgress(screenCenter,
                        mWorkspace.getPageAt(mCurrentGroup), mCurrentGroup)) >= 1f) {
            int selected = PagedView.INVALID_PAGE;
            int selectedDistance = Integer.MAX_VALUE;
            for (int group = 0; group < mWorkspace.getPageCount(); group += panels) {
                int delta = scroll - mWorkspace.getScrollForPage(group);
                if ((scroll >= mLastScroll && delta < 0)
                        || (scroll < mLastScroll && delta > 0)) {
                    continue;
                }
                if (Math.abs(delta) < selectedDistance) {
                    selected = group;
                    selectedDistance = Math.abs(delta);
                }
            }
            if (selected != PagedView.INVALID_PAGE) {
                mCurrentGroup = selected;
            }
        }
        mLastScroll = scroll;
        if (mCurrentGroup < 0 || mCurrentGroup >= mWorkspace.getPageCount()) {
            mProgress = 0f;
            mExpansion = 0f;
            return;
        }
        mProgress = Math.abs(mWorkspace.getScrollProgress(screenCenter,
                mWorkspace.getPageAt(mCurrentGroup), mCurrentGroup));
        mExpansion = mExpansionInterpolator.getInterpolation(
                Math.min(1f, Math.min(mProgress, 1f - mProgress) / 0.05f));
        if (mExpansion >= 1f) {
            mHasExpanded = true;
        }
        if ((mCurrentGroup != 0 || mProgress > 0.5f)
                && mHasExpanded && mWorkspace.isHandlingTouch()) {
            mExpansion = 1f;
        }
    }

    void reset() {
        mHasExpanded = false;
        mEdgeTranslation = 0f;
        mCurrentGroup = PagedView.INVALID_PAGE;
        mProgress = 0f;
        mExpansion = 0f;
    }

    boolean drawChild(Canvas canvas, View page, long drawingTime, boolean backfaces) {
        int index = mWorkspace.indexOfChild(page);
        if (index < 0) {
            return !backfaces && mWorkspace.drawPageForTransition(canvas, page, drawingTime);
        }
        int panels = mWorkspace.getPanelCount();
        int group = mWorkspace.getLeftmostVisiblePageForIndex(index);
        int pageScroll = mWorkspace.getScrollForPage(group);
        View firstPage = mWorkspace.getPageAt(group);
        int screenCenter = mWorkspace.getScrollX() + mWorkspace.getMeasuredWidth() / 2;
        float progress = mWorkspace.getScrollProgress(screenCenter, firstPage, group);
        float distance = Math.abs(progress);
        boolean atEdge = (mEdgeTranslation > 0f && pageScroll == mWorkspace.mMinScroll)
                || (mEdgeTranslation < 0f && pageScroll == mWorkspace.mMaxScroll);
        if (atEdge || (distance < 0.0001f && mExpansion == 0f)) {
            return !backfaces && mWorkspace.drawPageForTransition(canvas, page, drawingTime);
        }
        if (distance >= 1f) {
            return false;
        }

        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (int i = group; i < Math.min(group + panels, mWorkspace.getPageCount()); i++) {
            View panel = mWorkspace.getPageAt(i);
            left = Math.min(left, panel.getLeft());
            right = Math.max(right, panel.getRight());
        }
        float width = right - left;
        int fragments = Math.max(1, Launcher.getLauncher(mWorkspace.getContext())
                .getDeviceProfile().inv.numColumns);
        float fragmentWidth = page.getWidth() / (float) fragments;
        float radius = width * 0.452f;
        float expansion = group == mCurrentGroup ? mExpansion : 0.975f + 0.025f * mExpansion;
        DisplayMetrics metrics = mWorkspace.getResources().getDisplayMetrics();
        mCamera.setLocation(0f, 0f, -mWorkspace.getMeasuredHeight() * 2.498f
                * metrics.density / Math.max(1, metrics.densityDpi));
        float scrollOffset = mWorkspace.getScrollX() - pageScroll;
        float pivotY = page.getTop() + page.getHeight() / 2f;
        boolean drawn = false;
        for (int i = 0; i < fragments; i++) {
            float stripLeft = page.getLeft() + i * fragmentWidth;
            float stripRight = page.getLeft() + (i + 1) * fragmentWidth;
            float center = (stripLeft + stripRight) / 2f;
            float angle = (center - left) * 180f / width - 90f - progress * 180f;
            if (angle >= 270f) {
                angle -= 360f;
            } else if (angle < -90f) {
                angle += 360f;
            }
            double radians = Math.toRadians(angle);
            boolean behind = Math.cos(radians) < 0;
            if (behind != backfaces) {
                continue;
            }
            float offset = (left + width / 2f + radius * (float) Math.sin(radians)
                    - center) * expansion * expansion + scrollOffset;
            float depth = radius * (1f - (float) Math.cos(radians)) * expansion;
            mCamera.save();
            mCamera.translate(0f, 0f, depth);
            mCamera.rotateY(angle * expansion);
            mCamera.getMatrix(mMatrix);
            mCamera.restore();
            mMatrix.preTranslate(-center, -pivotY);
            mMatrix.postTranslate(center + offset, pivotY);
            int alpha = behind
                    ? (int) (150f * mExpansion * Math.min(1f, mProgress * 10f)) : 255;
            int save = behind ? canvas.saveLayerAlpha(null, alpha) : canvas.save();
            try {
                canvas.concat(mMatrix);
                canvas.clipRect(Math.max(stripLeft, page.getLeft()), page.getTop(),
                        Math.min(stripRight, page.getRight()), page.getBottom());
                drawn |= mWorkspace.drawPageForTransition(canvas, page, drawingTime);
            } finally {
                canvas.restoreToCount(save);
            }
        }
        return drawn;
    }
}
