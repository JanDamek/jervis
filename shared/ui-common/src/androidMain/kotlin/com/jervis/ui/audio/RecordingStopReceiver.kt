package com.jervis.ui.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the stop action from the recording notification.
 *
 * Two-pronged approach:
 * 1. Emit on [RecordingServiceBridge.stopRequested] so MeetingViewModel can stop recording gracefully.
 * 2. Stop the foreground service directly — ensures notification is dismissed even if
 *    MeetingViewModel doesn't exist (e.g., after crash, service was restored but app wasn't).
 */
class RecordingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == RecordingForegroundService.ACTION_STOP && context != null) {
            // Signal MeetingViewModel (if alive) to stop recording gracefully
            RecordingServiceBridge.stopRequested.tryEmit(Unit)
            // Also stop the foreground service directly — guarantees notification dismissal
            RecordingForegroundService.stop(context)
        }
    }
}
