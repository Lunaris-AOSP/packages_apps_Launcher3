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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pixel-style wallpaper wipe.
 *
 * Drawn above the workspace. The circle only swaps the wallpaper; icon pixels come from a still
 * taken at tap time, so live icons are not redrawn as the edge moves.
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
    private var uiSnapshot: Bitmap? = null
    private var radius = 0f
    private var maxRadius = 0f
    private var wipeAnimator: ValueAnimator? = null
    private var wipeFinished = false
    private var applyFinished = false
    private var applySucceeded = false
    private var started = false
    private var finishing = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var onSettled: ((success: Boolean) -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Above the popup and the workspace. Same-Z siblings were compositing the live icons
        // on top of this view, so the wipe never actually covered them.
        elevation = 32f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val oldBmp = oldWallpaper ?: return
        val newBmp = newWallpaper

        if (newBmp != null && !newBmp.isRecycled) {
            canvas.drawBitmap(newBmp, newMatrix, paint)
        }
        val save =
            if (radius > 0f && newBmp != null) {
                clipPath.reset()
                clipPath.addCircle(
                    origin.x.toFloat(),
                    origin.y.toFloat(),
                    radius,
                    Path.Direction.CW,
                )
                canvas.save().also { canvas.clipOutPath(clipPath) }
            } else {
                -1
            }
        if (!oldBmp.isRecycled) canvas.drawBitmap(oldBmp, oldMatrix, paint)
        if (save >= 0) canvas.restoreToCount(save)

        // Icon still is not clipped, so the circle never cuts through live icon pixels.
        uiSnapshot?.let { snapshot ->
            if (!snapshot.isRecycled) canvas.drawBitmap(snapshot, 0f, 0f, paint)
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
    ): Boolean {
        val dragLayer = resolveDragLayer() ?: return false
        if (
            !dragLayer.isAttachedToWindow ||
                previousWallpaper.isRecycled ||
                nextWallpaper == null ||
                nextWallpaper.isRecycled
        )
            return false

        cancelAndRemove()
        // Skip the wipe if a view cannot be captured (for example a hardware bitmap).
        // The regular wallpaper apply still runs, with the live workspace visible.
        uiSnapshot = captureUi(dragLayer) ?: return false
        oldWallpaper = previousWallpaper
        newWallpaper = nextWallpaper
        this.onSettled = onSettled
        wipeFinished = false
        applyFinished = false
        applySucceeded = false
        started = false
        finishing = false
        radius = 0f
        alpha = 1f

        source.getLocationInWindow(tmpLoc)
        sourceCenterInWindow.set(tmpLoc[0] + source.width / 2, tmpLoc[1] + source.height / 2)

        dragLayer.addView(this, fullBleedParams())
        dragLayer.bringChildToFront(this)

        preDrawListener =
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (width <= 0 || height <= 0) return true
                    removePreDrawListener()
                    updateOriginFromWindow()
                    fitMatrix(oldWallpaper, oldMatrix, width, height)
                    fitMatrix(newWallpaper, newMatrix, width, height)
                    maxRadius = maxRadiusToCorners()
                    startWipe()
                    return true
                }
            }
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        requestLayout()
        return true
    }

    fun onApplyFinished(success: Boolean) {
        if (onSettled == null) return
        applyFinished = true
        applySucceeded = success
        if (!success && !wipeFinished) {
            wipeAnimator?.cancel()
            removePreDrawListener()
            fadeAndFinish()
            return
        }
        maybeFinish()
    }

    fun cancelAndRemove() {
        animate().withEndAction(null).cancel()
        wipeAnimator?.cancel()
        wipeAnimator = null
        removePreDrawListener()
        onSettled = null
        detachFromParent()
        oldWallpaper = null
        newWallpaper = null
    }

    private fun startWipe() {
        if (started) return
        started = true
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
        if (!wipeFinished || !applyFinished || finishing) return
        if (!applySucceeded) {
            fadeAndFinish()
            return
        }
        finishing = true
        animate()
            .alpha(0f)
            .setDuration(SETTLE_MS)
            .withEndAction {
                detachFromParent()
                finish(true)
            }
            .start()
    }

    private fun fadeAndFinish() {
        if (finishing) return
        finishing = true
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

    private fun captureUi(dragLayer: ViewGroup): Bitmap? {
        val w = dragLayer.width
        val h = dragLayer.height
        if (w <= 0 || h <= 0) return null
        var snapshot: Bitmap? = null
        return runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                    snapshot = bitmap
                    // ViewGroup.draw respects child transforms, alpha and drawing order.
                    dragLayer.draw(Canvas(bitmap))
                }
            }
            .getOrElse {
                snapshot?.recycle()
                null
            }
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

    private fun fullBleedParams(): BaseDragLayer.LayoutParams {
        return BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            .also { it.ignoreInsets = true }
    }

    private fun resolveDragLayer(): BaseDragLayer<*>? {
        val activity: ActivityContext = ActivityContext.lookupContextNoThrow(context) ?: return null
        return activity.dragLayer
    }

    private fun detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
        uiSnapshot?.recycle()
        uiSnapshot = null
    }

    private fun removePreDrawListener() {
        preDrawListener?.let {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(it)
        }
        preDrawListener = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animate().withEndAction(null).cancel()
        wipeAnimator?.cancel()
        wipeAnimator = null
        removePreDrawListener()
        uiSnapshot?.recycle()
        uiSnapshot = null
        oldWallpaper = null
        newWallpaper = null
        finish(applySucceeded)
    }

    companion object {
        val WIPE_INTERPOLATOR = PathInterpolator(0f, 0f, 0.7f, 1f)
        const val WIPE_MS = 400L
        private const val SETTLE_MS = 90L
    }
}
