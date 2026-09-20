package com.android.launcher3.popup

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
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
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import com.android.launcher3.Utilities
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
 *
 * Selecting a chip morphs the row, then reveals the new wallpaper with Pixel's circular wipe from
 * the tapped thumbnail.
 */
class WallpaperCarouselView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    LinearLayout(context, attrs, defStyleAttr) {

    private var currentItemIndex = 0
    private var wallpapers: List<Wallpaper> = emptyList()
    private var chipSizes = ChipSizes(0, 0)

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
    // Survives popup dismiss so the wallpaper apply and reveal can finish.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var applyJob: Job? = null
    private var selectAnimator: ValueAnimator? = null
    private var revealView: WallpaperRevealView? = null
    private var selectionBusy = false
    private val chipTransition =
        LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            setInterpolator(LayoutTransition.CHANGING, WallpaperRevealView.WIPE_INTERPOLATOR)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = true
        clipChildren = true
        addView(loadingView)
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
        selectAnimator?.cancel()
        layoutTransition = null
        removeAllViews()
        this.wallpapers = wallpapers

        val appliedIndex = wallpapers.indexOfFirst { it.rank == 0 }.let { if (it >= 0) it else 0 }
        currentItemIndex = appliedIndex

        chipSizes = resolveChipSizes(wallpapers.size)
        wallpapers.forEachIndexed { index, wallpaper ->
            val chip =
                createChip(
                    index,
                    wallpaper,
                    selected = index == currentItemIndex,
                    selectedWidth = chipSizes.selectedWidth,
                    pillWidth = chipSizes.pillWidth,
                )
            addView(chip)
            loadWallpaperImage(wallpaper, chip.getChildAt(0) as ImageView)
        }
        loadingView.visibility = GONE
        layoutTransition = chipTransition
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
                tag = wallpaper
            }

        val image =
            ImageView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = ChipOutline(if (selected) selectedRadius else width / 2f)
                setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_deepshortcut_placeholder)
                )
            }
        chip.addView(image)

        if (selected) {
            chip.addView(createCheckBadge())
        }

        chip.setOnClickListener {
            if (index == currentItemIndex || selectionBusy) return@setOnClickListener
            selectWallpaper(index, wallpaper, chip)
        }
        return chip
    }

    private fun selectWallpaper(index: Int, wallpaper: Wallpaper, chip: FrameLayout) {
        val previousIndex = currentItemIndex
        currentItemIndex = index
        selectionBusy = true

        val previousChip = getChildAt(previousIndex) as? FrameLayout
        val previousFrame = previousChip?.let { previewBitmap(it) }
        val nextFrame = previewBitmap(chip)
        animateSelection(previousIndex, index)
        applyJob?.cancel()
        startReveal(chip, wallpaper, previousFrame, nextFrame, previousIndex, index)
        applyWallpaper(wallpaper, previousIndex, index, revealView)
    }

    private fun previewBitmap(chip: FrameLayout): Bitmap? {
        val image = chip.getChildAt(0) as? ImageView
        return (image?.drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }
    }

    private fun startReveal(
        chip: FrameLayout,
        wallpaper: Wallpaper,
        previousFrame: Bitmap?,
        nextFrame: Bitmap?,
        previousIndex: Int,
        newIndex: Int,
    ) {
        revealView?.cancelAndRemove()
        if (previousFrame == null || nextFrame == null) {
            revealView = null
            return
        }
        val reveal = WallpaperRevealView(context)
        revealView = reveal
        reveal.play(chip, previousFrame, nextFrame) { success ->
            if (revealView === reveal) {
                revealView = null
            }
            selectionBusy = false
            if (!success && isAttachedToWindow && currentItemIndex == newIndex) {
                currentItemIndex = previousIndex
                animateSelection(newIndex, previousIndex)
            }
        }
    }

    private fun applyWallpaper(
        wallpaper: Wallpaper,
        previousIndex: Int,
        newIndex: Int,
        reveal: WallpaperRevealView?,
    ) {
        applyJob?.cancel()
        applyJob =
            persistScope.launch {
                val success =
                    WallpaperService.INSTANCE.get(context.applicationContext)
                        .applyWallpaper(wallpaper, WallpaperManager.getInstance(context))
                if (reveal != null) {
                    reveal.onApplyFinished(success)
                } else {
                    selectionBusy = false
                    if (!success && isAttachedToWindow && currentItemIndex == newIndex) {
                        currentItemIndex = previousIndex
                        animateSelection(newIndex, previousIndex)
                    }
                }
            }
    }

    private fun animateSelection(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || childCount == 0) return
        chipSizes = resolveChipSizes(childCount)

        val fromWidths = IntArray(childCount) { getChildAt(it).layoutParams.width }
        val toWidths =
            IntArray(childCount) { index ->
                if (index == toIndex) chipSizes.selectedWidth else chipSizes.pillWidth
            }
        val fromRadii =
            FloatArray(childCount) { index ->
                chipOutline(getChildAt(index) as FrameLayout)?.radius
                    ?: if (index == fromIndex) selectedRadius else fromWidths[index] / 2f
            }
        val toRadii =
            FloatArray(childCount) { index ->
                if (index == toIndex) selectedRadius else toWidths[index] / 2f
            }

        animateCheck(fromIndex, toIndex)

        for (i in 0 until childCount) {
            (getChildAt(i) as FrameLayout).layoutParams.width = toWidths[i]
        }
        requestLayout()

        selectAnimator?.cancel()
        selectAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                interpolator = WallpaperRevealView.WIPE_INTERPOLATOR
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    for (i in 0 until childCount) {
                        val chip = getChildAt(i) as FrameLayout
                        chipOutline(chip)?.let { outline ->
                            outline.radius = Utilities.mapRange(t, fromRadii[i], toRadii[i])
                            chip.getChildAt(0)?.invalidateOutline()
                        }
                    }
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            selectAnimator = null
                        }
                    }
                )
                start()
            }
    }

    private fun animateCheck(fromIndex: Int, toIndex: Int) {
        val fromChip = getChildAt(fromIndex) as? FrameLayout
        val toChip = getChildAt(toIndex) as? FrameLayout
        fromChip?.findViewWithTag<View>(CHECK_TAG)?.let { check ->
            check
                .animate()
                .alpha(0f)
                .scaleX(0.6f)
                .scaleY(0.6f)
                .setDuration(CHECK_ANIM_MS)
                .setInterpolator(Interpolators.EMPHASIZED_ACCELERATE)
                .withEndAction { fromChip.removeView(check) }
                .start()
        }
        toChip?.let { chip ->
            if (chip.findViewWithTag<View>(CHECK_TAG) != null) return
            val check =
                createCheckBadge().apply {
                    alpha = 0f
                    scaleX = 0.6f
                    scaleY = 0.6f
                }
            chip.addView(check)
            check
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(CHECK_ANIM_MS)
                .setInterpolator(Interpolators.EMPHASIZED_DECELERATE)
                .start()
        }
    }

    private fun chipOutline(chip: FrameLayout): ChipOutline? {
        return (chip.getChildAt(0) as? ImageView)?.outlineProvider as? ChipOutline
    }

    private class ChipOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = radius.coerceAtMost(minOf(view.width, view.height) / 2f)
            outline.setRoundRect(0, 0, view.width, view.height, r)
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
            tag = CHECK_TAG
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
            val bitmap = decodePreview(path, inSampleSize = 2)
            if (!isAttachedToWindow) return@launch
            if (bitmap != null) {
                imageView.alpha = 0f
                imageView.setImageBitmap(bitmap)
                imageView.animate().alpha(1f).setDuration(200L).start()
            }
        }
    }

    private suspend fun decodePreview(path: String, inSampleSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) return@runCatching null
                    val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    BitmapFactory.decodeFile(file.path, opts)
                }
                .getOrNull()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (
            w > 0 &&
                w != oldw &&
                wallpapers.isNotEmpty() &&
                !selectionBusy &&
                selectAnimator?.isRunning != true
        ) {
            displayWallpapers(wallpapers)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        selectAnimator?.cancel()
        scope.cancel()
        // Leave persistScope + reveal running so the wipe can cover setBitmap().
        removeAllViews()
    }

    private data class ChipSizes(val selectedWidth: Int, val pillWidth: Int)

    companion object {
        private const val CHECK_TAG = "wallpaper_check"
        private const val CHECK_ANIM_MS = 200L
    }
}
