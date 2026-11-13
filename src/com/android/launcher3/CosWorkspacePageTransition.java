/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3;

import android.util.ArrayMap;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

final class CosWorkspacePageTransition {
    private static final float TRANSITION_SCALE_FACTOR = 0.74f;
    private static final float FOCAL_LENGTH = 0.5f;

    private final Workspace<?> mWorkspace;
    private final AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(0.9f);
    private final ArrayMap<View, Transformation> mTransformations = new ArrayMap<>();

    CosWorkspacePageTransition(Workspace<?> workspace) {
        mWorkspace = workspace;
    }

    boolean isActive() {
        return !mTransformations.isEmpty();
    }

    void apply(float edgeTranslation) {
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
            float depth = Math.abs(Math.min(progress, 0f));
            float z = (1f - FOCAL_LENGTH / (FOCAL_LENGTH + depth))
                    / (1f - FOCAL_LENGTH / (FOCAL_LENGTH + 1f));
            float scale = 1f - z * (1f - TRANSITION_SCALE_FACTOR);
            float alpha = progress < 0f
                    ? mAlphaInterpolator.getInterpolation(1f - Math.abs(progress)) : 1f;
            float translation = Math.min(progress, 0f) * (right - left);
            int pageScroll = mWorkspace.getScrollForPage(group);
            boolean atEdge = (edgeTranslation > 0f && pageScroll == mWorkspace.mMinScroll)
                    || (edgeTranslation < 0f && pageScroll == mWorkspace.mMaxScroll);
            if (atEdge) {
                translation = edgeTranslation;
                scale = 1f;
                alpha = 1f;
            }
            for (int i = group; i < end; i++) {
                View page = mWorkspace.getPageAt(i);
                Transformation original = mTransformations.get(page);
                page.setPivotX((left + right) / 2f - page.getLeft());
                page.setPivotY(page.getHeight() / 2f);
                page.setTranslationX(original.translationX + translation);
                page.setScaleX(original.scaleX * scale);
                page.setScaleY(original.scaleY * scale);
                page.setAlpha(original.alpha * alpha);
            }
        }
    }

    float getOriginalTranslationX(View page) {
        Transformation original = mTransformations.get(page);
        return original == null ? page.getTranslationX() : original.translationX;
    }

    void reset() {
        for (int i = 0; i < mTransformations.size(); i++) {
            View page = mTransformations.keyAt(i);
            Transformation original = mTransformations.valueAt(i);
            page.setTranslationX(original.translationX);
            page.setScaleX(original.scaleX);
            page.setScaleY(original.scaleY);
            page.setAlpha(original.alpha);
            if (original.pivotSet) {
                page.setPivotX(original.pivotX);
                page.setPivotY(original.pivotY);
            } else {
                page.resetPivot();
            }
        }
        mTransformations.clear();
    }

    private static final class Transformation {
        final float translationX;
        final float scaleX;
        final float scaleY;
        final float alpha;
        final float pivotX;
        final float pivotY;
        final boolean pivotSet;

        Transformation(View page) {
            translationX = page.getTranslationX();
            scaleX = page.getScaleX();
            scaleY = page.getScaleY();
            alpha = page.getAlpha();
            pivotX = page.getPivotX();
            pivotY = page.getPivotY();
            pivotSet = page.isPivotSet();
        }
    }
}
