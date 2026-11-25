/*
 * Copyright (C) 2026 crDroid Android Project
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

package com.android.launcher3.shapes

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.android.launcher3.folder.FolderPreviewLayout
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR
import kotlin.math.roundToInt

/**
 * Approximates a closed folder icon: the folder [shape] filled with [backgroundColor], holding up
 * to four [items] in a 2x2 grid.
 */
class FolderPreviewDrawable(
    private val shape: ShapeDelegate,
    private val items: List<Drawable>,
    backgroundColor: Int,
    private val size: Int,
    private val expanded: Boolean = false,
) : Drawable() {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = b.width().toFloat() / size
        // The folder background covers the visible area of an app icon, not its padding.
        val radius = b.width() * ICON_VISIBLE_AREA_FACTOR / 2
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        shape.drawShape(canvas, cx - radius, cy - radius, radius, paint)

        if (expanded) {
            drawExpandedItems(canvas, RectF(cx - radius, cy - radius, cx + radius, cy + radius))
            return
        }

        val itemSize = (size * ITEM_SIZE_RATIO * scale).roundToInt()
        val offset = size * ITEM_OFFSET_RATIO * scale
        items.take(MAX_ITEMS).forEachIndexed { i, item ->
            val itemCx = cx + if (i % 2 == 0) -offset else offset
            val itemCy = cy + if (i < 2) -offset else offset
            val left = (itemCx - itemSize / 2f).roundToInt()
            val top = (itemCy - itemSize / 2f).roundToInt()
            item.setBounds(left, top, left + itemSize, top + itemSize)
            item.draw(canvas)
        }
    }

    private fun drawExpandedItems(canvas: Canvas, backgroundBounds: RectF) {
        val grid =
            if (shape is ShapeDelegate.Circle) {
                FolderPreviewLayout.calculateCircularGrid(backgroundBounds, 2, 2, false)
            } else {
                val cellSize = backgroundBounds.width() * 0.43f
                val itemSize = cellSize * 0.8f
                val gap = cellSize - itemSize
                FolderPreviewLayout.Grid(
                    2,
                    2,
                    backgroundBounds.left + backgroundBounds.width() * 0.07f + gap / 2f,
                    backgroundBounds.top + backgroundBounds.height() * 0.07f + gap / 2f,
                    itemSize,
                    gap,
                    gap,
                )
            }
        val directCount = if (items.size > MAX_ITEMS) MAX_ITEMS - 1 else items.size
        items.take(7).forEachIndexed { index, item ->
            val tile =
                FolderPreviewLayout.calculateGridItemBounds(
                    minOf(index, MAX_ITEMS - 1),
                    grid,
                    false,
                )
            if (index >= directCount) {
                val miniIndex = index - directCount
                val miniSize = tile.width() * 0.42f
                val left = tile.left + tile.width() * (0.04f + miniIndex % 2 * 0.5f)
                val top = tile.top + tile.height() * (0.04f + miniIndex / 2 * 0.5f)
                tile.set(left, top, left + miniSize, top + miniSize)
            }
            item.setBounds(
                tile.left.roundToInt(),
                tile.top.roundToInt(),
                tile.right.roundToInt(),
                tile.bottom.roundToInt(),
            )
            item.draw(canvas)
        }
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        items.forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val MAX_ITEMS = 4
        /** Size of each item relative to the folder icon. */
        const val ITEM_SIZE_RATIO = 0.4f
        /** Distance of each item's center from the folder center, relative to the icon. */
        const val ITEM_OFFSET_RATIO = 0.19f
    }
}
