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
import platform.Foundation.NSTimer
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual class AudioPlayer actual constructor() {

    private var player: AVAudioPlayer? = null
    private var rangeTimer: NSTimer? = null

    private fun setupSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)
    }

    private fun createPlayer(audioData: ByteArray): AVAudioPlayer? {
        val nsData = audioData.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = audioData.size.toULong())
        }
        return AVAudioPlayer(data = nsData, error = null)
    }

    actual fun play(audioData: ByteArray) {
        stop()
        try {
            setupSession()
            val audioPlayer = createPlayer(audioData) ?: return
            audioPlayer.prepareToPlay()
            audioPlayer.play()
            player = audioPlayer
        } catch (e: Exception) {
            player = null
        }
    }

    actual fun playAsync(audioData: ByteArray) {
        // AVAudioPlayer is already non-blocking
        play(audioData)
    }

    actual fun playRange(audioData: ByteArray, startSec: Double, endSec: Double) {
        stop()
        try {
            setupSession()
            val audioPlayer = createPlayer(audioData) ?: return
            audioPlayer.prepareToPlay()
            audioPlayer.currentTime = startSec
            audioPlayer.play()
            player = audioPlayer
            val duration = endSec - startSec
            rangeTimer = NSTimer.scheduledTimerWithTimeInterval(
                interval = duration, repeats = false,
            ) { _ ->
                if (player === audioPlayer) stop()
            }
        } catch (e: Exception) {
            player = null
        }
    }

    actual fun stop() {
        rangeTimer?.invalidate()
        rangeTimer = null
        player?.stop()
        player = null
    }

    actual val isPlaying: Boolean
        get() = player?.isPlaying() == true

    actual val positionSec: Double
        get() = player?.currentTime ?: 0.0

    actual val durationSec: Double
        get() = player?.duration ?: 0.0

    actual fun seekTo(positionSec: Double) {
        player?.currentTime = positionSec
        if (player?.isPlaying() != true) player?.play()
    }

    actual fun release() { stop() }

    // ── PCM Streaming (no-op on iOS) ────
    actual fun startStream(sampleRate: Int, sampleSizeInBits: Int, channels: Int) {}
    actual fun streamPcm(pcmData: ByteArray) {}
    actual fun finishStream() {}
    actual fun stopStream() {}
}
