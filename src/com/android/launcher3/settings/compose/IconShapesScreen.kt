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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_ICON_SHAPE
import com.android.launcher3.shapes.*

/** Observe shape changes while the picker is open. */
@Composable
private fun shapeRevision(): Int {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = LauncherPrefs.get(context)
        val listener = LauncherPrefChangeListener { revision++ }
        prefs.addListener(listener, PREF_ICON_SHAPE)
        onDispose { prefs.removeListener(listener, PREF_ICON_SHAPE) }
    }
    return revision
}

@Composable
internal fun IconShapesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision = shapeRevision()
    val current = remember(revision) { IconShapeSettingsActivity.currentShape(context) }
    ShapeScaffold(stringResource(R.string.icon_shape_title), onBack) {
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
