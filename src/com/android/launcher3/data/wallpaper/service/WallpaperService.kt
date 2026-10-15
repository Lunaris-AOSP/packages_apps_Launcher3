package com.android.launcher3.data.wallpaper.service

import android.app.WallpaperManager
import android.app.WallpaperManager.FLAG_LOCK
import android.app.WallpaperManager.FLAG_SYSTEM
import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import android.util.SparseArray

import androidx.room.withTransaction

import com.android.launcher3.LauncherPrefs
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.data.AppDatabase
import com.android.launcher3.data.wallpaper.Wallpaper
import com.android.launcher3.util.ApiWrapper
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@LauncherAppSingleton
class WallpaperService @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val rankMutex = Mutex()

    private val roomDb by lazy { AppDatabase.INSTANCE.get(context) }
    private val dao by lazy { roomDb.wallpaperDao() }
    private val apiWrapper by lazy { ApiWrapper.INSTANCE.get(context) }

    @Volatile
    private var lastHandledWallpaperId: Int = -1

    suspend fun saveWallpaper(wallpaperManager: WallpaperManager) {
        runCatching {
            withContext(Dispatchers.IO) {
                rankMutex.withLock {
                    if (wallpaperManager.wallpaperInfo != null) return@withLock
                    val wallpaperId = wallpaperManager.getWallpaperId(FLAG_SYSTEM)
                    if (wallpaperId == lastHandledWallpaperId) return@withLock

                    val originalBytes = apiWrapper.getWallpaperSource(wallpaperManager)?.let {
                        ParcelFileDescriptor.AutoCloseInputStream(it).use { stream ->
                            stream.readBytes()
                        }
                    } ?: return@withLock
                    val bytes = wallpaperManager.getWallpaperFile(FLAG_SYSTEM)?.let {
                        ParcelFileDescriptor.AutoCloseInputStream(it).use { stream ->
                            stream.readBytes()
                        }
                    } ?: return@withLock
                    val cropHints = encodeCropHints(apiWrapper.getWallpaperCropHints(wallpaperManager))
                    if (wallpaperManager.getWallpaperId(FLAG_SYSTEM) != wallpaperId) return@withLock

                    saveWallpaperLocked(bytes, originalBytes, cropHints)
                    lastHandledWallpaperId = wallpaperId
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e("WallpaperService", "Error reading current wallpaper: ${it.message}", it)
        }
    }

    private suspend fun saveWallpaperLocked(
        imageData: ByteArray,
        originalData: ByteArray,
        cropHints: String,
    ) {
        val timestamp = System.currentTimeMillis()
        val checksum = calculateChecksum(originalData, cropHints)

        val existingWallpapers = dao.getTopWallpapers()
        existingWallpapers.firstOrNull { it.checksum == checksum }?.let {
            promoteToRank0(it.id, timestamp)
            return
        }

        val imagePath = saveImageToAppStorage(imageData, checksum)
        val originalImagePath = saveImageToAppStorage(originalData, "${checksum}_original")
        val legacyChecksum = calculateChecksum(imageData)
        val legacyWallpaper = existingWallpapers.firstOrNull {
            it.originalImagePath == null && it.checksum == legacyChecksum
        }
        if (legacyWallpaper != null) {
            roomDb.withTransaction {
                dao.update(legacyWallpaper.copy(
                    imagePath = imagePath,
                    originalImagePath = originalImagePath,
                    cropHints = cropHints,
                    checksum = checksum,
                ))
                promoteToRank0(legacyWallpaper.id, timestamp)
            }
            deleteWallpaperFile(legacyWallpaper.imagePath)
            return
        }

        val toRemove = if (existingWallpapers.size >= 4) {
            existingWallpapers.minByOrNull { it.timestamp }
        } else null
        roomDb.withTransaction {
            toRemove?.let { dao.deleteWallpaper(it.id) }
            dao.bumpAllRanks()
            dao.insert(Wallpaper(
                imagePath = imagePath,
                originalImagePath = originalImagePath,
                cropHints = cropHints,
                rank = 0,
                timestamp = timestamp,
                checksum = checksum,
            ))
        }
        toRemove?.let {
            deleteWallpaperFile(it.imagePath)
            it.originalImagePath?.let { path -> deleteWallpaperFile(path) }
        }
    }

    suspend fun applyWallpaper(
        wallpaper: Wallpaper,
        wallpaperManager: WallpaperManager,
    ): Boolean = withContext(Dispatchers.IO) {
        rankMutex.withLock {
            runCatching {
                val savedWallpaper = dao.getById(wallpaper.id) ?: return@runCatching false
                val crops = decodeCropHints(savedWallpaper.cropHints)
                val which = if (LauncherPrefs.WALLPAPER_CAROUSEL_LOCKSCREEN.get(context)) {
                    FLAG_SYSTEM or FLAG_LOCK
                } else FLAG_SYSTEM
                val imagePath = savedWallpaper.originalImagePath ?: savedWallpaper.imagePath
                val newId = FileInputStream(imagePath).use {
                    apiWrapper.setWallpaperWithCrops(wallpaperManager, it, crops, which)
                }
                if (newId == 0) return@runCatching false
                promoteToRank0(wallpaper.id, System.currentTimeMillis())
                lastHandledWallpaperId = newId
                true
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e("WallpaperService", "Error applying wallpaper: ${it.message}", it)
            }.getOrDefault(false)
        }
    }

    private fun encodeCropHints(crops: SparseArray<Rect>): String {
        return JSONObject().apply {
            for (index in 0 until crops.size()) {
                put(crops.keyAt(index).toString(), crops.valueAt(index).flattenToString())
            }
        }.toString()
    }

    private fun decodeCropHints(value: String?): SparseArray<Rect> {
        val crops = SparseArray<Rect>()
        if (value == null) return crops
        val json = JSONObject(value)
        for (key in json.keys()) {
            val rect = requireNotNull(Rect.unflattenFromString(json.getString(key)))
            require(!rect.isEmpty)
            crops.put(key.toInt(), rect)
        }
        return crops
    }

    private fun calculateChecksum(imageData: ByteArray, cropHints: String = ""): String {
        return MessageDigest.getInstance("MD5")
            .apply { update(cropHints.toByteArray(Charsets.UTF_8)) }
            .digest(imageData)
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun promoteToRank0(id: Long, timestamp: Long) {
        roomDb.withTransaction {
            dao.updateWallpaper(id, rank = 0, timestamp = timestamp)

            val others = dao.getTopWallpapersExcluding(excludeId = id)
            others.forEachIndexed { index, w ->
                dao.setRankOnly(w.id, rank = index + 1)
            }
        }
    }

    suspend fun getTopWallpapers(): List<Wallpaper> = withContext(Dispatchers.IO) {
        dao.getTopWallpapers()
    }

    fun getTopWallpapersBlocking(): List<Wallpaper> {
        return runBlocking {
            withContext(Dispatchers.IO) { dao.getTopWallpapers() }
        }
    }

    private fun deleteWallpaperFile(imagePath: String) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) file.delete()
        }
    }

    private fun saveImageToAppStorage(imageData: ByteArray, checksum: String): String {
        val storageDir = File(context.filesDir, "wallpapers").apply { if (!exists()) mkdirs() }
        val imageFile = File(storageDir, "wallpaper_$checksum.jpg")
        if (!imageFile.exists()) {
            val atomicFile = AtomicFile(imageFile)
            val stream = atomicFile.startWrite()
            try {
                stream.write(imageData)
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        }
        return imageFile.absolutePath
    }

    override fun close() {
        // Backed by an app-scoped DB singleton; nothing to release.
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWallpaperService)
    }
}
