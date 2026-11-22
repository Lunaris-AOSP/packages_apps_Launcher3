/*
 * Copyright (C) 2026 crDroid Android Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.shapes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.graphics.ThemeManager.Companion.PREF_ICON_SHAPE
import com.android.launcher3.settings.ComposeSettingsActivity
import com.android.launcher3.settings.compose.HomeSettingsTheme
import com.android.launcher3.settings.compose.IconShapeEditorScreen
import com.android.launcher3.settings.compose.IconShapesScreen

/** Hosts the picker and editor using the launcher's Compose settings theme. */
class IconShapeSettingsActivity : ComposeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isEditor = intent.getBooleanExtra(EXTRA_EDITOR, false)
        if (isEditor) setTitle(R.string.icon_shape_custom_title)
        setContent {
            HomeSettingsTheme {
                if (isEditor) IconShapeEditorScreen(::finish) else IconShapesScreen(::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_EDITOR = "icon_shape_editor"

        fun createEditorIntent(context: Context): Intent =
            Intent(context, IconShapeSettingsActivity::class.java).putExtra(EXTRA_EDITOR, true)

        internal fun currentShape(context: Context): IconShapeModel? =
            when (val key = PREF_ICON_SHAPE.get(context)) {
                CustomIconShape.KEY ->
                    CustomIconShape.fromPrefs(LauncherPrefs.get(context))?.toModel()
                else -> ShapesProvider.iconShapes.firstOrNull { it.key == key }
            }

        @JvmStatic
        fun getCurrentShapeLabel(context: Context): String =
            context.getString(currentShape(context)?.titleId ?: R.string.icon_shape_default_label)
    }
}
