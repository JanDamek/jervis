package com.jervis.ui.audio

import platform.CoreGraphics.CGSizeMake
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

actual class PlatformRecordingService actual constructor() {

    private val artwork: MPMediaItemArtwork? by lazy {
        // Load the app icon from the asset catalog for lock screen display
        val image = UIImage.imageNamed("AppIcon") ?: UIImage.imageNamed("ic_launcher")
        image?.let { img ->
            MPMediaItemArtwork(boundsSize = CGSizeMake(600.0, 600.0)) { _ -> img }
        }
    }

    actual fun startBackgroundRecording(title: String) {
        val info = mutableMapOf<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = title
        info[MPMediaItemPropertyArtist] = "Jervis"
        info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
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
