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

import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_WORKSPACE_UNLOCK_ANIM;

import android.util.ArrayMap;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.Hotseat;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.ShortcutAndWidgetContainer;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.widget.NavigableAppWidgetHostView;

import java.util.ArrayList;
import java.util.Comparator;

public final class HomeUnlockReveal implements ViewTreeObserver.OnPreDrawListener {
    private final Launcher mLauncher;
    private final ArrayMap<View, ViewState> mViews = new ArrayMap<>();
    private final ArrayMap<ViewGroup, ClippingState> mClipping = new ArrayMap<>();
    private final ArrayList<CellLayout> mPages = new ArrayList<>();
    private final ArrayMap<Float, SpringState> mItemSprings = new ArrayMap<>();
    private CosUnlockAnimationSpec mSpec;
    private SpringState mDockTranslation;
    private SpringState mDockAlpha;
    private ViewTreeObserver mObserver;
    private float mUnlockAmount = 1f;
    private float mMaxResponse;
    private double mDuration;
    private double mDockDelay;
    private float mDockDistance;
    private int mPage;
    private boolean mTimelineFrozen;
    private boolean mDockGeometryReady;
    private boolean mFinished;

    public HomeUnlockReveal(Launcher launcher) {
        mLauncher = launcher;
    }

    public boolean prepareForUnlock() {
        reset();
        if (!canAnimate() || !mLauncher.getDragLayer().isLaidOut()
                || !mLauncher.getDragLayer().isAttachedToWindow()) return false;
        var properties = mLauncher.getDeviceProfile().getDeviceProperties();
        mSpec = new CosUnlockAnimationSpec(
                mLauncher.getResources().getDisplayMetrics().density,
                properties.isTwoPanels(), properties.isTablet());
        mDockTranslation = new SpringState(mSpec.dockTranslation, Float.MAX_VALUE);
        mDockAlpha = new SpringState(mSpec.dockAlpha, CosUnlockAnimationSpec.MIN_VISIBLE_CHANGE);
        mPage = mLauncher.getWorkspace().getNextPage();
        mUnlockAmount = 0f;
        collectViews();
        mObserver = mLauncher.getDragLayer().getViewTreeObserver();
        mObserver.addOnPreDrawListener(this);
        setUnlockAmount(0f);
        return isPrepared();
    }

    private boolean canAnimate() {
        return mLauncher.getStateManager().isInStableState(NORMAL) && !mLauncher.isWorkspaceLoading()
                && !mLauncher.getStateManager().isInTransition()
                && !mLauncher.isTouchInProgress() && !mLauncher.getDragController().isDragging()
                && !mLauncher.getWorkspace().isPageInTransition()
                && !mLauncher.getWorkspace().isOverlayShown()
                && AbstractFloatingView.getTopOpenView(mLauncher) == null;
    }

    private boolean collectViews() {
        Workspace<?> workspace = mLauncher.getWorkspace();
        disableClipping(mLauncher.getDragLayer());
        disableClipping(workspace);
        mPages.clear();
        workspace.forEachVisiblePage(page -> mPages.add((CellLayout) page));
        mPages.sort(Comparator.comparingInt(View::getLeft));
        int columns = 0;
        for (CellLayout layout : mPages) columns += layout.getCountX();
        int columnOffset = 0;
        for (CellLayout layout : mPages) {
            ShortcutAndWidgetContainer container = layout.getShortcutsAndWidgets();
            disableClipping(layout);
            disableClipping(container);
            for (int i = 0; i < container.getChildCount(); i++) {
                View view = container.getChildAt(i);
                if (!canAdd(view)
                        || !(view.getLayoutParams() instanceof CellLayoutLayoutParams lp)) {
                    continue;
                }
                if (mTimelineFrozen) return false;
                int cellX = container.invertLayoutHorizontally()
                        ? layout.getCountX() - lp.getCellX() - lp.cellHSpan : lp.getCellX();
                float[] center = getPosition(view, view.getWidth() / 2f, view.getHeight() / 2f);
                CosUnlockAnimationSpec.Item item = mSpec.item(cellX + columnOffset, lp.getCellY(),
                        lp.cellHSpan, lp.cellVSpan, columns, center[0], center[1],
                        view.getHeight() * view.getScaleY(),
                        mLauncher.getDragLayer().getWidth(), mLauncher.getDragLayer().getHeight());
                SpringState spring = mItemSprings.get(item.spring.response);
                if (spring == null) {
                    spring = new SpringState(item.spring, item.minimumVisibleChange);
                    mItemSprings.put(item.spring.response, spring);
                } else {
                    spring.includeMinimumVisibleChange(item.minimumVisibleChange);
                }
                mViews.put(view, new ViewState(view, item, spring, null, null, 0f, 0f));
                mMaxResponse = Math.max(mMaxResponse, item.spring.response);
            }
            columnOffset += layout.getCountX();
        }
        if (!collectDock()) return false;
        if (!mTimelineFrozen) {
            mDockDelay = Math.round((double) (mMaxResponse * 0.3f * 1000f)) / 1000.0;
            mDuration = mDockDelay + Math.max(mDockTranslation.maxDuration(),
                    mDockAlpha.maxDuration());
            for (int i = 0; i < mItemSprings.size(); i++) {
                mDuration = Math.max(mDuration, mItemSprings.valueAt(i).maxDuration());
            }
            mDuration = Math.ceil(mDuration * 1000) / 1000.0;
        }
        return true;
    }

