package com.jervis.ui.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor() {
    private var recorder: AVAudioRecorder? = null
    private var tempFileUrl: NSURL? = null

    @kotlin.concurrent.Volatile
    private var _isRecording = false
    private var startTimeMs = 0L
    private var lastReadOffset: Long = 0L

    actual fun getAvailableInputDevices(): List<AudioDevice> =
        listOf(
            AudioDevice(
                id = "macos_mic",
                name = "System Microphone",
                type = AudioDeviceType.MICROPHONE,
            ),
        )

    actual fun getSystemAudioCapabilities(): SystemAudioCapability =
        SystemAudioCapability.RequiresSetup("macOS supports system audio capture via specialized drivers (e.g. BlackHole) or native APIs.")

    actual fun startRecording(config: AudioRecordingConfig): Boolean {
        if (_isRecording) return false

        return try {
            val tempPath = NSTemporaryDirectory() + "jervis_macos_recording.wav"
            val url = NSURL.fileURLWithPath(tempPath)
            tempFileUrl = url

            @Suppress("UNCHECKED_CAST")
            val settings =
                mapOf<Any?, Any?>(
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
            lastReadOffset = 44L // WAV header

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
            readBytesFrom(url, lastReadOffset)
        } catch (e: Exception) {
            null
        } finally {
            lastReadOffset = 0L
        }
    }

    actual fun getAndClearBuffer(): ByteArray? {
        if (!_isRecording) return null
        val url = tempFileUrl ?: return null

        return try {
            val newBytes = readBytesFrom(url, lastReadOffset) ?: return null
            lastReadOffset += newBytes.size
            newBytes
        } catch (e: Exception) {
            null
        }
    }

    actual val isRecording: Boolean
        get() = _isRecording

    actual val durationSeconds: Long
        get() =
            if (_isRecording) {
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

    private fun readBytesFrom(
        url: NSURL,
        fromOffset: Long,
    ): ByteArray? {
        val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
        val totalSize = data.length.toLong()
        if (totalSize <= fromOffset) return null

        val newSize = (totalSize - fromOffset).toInt()
        val allBytes = ByteArray(totalSize.toInt())
        allBytes.usePinned { pinned ->
            val srcPtr = data.bytes ?: return null
            platform.posix.memcpy(
                pinned.addressOf(0),
                srcPtr,
                totalSize.toULong(),
            )
        }
        return allBytes.sliceArray(fromOffset.toInt() until totalSize.toInt())
    }
}
