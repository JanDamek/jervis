package com.jervis.ui.audio

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Bridge between platform-specific recording controls (Android notification, iOS lock screen)
 * and the shared MeetingViewModel. Both platforms emit on [stopRequested] when the user
 * taps stop from outside the app UI.
 */
object RecordingServiceBridge {
    val stopRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