    private boolean collectDock() {
        Hotseat hotseat = mLauncher.getHotseat();
        if (hotseat == null) return true;
        disableClipping(hotseat);
        View icons = hotseat.getShortcutsAndWidgets();
        View qsb = hotseat.getQsb();
        View indicator = mLauncher.findViewById(R.id.page_indicator_container);
        if (indicator == null) indicator = mLauncher.getWorkspace().getPageIndicator();
        if (mTimelineFrozen && (canAdd(icons) || canAdd(qsb) || canAdd(indicator))) return false;
        if (!mDockGeometryReady) {
            float[] position = getPosition(hotseat, 0f, 0f);
            mDockDistance = Math.max(0f, mLauncher.getDragLayer().getHeight() - position[1]);
            if (canAdd(indicator)) {
                position = getPosition(indicator, 0f, 0f);
                mDockDistance = Math.max(mDockDistance,
                        mLauncher.getDragLayer().getHeight() - position[1]);
            }
            mDockGeometryReady = true;
        }
        float dockX = 0f;
        float dockY = mDockDistance;
        if (mLauncher.getDeviceProfile().isVerticalBarLayout()) {
            float[] position = getPosition(hotseat, hotseat.getWidth() / 2f, 0f);
            dockX = position[0] < mLauncher.getDragLayer().getWidth() / 2f
                    ? -hotseat.getWidth() : hotseat.getWidth();
            dockY = 0f;
        }
        addDockView(icons, hotseat.getIconsAlpha(Hotseat.ALPHA_CHANNEL_WORKSPACE_UNLOCK),
                hotseat.getIconsTranslationX(Hotseat.ICONS_TRANSLATION_X_WORKSPACE_UNLOCK),
                dockX, dockY);
        addDockView(qsb, hotseat.getQsbAlpha(Hotseat.ALPHA_CHANNEL_WORKSPACE_UNLOCK),
                null, dockX, dockY);
        addDockView(indicator, null, null, 0f, mDockDistance);
        return true;
    }

    private void addDockView(View view, MultiProperty alpha, MultiProperty translationX,
            float x, float y) {
        if (canAdd(view)) {
            mDockTranslation.includeMinimumVisibleChange(
                    1f / Math.max(1f, Math.max(Math.abs(x), Math.abs(y))));
            mViews.put(view, new ViewState(view, null, null, alpha, translationX, x, y));
        }
    }

    private boolean canAdd(View view) {
        return view != null && !mViews.containsKey(view) && view.getVisibility() == View.VISIBLE
                && view.isLaidOut() && view.isAttachedToWindow() && view.getAlpha() > 0f
                && view.getScaleX() > 0f && view.getScaleY() > 0f;
    }

    private float[] getPosition(View view, float x, float y) {
        float[] position = {x, y};
        Utilities.getDescendantCoordRelativeToAncestor(
                view, mLauncher.getDragLayer(), position, false);
        return position;
    }

    private void disableClipping(ViewGroup view) {
        if (!mClipping.containsKey(view)) {
            mClipping.put(view, new ClippingState(view));
            view.setClipChildren(false);
            view.setClipToPadding(false);
        }
    }

    @Override
    public boolean onPreDraw() {
        if (mObserver == null) return true;
        if (!isSceneValid() || !collectViews()) {
            reset();
            return true;
        }
        setUnlockAmount(mUnlockAmount);
        return true;
    }

    private boolean isSceneValid() {
        if (!canAnimate() || mPage != mLauncher.getWorkspace().getNextPage()) return false;
        for (int i = 0; i < mViews.size(); i++) {
            if (mViews.valueAt(i).hasLayoutChanged()) return false;
        }
        for (int i = 0; i < mClipping.size(); i++) {
            if (mClipping.valueAt(i).hasChanged()) return false;
        }
        return true;
    }

