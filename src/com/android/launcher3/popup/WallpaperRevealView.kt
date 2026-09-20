/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.popup

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pixel-style wallpaper wipe. The old and new frames are already in memory so the circle can start
 * on the first frame; [setBitmap] runs in the background under this overlay.
 */
class WallpaperRevealView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val oldMatrix = Matrix()
    private val newMatrix = Matrix()
    private val clipPath = Path()
    private val origin = Point()
    private val sourceCenterInWindow = Point()
    private val tmpLoc = IntArray(2)

    private var oldWallpaper: Bitmap? = null
    private var newWallpaper: Bitmap? = null
    private var radius = 0f
    private var maxRadius = 0f
    private var wipeAnimator: ValueAnimator? = null
    private var wipeFinished = false
    private var applyFinished = false
    private var applySucceeded = false
    private var started = false
    private var onSettled: ((success: Boolean) -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val oldBmp = oldWallpaper
        val newBmp = newWallpaper

        if (oldBmp != null && !oldBmp.isRecycled) {
            canvas.drawBitmap(oldBmp, oldMatrix, paint)
        } else {
            canvas.drawColor(Color.BLACK)
        }

        if (radius > 0f && newBmp != null && !newBmp.isRecycled) {
            clipPath.reset()
            clipPath.addCircle(origin.x.toFloat(), origin.y.toFloat(), radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(newBmp, newMatrix, paint)
            canvas.restoreToCount(save)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            fitMatrix(oldWallpaper, oldMatrix, w, h)
            fitMatrix(newWallpaper, newMatrix, w, h)
            maxRadius = maxRadiusToCorners()
        }
    }

    fun play(
        source: View,
        previousWallpaper: Bitmap,
        nextWallpaper: Bitmap?,
        onSettled: (success: Boolean) -> Unit,
    ) {
        val dragLayer =
            resolveDragLayer()
                ?: run {
                    onSettled(true)
                    return
                }

        detachFromParent()
        oldWallpaper = previousWallpaper
        newWallpaper = nextWallpaper
        this.onSettled = onSettled
        wipeFinished = false
        applyFinished = false
        applySucceeded = false
        started = false
        radius = 0f
        alpha = 1f

        source.getLocationInWindow(tmpLoc)
        sourceCenterInWindow.set(tmpLoc[0] + source.width / 2, tmpLoc[1] + source.height / 2)

        val lp =
            BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        lp.ignoreInsets = true
        dragLayer.addView(this, 0, lp)

        viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (width <= 0 || height <= 0) return true
                    viewTreeObserver.removeOnPreDrawListener(this)
                    updateOriginFromWindow()
                    fitMatrix(oldWallpaper, oldMatrix, width, height)
                    fitMatrix(newWallpaper, newMatrix, width, height)
                    maxRadius = maxRadiusToCorners()
                    startWipe()
                    return true
                }
            }
        )
        requestLayout()
    }

    fun onApplyFinished(success: Boolean) {
        applyFinished = true
        applySucceeded = success
        if (!success && !wipeFinished) {
            wipeAnimator?.cancel()
            fadeAndFinish()
            return
        }
        maybeFinish()
    }

    fun cancelAndRemove() {
        wipeAnimator?.cancel()
        wipeAnimator = null
        onSettled = null
        detachFromParent()
    }

    private fun startWipe() {
        if (started) return
        started = true
        if (newWallpaper == null) {
            wipeFinished = true
            maybeFinish()
            return
        }
        updateOriginFromWindow()
        maxRadius = maxRadiusToCorners()
        if (maxRadius <= 0f) {
            wipeFinished = true
            maybeFinish()
            return
        }
        wipeAnimator?.cancel()
        wipeAnimator =
            ValueAnimator.ofFloat(0f, maxRadius).apply {
                duration = WIPE_MS
                interpolator = WIPE_INTERPOLATOR
                addUpdateListener { animation ->
                    radius = animation.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var canceled = false

                        override fun onAnimationCancel(animation: Animator) {
                            canceled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (canceled) return
                            radius = maxRadius
                            invalidate()
                            wipeFinished = true
                            maybeFinish()
                        }
                    }
                )
                start()
            }
    }

    private fun maybeFinish() {
        if (!wipeFinished || !applyFinished) return
        if (applySucceeded) {
            detachFromParent()
            finish(true)
        } else {
            fadeAndFinish()
        }
    }

    private fun fadeAndFinish() {
        animate()
            .alpha(0f)
            .setDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            .withEndAction {
                detachFromParent()
                finish(false)
            }
            .start()
    }

    private fun finish(success: Boolean) {
        val callback = onSettled
        onSettled = null
        callback?.invoke(success)
    }

    private fun fitMatrix(bitmap: Bitmap?, matrix: Matrix, w: Int, h: Int) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = max(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - bitmap.width * scale) / 2f, (h - bitmap.height * scale) / 2f)
    }

    private fun updateOriginFromWindow() {
        getLocationInWindow(tmpLoc)
        origin.set(sourceCenterInWindow.x - tmpLoc[0], sourceCenterInWindow.y - tmpLoc[1])
    }

    private fun maxRadiusToCorners(): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = origin.x.toFloat()
        val y = origin.y.toFloat()
        return max(max(hypot(x, y), hypot(x, y - h)), max(hypot(x - w, y), hypot(x - w, y - h)))
    }

    private fun resolveDragLayer(): BaseDragLayer<*>? {
        val activity: ActivityContext = ActivityContext.lookupContextNoThrow(context) ?: return null
        return activity.dragLayer
    }

    private fun detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {
        val WIPE_INTERPOLATOR = PathInterpolator(0f, 0f, 0.7f, 1f)
        const val WIPE_MS = 400L
    }
}
