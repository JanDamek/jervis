package com.jervis.ui.audio

actual class PlatformRecordingService actual constructor() {
    actual fun startBackgroundRecording(title: String) {
        // No-op on desktop
    }

    actual fun stopBackgroundRecording() {
        // No-op on desktop
    }

    actual fun updateDuration(seconds: Long) {
        // No-op on desktop
    }
}