    public boolean finishPreparation() {
        if (mObserver == null) return false;
        if (!isSceneValid() || !collectViews()) {
            reset();
            return false;
        }
        mTimelineFrozen = true;
        return true;
    }

    public long getDuration() {
        return Math.round(mDuration * 1000);
    }

    public boolean isPrepared() {
        return mObserver != null;
    }

    public float getUnlockAmount() {
        return mUnlockAmount;
    }

    public boolean isFinished() {
        return mFinished;
    }

    public void setUnlockAmount(float amount) {
        if (mObserver == null || !Float.isFinite(amount)) return;
        if (!isSceneValid()) {
            reset();
            return;
        }
        mUnlockAmount = Utilities.boundToRange(amount, 0f, 1f);
        double seconds = Math.round(mDuration * mUnlockAmount * 1000) / 1000.0;
        mFinished = true;
        for (int i = 0; i < mItemSprings.size(); i++) {
            SpringState spring = mItemSprings.valueAt(i);
            spring.update(seconds);
            mFinished &= spring.isFinished(seconds);
        }
        double dockSeconds = seconds - mDockDelay;
        mDockTranslation.update(dockSeconds);
        mDockAlpha.update(dockSeconds);
        mFinished &= mDockTranslation.isFinished(dockSeconds) && mDockAlpha.isFinished(dockSeconds);
        mFinished |= mUnlockAmount == 1f;
        for (int i = 0; i < mViews.size(); i++) {
            mViews.valueAt(i).apply(mUnlockAmount == 1f, mSpec,
                    mDockTranslation.value, mDockAlpha.value);
        }
        if (mLauncher.getHotseat() != null) mLauncher.getHotseat().invalidate();
    }

    public void reset() {
        if (mObserver != null) {
            ViewTreeObserver observer = mObserver.isAlive()
                    ? mObserver : mLauncher.getDragLayer().getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
        }
        mObserver = null;
        for (int i = 0; i < mViews.size(); i++) {
            mViews.valueAt(i).restore();
        }
        mViews.clear();
        mPages.clear();
        mItemSprings.clear();
        mDockTranslation = null;
        mDockAlpha = null;
        for (int i = 0; i < mClipping.size(); i++) {
            mClipping.valueAt(i).restore();
        }
        mClipping.clear();
        mUnlockAmount = 1f;
        mMaxResponse = 0f;
        mDuration = 0;
        mDockDistance = 0f;
        mDockDelay = 0;
        mTimelineFrozen = false;
        mDockGeometryReady = false;
        mFinished = false;
        if (mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().postInvalidate();
        }
    }

    private static final class SpringState {
        private final CosUnlockAnimationSpec.Spring mSpring;
        private float mMinimumVisibleChange;
        private double mFinishedAt = Double.POSITIVE_INFINITY;
        float value;

        SpringState(CosUnlockAnimationSpec.Spring spring, float minimumVisibleChange) {
            mSpring = spring;
            mMinimumVisibleChange = minimumVisibleChange;
        }

        void includeMinimumVisibleChange(float minimumVisibleChange) {
            if (minimumVisibleChange < mMinimumVisibleChange) {
                mMinimumVisibleChange = minimumVisibleChange;
                mFinishedAt = Double.POSITIVE_INFINITY;
            }
        }

        void update(double seconds) {
            if (seconds > 0 && seconds < mFinishedAt
                    && mSpring.isAtEquilibrium(seconds, mMinimumVisibleChange)) {
                mFinishedAt = seconds;
            }
            value = isFinished(seconds) ? 1f : mSpring.value(seconds);
        }

        boolean isFinished(double seconds) {
            return seconds >= mFinishedAt;
        }

        double maxDuration() {
            return mSpring.maxDuration(mMinimumVisibleChange);
        }
    }

    private static class LayoutState {
        private final View mView;
        private final ViewParent mParent;
        private final int mLeft;
        private final int mTop;
        private final int mWidth;
        private final int mHeight;

        LayoutState(View view) {
            mView = view;
            mParent = view.getParent();
            mLeft = view.getLeft();
            mTop = view.getTop();
            mWidth = view.getWidth();
            mHeight = view.getHeight();
        }

        boolean hasChanged() {
            return !mView.isAttachedToWindow() || mView.getParent() != mParent
                    || mView.getLeft() != mLeft || mView.getTop() != mTop
                    || mView.getWidth() != mWidth || mView.getHeight() != mHeight;
        }
    }

    private static final class ClippingState extends LayoutState {
        private final ViewGroup mView;
        private final boolean mClipChildren;
        private final boolean mClipToPadding;

        ClippingState(ViewGroup view) {
            super(view);
            mView = view;
            mClipChildren = view.getClipChildren();
            mClipToPadding = view.getClipToPadding();
        }

