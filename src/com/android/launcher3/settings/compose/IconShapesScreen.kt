/*
 * Copyright (C) 2026 crDroid Android Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.android.launcher3.settings.compose

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.graphics.ShapeDelegate.Companion.pickBestShape
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_CUSTOM_ICON_SHAPE
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_ICON_SHAPE
import com.android.launcher3.icons.BitmapInfo.Companion.FLAG_NO_BADGE
import com.android.launcher3.icons.BitmapInfo.Companion.FLAG_THEMED
import com.android.launcher3.icons.GraphicsUtils
import com.android.launcher3.icons.cache.CacheLookupFlag.Companion.DEFAULT_LOOKUP_FLAG
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.settings.preferences.PreviewWallpaper
import com.android.launcher3.shapes.*
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.text.NumberFormat
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Observe both values, including changes made while the editor is a separate activity. */
@Composable
private fun shapeRevision(): Int {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = LauncherPrefs.get(context)
        var active = true
        val listener = LauncherPrefChangeListener { key ->
            if (
                key == PREF_ICON_SHAPE.sharedPrefKey || key == PREF_CUSTOM_ICON_SHAPE.sharedPrefKey
            ) {
                MAIN_EXECUTOR.execute { if (active) revision++ }
            }
        }
        prefs.addListener(listener, PREF_ICON_SHAPE, PREF_CUSTOM_ICON_SHAPE)
        onDispose {
            active = false
            prefs.removeListener(listener, PREF_ICON_SHAPE, PREF_CUSTOM_ICON_SHAPE)
        }
    }
    return revision
}

@Composable
internal fun IconShapesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val custom = remember(revision) { CustomIconShape.fromPrefs(LauncherPrefs.get(context)) }
    val current = remember(revision) { IconShapeSettingsActivity.currentShape(context) }
    ShapeScaffold(stringResource(R.string.icon_shape_title), onBack) {
        item("preview") { IconShapeAppPreview(current?.pathString.orEmpty()) }
        item("custom_heading") { ShapeHeading(stringResource(R.string.icon_shape_custom)) }
        custom?.let {
            item(CustomIconShape.KEY) {
                ShapeOption(it.toModel(), current?.key == CustomIconShape.KEY)
            }
        }
        item("edit") {
            Surface(
                onClick = {
                    context.startActivity(IconShapeSettingsActivity.createEditorIntent(context))
                },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(if (custom == null) Icons.Rounded.Add else Icons.Rounded.Edit, null)
                    Text(
                        stringResource(
                            if (custom == null) R.string.icon_shape_custom_create
                            else R.string.icon_shape_custom_edit
                        )
                    )
                }
            }
        }
        item("presets_heading") { ShapeHeading(stringResource(R.string.icon_shape_presets)) }
        item("system_default") { ShapeOption(null, current == null) }
        items(ShapesProvider.iconShapes.toList(), key = { it.key }) { shape ->
            ShapeOption(shape, current?.key == shape.key)
        }
    }
}

@Composable
private fun ShapeOption(shape: IconShapeModel?, selected: Boolean) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = {
                        LauncherPrefs.get(context).put(PREF_ICON_SHAPE, shape?.key.orEmpty())
                    },
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected, onClick = null)
            Text(
                stringResource(shape?.titleId ?: R.string.icon_shape_default_label),
                Modifier.weight(1f),
            )
            ShapeOutline(shape?.pathString.orEmpty())
        }
    }
}

@Composable
internal fun IconShapeEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val saved = remember(revision) { CustomIconShape.fromPrefs(LauncherPrefs.get(context)) }
    var serialized by rememberSaveable {
        mutableStateOf((saved ?: CustomIconShape.DEFAULT).serialize())
    }
    val draft =
        remember(serialized) { CustomIconShape.parse(serialized) ?: CustomIconShape.DEFAULT }
    val canApply = draft.needsApply(saved, PREF_ICON_SHAPE.get(context))
    ShapeScaffold(
        stringResource(R.string.icon_shape_custom_title),
        onBack,
        actions = {
            TextButton(
                enabled = canApply,
                onClick = {
                    LauncherPrefs.get(context)
                        .put(
                            PREF_CUSTOM_ICON_SHAPE to draft.serialize(),
                            PREF_ICON_SHAPE to CustomIconShape.KEY,
                        )
                    onBack()
                },
            ) {
                Text(stringResource(R.string.icon_shape_apply))
            }
        },
    ) {
        item("preview") { IconShapeAppPreview(draft.pathString) }
        item("top_left") {
            ShapeCornerEditor(stringResource(R.string.icon_shape_corner_top_left), draft.topLeft) {
                serialized = draft.copy(topLeft = it).serialize()
            }
        }
        item("top_right") {
            ShapeCornerEditor(
                stringResource(R.string.icon_shape_corner_top_right),
                draft.topRight,
            ) {
                serialized = draft.copy(topRight = it).serialize()
            }
        }
        item("bottom_left") {
            ShapeCornerEditor(
                stringResource(R.string.icon_shape_corner_bottom_left),
                draft.bottomLeft,
            ) {
                serialized = draft.copy(bottomLeft = it).serialize()
            }
        }
        item("bottom_right") {
            ShapeCornerEditor(
                stringResource(R.string.icon_shape_corner_bottom_right),
                draft.bottomRight,
            ) {
                serialized = draft.copy(bottomRight = it).serialize()
            }
        }
    }
}

