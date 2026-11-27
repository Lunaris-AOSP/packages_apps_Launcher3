/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.folder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.icu.text.UnicodeSet
import com.android.launcher3.R
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.ThemedIconSettings
import kotlin.math.max
import kotlin.math.min

class FolderCover private constructor(
    val bitmap: Bitmap,
    val glyph: String?,
    val useFolderBackground: Boolean = false,
) {
    private val imageBounds = Rect(0, 0, bitmap.width, bitmap.height)

    fun withFolderBackground(enabled: Boolean): FolderCover =
        if (glyph == null || enabled == useFolderBackground) this else FolderCover(bitmap, glyph, enabled)

    class Colors private constructor(val background: Int, foreground: Int) {
        internal val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        }

        companion object {
            private val default = Colors(0xff29292d.toInt(), Color.WHITE)

            @JvmStatic
            fun resolve(context: Context): Colors =
                if (ThemeManager.INSTANCE[context].isIconThemeEnabled) {
                    Colors(
                        ThemedIconSettings.getBackgroundColor(context),
                        ThemedIconSettings.getForegroundColor(context),
                    )
                } else default
        }
    }

    fun draw(canvas: Canvas, bounds: RectF, colors: Colors, folderColor: Int) {
        val sx = bounds.width() / bitmap.width
        val sy = bounds.height() / bitmap.height
        val scale = if (glyph == null) max(sx, sy) else min(sx, sy)
        val save = canvas.save()
        canvas.clipRect(bounds)
        if (glyph != null && !useFolderBackground) canvas.drawColor(colors.background)
        canvas.translate(bounds.centerX() - bitmap.width * scale / 2,
            bounds.centerY() - bitmap.height * scale / 2)
        canvas.scale(scale, scale)
        val paint = when {
            glyph == null -> bitmapPaint
            !useFolderBackground -> colors.glyphPaint
            Color.luminance(folderColor) > BLACK_WHITE_CONTRAST_THRESHOLD -> darkGlyphPaint
            else -> bitmapPaint
        }
        canvas.drawBitmap(bitmap, null, imageBounds, paint)
        canvas.restoreToCount(save)
    }

    companion object {
        private const val SIZE = 256
        private const val EMOJI_FRACTION = 0.465f
        private const val BLACK_WHITE_CONTRAST_THRESHOLD = 0.179f
        private val bitmapPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
        private val darkGlyphPaint by lazy {
            Paint(bitmapPaint).apply { colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN) }
        }
        private val grayscale by lazy { ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        private val emojiSet by lazy { UnicodeSet("[:RGI_Emoji:]").freeze() }

        fun photo(bitmap: Bitmap) = FolderCover(bitmap, null)

        fun emojiKey(input: String): String? {
            val text = input.trim()
            if (text.isEmpty() || text.length > 64) return null
            return when {
                emojiSet.contains(text) -> text
                emojiSet.contains(text + "\uFE0F") -> text + "\uFE0F"
                else -> null
            }
        }

        fun fromGlyph(context: Context, key: String, size: Int = SIZE): FolderCover? {
            val symbol = CoverSymbol.entries.find { it.key == key }
            val glyph = symbol?.key ?: emojiKey(key) ?: return null
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(size.toFloat() / SIZE, size.toFloat() / SIZE)
            if (symbol != null) {
                symbol.draw(canvas, SIZE)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    color = Color.WHITE
                    textSize = SIZE.toFloat()
                    typeface = context.resources.getFont(R.font.folder_cover_emoji)
                    colorFilter = grayscale
                }
                val ink = Rect()
                paint.getTextBounds(glyph, 0, glyph.length, ink)
                if (ink.isEmpty) return null
                val scale = SIZE * EMOJI_FRACTION / max(ink.width(), ink.height())
                canvas.translate(SIZE / 2f, SIZE / 2f)
                canvas.scale(scale, scale)
                canvas.drawText(glyph, -ink.exactCenterX(), -ink.exactCenterY(), paint)
            }
            return FolderCover(bitmap, glyph)
        }
    }
}

internal enum class CoverSymbol(val key: String, val label: Int, private vararg val rows: String) {
    FAVORITES("favorites", R.string.folder_cover_favorites,
        ".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#...."),
    MUSIC("music", R.string.folder_cover_music,
        "...######", "...######", "...#....#", "...#....#", "...#....#", ".###..###", "####.####", ".##...##."),
    GAMES("games", R.string.caddy_games,
        ".##...##.", ".#######.", "#########", "##.###.##", "#...###.#", "##.######", "###...###", "##.....##"),
    ART("art", R.string.folder_cover_art,
        "...####..", ".#######.", "##.###.##", "#########", "#.####.##", "######...", ".##.##...", "..#####..", "....##..."),
    TRAVEL("travel", R.string.folder_cover_travel,
        "....#....", "....#....", "...###...", "..#####..", ".#######.", "###.#.###", "....#....", "...###...", "..##.##.."),
    CHAT("chat", R.string.folder_cover_chat,
        ".#######.", "#########", "#########", "##.#.#.##", "#########", ".#######.", "..###....", "..##....."),
    MAIL("mail", R.string.folder_cover_mail,
        "#########", "##.....##", "#.#...#.#", "#..#.#..#", "#...#...#", "#.......#", "#########"),
    CALENDAR("events", R.string.folder_cover_calendar,
        "..#...#..", "#########", "#.......#", "#########", "#.#.#.#.#", "#.......#", "#.#.#.#.#", "#########"),
    FILES("files", R.string.folder_cover_files,
        ".###.....", "#####....", "#########", "#.......#", "#.......#", "#.......#", "#########"),
    CLOCK("clock", R.string.folder_cover_clock,
        "...###...", ".##...##.", ".#..#..#.", "#...#...#", "#...###.#", "#.......#", ".#.....#.", ".##...##.", "...###..."),
    CAMERA("camera", R.string.folder_cover_camera,
        "...###...", "..#####..", "#########", "#..###..#", "#.#...#.#", "#.#...#.#", "#..###..#", "#########"),
    WORK("work", R.string.folder_cover_work,
        "...###...", "..#...#..", ".#######.", "#########", "....#....", "#########", "#########", ".#######.");

    fun draw(canvas: Canvas, size: Int) {
        val pitch = size * 0.059f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, dot ->
                if (dot == '#') {
                    canvas.drawCircle(
                        size / 2f + (x - 4) * pitch,
                        size / 2f + (y - (rows.size - 1) / 2f) * pitch,
                        pitch * 0.4f,
                        paint,
                    )
                }
            }
        }
    }
}