        void restore() {
            mView.setClipChildren(mClipChildren);
            mView.setClipToPadding(mClipToPadding);
        }
    }

    private static final class ViewState {
        private final View mView;
        private final float mAlpha;
        private final float mScaleX;
        private final float mScaleY;
        private final float mTranslationX;
        private final float mTranslationY;
        private final float mWidgetScale;
        private final MultiTranslateDelegate mTranslateDelegate;
        private final MultiProperty mAlphaChannel;
        private final MultiProperty mTranslationXChannel;
        private final CosUnlockAnimationSpec.Item mItem;
        private final SpringState mItemSpring;
        private final float mDockX;
        private final float mDockY;
        private final LayoutState mLayout;

        ViewState(View view, CosUnlockAnimationSpec.Item item, SpringState itemSpring,
                MultiProperty alphaChannel, MultiProperty translationXChannel,
                float dockX, float dockY) {
            mView = view;
            mItem = item;
            mItemSpring = itemSpring;
            mAlphaChannel = alphaChannel;
            mTranslationXChannel = translationXChannel;
            mAlpha = alphaChannel == null ? view.getAlpha() : alphaChannel.getValue();
            mScaleX = view.getScaleX();
            mScaleY = view.getScaleY();
            mWidgetScale = view instanceof NavigableAppWidgetHostView widget
                    ? widget.getScaleToFit() : 1f;
            mTranslateDelegate = view instanceof Reorderable reorderable
                    ? reorderable.getTranslateDelegate() : null;
            mTranslationX = translationXChannel != null ? translationXChannel.getValue()
                    : mTranslateDelegate == null ? view.getTranslationX()
                            : mTranslateDelegate.getTranslationX(INDEX_WORKSPACE_UNLOCK_ANIM)
                                    .getValue();
            mTranslationY = mTranslateDelegate == null ? view.getTranslationY()
                    : mTranslateDelegate.getTranslationY(INDEX_WORKSPACE_UNLOCK_ANIM).getValue();
            mDockX = dockX;
            mDockY = dockY;
            mLayout = new LayoutState(view);
        }

        boolean hasLayoutChanged() {
            return mLayout.hasChanged();
        }

        void apply(boolean finished, CosUnlockAnimationSpec spec,
                float dockTranslation, float dockAlpha) {
            if (mItem == null) {
                float progress = finished ? 1f : dockTranslation;
                float alpha = finished ? 1f : dockAlpha;
                setAlpha(alpha);
                setTranslation(mDockX * (1f - progress), mDockY * (1f - progress));
            } else {
                float progress = finished ? 1f : mItemSpring.value;
                float scale = spec.startScale + (1f - spec.startScale) * progress;
                float fit = getWidgetScaleRatio();
                setAlpha(progress);
                mView.setScaleX(mScaleX * fit * scale);
                mView.setScaleY(mScaleY * fit * scale);
                float pivotX = mScaleX * fit * (mView.getWidth() / 2f - mView.getPivotX());
                float pivotY = mScaleY * fit * (mView.getHeight() / 2f - mView.getPivotY());
                setTranslation(mItem.translationX * (1f - progress) + pivotX * (1f - scale),
                        mItem.translationY * (1f - progress) + pivotY * (1f - scale));
            }
        }

        private float getWidgetScaleRatio() {
            return mView instanceof NavigableAppWidgetHostView widget && mWidgetScale > 0f
                    ? widget.getScaleToFit() / mWidgetScale : 1f;
        }

        private void setAlpha(float alpha) {
            float value = mAlpha * Utilities.boundToRange(alpha, 0f, 1f);
            if (mAlphaChannel == null) mView.setAlpha(value);
            else mAlphaChannel.setValue(value);
        }

        private void setTranslation(float x, float y) {
            if (mTranslateDelegate != null) {
                mTranslateDelegate.setTranslation(INDEX_WORKSPACE_UNLOCK_ANIM,
                        mTranslationX + x, mTranslationY + y);
            } else {
                if (mTranslationXChannel != null) {
                    mTranslationXChannel.setValue(mTranslationX + x);
                } else if (mItem != null || mDockX != 0f) {
                    mView.setTranslationX(mTranslationX + x);
                }
                if (mItem != null || mDockY != 0f) mView.setTranslationY(mTranslationY + y);
            }
        }

        void restore() {
            setAlpha(1f);
            if (mItem != null) {
                float fit = getWidgetScaleRatio();
                mView.setScaleX(mScaleX * fit);
                mView.setScaleY(mScaleY * fit);
            }
            setTranslation(0f, 0f);
        }
    }
}
