package com.jervis.ui.audio

/**
 * Cross-platform audio player for WAV playback.
 *
 * Platform implementations:
 * - Desktop (JVM): javax.sound.sampled.Clip
 * - Android/iOS: stub (not yet implemented)
 */
expect class AudioPlayer() {
    fun play(audioData: ByteArray)
    fun stop()
    val isPlaying: Boolean
    fun release()
}
