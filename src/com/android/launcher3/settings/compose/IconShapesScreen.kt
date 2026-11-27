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
import androidx.compose.foundation.selection.toggleable
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
import com.android.launcher3.customization.IconDatabase
import com.android.launcher3.graphics.ShapeDelegate
import com.android.launcher3.graphics.ShapeDelegate.Companion.pickBestShape
import com.android.launcher3.graphics.ThemeManager
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
internal fun shapeRevision(): Int {
    val context = LocalContext.current
    val items = remember {
        arrayOf(
            ShapeTarget.ICON.shapePref,
            ShapeTarget.ICON.customShapePref,
            ShapeTarget.FOLDER.shapePref,
            ShapeTarget.FOLDER.customShapePref,
            ThemeManager.PREF_RESHAPE_PACK_ICONS,
            ThemeManager.PREF_CIRCULAR_EXPANDED_FOLDERS,
        )
    }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = LauncherPrefs.get(context)
        var active = true
        val listener = LauncherPrefChangeListener { key ->
            if (items.any { it.sharedPrefKey == key } || key == IconDatabase.KEY_ICON_PACK) {
                MAIN_EXECUTOR.execute { if (active) revision++ }
            }
        }
        prefs.addListener(listener, *items)
        val shapeSubscription =
            ThemeManager.INSTANCE.get(context).iconShapeData.forEach(MAIN_EXECUTOR) {
                if (active) revision++
            }
        onDispose {
            shapeSubscription.close()
            active = false
            prefs.removeListener(listener, *items)
        }
    }
    return revision
}

@Composable
internal fun IconShapesScreen(onBack: () -> Unit, target: ShapeTarget = ShapeTarget.ICON) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val custom = remember(revision, target) { target.customShape(LauncherPrefs.get(context)) }
    val current =
        remember(revision, target) { IconShapeSettingsActivity.currentShape(context, target) }
    val circularExpandedFolders =
        remember(revision) {
            LauncherPrefs.get(context).get(ThemeManager.PREF_CIRCULAR_EXPANDED_FOLDERS)
        }
    ShapeScaffold(stringResource(target.title), onBack) {
        if (target == ShapeTarget.FOLDER)
            item("circular_expanded") { CircularExpandedFoldersOption(circularExpandedFolders) }
        item("preview") { IconShapeAppPreview(null, target) }
        if (target == ShapeTarget.FOLDER)
            item("scope") {
                Text(
                    stringResource(R.string.folder_shape_normal_only),
                    Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        item("custom_heading") { ShapeHeading(stringResource(R.string.icon_shape_custom)) }
        custom?.let {
            item(CustomIconShape.KEY) {
                ShapeOption(it.toModel(), current?.key == CustomIconShape.KEY, target)
            }
        }
        item("edit") {
            Surface(
                onClick = {
                    context.startActivity(
                        IconShapeSettingsActivity.createEditorIntent(context, target)
                    )
                },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceBright,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(if (custom == null) Icons.Rounded.Add else Icons.Rounded.Edit, null)
                    Text(
                        stringResource(
                            if (custom == null) target.customCreate else target.customEdit
                        )
                    )
                }
            }
        }
        item("presets_heading") { ShapeHeading(stringResource(R.string.icon_shape_presets)) }
        item("system_default") { ShapeOption(null, current == null, target) }
        items(ShapesProvider.iconShapes.toList(), key = { it.key }) { shape ->
            ShapeOption(shape, current?.key == shape.key, target)
        }
    }
}

@Composable
private fun CircularExpandedFoldersOption(checked: Boolean) {
    val prefs = LauncherPrefs.get(LocalContext.current)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = { prefs.put(ThemeManager.PREF_CIRCULAR_EXPANDED_FOLDERS, it) },
                )
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.folder_shape_circular_expanded_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.folder_shape_circular_expanded_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked, onCheckedChange = null, colors = settingsSwitchColors())
        }
    }
}

@Composable
private fun ShapeOption(shape: IconShapeModel?, selected: Boolean, target: ShapeTarget) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = {
                        LauncherPrefs.get(context).put(target.shapePref, shape?.key.orEmpty())
                    },
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected, onClick = null)
            Text(stringResource(shape?.titleId ?: target.defaultLabel), Modifier.weight(1f))
            ShapeOutline(shape?.pathString ?: defaultPreviewPath(context, target))
        }
    }
}

