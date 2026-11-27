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
import android.graphics.Rect
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import android.util.LruCache
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.celllayout.CellPosMapper
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.function.Consumer
import kotlin.math.min
import kotlin.math.roundToInt

object FolderThumbnailManager {
    private const val TAG = "FolderThumbnailManager"
    private const val DIR = "folder_thumbnails"
    private const val TARGET_SIZE_PX = 512

    private val cache = LruCache<Int, FolderCover>(8)

    private fun files(context: Context, folderId: Int) =
        FolderCoverFiles(File(context.filesDir, DIR), folderId)

    const val EXTRA_FOLDER_ID = "folder_id"
    const val EXTRA_FOLDER_NAME = "folder_name"
    const val EXTRA_HAS_COVER = "has_cover"

    @JvmStatic
    fun startPicker(context: Context, folderInfo: FolderInfo) {
        context.startActivity(
            Intent(context, FolderThumbnailPickerActivity::class.java)
                .putExtra(EXTRA_FOLDER_ID, folderInfo.id)
                .putExtra(EXTRA_FOLDER_NAME, folderInfo.title?.toString())
                .putExtra(EXTRA_HAS_COVER, folderInfo.hasOption(FolderInfo.FLAG_CUSTOM_THUMBNAIL))
        )
    }

    @JvmStatic
    fun loadAsync(context: Context, folderId: Int, callback: Consumer<FolderCover?>) {
        cache.get(folderId)?.let {
            callback.accept(it)
            return
        }
        val app = context.applicationContext
        MODEL_EXECUTOR.execute {
            val cover = cache.get(folderId) ?: runCatching { files(app, folderId).read(app) }
                .onFailure { Log.w(TAG, "Unable to read cover for folder $folderId", it) }
                .getOrNull()
            if (cover != null) cache.put(folderId, cover)
            MAIN_EXECUTOR.execute { callback.accept(cover) }
        }
    }

    fun apply(context: Context, folderId: Int, cover: FolderCover, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        val model = LauncherAppState.getInstance(app).model
        model.loadAsync { data ->
            val folder = data?.itemsIdMap?.get(folderId) as? FolderInfo
            val saved = folder != null && runCatching {
                files(app, folderId).write(cover)
                folder.setOption(
                    FolderInfo.FLAG_CUSTOM_THUMBNAIL, true,
                    model.getWriter(false, CellPosMapper.DEFAULT, null),
                )
                cache.put(folderId, cover)
            }.onFailure { Log.e(TAG, "Unable to apply cover to folder $folderId", it) }.isSuccess
            MAIN_EXECUTOR.execute {
                if (saved) {
                    Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()?.findFolderIcon(folderId)
                        ?.onCustomThumbnailChanged(true)
                }
                onDone(saved)
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
        val app = context.applicationContext
        MODEL_EXECUTOR.execute {
            cache.remove(folderId)
            files(app, folderId).delete()
        }
    }

    fun decodeSquare(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = min(1.0, TARGET_SIZE_PX.toDouble() / min(info.size.width, info.size.height))
            val width = (info.size.width * scale).roundToInt()
            val height = (info.size.height * scale).roundToInt()
            decoder.setTargetSize(width, height)
            val side = min(width, height)
            val left = (width - side) / 2
            val top = (height - side) / 2
            decoder.crop = Rect(left, top, left + side, top + side)
        }
    }
}

internal class FolderCoverFiles(directory: File, id: Int) {
    private val image = AtomicFile(File(directory, "$id.png"))
    private val glyph = AtomicFile(File(directory, "$id.glyph"))

    fun read(context: Context): FolderCover? {
        if (glyph.exists()) {
            val value = glyph.readFully().toString(Charsets.UTF_8)
            return FolderCover.fromGlyph(context, value.removePrefix(FOLDER_BACKGROUND_PREFIX))
                ?.withFolderBackground(value.startsWith(FOLDER_BACKGROUND_PREFIX))
        }
        if (!image.exists()) return null
        return image.openRead().use { BitmapFactory.decodeStream(it) }?.let { FolderCover.photo(it) }
    }

    fun write(cover: FolderCover) {
        val key = cover.glyph
        if (key != null) {
            val value = if (cover.useFolderBackground) FOLDER_BACKGROUND_PREFIX + key else key
            atomicWrite(glyph) { it.write(value.toByteArray(Charsets.UTF_8)) }
            image.delete()
        } else {
            atomicWrite(image) {
                if (!cover.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("Could not encode folder image")
                }
            }
            glyph.delete()
            if (glyph.exists()) throw IOException("Could not replace folder glyph")
        }
    }

    fun delete() {
        glyph.delete()
        image.delete()
    }

    private inline fun atomicWrite(file: AtomicFile, block: (FileOutputStream) -> Unit) {
        val stream = file.startWrite()
        try {
            block(stream)
            file.finishWrite(stream)
        } catch (e: Throwable) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val FOLDER_BACKGROUND_PREFIX = "folder-background:"
    }
}
