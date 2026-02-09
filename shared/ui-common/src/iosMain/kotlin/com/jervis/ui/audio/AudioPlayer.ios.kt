package com.jervis.ui.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual class AudioPlayer actual constructor() {

    private var player: AVAudioPlayer? = null

    actual fun play(audioData: ByteArray) {
        stop()
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker,
                error = null,
            )
            session.setActive(true, error = null)

            val nsData = audioData.usePinned { pinned ->
                NSData.create(
                    bytes = pinned.addressOf(0),
                    length = audioData.size.toULong(),
                )
            }

            val audioPlayer = AVAudioPlayer(data = nsData, error = null)
            audioPlayer.prepareToPlay()
            audioPlayer.play()
            player = audioPlayer
        } catch (e: Exception) {
            player = null
        }
    }

    actual fun stop() {
        player?.stop()
        player = null
    }

    actual val isPlaying: Boolean
        get() = player?.isPlaying() == true

    actual fun release() {
        stop()
    }
}
