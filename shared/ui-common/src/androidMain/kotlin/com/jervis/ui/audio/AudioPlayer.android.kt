package com.jervis.ui.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

actual class AudioPlayer actual constructor() {

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rangeStopRunnable: Runnable? = null

    actual fun play(audioData: ByteArray) {
        stop()
        try {
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

    actual fun playRange(audioData: ByteArray, startSec: Double, endSec: Double) {
        stop()
        try {
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
            player.seekTo((startSec * 1000).toInt())
            player.start()
            mediaPlayer = player
            // Schedule stop at endSec
            val durationMs = ((endSec - startSec) * 1000).toLong()
            val stopRunnable = Runnable {
                if (mediaPlayer === player) {
                    stop()
                }
            }
            rangeStopRunnable = stopRunnable
            handler.postDelayed(stopRunnable, durationMs)
        } catch (e: Exception) {
            mediaPlayer = null
            cleanupTempFile()
        }
    }

    actual fun stop() {
        rangeStopRunnable?.let { handler.removeCallbacks(it) }
        rangeStopRunnable = null
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
