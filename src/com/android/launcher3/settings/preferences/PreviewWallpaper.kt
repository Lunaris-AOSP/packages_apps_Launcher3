/*
 * Copyright (C) 2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.settings.preferences

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.WorkerThread
import kotlin.math.roundToInt

/** Loads the home screen wallpaper for use as a backdrop behind settings previews. */
object PreviewWallpaper {

    private const val TAG = "PreviewWallpaper"
    private const val MAX_SIZE = 720

    /** Returns the wallpaper scaled down, since previews only show a snippet, or null. */
    @JvmStatic
    @WorkerThread
    fun load(context: Context): Drawable? {
        return runCatching {
                val drawable =
                    WallpaperManager.getInstance(context).drawable ?: return@runCatching null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@runCatching drawable
                val longestSide = maxOf(bitmap.width, bitmap.height)
                if (longestSide <= MAX_SIZE) return@runCatching drawable
                val scale = MAX_SIZE.toFloat() / longestSide
                val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                BitmapDrawable(
                    context.resources,
                    Bitmap.createScaledBitmap(bitmap, width, height, true),
                )
            }
            .onFailure { Log.w(TAG, "Unable to read wallpaper preview", it) }
            .getOrNull()
    }
}
