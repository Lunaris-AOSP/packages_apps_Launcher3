/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.launcher3.folder

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.blur.AxBlurColors
import com.android.axion.blur.ui.view.AxViewBackdropBlur
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.settings.compose.SettingsPreviewCard
import com.android.launcher3.settings.compose.SettingsTopAppBar
import com.android.launcher3.settings.compose.settingsSwitchColors
import com.android.launcher3.util.Themes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val emojiPresets = listOf(
    "⭐", "❤️", "🎮", "🎵", "📷", "🎬", "💬", "📚", "💼", "🧰", "💳", "🛒",
    "✈️", "🚀", "🚲", "⚽", "🎨", "🎧", "🌱", "🌙", "☀️", "🐱", "🍒", "🔥",
)

@Composable
internal fun FolderCoverPicker(
    folderId: Int,
    folderName: String?,
    hasCover: Boolean,
    saveState: FolderCoverSaveState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var glyph by rememberSaveable { mutableStateOf(if (hasCover) null else CoverSymbol.FAVORITES.key) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var customInput by rememberSaveable { mutableStateOf(false) }
    var showSymbols by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var folderBackground by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var current by remember { mutableStateOf<FolderCover?>(null) }
    LaunchedEffect(folderId) {
        if (hasCover) FolderThumbnailManager.loadAsync(context, folderId) { current = it }
    }
    LaunchedEffect(saveState.saved) { if (saveState.saved) onBack() }

    val chooseGlyph: (String) -> Unit = {
        glyph = it
        photo = null
    }
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            photo = uri.toString()
        }
    }
    val selectedPhoto = photo
    val selectedGlyph = glyph.takeIf { selectedPhoto == null }
    val selectedCover = key(selectedGlyph, selectedPhoto) {
        when {
            selectedGlyph != null -> remember { FolderCover.fromGlyph(context, selectedGlyph) }
            selectedPhoto != null -> {
                val image by produceState<FolderCover?>(null) {
                    try {
                        value = withContext(Dispatchers.IO) {
                            val bitmap = FolderThumbnailManager.decodeSquare(context, Uri.parse(selectedPhoto))
                            FolderCover.photo(bitmap)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.folder_thumbnail_failed, Toast.LENGTH_SHORT).show()
                        photo = null
                    }
                }
                image
            }
            else -> current
        }
    }
    val useFolderBackground = folderBackground ?: (current?.useFolderBackground == true)
    val preview = remember(selectedCover, useFolderBackground) {
        selectedCover?.withFolderBackground(useFolderBackground)
    }
    val activeGlyph = selectedGlyph ?: preview?.glyph
    val symbols = showSymbols ?: CoverSymbol.entries.any { it.key == activeGlyph }
    val canEdit = !saveState.saving && !saveState.saved
    val backgroundChanged = current != null && preview?.useFolderBackground != current?.useFolderBackground
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            SettingsTopAppBar(stringResource(R.string.folder_thumbnail_set), onBack, scroll)
        },
        bottomBar = {
            CoverActions(
                enabled = canEdit,
                canApply = preview != null && (selectedPhoto != null || selectedGlyph != null || backgroundChanged),
                saving = saveState.saving,
                onChoosePhoto = {
                    try {
                        picker.launch(arrayOf("image/*"))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.folder_thumbnail_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onApply = { preview?.let { saveState.apply(context, folderId, it) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "preview", span = { GridItemSpan(maxLineSpan) }) {
                    SettingsPreviewCard { wallpaper ->
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CoverPreview(folderId, preview, wallpaper)
                                if (selectedPhoto != null && preview == null) {
                                    ContainedLoadingIndicator()
                                }
                            }
                            if (!folderName.isNullOrBlank()) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                                    Text(
                                        folderName,
                                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelLargeEmphasized,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (activeGlyph != null) {
                    item(key = "folderBackground", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(
                                Modifier.fillMaxWidth().toggleable(
                                    value = useFolderBackground,
                                    enabled = canEdit,
                                    role = Role.Switch,
                                    onValueChange = { folderBackground = it },
                                ).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    stringResource(R.string.folder_cover_use_folder_background),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Switch(
                                    checked = useFolderBackground,
                                    onCheckedChange = null,
                                    enabled = canEdit,
                                    colors = settingsSwitchColors(),
                                )
                            }
                        }
                    }
                }
                item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                    CoverCategories(symbols, onSelect = { showSymbols = it })
                }
                if (saveState.failed) {
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.folder_cover_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (symbols) {
                    items(CoverSymbol.entries, key = { it.key }) { symbol ->
                        val label = stringResource(symbol.label)
                        CoverChoice(symbol.key, label, activeGlyph == symbol.key, canEdit, chooseGlyph, caption = label)
                    }
                } else {
                    item(key = "customEmoji", span = { GridItemSpan(maxLineSpan) }) {
                        FilledTonalButton(
                            onClick = { customInput = true },
                            enabled = canEdit,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.folder_cover_custom_emoji))
                        }
                    }
                    items(emojiPresets, key = { it }) { emoji ->
                        CoverChoice(emoji, emoji, emoji == activeGlyph, canEdit, chooseGlyph)
                    }
                }
            }
        }
    }
    if (customInput) {
        var text by rememberSaveable { mutableStateOf("") }
        val value = FolderCover.emojiKey(text)
        AlertDialog(
            onDismissRequest = { customInput = false },
            title = { Text(stringResource(R.string.folder_cover_custom_emoji)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    label = { Text(stringResource(R.string.folder_cover_single_emoji)) },
                    isError = text.isNotBlank() && value == null,
                )
            },
            dismissButton = {
                TextButton(onClick = { customInput = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                Button(shapes = ButtonDefaults.shapes(), enabled = value != null, onClick = {
                    value?.let(chooseGlyph)
                    customInput = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun CoverActions(
    enabled: Boolean,
    canApply: Boolean,
    saving: Boolean,
    onChoosePhoto: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val button = Modifier.weight(1f).heightIn(min = 52.dp)
                val padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                FilledTonalButton(
                    onClick = onChoosePhoto,
                    enabled = enabled,
                    modifier = button,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = padding,
                ) {
                    Icon(painterResource(R.drawable.ic_folder_thumbnail), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.folder_cover_choose_photo), textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onApply,
                    enabled = enabled && canApply,
                    modifier = button,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = padding,
                ) {
                    if (saving) {
                        LoadingIndicator(Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.icon_shape_apply), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun CoverCategories(symbols: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        listOf(true, false).forEach { dotted ->
            ToggleButton(
                checked = symbols == dotted,
                onCheckedChange = { onSelect(dotted) },
                shapes = if (dotted) ButtonGroupDefaults.connectedLeadingButtonShapes()
                    else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                if (dotted) {
                    Icon(painterResource(R.drawable.ic_folder_style_3x3), null, Modifier.size(18.dp))
                } else {
                    Icon(Icons.Rounded.Face, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (dotted) R.string.folder_cover_symbols else R.string.folder_cover_emoji),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CoverChoice(
    key: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    select: (String) -> Unit,
    caption: String? = null,
) {
    val context = LocalContext.current
    val glyph = remember(context, key) { FolderCover.fromGlyph(context, key, 160)?.bitmap?.asImageBitmap() }
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "cover tile color",
    )
    val corner by animateDpAsState(
        if (selected) 28.dp else 16.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "cover tile shape",
    )
    Surface(
        selected = selected,
        onClick = { select(key) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(corner),
        color = fill,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (glyph != null) {
                    Icon(glyph, if (caption == null) label else null, Modifier.size(48.dp))
                }
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    shape = CircleShape,
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.padding(3.dp).size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun CoverPreview(folderId: Int, cover: FolderCover?, wallpaper: View?) {
    val context = LocalContext.current
    val colors = remember(context, LocalConfiguration.current, ThemeManager.INSTANCE[context].iconState) {
        FolderCover.Colors.resolve(context)
    }
    val description = stringResource(R.string.folder_cover_preview)
    AndroidView(
        factory = { FolderCoverPreviewView(it, folderId) },
        modifier = Modifier.fillMaxWidth(0.8f).height(132.dp),
        update = {
            it.contentDescription = description
            it.bind(cover, colors, wallpaper)
        },
    )
}

private class FolderCoverPreviewView(context: Context, folderId: Int) : View(context) {
    private val icon = Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()?.findFolderIcon(folderId)
    private val outline = Path().also { path ->
        icon?.getPreviewBackgroundPath(path)
        if (path.isEmpty) path.set(ThemeManager.INSTANCE[context].folderShape.getPath())
    }
    private val outlineBounds = RectF().also { outline.computeBounds(it, true) }
    private val bounds = RectF()
    private val clip = Path()
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val baseBlurRadius = resources.getDimension(R.dimen.folder_blur_radius)
    private var cornerRadius = 0f
    private val folderColor = icon?.folderBackground?.bgColor ?: Themes.getAttrColor(
        ContextThemeWrapper(context, Themes.getActivityThemeRes(context)), R.attr.folderPreviewColor,
    )
    private val blur = AxViewBackdropBlur(this, false).apply {
        setBlurRadiusPx(baseBlurRadius)
        setOverlayColor(AxBlurColors.surfaceBrightTint(context))
        setPreferSourceBlur(true)
    }
    private var cover: FolderCover? = null
    private lateinit var colors: FolderCover.Colors
    private var wallpaper: View? = null

    fun bind(cover: FolderCover?, colors: FolderCover.Colors, wallpaper: View?) {
        this.cover = cover
        this.colors = colors
        this.wallpaper = wallpaper
        blur.setSourceView(wallpaper)
        blur.setEnabled(
            cover?.useFolderBackground == true && wallpaper != null && LauncherPrefs.FOLDER_PREVIEW_BLUR.get(context)
        )
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur.onAttachedToWindow()
        blur.setSourceView(wallpaper)
    }

    override fun onDetachedFromWindow() {
        blur.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        val fit = Matrix().apply { setRectToRect(outlineBounds, bounds, Matrix.ScaleToFit.CENTER) }
        outline.transform(fit, clip)
        fit.mapRect(bounds, outlineBounds)
        cornerRadius = fit.mapRadius(icon?.previewBackgroundCornerRadius ?: 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val cover = cover ?: return
        val save = canvas.save()
        canvas.clipPath(clip)
        val blurred =
            cover.useFolderBackground && blur.isEnabled() && windowManager?.isCrossWindowBlurEnabled == true &&
                (blur.resolveBlurRadiusPx(baseBlurRadius) > 0f || blur.isTintOverrideEnabled()) &&
                blur.draw(canvas, bounds, clip, cornerRadius)
        if (cover.useFolderBackground && !blurred) canvas.drawColor(folderColor)
        cover.draw(canvas, bounds, colors, if (blurred) FolderIcon.getFolderBlurTint(context) else folderColor)
        canvas.restoreToCount(save)
    }
}
