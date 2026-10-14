/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.launcher3.util

import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionLegacyHelper
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MediaSessionManagerHelper private constructor(private val context: Context) {

    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}
        fun onPlaybackStateChanged() {}
    }

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectJob: Job? = null

    private var lastSavedPackageName: String? = null
    private val mediaSessionManager: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
    private var activeController: MediaController? = null
    private val listeners = mutableSetOf<MediaMetadataListener>()
    private val sessionControllers = mutableListOf<MediaController>()
    private val handler = Handler(Looper.getMainLooper())
    private var observingSessions = false
    private var observationGeneration = 0
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        if (observingSessions) updateSessions(it.orEmpty())
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateActiveController()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateActiveController()
        }
    }

    init {
        lastSavedPackageName = Settings.System.getString(
            context.contentResolver,
            "media_session_last_package_name"
        )
    }

    private fun updateSessions(controllers: List<MediaController>) {
        observationGeneration++
        sessionControllers.forEach { it.unregisterCallback(mediaControllerCallback) }
        sessionControllers.clear()
        sessionControllers.addAll(controllers)
        sessionControllers.forEach { it.registerCallback(mediaControllerCallback, handler) }
        updateActiveController()
    }

    private fun updateActiveController() {
        var localController: MediaController? = null
        val remoteSessions = mutableSetOf<String>()

        sessionControllers
            .filter { controller ->
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                controller.playbackInfo != null
            }
            .forEach { controller ->
                when (controller.playbackInfo?.playbackType) {
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> {
                        remoteSessions.add(controller.packageName)
                        if (localController?.packageName == controller.packageName) {
                            localController = null
                        }
                    }
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> {
                        if (!remoteSessions.contains(controller.packageName)) {
                            localController = localController ?: controller
                        }
                    }
                }
            }
        activeController = localController
        _mediaMetadata.value = localController?.metadata
        _playbackState.value = localController?.playbackState
        saveLastNonNullPackageName()
    }

    private fun startObservingSessions() {
        if (observingSessions) return
        observingSessions = true
        val generation = ++observationGeneration
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            Executors.UI_HELPER_EXECUTOR.execute {
                val controllers = try {
                    mediaSessionManager.getActiveSessions(null)
                } catch (e: SecurityException) {
                    Log.w("LauncherMedia", "Cannot read media sessions", e)
                    emptyList()
                }
                handler.post {
                    if (observingSessions && observationGeneration == generation) {
                        updateSessions(controllers)
                    }
                }
            }
        } catch (e: SecurityException) {
            observingSessions = false
            Log.w("LauncherMedia", "Cannot observe media sessions", e)
        }
    }

    private fun stopObservingSessions() {
        if (observingSessions) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        observingSessions = false
        observationGeneration++
        sessionControllers.forEach { it.unregisterCallback(mediaControllerCallback) }
        sessionControllers.clear()
        activeController = null
        _mediaMetadata.value = null
        _playbackState.value = null
    }

    fun addMediaMetadataListener(listener: MediaMetadataListener) {
        if (listeners.add(listener) && listeners.size == 1) {
            startCollecting()
        }
        listener.onMediaMetadataChanged()
        listener.onPlaybackStateChanged()
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopCollecting()
        }
    }

    private fun startCollecting() {
        startObservingSessions()
        collectJob = scope.launch {
            launch { mediaMetadata.collect { notifyListeners { onMediaMetadataChanged() } } }
            launch { playbackState.collect { notifyListeners { onPlaybackStateChanged() } } }
        }
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        stopObservingSessions()
    }

    private fun notifyListeners(action: MediaMetadataListener.() -> Unit) {
        listeners.toList().forEach { it.action() }
    }

    fun seekTo(time: Long) {
        activeController?.transportControls?.seekTo(time)
    }

    fun getTotalDuration() = mediaMetadata.value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun saveLastNonNullPackageName() {
        activeController?.packageName?.takeIf { it.isNotEmpty() }?.let { pkg ->
            if (pkg != lastSavedPackageName) {
                Settings.System.putString(
                    context.contentResolver,
                    "media_session_last_package_name",
                    pkg
                )
                lastSavedPackageName = pkg
            }
        }
    }

    fun getMediaBitmap(): Bitmap? = mediaMetadata.value?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: 
        it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: 
        it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    fun getCurrentMediaMetadata(): MediaMetadata? {
        return mediaMetadata.value
    }

    fun getMediaAppIcon(): Drawable? {
        val packageName = activeController?.packageName ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isMediaControllerAvailable() = activeController?.packageName?.isNotEmpty() ?: false

    fun isMediaPlaying() = playbackState.value?.state == PlaybackState.STATE_PLAYING

    fun getMediaControllerPlaybackState(): PlaybackState? {
        return activeController?.playbackState ?: null
    }

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(context) ?: return
        var event = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keycode,
            0
        )
        helper.sendMediaButtonEvent(event, true)
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP)
        helper.sendMediaButtonEvent(event, true)
    }

    fun prevSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun nextSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun toggleMediaPlaybackState() {
        if (isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun launchMediaApp() {
        lastSavedPackageName?.takeIf { it.isNotEmpty() }?.let {
            launchMediaPlayerApp(it)
        }
    }

    fun launchMediaPlayerApp(packageName: String) {
        if (packageName.isNotEmpty()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let { intent ->
                context.startActivity(intent)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MediaSessionManagerHelper? = null

        fun getInstance(context: Context): MediaSessionManagerHelper =
            instance ?: synchronized(this) {
                instance ?: MediaSessionManagerHelper(context.applicationContext).also { instance = it }
            }
    }
}
