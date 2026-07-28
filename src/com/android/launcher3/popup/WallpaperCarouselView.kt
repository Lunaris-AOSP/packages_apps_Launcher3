/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.popup

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.android.launcher3.R
import com.android.launcher3.data.wallpaper.Wallpaper
import com.android.launcher3.data.wallpaper.service.WallpaperService
import com.android.launcher3.util.Themes
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wallpaper history previews in the style of Android 17 QPR1 Beta 6: selected item is a landscape
 * rounded rectangle; others are upright round pills. Chips scale up to fill the menu width so the
 * row reaches the right edge.
 */
class WallpaperCarouselView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    LinearLayout(context, attrs, defStyleAttr) {

    private var currentItemIndex = 0
    private var wallpapers: List<Wallpaper> = emptyList()

    private val baseSelectedWidth =
        resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_selected_width)
    private val selectedHeight =
        resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_selected_height)
    private val selectedRadius =
        resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_selected_radius).toFloat()
    private val basePillWidth =
        resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_pill_width)
    private val pillHeight = resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_pill_height)
    private val itemGap = resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_item_gap)
    private val checkSize = resources.getDimensionPixelSize(R.dimen.wallpaper_carousel_check_size)

    private val loadingView = ProgressBar(context).apply { isIndeterminate = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var applyJob: Job? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = true
        clipChildren = true
        addView(loadingView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeWallpapers()
    }

    private fun observeWallpapers() {
        loadingView.visibility = VISIBLE
        scope.launch {
            wallpapers =
                withContext(Dispatchers.IO) {
                    runCatching { WallpaperService.INSTANCE.get(context).getTopWallpapers() }
                        .getOrDefault(emptyList())
                }

            if (!isAttachedToWindow) return@launch

            visibility = if (wallpapers.isEmpty()) GONE else VISIBLE
            if (wallpapers.isNotEmpty()) {
                displayWallpapers(wallpapers)
            } else {
                loadingView.visibility = GONE
            }
        }
    }

    private fun displayWallpapers(wallpapers: List<Wallpaper>) {
        removeAllViews()
        this.wallpapers = wallpapers

        val appliedIndex = wallpapers.indexOfFirst { it.rank == 0 }.let { if (it >= 0) it else 0 }
        currentItemIndex = appliedIndex

        val sizes = resolveChipSizes(wallpapers.size)
        wallpapers.forEachIndexed { index, wallpaper ->
            val chip =
                createChip(
                    index,
                    wallpaper,
                    selected = index == currentItemIndex,
                    selectedWidth = sizes.selectedWidth,
                    pillWidth = sizes.pillWidth,
                )
            addView(chip)
            loadWallpaperImage(wallpaper, chip.getChildAt(0) as ImageView)
        }
        loadingView.visibility = GONE
    }

    /**
     * Scale selected + pills to exactly fill [width] (already inset by menu side padding), keeping
     * the Beta 6 width ratio. Never exceeds available space.
     */
    private fun resolveChipSizes(itemCount: Int): ChipSizes {
        val otherCount = (itemCount - 1).coerceAtLeast(0)
        val available =
            if (width > 0) {
                width
            } else {
                // Before first measure: stay conservative so we don't widen the menu.
                (baseSelectedWidth + otherCount * (basePillWidth + itemGap)).coerceAtMost(
                    resources.getDimensionPixelSize(R.dimen.bg_popup_item_width) -
                        2 *
                            resources.getDimensionPixelSize(
                                R.dimen.wallpaper_carousel_horizontal_padding
                            )
                )
            }
        if (itemCount <= 0) return ChipSizes(0, 0)
        if (itemCount == 1) {
            return ChipSizes(selectedWidth = available.coerceAtLeast(1), pillWidth = 0)
        }

        val gaps = itemGap * otherCount
        val usable = max(1, available - gaps)
        val totalWeight = (baseSelectedWidth + basePillWidth * otherCount).toFloat()
        var selectedW = ((usable * baseSelectedWidth) / totalWeight).roundToInt().coerceAtLeast(1)
        var remaining = usable - selectedW
        var pillW = remaining / otherCount
        if (pillW < 1) {
            pillW = 1
            selectedW = max(1, usable - pillW * otherCount)
            remaining = usable - selectedW
            pillW = remaining / otherCount
        }
        val leftover = remaining - pillW * otherCount
        // Exact fit: selected + leftover + pills*count + gaps == available
        return ChipSizes(selectedWidth = selectedW + leftover, pillWidth = pillW.coerceAtLeast(1))
    }

    private fun createChip(
        index: Int,
        wallpaper: Wallpaper,
        selected: Boolean,
        selectedWidth: Int,
        pillWidth: Int,
    ): FrameLayout {
        val width = if (selected) selectedWidth else pillWidth
        val height = if (selected) selectedHeight else pillHeight
        val chip =
            FrameLayout(context).apply {
                layoutParams =
                    LayoutParams(width, height).apply {
                        marginStart = if (index > 0) itemGap else 0
                    }
            }

        val image =
            ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(width, height)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider =
                    if (selected) {
                        roundedRectOutline(selectedRadius)
                    } else {
                        roundedRectOutline(width / 2f)
                    }
                setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_deepshortcut_placeholder)
                )
            }
        chip.addView(image)

        if (selected) {
            chip.addView(createCheckBadge())
        }

        chip.setOnClickListener {
            if (index == currentItemIndex) return@setOnClickListener
            currentItemIndex = index
            setWallpaper(wallpaper, chip)
        }
        return chip
    }

    private fun roundedRectOutline(cornerRadius: Float): ViewOutlineProvider {
        return object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = cornerRadius.coerceAtMost(minOf(view.width, view.height) / 2f)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val path =
                        Path().apply {
                            addRoundRect(
                                RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                                radius,
                                radius,
                                Path.Direction.CW,
                            )
                        }
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
    }

    private fun createCheckBadge(): ImageView {
        val accent = Themes.getColorAccent(context)
        val checkColor =
            if (ColorUtils.calculateLuminance(accent) > 0.4) {
                ColorUtils.blendARGB(accent, Color.BLACK, 0.72f)
            } else {
                Color.WHITE
            }
        val padding = (checkSize * 0.22f).toInt()
        return ImageView(context).apply {
            layoutParams =
                FrameLayout.LayoutParams(checkSize, checkSize).apply { gravity = Gravity.CENTER }
            setImageResource(R.drawable.ic_tick)
            setColorFilter(checkColor)
            setPadding(padding, padding, padding, padding)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            elevation = 2f * resources.displayMetrics.density
        }
    }

    private fun loadWallpaperImage(wallpaper: Wallpaper, imageView: ImageView) {
        val path = wallpaper.imagePath
        scope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val file = File(path)
                            if (!file.exists() || !file.canRead()) return@runCatching null
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeFile(file.path, opts)
                        }
                        .getOrNull()
                }
            if (!isAttachedToWindow) return@launch
            if (bitmap != null) {
                imageView.alpha = 0f
                imageView.setImageBitmap(bitmap)
                imageView.animate().alpha(1f).setDuration(200L).start()
            }
        }
    }

    private fun setWallpaper(wallpaper: Wallpaper, chip: FrameLayout) {
        val spinner =
            ProgressBar(context).apply {
                isIndeterminate = true
                layoutParams =
                    FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                        .apply { gravity = Gravity.CENTER }
            }
        for (i in chip.childCount - 1 downTo 1) {
            chip.removeViewAt(i)
        }
        chip.addView(spinner)

        applyJob?.cancel()
        applyJob =
            scope.launch {
                val success =
                    WallpaperService.INSTANCE.get(context)
                        .applyWallpaper(wallpaper, WallpaperManager.getInstance(context))

                if (!isAttachedToWindow) return@launch
                chip.removeView(spinner)

                if (success) {
                    observeWallpapers()
                }
            }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw && wallpapers.isNotEmpty()) {
            displayWallpapers(wallpapers)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        removeAllViews()
    }

    private data class ChipSizes(val selectedWidth: Int, val pillWidth: Int)
}
