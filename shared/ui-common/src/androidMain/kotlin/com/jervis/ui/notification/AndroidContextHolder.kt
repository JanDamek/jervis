package com.jervis.ui.notification

import android.app.NotificationManager
import android.content.Context
import com.jervis.ui.audio.RecordingForegroundService
import com.jervis.ui.storage.AudioChunkQueue
import com.jervis.ui.storage.OfflineMeetingStorage
import com.jervis.ui.storage.PendingMessageStorage
import com.jervis.ui.storage.RecordingSessionStorage
import com.jervis.ui.storage.RecordingStateStorage

/**
 * Holds reference to application context for notification services.
 *
 * Must be initialized from MainActivity.onCreate() before any
 * notification operations are performed.
 */
object AndroidContextHolder {
    lateinit var applicationContext: Context
        private set

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        AudioChunkQueue.init(applicationContext)
        PendingMessageStorage.init(applicationContext)
        OfflineMeetingStorage.init(applicationContext) // Legacy — needed for migration
        RecordingStateStorage.init(applicationContext) // Legacy — needed for migration
        RecordingSessionStorage.init(applicationContext)

        // Clean up stale recording notification from previous crash.
        // After crash the foreground service may have been restarted (START_STICKY legacy)
        // or notification may linger. Cancel it on fresh app start — if recording is truly
        // active, MeetingViewModel.startRecording() will recreate it.
        cancelStaleRecordingNotification(applicationContext)
    }

    /**
     * Remove any leftover recording notification from a previous session/crash.
     * Also stops the foreground service if it's somehow still running.
     */
    private fun cancelStaleRecordingNotification(context: Context) {
        try {
            // Cancel the notification directly
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(RecordingForegroundService.NOTIFICATION_ID)
            // Stop the service if running (no-op if not running)
            RecordingForegroundService.stop(context)
        } catch (e: Exception) {
            println("[AndroidContextHolder] Failed to clean stale recording notification: ${e.message}")
        }
    }
}
