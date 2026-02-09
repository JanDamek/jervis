package com.jervis.ui.audio

/**
 * iOS AudioRecorder stub.
 *
 * Full implementation will use AVAudioEngine with tap on input node.
 * System audio is NOT supported on iOS (platform limitation).
 * Mic captures room audio including speakers if on speakerphone.
 *
 * TODO: Full iOS implementation with AVAudioEngine interop.
 */
actual class AudioRecorder actual constructor() {

    private var _isRecording = false
    private var startTimeMs = 0L

    actual fun getAvailableInputDevices(): List<AudioDevice> {
        return listOf(
            AudioDevice(
                id = "ios_mic",
                name = "Microphone",
                type = AudioDeviceType.MICROPHONE,
            ),
        )
    }

    actual fun getSystemAudioCapabilities(): SystemAudioCapability {
        return SystemAudioCapability.NotSupported(
            "iOS does not support system audio capture. Microphone will capture room audio including speakers.",
        )
    }

    actual fun startRecording(config: AudioRecordingConfig): Boolean {
        // TODO: AVAudioEngine implementation
        return false
    }

    actual fun stopRecording(): ByteArray? {
        if (!_isRecording) return null
        _isRecording = false
        return null
    }

    actual fun getAndClearBuffer(): ByteArray? {
        return null
    }

    actual val isRecording: Boolean
        get() = _isRecording

    actual val durationSeconds: Long
        get() = 0L

    actual fun release() {
        _isRecording = false
    }
}
