package com.jervis.ui.audio

/**
 * Platform-specific background recording support.
 * - Android: foreground service with persistent notification
 * - iOS: MPNowPlayingInfoCenter for lock screen controls
 * - Desktop/JVM: no-op
 */
expect class PlatformRecordingService() {
    fun startBackgroundRecording(title: String)
    fun stopBackgroundRecording()
    fun updateDuration(seconds: Long)
}
