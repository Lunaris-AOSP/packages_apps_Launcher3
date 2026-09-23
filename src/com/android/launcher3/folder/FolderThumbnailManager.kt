/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.launcher3.folder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR
import java.io.File
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FolderThumbnailManager {
    private const val TAG = "FolderThumbnailManager"
    private const val DIR = "folder_thumbnails"
    private const val TARGET_SIZE_PX = 512

    private val cache = LruCache<Int, Bitmap>(8)

    private fun fileFor(context: Context, folderId: Int) =
        File(File(context.filesDir, DIR), "$folderId.png")

    const val EXTRA_FOLDER_ID = "folder_id"

    @JvmStatic
    fun startPicker(context: Context, folderInfo: FolderInfo) {
        context.startActivity(
            Intent(context, FolderThumbnailPickerActivity::class.java)
                .putExtra(EXTRA_FOLDER_ID, folderInfo.id)
        )
    }

    @JvmStatic
    fun loadAsync(context: Context, folderId: Int, callback: Consumer<Bitmap?>) {
        cache.get(folderId)?.let {
            callback.accept(it)
            return
        }
        val appContext = context.applicationContext
        THREAD_POOL_EXECUTOR.execute {
            val bitmap =
                fileFor(appContext, folderId).takeIf { it.exists() }?.let {
                    BitmapFactory.decodeFile(it.path)
                }
            bitmap?.let { cache.put(folderId, it) }
            MAIN_EXECUTOR.execute { callback.accept(bitmap) }
        }
    }

    @JvmStatic
    fun importFromUri(context: Context, folderId: Int, uri: Uri, onDone: Runnable) {
        val appContext = context.applicationContext
        THREAD_POOL_EXECUTOR.execute {
            val bitmap =
                runCatching { decodeSquare(appContext, uri) }
                    .onFailure { Log.e(TAG, "Failed to decode $uri", it) }
                    .getOrNull()
            val saved = bitmap != null && save(appContext, folderId, bitmap)
            MAIN_EXECUTOR.execute {
                val launcher: Launcher? = Launcher.ACTIVITY_TRACKER.getCreatedContext()
                if (!saved || launcher == null) {
                    Toast.makeText(appContext, R.string.folder_thumbnail_failed, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    bitmap?.let { cache.put(folderId, it) }
                    val icon = launcher.findFolderIcon(folderId)
                    icon?.mInfo?.setOption(
                        FolderInfo.FLAG_CUSTOM_THUMBNAIL,
                        true,
                        launcher.modelWriter,
                    )
                    icon?.onCustomThumbnailChanged(true)
                }
                onDone.run()
            }
        }
    }

    @JvmStatic
    fun reset(launcher: Launcher, folderInfo: FolderInfo) {
        delete(launcher, folderInfo.id)
        folderInfo.setOption(FolderInfo.FLAG_CUSTOM_THUMBNAIL, false, launcher.modelWriter)
        launcher.findFolderIcon(folderInfo.id)?.onCustomThumbnailChanged(true)
    }

    @JvmStatic
    fun delete(context: Context, folderId: Int) {
        cache.remove(folderId)
        val file = fileFor(context, folderId)
        THREAD_POOL_EXECUTOR.execute { file.delete() }
    }

    private fun decodeSquare(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val shortSide = min(info.size.width, info.size.height)
                if (shortSide > TARGET_SIZE_PX) {
                    val scale = TARGET_SIZE_PX.toFloat() / shortSide
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
            }
        val side = min(decoded.width, decoded.height)
        return Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
    }

    private fun save(context: Context, folderId: Int, bitmap: Bitmap): Boolean {
        val file = fileFor(context, folderId)
        return runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            .onFailure { Log.e(TAG, "Failed to save thumbnail", it) }
            .getOrDefault(false)
    }
}
