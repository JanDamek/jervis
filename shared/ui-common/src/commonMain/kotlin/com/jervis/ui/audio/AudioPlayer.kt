package com.jervis.ui.audio

/**
 * Cross-platform audio player for WAV playback and PCM streaming.
 *
 * Platform implementations:
 * - Desktop (JVM): javax.sound.sampled.Clip + SourceDataLine (streaming)
 * - Android: MediaPlayer (streaming: no-op)
 * - iOS: AVAudioPlayer (streaming: no-op)
 */
expect class AudioPlayer() {
    fun play(audioData: ByteArray)
    /** Play a range of audio from [startSec] to [endSec] seconds. */
    fun playRange(audioData: ByteArray, startSec: Double, endSec: Double)
    fun stop()
    val isPlaying: Boolean
    fun release()

    // ── PCM Streaming (for TTS) ─────────────────────────────────────────
    /** Open continuous audio stream for gapless PCM playback. */
    fun startStream(sampleRate: Int, sampleSizeInBits: Int = 16, channels: Int = 1)
    /** Write raw PCM chunk to the stream. Blocks if buffer full (backpressure). */
    fun streamPcm(pcmData: ByteArray)
    /** Drain remaining audio and close the stream. */
    fun finishStream()
    /** Stop streaming immediately (discard remaining audio). */
    fun stopStream()
}
