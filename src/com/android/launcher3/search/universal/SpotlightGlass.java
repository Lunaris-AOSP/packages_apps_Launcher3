/* Copyright (C) 2026 The PenguinOS Project. SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.search.universal;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

/** Spotlight's bounded frost and rim, adapted from PenguinDockGlass. */
final class SpotlightGlass extends Drawable {
    private final View mOwner;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private BackgroundBlurDrawable mBlur;
    private int mAlpha = 255;

    SpotlightGlass(View owner) {
        mOwner = owner;
    }

    @Override
    public void draw(Canvas canvas) {
        if (getBounds().isEmpty() || mAlpha == 0) return;
        float density = mOwner.getResources().getDisplayMetrics().density;
        float radius = radius(density);
        WindowManager wm = mOwner.getContext().getSystemService(WindowManager.class);
        boolean live = canvas.isHardwareAccelerated() && wm != null
                && wm.isCrossWindowBlurEnabled() && mOwner.getViewRootImpl() != null;
        if (live) {
            if (mBlur == null) {
                mBlur = mOwner.getViewRootImpl().createBackgroundBlurDrawable();
                mOwner.postInvalidateOnAnimation();
            }
            mBlur.setBlurRadius(Math.round(16 * density));
            mBlur.setCornerRadius(radius);
            mBlur.setColor(0);
            mBlur.setAlpha(mAlpha);
            mBlur.setBounds(getBounds());
            mBlur.draw(canvas);
        } else {
            release();
        }
        mRect.set(getBounds());
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(live ? 0x4033475B : 0xED20242C);
        mPaint.setAlpha((mPaint.getColor() >>> 24) * mAlpha / 255);
        canvas.drawRoundRect(mRect, radius, radius, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1, density));
        mPaint.setColor(0x33FFFFFF);
        mPaint.setAlpha(0x33 * mAlpha / 255);
        canvas.drawRoundRect(mRect, radius, radius, mPaint);
    }

    /** Drop the old window's compositor region when hidden or detached. */
    void release() {
        if (mBlur != null) {
            mBlur.setAlpha(0);
            mBlur = null;
        }
    }

    private float radius(float density) {
        return Math.min(28 * density, getBounds().height() * 0.4f);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(),
                radius(mOwner.getResources().getDisplayMetrics().density));
        outline.setAlpha(mAlpha / 255f);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha == alpha) return;
        mAlpha = alpha;
        if (mBlur != null) mBlur.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        mPaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
