package com.jervis.ui.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Android AudioRecorder implementation using AudioRecord API.
 *
 * Mic capture: AudioRecord with VOICE_RECOGNITION source.
 * System audio: requires MediaProjection (AudioPlaybackCapture API, Android 10+),
 * which must be started from the Activity layer. For now, mic-only.
 *
 * Supports incremental upload: [getAndClearBuffer] returns raw PCM accumulated since last call.
 * Server owns the WAV header — clients send only raw PCM data.
 * On [stopRecording], returns only the un-uploaded tail from the chunk buffer.
 */
actual class AudioRecorder actual constructor() {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkLock = Any()

    @Volatile
    private var _isRecording = false

    @Volatile
    private var startTimeMs = 0L

    actual fun getAvailableInputDevices(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        devices.add(
            AudioDevice(
                id = "mic_default",
                name = "Default Microphone",
                type = AudioDeviceType.MICROPHONE,
            ),
        )
        return devices
    }

    actual fun getSystemAudioCapabilities(): SystemAudioCapability {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            SystemAudioCapability.RequiresSetup(
                "System audio capture requires MediaProjection permission from the Activity layer.",
            )
        } else {
            SystemAudioCapability.NotSupported("System audio capture requires Android 10+ (API 29+).")
        }
    }

    actual fun startRecording(config: AudioRecordingConfig): Boolean {
        if (_isRecording) return false

        val sampleRate = config.sampleRate
        val channelConfig = if (config.channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val bufferSize = minBufferSize * 2

        try {
            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSize,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            _isRecording = true
            startTimeMs = System.currentTimeMillis()

            synchronized(chunkLock) { chunkBuffer.reset() }

            // No WAV header — server owns the header, clients send raw PCM only.
            recordingThread = thread(name = "AudioRecorder") {
                val readBuffer = ByteArray(bufferSize)
                while (_isRecording) {
                    val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (read > 0) {
                        synchronized(chunkLock) {
                            chunkBuffer.write(readBuffer, 0, read)
                        }
                    }
                }
            }

            return true
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            _isRecording = false
            return false
        }
    }

    actual fun stopRecording(): ByteArray? {
        if (!_isRecording) return null

        _isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Return only the remaining un-uploaded tail from the chunk buffer.
        // The bulk of the recording was already sent incrementally via getAndClearBuffer().
        val remaining = synchronized(chunkLock) {
            val data = chunkBuffer.toByteArray()
            chunkBuffer.reset()
            data
        }

        return if (remaining.isNotEmpty()) remaining else null
    }

    actual fun getAndClearBuffer(): ByteArray? {
        if (!_isRecording) return null

        synchronized(chunkLock) {
            val data = chunkBuffer.toByteArray()
            chunkBuffer.reset()
            return if (data.isNotEmpty()) data else null
        }
    }

    actual val isRecording: Boolean
        get() = _isRecording

    actual val durationSeconds: Long
        get() = if (_isRecording) {
            (System.currentTimeMillis() - startTimeMs) / 1000
        } else {
            0
        }

    actual fun release() {
        if (_isRecording) {
            stopRecording()
        }
        audioRecord?.release()
        audioRecord = null
    }
}
