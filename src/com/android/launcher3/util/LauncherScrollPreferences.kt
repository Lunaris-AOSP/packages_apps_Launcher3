/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs

object LauncherScrollPreferences {
    private var current by mutableStateOf(SpringScrollPhysics.DEFAULT)
    private var listener: LauncherPrefChangeListener? = null

    @JvmStatic
    fun get(context: Context): SpringScrollPhysics {
        if (listener == null) {
            val prefs = LauncherPrefs.get(context.applicationContext)
            val items = arrayOf(
                LauncherPrefs.OVERSCROLL_STIFFNESS,
                LauncherPrefs.OVERSCROLL_DAMPING_RATIO,
                LauncherPrefs.OVERSCROLL_MAX_STRETCH,
                LauncherPrefs.OVERSCROLL_VELOCITY_SCALE,
            )
            val keys = items.map { it.sharedPrefKey }.toSet()
            fun read() {
                current = SpringScrollPhysics(
                    true,
                    prefs.get(LauncherPrefs.OVERSCROLL_STIFFNESS).coerceIn(50, 1000).toFloat(),
                    prefs.get(LauncherPrefs.OVERSCROLL_DAMPING_RATIO).coerceIn(10, 100) / 100f,
                    prefs.get(LauncherPrefs.OVERSCROLL_MAX_STRETCH).coerceIn(5, 40) / 100f,
                    prefs.get(LauncherPrefs.OVERSCROLL_VELOCITY_SCALE).coerceIn(10, 200) / 100f,
                )
            }
            val changeListener = LauncherPrefChangeListener { key ->
                if (key == null || key in keys) read()
            }
            listener = changeListener
            prefs.addListener(changeListener, *items)
            read()
        }
        return current
    }
}
