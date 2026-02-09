package com.jervis.ui.audio

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

actual class AudioPlayer actual constructor() {

    private var clip: Clip? = null

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

    actual fun stop() {
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
