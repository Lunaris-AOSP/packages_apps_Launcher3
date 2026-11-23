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
import com.android.launcher3.settings.ComposeSettingsActivity
import com.android.launcher3.settings.compose.HomeSettingsTheme
import com.android.launcher3.settings.compose.IconShapeEditorScreen
import com.android.launcher3.settings.compose.IconShapesScreen

/** Hosts the picker and editor using the launcher's Compose settings theme. */
class IconShapeSettingsActivity : ComposeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isEditor = intent.getBooleanExtra(EXTRA_EDITOR, false)
        val target = ShapeTarget.fromName(intent.getStringExtra(EXTRA_TARGET))
        setTitle(if (isEditor) target.customTitle else target.title)
        setContent {
            HomeSettingsTheme {
                if (isEditor) IconShapeEditorScreen(::finish, target)
                else IconShapesScreen(::finish, target)
            }
        }
    }

    companion object {
        private const val EXTRA_EDITOR = "icon_shape_editor"

        const val EXTRA_TARGET = "shape_target"

        @JvmStatic
        fun createIntent(context: Context, target: ShapeTarget): Intent =
            Intent(context, IconShapeSettingsActivity::class.java)
                .putExtra(EXTRA_TARGET, target.name)

        fun createEditorIntent(context: Context, target: ShapeTarget = ShapeTarget.ICON): Intent =
            createIntent(context, target).putExtra(EXTRA_EDITOR, true)

        internal fun currentShape(
            context: Context,
            target: ShapeTarget = ShapeTarget.ICON,
        ): IconShapeModel? =
            when (val key = target.shapePref.get(context)) {
                CustomIconShape.KEY -> target.customShape(LauncherPrefs.get(context))?.toModel()
                else -> ShapesProvider.iconShapes.firstOrNull { it.key == key }
            }

        @JvmStatic
        @JvmOverloads
        fun getCurrentShapeLabel(context: Context, target: ShapeTarget = ShapeTarget.ICON): String =
            context.getString(currentShape(context, target)?.titleId ?: target.defaultLabel)
    }
}
