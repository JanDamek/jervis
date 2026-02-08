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
 */
actual class AudioRecorder actual constructor() {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
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

            buffer.reset()
            synchronized(chunkLock) { chunkBuffer.reset() }

            // Write WAV header placeholder (44 bytes)
            writeWavHeader(buffer, sampleRate, config.channels)

            recordingThread = thread(name = "AudioRecorder") {
                val readBuffer = ByteArray(bufferSize)
                while (_isRecording) {
                    val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (read > 0) {
                        buffer.write(readBuffer, 0, read)
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

        val result = buffer.toByteArray()
        buffer.reset()
        synchronized(chunkLock) { chunkBuffer.reset() }

        // Fix WAV header with actual data size
        if (result.size > 44) {
            fixWavHeader(result)
        }

        return result
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

    private fun writeWavHeader(out: ByteArrayOutputStream, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF header (will be fixed later with actual sizes)
        out.write("RIFF".toByteArray())
        out.write(intToBytes(0)) // placeholder for file size - 8
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToBytes(16)) // chunk size
        out.write(shortToBytes(1)) // PCM format
        out.write(shortToBytes(channels.toShort()))
        out.write(intToBytes(sampleRate))
        out.write(intToBytes(byteRate))
        out.write(shortToBytes(blockAlign.toShort()))
        out.write(shortToBytes(bitsPerSample.toShort()))

        // data chunk
        out.write("data".toByteArray())
        out.write(intToBytes(0)) // placeholder for data size
    }

    private fun fixWavHeader(data: ByteArray) {
        val dataSize = data.size - 44
        val fileSize = data.size - 8

        // File size at offset 4
        data[4] = (fileSize and 0xFF).toByte()
        data[5] = ((fileSize shr 8) and 0xFF).toByte()
        data[6] = ((fileSize shr 16) and 0xFF).toByte()
        data[7] = ((fileSize shr 24) and 0xFF).toByte()

        // Data size at offset 40
        data[40] = (dataSize and 0xFF).toByte()
        data[41] = ((dataSize shr 8) and 0xFF).toByte()
        data[42] = ((dataSize shr 16) and 0xFF).toByte()
        data[43] = ((dataSize shr 24) and 0xFF).toByte()
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte(),
    )
}
