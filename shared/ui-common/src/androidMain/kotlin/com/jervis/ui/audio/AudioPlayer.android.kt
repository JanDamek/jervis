package com.jervis.ui.audio

import android.media.MediaPlayer
import java.io.File

actual class AudioPlayer actual constructor() {

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    actual fun play(audioData: ByteArray) {
        stop()
        try {
            // MediaPlayer needs a file or FileDescriptor — write to temp file
            val tmp = File.createTempFile("jervis_playback_", ".wav")
            tmp.writeBytes(audioData)
            tmp.deleteOnExit()
            tempFile = tmp

            val player = MediaPlayer()
            player.setDataSource(tmp.absolutePath)
            player.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
                cleanupTempFile()
            }
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (e: Exception) {
            mediaPlayer = null
            cleanupTempFile()
        }
    }

    actual fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        cleanupTempFile()
    }

    actual val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    actual fun release() {
        stop()
    }

    private fun cleanupTempFile() {
        tempFile?.delete()
        tempFile = null
    }
}
