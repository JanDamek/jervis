package com.jervis.ui.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

/**
 * Receives the stop action from the recording notification.
 * Emits on [RecordingServiceBridge.stopRequested] so MeetingViewModel can stop recording.
 */
class RecordingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == RecordingForegroundService.ACTION_STOP) {
            RecordingServiceBridge.stopRequested.tryEmit(Unit)
        }
    }
}