@Composable
private fun ShapeCornerEditor(title: String, corner: ShapeCorner, onChange: (ShapeCorner) -> Unit) {
    val format = remember { NumberFormat.getPercentInstance() }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(format.format(corner.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = corner.size,
                onValueChange = { onChange(corner.copy(size = (it * 100).roundToInt() / 100f)) },
                valueRange = 0f..1f,
                steps = 99,
                modifier =
                    Modifier.semantics {
                        contentDescription = title
                        stateDescription = format.format(corner.size)
                    },
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerStyle.entries.forEach { style ->
                    FilterChip(
                        selected = corner.style == style,
                        onClick = { onChange(corner.copy(style = style)) },
                        label = {
                            Text(
                                stringResource(
                                    when (style) {
                                        CornerStyle.ROUND -> R.string.icon_shape_corner_round
                                        CornerStyle.SMOOTH -> R.string.icon_shape_corner_smooth
                                        CornerStyle.CUT -> R.string.icon_shape_corner_cut
                                    }
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeHeading(title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ShapeScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = { SettingsTopAppBar(title, onBack, scroll, actions) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ShapeOutline(path: String, modifier: Modifier = Modifier.size(40.dp)) {
    val context = LocalContext.current
    val drawable = remember(context, path) { IconShapePreviewDrawable(context, path) }
    AndroidView(
        factory = {
            ImageView(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        },
        modifier = modifier,
        update = { it.setImageDrawable(drawable) },
    )
}

/** Shared wallpaper card also used by the icon pack screen. Loading follows the composition. */
@Composable
internal fun SettingsPreviewCard(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val wallpaper by
        produceState<android.graphics.drawable.Drawable?>(null, context) {
            value =
                withContext(Dispatchers.IO) { PreviewWallpaper.load(context.applicationContext) }
        }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(Modifier.fillMaxWidth()) {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { it.setImageDrawable(wallpaper) },
            )
            content()
        }
    }
}

@Composable
private fun IconShapeAppPreview(path: String) {
    val context = LocalContext.current
    val apps by
        produceState<List<AppInfo>>(emptyList(), context) {
            value = loadPreviewApps(context.applicationContext)
        }
    val theme = ThemeManager.INSTANCE.get(context)
    val renderedPath by
        produceState(path, path) {
            withFrameNanos {}
            value = path
        }
    val drawables =
        remember(apps, renderedPath) {
            val size = InvariantDeviceProfile.INSTANCE.get(context).iconBitmapSize
            val shape =
                if (apps.isEmpty()) null
                else
                    GraphicsUtils.generateIconShape(
                        size,
                        pickBestShape(renderedPath).getPath(size.toFloat()),
                    )
            apps.map { app ->
                val flags =
                    app.resolveIconCreationFlags(
                        context,
                        FLAG_NO_BADGE or if (theme.isIconThemeEnabled) FLAG_THEMED else 0,
                    )
                app.bitmap.newIcon(
                    context,
                    flags,
                    if (app.supportsCustomShapes(flags)) shape else null,
                )
            }
        }
    SettingsPreviewCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            repeat(2) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { column ->
                        val index = row * 4 + column
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val drawable = drawables.getOrNull(index)
                            if (drawable == null) ShapeOutline(path, Modifier.size(56.dp))
                            else
                                AndroidView(
                                    factory = {
                                        ImageView(it).apply {
                                            scaleType = ImageView.ScaleType.FIT_CENTER
                                        }
                                    },
                                    modifier = Modifier.size(56.dp),
                                    update = {
                                        it.setImageDrawable(drawable)
                                        it.contentDescription = apps[index].title
                                    },
                                )
                        }
                    }
                }
            }
        }
    }
}

/** The icon cache expects its model executor; keep package and bitmap work off the UI thread. */
private suspend fun loadPreviewApps(context: Context): List<AppInfo> =
    suspendCancellableCoroutine { continuation ->
        MODEL_EXECUTOR.execute {
            val apps =
                runCatching {
                        val user = Process.myUserHandle()
                        val launcherApps = context.getSystemService(LauncherApps::class.java)
                        val cache = LauncherAppState.getInstance(context).iconCache
                        val flag =
                            DEFAULT_LOOKUP_FLAG.withUseLowRes(false)
                                .withThemeIcon(
                                    ThemeManager.INSTANCE.get(context).isIconThemeEnabled
                                )
                        launcherApps
                            ?.getActivityList(null, user)
                            .orEmpty()
                            .distinctBy { it.componentName.packageName }
                            .sortedBy { it.label.toString().lowercase() }
                            .take(8)
                            .map { activity ->
                                AppInfo(context, activity, user).also {
                                    cache.getTitleAndIcon(it, activity, flag)
                                }
                            }
                    }
                    .onFailure { Log.w("IconShapePreview", "Unable to load preview icons", it) }
                    .getOrDefault(emptyList())
            if (continuation.isActive) continuation.resume(apps)
        }
    }
