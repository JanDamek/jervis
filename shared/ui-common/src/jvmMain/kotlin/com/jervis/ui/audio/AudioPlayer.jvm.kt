package com.jervis.ui.audio

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

actual class AudioPlayer actual constructor() {

    private var clip: Clip? = null
    private var rangeStopThread: Thread? = null

    actual fun play(audioData: ByteArray) {
        stop()
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
            val newClip = AudioSystem.getClip()
            newClip.open(stream)
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    newClip.close()
                    if (clip === newClip) clip = null
                }
            }
            clip = newClip
            newClip.start()
        } catch (e: Exception) {
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
        rangeStopThread?.interrupt()
        rangeStopThread = null
        clip?.let {
            if (it.isRunning) it.stop()
            it.close()
        }
        clip = null
    }

    actual val isPlaying: Boolean
        get() = clip?.isRunning == true

    actual fun release() {
        stop()
    }
}
