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

    actual fun startStream(sampleRate: Int, sampleSizeInBits: Int, channels: Int) {
        stopStream()
        val format = AudioFormat(
            sampleRate.toFloat(), sampleSizeInBits, channels, true, false,
        )
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        val bufferSize = sampleRate * channels * (sampleSizeInBits / 8) / 2
        line.open(format, bufferSize)
        line.start()
        streamLine = line
    }

    actual fun streamPcm(pcmData: ByteArray) {
        streamLine?.write(pcmData, 0, pcmData.size)
    }

    actual fun finishStream() {
        streamLine?.let { it.drain(); it.stop(); it.close() }
        streamLine = null
    }

    actual fun stopStream() {
        streamLine?.let { it.stop(); it.close() }
        streamLine = null
    }

    // ── One-shot WAV playback via Clip (blocking — for TTS chunks) ──────
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
            val durationMs = (newClip.microsecondLength / 1000) + 500
            latch.await(durationMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            println("AudioPlayer.play error: ${e::class.simpleName}: ${e.message}")
            clip = null
        }
    }

    // ── Non-blocking playback (for meeting player with seek) ─────────────
    actual fun playAsync(audioData: ByteArray) {
        stop()
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
            val newClip = AudioSystem.getClip()
            newClip.open(stream)
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    if (clip === newClip) clip = null
                }
            }
            clip = newClip
            newClip.start()
        } catch (e: Exception) {
            println("AudioPlayer.playAsync error: ${e::class.simpleName}: ${e.message}")
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
            rangeStopThread = Thread {
                try {
                    Thread.sleep(((endSec - startSec) * 1000).toLong())
                    if (clip === newClip && newClip.isRunning) newClip.stop()
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

    actual val positionSec: Double
        get() = clip?.microsecondPosition?.let { it / 1_000_000.0 } ?: 0.0

    actual val durationSec: Double
        get() = clip?.microsecondLength?.let { it / 1_000_000.0 } ?: 0.0

    actual fun seekTo(positionSec: Double) {
        clip?.let { c ->
            val frame = (positionSec * c.format.frameRate).toLong()
                .coerceIn(0, c.frameLength.toLong())
            c.framePosition = frame.toInt()
            if (!c.isRunning) c.start()
        }
    }

    actual fun release() {
        stop()
    }
}
