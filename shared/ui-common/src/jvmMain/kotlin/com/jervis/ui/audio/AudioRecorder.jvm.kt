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
 *
 * Supports incremental upload: [getAndClearBuffer] returns raw PCM accumulated since last call.
 * Server owns the WAV header — clients send only raw PCM data.
 * On [stopRecording], returns only the un-uploaded tail from the chunk buffer.
 */
actual class AudioRecorder actual constructor() {

    private var targetLine: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkLock = Any()

    @Volatile
    private var _isRecording = false

    @Volatile
    private var startTimeMs = 0L

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

            synchronized(chunkLock) { chunkBuffer.reset() }

            // No WAV header — server owns the header, clients send raw PCM only.
            recordingThread = thread(name = "AudioRecorder-JVM") {
                val readBuffer = ByteArray(4096)
                while (_isRecording) {
                    val read = targetLine?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (read > 0) {
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
}
