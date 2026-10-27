/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.cos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/** Keeps the closing window visible after its recents transition finishes for a new launch. */
public final class CosHomeSnapshot extends View {
    private final Bitmap mBitmap;
    private final RectF mSourceCrop;
    private final RectF mBufferBounds;
    private final Matrix mMatrix = new Matrix();
    private final Path mClip = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private ViewGroup mRoot;

    public CosHomeSnapshot(Context context, Bitmap bitmap, RectF sourceCrop, Rect taskBounds) {
        super(context);
        mBitmap = bitmap;
        mSourceCrop = new RectF(sourceCrop);
        mBufferBounds = new RectF(0, 0, taskBounds.width(), taskBounds.height());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The rect is in launcher coordinates; the radius is in the window's crop coordinates. */
    public void update(RectF rect, float radius, float alpha) {
        mMatrix.setRectToRect(mSourceCrop, rect, Matrix.ScaleToFit.FILL);
        mClip.reset();
        mClip.addRoundRect(mSourceCrop, Math.max(0f, radius), Math.max(0f, radius),
                Path.Direction.CW);
        mPaint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        invalidate();
    }

    /** An overlay stays above home content and below the floating icon, without taking touches. */
    public void attachTo(ViewGroup root) {
        remove();
        mRoot = root;
        layout(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(this);
    }

    public void remove() {
        if (mRoot != null) {
            mRoot.getOverlay().remove(this);
            mRoot = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.concat(mMatrix);
        canvas.clipPath(mClip);
        canvas.drawBitmap(mBitmap, null, mBufferBounds, mPaint);
        canvas.restoreToCount(save);
    }
}
