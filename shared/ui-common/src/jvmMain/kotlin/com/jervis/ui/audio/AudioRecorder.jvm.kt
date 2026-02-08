package com.jervis.ui.audio

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat as JvmAudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * Desktop (JVM) AudioRecorder using Java Sound API.
 *
 * Mic capture: TargetDataLine from default or selected mixer.
 * System audio: User must select a virtual audio device (e.g., BlackHole on macOS,
 * Stereo Mix on Windows, PulseAudio monitor on Linux) from the device list.
 */
actual class AudioRecorder actual constructor() {

    private var targetLine: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkLock = Any()

    @Volatile
    private var _isRecording = false

    @Volatile
    private var startTimeMs = 0L

    private var currentFormat: JvmAudioFormat? = null

    actual fun getAvailableInputDevices(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        val format = getDefaultFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

        AudioSystem.getMixerInfo().forEach { mixerInfo ->
            try {
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (mixer.isLineSupported(lineInfo)) {
                    val type = guessDeviceType(mixerInfo)
                    devices.add(
                        AudioDevice(
                            id = mixerInfo.name,
                            name = mixerInfo.name,
                            type = type,
                        ),
                    )
                }
            } catch (_: Exception) {
                // Skip unsupported mixers
            }
        }

        if (devices.isEmpty()) {
            devices.add(
                AudioDevice(
                    id = "default",
                    name = "Default Input",
                    type = AudioDeviceType.MICROPHONE,
                ),
            )
        }

        return devices
    }

    actual fun getSystemAudioCapabilities(): SystemAudioCapability {
        val devices = getAvailableInputDevices()
        val hasVirtual = devices.any { it.type == AudioDeviceType.VIRTUAL_DEVICE || it.type == AudioDeviceType.SYSTEM_AUDIO }

        return if (hasVirtual) {
            SystemAudioCapability.Available
        } else {
            SystemAudioCapability.RequiresSetup(
                "Install a virtual audio device (BlackHole on macOS, Stereo Mix on Windows, PulseAudio monitor on Linux) to capture system audio.",
            )
        }
    }

    actual fun startRecording(config: AudioRecordingConfig): Boolean {
        if (_isRecording) return false

        val format = JvmAudioFormat(
            config.sampleRate.toFloat(),
            16,
            config.channels,
            true,
            false,
        )
        currentFormat = format
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

        try {
            targetLine = if (config.inputDevice != null && config.inputDevice.id != "default") {
                val mixerInfo = AudioSystem.getMixerInfo().find { it.name == config.inputDevice.id }
                if (mixerInfo != null) {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    mixer.getLine(lineInfo) as TargetDataLine
                } else {
                    AudioSystem.getLine(lineInfo) as TargetDataLine
                }
            } else {
                AudioSystem.getLine(lineInfo) as TargetDataLine
            }

            targetLine?.open(format)
            targetLine?.start()

            _isRecording = true
            startTimeMs = System.currentTimeMillis()

            buffer.reset()
            synchronized(chunkLock) { chunkBuffer.reset() }

            // Write WAV header placeholder
            writeWavHeader(buffer, config.sampleRate, config.channels)

            recordingThread = thread(name = "AudioRecorder-JVM") {
                val readBuffer = ByteArray(4096)
                while (_isRecording) {
                    val read = targetLine?.read(readBuffer, 0, readBuffer.size) ?: -1
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
            targetLine?.close()
            targetLine = null
            _isRecording = false
            return false
        }
    }

    actual fun stopRecording(): ByteArray? {
        if (!_isRecording) return null

        _isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        targetLine?.stop()
        targetLine?.close()
        targetLine = null

        val result = buffer.toByteArray()
        buffer.reset()
        synchronized(chunkLock) { chunkBuffer.reset() }

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
        targetLine?.close()
        targetLine = null
    }

    private fun getDefaultFormat() = JvmAudioFormat(16000f, 16, 1, true, false)

    private fun guessDeviceType(mixerInfo: Mixer.Info): AudioDeviceType {
        val name = mixerInfo.name.lowercase()
        return when {
            name.contains("blackhole") || name.contains("stereo mix") ||
                name.contains("monitor") || name.contains("loopback") ||
                name.contains("virtual") -> AudioDeviceType.VIRTUAL_DEVICE

            name.contains("microphone") || name.contains("mic") ||
                name.contains("input") || name.contains("capture") -> AudioDeviceType.MICROPHONE

            else -> AudioDeviceType.UNKNOWN
        }
    }

    private fun writeWavHeader(out: ByteArrayOutputStream, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        out.write("RIFF".toByteArray())
        out.write(intToBytes(0))
        out.write("WAVE".toByteArray())

        out.write("fmt ".toByteArray())
        out.write(intToBytes(16))
        out.write(shortToBytes(1))
        out.write(shortToBytes(channels.toShort()))
        out.write(intToBytes(sampleRate))
        out.write(intToBytes(byteRate))
        out.write(shortToBytes(blockAlign.toShort()))
        out.write(shortToBytes(bitsPerSample.toShort()))

        out.write("data".toByteArray())
        out.write(intToBytes(0))
    }

    private fun fixWavHeader(data: ByteArray) {
        val dataSize = data.size - 44
        val fileSize = data.size - 8

        data[4] = (fileSize and 0xFF).toByte()
        data[5] = ((fileSize shr 8) and 0xFF).toByte()
        data[6] = ((fileSize shr 16) and 0xFF).toByte()
        data[7] = ((fileSize shr 24) and 0xFF).toByte()

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
