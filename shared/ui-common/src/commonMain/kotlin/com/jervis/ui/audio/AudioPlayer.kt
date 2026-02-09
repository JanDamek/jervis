package com.jervis.ui.audio

/**
 * Cross-platform audio player for WAV playback.
 *
 * Platform implementations:
 * - Desktop (JVM): javax.sound.sampled.Clip
 * - Android: MediaPlayer
 * - iOS: AVAudioPlayer
 */
expect class AudioPlayer() {
    fun play(audioData: ByteArray)
    /** Play a range of audio from [startSec] to [endSec] seconds. */
    fun playRange(audioData: ByteArray, startSec: Double, endSec: Double)
    fun stop()
    val isPlaying: Boolean
    fun release()
}
