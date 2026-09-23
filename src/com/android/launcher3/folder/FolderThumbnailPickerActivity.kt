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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

class FolderThumbnailPickerActivity : Activity() {
    private var folderId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderId = intent.getIntExtra(FolderThumbnailManager.EXTRA_FOLDER_ID, -1)
        if (folderId < 0) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val pick =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
            try {
                startActivityForResult(pick, REQUEST_PICK_IMAGE)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "No document picker available", e)
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && uri != null) {
            FolderThumbnailManager.importFromUri(applicationContext, folderId, uri) { finish() }
        } else {
            finish()
        }
    }

    private companion object {
        const val TAG = "FolderThumbnailPicker"
        const val REQUEST_PICK_IMAGE = 1
    }
}
