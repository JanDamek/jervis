package com.jervis.ui.audio

import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

actual class PlatformRecordingService actual constructor() {

    actual fun startBackgroundRecording(title: String) {
        val info = mutableMapOf<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = title
        info[MPMediaItemPropertyArtist] = "Jervis - Nahravani"
        info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info

        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
        commandCenter.pauseCommand.enabled = true
        commandCenter.pauseCommand.addTargetWithHandler { _ ->
            RecordingServiceBridge.stopRequested.tryEmit(Unit)
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.playCommand.enabled = false
    }

    actual fun stopBackgroundRecording() {
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
        commandCenter.pauseCommand.removeTarget(null)
        commandCenter.pauseCommand.enabled = false
    }

    actual fun updateDuration(seconds: Long) {
        val info = MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo?.toMutableMap() ?: return
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = seconds.toDouble()
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }
}
