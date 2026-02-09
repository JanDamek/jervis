package com.jervis.ui.audio

import com.jervis.ui.notification.AndroidContextHolder

actual class PlatformRecordingService actual constructor() {
    actual fun startBackgroundRecording(title: String) {
        try {
            val context = AndroidContextHolder.applicationContext
            RecordingForegroundService.start(context, title)
        } catch (e: Exception) {
            println("[PlatformRecordingService] Failed to start foreground service: ${e.message}")
        }
    }

    actual fun stopBackgroundRecording() {
        try {
            val context = AndroidContextHolder.applicationContext
            RecordingForegroundService.stop(context)
        } catch (e: Exception) {
            println("[PlatformRecordingService] Failed to stop foreground service: ${e.message}")
        }
    }

    actual fun updateDuration(seconds: Long) {
        try {
            val context = AndroidContextHolder.applicationContext
            RecordingForegroundService.updateNotification(context, seconds)
        } catch (e: Exception) {
            // Silently ignore — notification update is best-effort
        }
    }
}
