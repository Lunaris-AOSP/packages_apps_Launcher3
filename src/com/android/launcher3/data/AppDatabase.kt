package com.android.launcher3.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase

import com.android.launcher3.data.wallpaper.Wallpaper
import com.android.launcher3.data.wallpaper.service.WallpaperDao
import com.android.launcher3.util.override.MainThreadInitializedObject

import kotlinx.coroutines.runBlocking

@Database(entities = [Wallpaper::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wallpaperDao(): WallpaperDao

    suspend fun checkpoint() {
        wallpaperDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
    }

    fun checkpointSync() {
        runBlocking {
            checkpoint()
        }
    }

    companion object {

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Wallpapers ADD COLUMN originalImagePath TEXT")
                db.execSQL("ALTER TABLE Wallpapers ADD COLUMN cropHints TEXT")
            }
        }

        @JvmField
        val INSTANCE = MainThreadInitializedObject { context ->
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "preferences",
            ).addMigrations(MIGRATION_1_2).build()
        }

    }
}
