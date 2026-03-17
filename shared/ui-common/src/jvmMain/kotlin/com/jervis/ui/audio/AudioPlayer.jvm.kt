package com.jervis.ui.audio

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine

actual class AudioPlayer actual constructor() {

    private var clip: Clip? = null
    private var rangeStopThread: Thread? = null

    // ── Streaming via SourceDataLine ────────────────────────────────────
    private var streamLine: SourceDataLine? = null

    /**
     * Open a continuous audio stream for PCM playback.
     * Call [streamPcm] to write chunks, [finishStream] when done.
     */
    fun startStream(sampleRate: Int, sampleSizeInBits: Int = 16, channels: Int = 1) {
        stopStream()
        val format = AudioFormat(
            sampleRate.toFloat(),
            sampleSizeInBits,
            channels,
            true, // signed
            false, // little-endian (matches numpy int16 default)
        )
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        // Buffer size: ~500ms of audio for smooth playback
        val bufferSize = sampleRate * channels * (sampleSizeInBits / 8) / 2
        line.open(format, bufferSize)
        line.start()
        streamLine = line
        println("AudioPlayer: stream opened, sampleRate=$sampleRate, bufferSize=$bufferSize")
    }

    /**
     * Write raw PCM data to the audio stream. Blocks until data is written to the buffer.
     * This provides gapless playback — SourceDataLine handles continuous output.
     */
    fun streamPcm(pcmData: ByteArray) {
        val line = streamLine ?: return
        // write() blocks if the internal buffer is full — natural backpressure
        line.write(pcmData, 0, pcmData.size)
    }

    /**
     * Finish streaming — drain remaining audio and close.
     */
    fun finishStream() {
        streamLine?.let { line ->
            line.drain() // play remaining buffered audio
            line.stop()
            line.close()
        }
        streamLine = null
        println("AudioPlayer: stream finished")
    }

    fun stopStream() {
        streamLine?.let { line ->
            line.stop()
            line.close()
        }
        streamLine = null
    }

    // ── One-shot WAV playback via Clip (for non-streaming use) ──────────
    actual fun play(audioData: ByteArray) {
        stop()
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
            val newClip = AudioSystem.getClip()
            newClip.open(stream)
            val latch = CountDownLatch(1)
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    newClip.close()
                    if (clip === newClip) clip = null
                    latch.countDown()
                }
            }
            clip = newClip
            newClip.start()
            // Block until clip finishes so sequential TTS chunks play in order
            val durationMs = (newClip.microsecondLength / 1000) + 500
            latch.await(durationMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("AudioPlayer.play error: ${e::class.simpleName}: ${e.message}")
            clip = null
        }
    }

    actual fun playRange(audioData: ByteArray, startSec: Double, endSec: Double) {
        stop()
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
            val newClip = AudioSystem.getClip()
            newClip.open(stream)
            val startFrame = (startSec * newClip.format.frameRate).toLong()
                .coerceIn(0, newClip.frameLength.toLong())
            val endFrame = (endSec * newClip.format.frameRate).toLong()
                .coerceIn(startFrame, newClip.frameLength.toLong())
            newClip.framePosition = startFrame.toInt()
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    newClip.close()
                    if (clip === newClip) clip = null
                }
            }
            clip = newClip
            newClip.start()
            // Stop at endFrame using a background thread
            rangeStopThread = Thread {
                try {
                    val durationMs = ((endSec - startSec) * 1000).toLong()
                    Thread.sleep(durationMs)
                    if (clip === newClip && newClip.isRunning) {
                        newClip.stop()
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            clip = null
        }
    }

    actual fun stop() {
        stopStream()
        rangeStopThread?.interrupt()
        rangeStopThread = null
        clip?.let {
            if (it.isRunning) it.stop()
            it.close()
        }
        clip = null
    }

    actual val isPlaying: Boolean
        get() = clip?.isRunning == true || streamLine?.isRunning == true

    actual fun release() {
        stop()
    }
}