@Composable
internal fun IconShapeEditorScreen(onBack: () -> Unit, target: ShapeTarget = ShapeTarget.ICON) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val saved = remember(revision, target) { target.customShape(LauncherPrefs.get(context)) }
    var serialized by rememberSaveable {
        mutableStateOf((saved ?: CustomIconShape.DEFAULT).serialize())
    }
    val draft =
        remember(serialized) { CustomIconShape.parse(serialized) ?: CustomIconShape.DEFAULT }
    val canApply = draft.needsApply(saved, target.shapePref.get(context))
    ShapeScaffold(
        stringResource(target.customTitle),
        onBack,
        actions = {
            TextButton(
                enabled = canApply,
                onClick = {
                    LauncherPrefs.get(context)
                        .put(
                            target.customShapePref to draft.serialize(),
                            target.shapePref to CustomIconShape.KEY,
                        )
                    onBack()
                },
            ) {
                Text(stringResource(R.string.icon_shape_apply))
            }
        },
    ) {
        item("preview") { IconShapeAppPreview(draft.pathString, target) }
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright) {
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
        containerColor = MaterialTheme.colorScheme.background,
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

private fun defaultPreviewPath(context: Context, target: ShapeTarget): String {
    if (target == ShapeTarget.ICON) return ""
    return ThemeManager.INSTANCE.get(context).iconState.iconMask
}

/** Shared wallpaper card also used by the icon pack screen. Loading follows the composition. */
@Composable
internal fun SettingsPreviewCard(content: @Composable (ImageView?) -> Unit) {
    val context = LocalContext.current
    var wallpaperView by remember { mutableStateOf<ImageView?>(null) }
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
                    }.also { view -> wallpaperView = view }
                },
                modifier = Modifier.matchParentSize(),
                update = { it.setImageDrawable(wallpaper) },
            )
            content(wallpaperView.takeIf { wallpaper != null })
        }
    }
}

@Composable
private fun IconShapeAppPreview(path: String?, target: ShapeTarget) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val apps by
        produceState<List<AppInfo>>(emptyList(), context, revision) {
            value = loadPreviewApps(context.applicationContext)
        }
    val theme = ThemeManager.INSTANCE.get(context)
    val renderedPath by
        produceState(path, path) {
            withFrameNanos {}
            value = path
        }
    val drawables =
        remember(apps, renderedPath, target, revision) {
            val size = InvariantDeviceProfile.INSTANCE.get(context).iconBitmapSize
            val shape =
                if (apps.isEmpty()) null
                else if (renderedPath == null || target == ShapeTarget.FOLDER)
                    theme.iconShapeData.value
                else
                    GraphicsUtils.generateIconShape(
                        size,
                        pickBestShape(renderedPath.orEmpty()).getPath(size.toFloat()),
                    )
            val previewApps = if (target == ShapeTarget.FOLDER) apps + apps.take(14) else apps
            previewApps.map { app ->
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
    val folderShape =
        remember(renderedPath, target, revision) {
            val editedPath = renderedPath
            if (
                editedPath != null &&
                    (target == ShapeTarget.FOLDER ||
                        IconShapeSettingsActivity.currentShape(context, ShapeTarget.FOLDER) == null)
            )
                pickBestShape(editedPath)
            else theme.folderShape
        }
    val folderColor =
        context.getColor(
            if (context.resources.configuration.isNightModeActive) R.color.folder_preview_dark
            else R.color.folder_preview_light
        )
    val appDrawables = remember(drawables, apps) { drawables.take(apps.size) }
    val folders =
        remember(appDrawables, folderShape, folderColor) {
            val size = InvariantDeviceProfile.INSTANCE.get(context).iconBitmapSize
            List(4) { index ->
                FolderPreviewDrawable(
                    folderShape,
                    appDrawables.drop(4 + index * 4).take(4),
                    folderColor,
                    size,
                )
            }
        }
    val circularExpandedFolders = theme.useCircularExpandedFolders
    val expandedFolders =
        remember(drawables, folderColor, circularExpandedFolders) {
            val size = InvariantDeviceProfile.INSTANCE.get(context).iconBitmapSize
            val shape =
                if (circularExpandedFolders) ShapeDelegate.Circle()
                else ShapeDelegate.RoundedSquare(0.3f)
            List(2) { index ->
                FolderPreviewDrawable(
                    shape,
                    drawables.drop(apps.size + index * 7).take(7),
                    folderColor,
                    size,
                    expanded = true,
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
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val drawable =
                                if (row == 0) appDrawables.getOrNull(column) else folders[column]
                            if (drawable == null) ShapeOutline(path.orEmpty(), Modifier.size(56.dp))
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
                                        it.contentDescription =
                                            if (row == 0) apps[column].title
                                            else context.getString(R.string.folder_shape_title)
                                    },
                                )
                        }
                    }
                }
            }
            if (target == ShapeTarget.FOLDER) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    expandedFolders.forEach { drawable ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AndroidView(
                                factory = {
                                    ImageView(it).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                    }
                                },
                                modifier = Modifier.size(112.dp),
                                update = {
                                    it.setImageDrawable(drawable)
                                    it.contentDescription =
                                        context.getString(R.string.folder_shape_title)
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
internal suspend fun loadPreviewApps(context: Context, count: Int = 20): List<AppInfo> =
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
                            .take(count)
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
