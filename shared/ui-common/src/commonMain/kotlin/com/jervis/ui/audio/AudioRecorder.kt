package com.jervis.ui.audio

/**
 * Available audio input device descriptor.
 */
data class AudioDevice(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
)

enum class AudioDeviceType {
    MICROPHONE,
    SYSTEM_AUDIO,
    VIRTUAL_DEVICE,
    UNKNOWN,
}

/**
 * System audio capture capability for the current platform.
 */
sealed class SystemAudioCapability {
    /** System audio capture is available. */
    data object Available : SystemAudioCapability()

    /** System audio capture requires additional setup (e.g., virtual audio device on macOS). */
    data class RequiresSetup(val message: String) : SystemAudioCapability()

    /** System audio capture is not supported on this platform. */
    data class NotSupported(val reason: String) : SystemAudioCapability()
}

/**
 * Audio recording configuration.
 */
data class AudioRecordingConfig(
    val inputDevice: AudioDevice? = null,
    val captureSystemAudio: Boolean = false,
    val format: AudioFormat = AudioFormat.WAV_PCM,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
)

enum class AudioFormat {
    WAV_PCM,
    WEBM_OPUS,
}

/**
 * Cross-platform audio recorder.
 *
 * Platform implementations:
 * - Android: AudioRecord API + AudioPlaybackCapture (Android 10+)
 * - Desktop (JVM): Java Sound API (TargetDataLine)
 * - iOS: AVAudioEngine (mic only, no system audio)
 */
expect class AudioRecorder() {
    /**
     * Get list of available audio input devices.
     */
    fun getAvailableInputDevices(): List<AudioDevice>

    /**
     * Check if system audio capture is supported on this platform.
     */
    fun getSystemAudioCapabilities(): SystemAudioCapability

    /**
     * Start recording audio.
     * @return true if recording started successfully
     */
    fun startRecording(config: AudioRecordingConfig = AudioRecordingConfig()): Boolean

    /**
     * Stop recording and return the complete audio buffer.
     * @return Audio data as ByteArray, or null if recording wasn't active
     */
    fun stopRecording(): ByteArray?

    /**
     * Get accumulated audio since last call and clear the buffer.
     * Used for chunked upload during recording.
     * @return Audio data chunk, or null if no new data
     */
    fun getAndClearBuffer(): ByteArray?

    /**
     * Whether recording is currently active.
     */
    val isRecording: Boolean

    /**
     * Duration of current recording in seconds.
     */
    val durationSeconds: Long

    /**
     * Release all resources. Call when done with the recorder.
     */
    fun release()
}
