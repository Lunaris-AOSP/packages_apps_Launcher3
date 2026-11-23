/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.icons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PackIconWrapperTest {
    @Test
    fun `opaque icon keeps its background color`() {
        val wrapped = PackIconWrapper.wrap(ColorDrawable(Color.RED))
        assertEquals(Color.RED, (wrapped.background as ColorDrawable).color)
    }

    @Test
    fun `transparent icon with light content gets contrasting dark background`() {
        val wrapped = PackIconWrapper.wrap(SparseIcon(Color.WHITE))
        assertEquals(Color.rgb(0x1F, 0x1F, 0x1F), (wrapped.background as ColorDrawable).color)
    }

    @Test
    fun `transparent icon with dark content gets contrasting light background`() {
        val wrapped = PackIconWrapper.wrap(SparseIcon(Color.BLACK))
        assertEquals(Color.WHITE, (wrapped.background as ColorDrawable).color)
    }

    @Test
    fun `sampling restores source bounds`() {
        val icon = ColorDrawable(Color.BLUE).apply { bounds = Rect(3, 7, 40, 55) }
        PackIconWrapper.wrap(icon)
        assertEquals(Rect(3, 7, 40, 55), icon.bounds)
    }

    @Test
    fun `foreground inset compensates for adaptive icon extra area`() {
        val icon = ColorDrawable(Color.BLUE)
        val wrapped = PackIconWrapper.wrap(icon)
        wrapped.setBounds(0, 0, 100, 100)
        assertEquals(Rect(0, 0, 100, 100), icon.bounds)
    }

    private class SparseIcon(color: Int) : Drawable() {
        private val paint = Paint().apply { this.color = color }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(
                b.left + b.width() * 0.35f,
                b.top + b.height() * 0.35f,
                b.right - b.width() * 0.35f,
                b.bottom - b.height() * 0.35f,
                paint,
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
        }

        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
