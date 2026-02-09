package com.jervis.ui.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970

/**
 * iOS AudioRecorder using AVAudioRecorder (file-based).
 *
 * Records to a temporary WAV file. On stop, reads the file into a ByteArray.
 * More reliable than AVAudioEngine for simple recording use cases.
 *
 * System audio capture is NOT supported on iOS (platform limitation).
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor() {

    private var recorder: AVAudioRecorder? = null
    private var tempFileUrl: NSURL? = null

    @kotlin.concurrent.Volatile
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
        if (_isRecording) return false

        val session = AVAudioSession.sharedInstance()

        // Check microphone permission first.
        val permission = session.recordPermission
        if (permission == AVAudioSessionRecordPermissionUndetermined) {
            session.requestRecordPermission { _ -> }
            return false
        }
        if (permission != AVAudioSessionRecordPermissionGranted) {
            return false
        }

        return try {
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or
                    AVAudioSessionCategoryOptionAllowBluetooth,
                error = null,
            )
            session.setActive(true, error = null)

            val tempPath = NSTemporaryDirectory() + "jervis_recording.wav"
            val url = NSURL.fileURLWithPath(tempPath)
            tempFileUrl = url

            @Suppress("UNCHECKED_CAST")
            val settings = mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatLinearPCM.toLong(),
                AVSampleRateKey to config.sampleRate.toDouble(),
                AVNumberOfChannelsKey to config.channels,
                AVLinearPCMBitDepthKey to 16,
                AVLinearPCMIsFloatKey to false,
                AVLinearPCMIsBigEndianKey to false,
                AVEncoderAudioQualityKey to AVAudioQualityHigh,
            ) as Map<Any?, *>

            val audioRecorder = AVAudioRecorder(uRL = url, settings = settings, error = null)
            recorder = audioRecorder

            audioRecorder.prepareToRecord()
            val started = audioRecorder.record()
            if (!started) {
                recorder = null
                return false
            }

            _isRecording = true
            startTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()

            true
        } catch (e: Exception) {
            recorder?.stop()
            recorder = null
            _isRecording = false
            false
        }
    }

    actual fun stopRecording(): ByteArray? {
        if (!_isRecording) return null

        _isRecording = false
        recorder?.stop()
        recorder = null

        val url = tempFileUrl ?: return null
        tempFileUrl = null

        return try {
            val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
            val size = data.length.toInt()
            if (size <= 44) return null

            val bytes = ByteArray(size)
            bytes.usePinned {
                platform.posix.memcpy(it.addressOf(0), data.bytes, data.length)
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    actual fun getAndClearBuffer(): ByteArray? {
        // File-based recording — chunked access not supported.
        // Full data is returned on stopRecording().
        return null
    }

    actual val isRecording: Boolean
        get() = _isRecording

    actual val durationSeconds: Long
        get() = if (_isRecording) {
            ((NSDate().timeIntervalSince1970 * 1000).toLong() - startTimeMs) / 1000
        } else {
            0L
        }

    actual fun release() {
        if (_isRecording) {
            stopRecording()
        }
        recorder?.stop()
        recorder = null
    }
}
