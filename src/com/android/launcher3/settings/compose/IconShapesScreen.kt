/*
 * Copyright (C) 2026 crDroid Android Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.android.launcher3.settings.compose

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_CUSTOM_ICON_SHAPE
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_ICON_SHAPE
import com.android.launcher3.shapes.*
import java.text.NumberFormat

/** Observe both values, including changes made while the editor is a separate activity. */
@Composable
private fun shapeRevision(): Int {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = LauncherPrefs.get(context)
        val listener = LauncherPrefChangeListener { revision++ }
        prefs.addListener(listener, PREF_ICON_SHAPE, PREF_CUSTOM_ICON_SHAPE)
        onDispose { prefs.removeListener(listener, PREF_ICON_SHAPE, PREF_CUSTOM_ICON_SHAPE) }
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
    val saved = remember { CustomIconShape.fromPrefs(LauncherPrefs.get(context)) }
    var serialized by rememberSaveable {
        mutableStateOf((saved ?: CustomIconShape.DEFAULT).serialize())
    }
    val draft =
        remember(serialized) { CustomIconShape.parse(serialized) ?: CustomIconShape.DEFAULT }
    val canApply = draft != (saved ?: CustomIconShape.DEFAULT)
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
                onValueChange = { onChange(corner.copy(size = (it * 100).toInt() / 100f)) },
                valueRange = 0f..1f,
                steps = 99,
                modifier = Modifier.semantics { contentDescription = title },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        LazyColumn(
            Modifier.padding(padding).widthIn(max = 840.dp).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
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

@Composable
private fun IconShapeAppPreview(path: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        ShapeOutline(path, Modifier.size(128.dp))
    }
}
