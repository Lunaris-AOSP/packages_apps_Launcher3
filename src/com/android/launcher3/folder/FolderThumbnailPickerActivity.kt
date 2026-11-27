/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.folder

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.launcher3.settings.ComposeSettingsActivity
import com.android.launcher3.settings.compose.HomeSettingsTheme

class FolderThumbnailPickerActivity : ComposeSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderId = intent.getIntExtra(FolderThumbnailManager.EXTRA_FOLDER_ID, -1)
        if (folderId < 0) {
            finish()
            return
        }
        val state = ViewModelProvider(this)[FolderCoverSaveState::class.java]
        setContent {
            HomeSettingsTheme {
                FolderCoverPicker(
                    folderId = folderId,
                    folderName = intent.getStringExtra(FolderThumbnailManager.EXTRA_FOLDER_NAME),
                    hasCover = intent.getBooleanExtra(FolderThumbnailManager.EXTRA_HAS_COVER, false),
                    saveState = state,
                    onBack = ::finish,
                )
            }
        }
    }
}

class FolderCoverSaveState : ViewModel() {
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun apply(context: Context, folderId: Int, cover: FolderCover) {
        if (saving || saved) return
        saving = true
        failed = false
        FolderThumbnailManager.apply(context, folderId, cover) { success ->
            saving = false
            saved = success
            failed = !success
        }
    }
}
