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
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
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
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS AudioRecorder using AVAudioRecorder (file-based).
 *
 * Records to a temporary WAV file. Supports incremental reads via [getAndClearBuffer]
 * which reads newly-written bytes from the file since the last read.
 * On [stopRecording], returns only the unread tail bytes.
 *
 * Background recording: uses UIApplication.beginBackgroundTask + audio session
 * interruption handling to survive screen lock and brief interruptions.
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

    /** Byte offset into the temp file up to which data has already been read. */
    private var lastReadOffset: Long = 0L

    /** iOS background task identifier — keeps app alive when screen locks. */
    private var backgroundTaskId: ULong = UIBackgroundTaskInvalid

    /** Observer token for audio session interruption notifications. */
    private var interruptionObserver: Any? = null

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
            println("[AudioRecorder.ios] Permission granted, configuring session...")
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or
                    AVAudioSessionCategoryOptionAllowBluetooth,
                error = null,
            )
            session.setActive(true, error = null)
            println("[AudioRecorder.ios] Audio session active")

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
            println("[AudioRecorder.ios] record() = $started, url=$tempPath")
            if (!started) {
                println("[AudioRecorder.ios] AVAudioRecorder.record() returned false")
                recorder = null
                return false
            }

            _isRecording = true
            startTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
            // Skip the 44-byte WAV header written by AVAudioRecorder —
            // the server writes its own header, clients send raw PCM only.
            lastReadOffset = 44L

            // Begin iOS background task — prevents app suspension when screen locks.
            // The audio background mode keeps the audio session alive, but the
            // background task ensures our coroutine upload loop also keeps running.
            beginBackgroundTask()

            // Listen for audio session interruptions (phone calls, Siri, etc.)
            // to resume recording when the interruption ends.
            registerInterruptionHandler()

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
        unregisterInterruptionHandler()
        recorder?.stop()
        recorder = null
        endBackgroundTask()

        val url = tempFileUrl ?: return null
        tempFileUrl = null

        // Return only the unread tail (data accumulated since the last getAndClearBuffer call)
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
        unregisterInterruptionHandler()
        endBackgroundTask()
    }

    // --- iOS background task ---

    private fun beginBackgroundTask() {
        if (backgroundTaskId != UIBackgroundTaskInvalid) return
        backgroundTaskId = UIApplication.sharedApplication.beginBackgroundTaskWithName(
            "JervisAudioRecording",
        ) {
            // Expiration handler — iOS is about to kill the task.
            // Don't stop recording — the audio background mode keeps AVAudioRecorder alive.
            // Just end the background task to avoid iOS terminating the entire app.
            println("[AudioRecorder] Background task expiring, ending task (recording continues via audio mode)")
            endBackgroundTask()
        }
        println("[AudioRecorder] Background task started: $backgroundTaskId")
    }

    private fun endBackgroundTask() {
        if (backgroundTaskId == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(backgroundTaskId)
        println("[AudioRecorder] Background task ended: $backgroundTaskId")
        backgroundTaskId = UIBackgroundTaskInvalid
    }

    // --- Audio session interruption handling ---

    private fun registerInterruptionHandler() {
        unregisterInterruptionHandler()
        interruptionObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            handleInterruption(notification)
        }
        println("[AudioRecorder] Interruption handler registered")
    }

    private fun unregisterInterruptionHandler() {
        interruptionObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            interruptionObserver = null
        }
    }

    private fun handleInterruption(notification: NSNotification?) {
        val userInfo = notification?.userInfo ?: return
        val typeValue = (userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue ?: return

        // AVAudioSessionInterruptionType: began = 1, ended = 0
        if (typeValue == 1UL) {
            // Interruption began (phone call, Siri, etc.)
            // AVAudioRecorder automatically pauses — we just log it.
            println("[AudioRecorder] Audio session interrupted (began)")
        } else {
            // Interruption ended — resume recording if we were recording
            println("[AudioRecorder] Audio session interruption ended")
            val optionsValue = (userInfo[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0UL
            val shouldResume = (optionsValue and AVAudioSessionInterruptionOptionShouldResume) != 0UL

            if (_isRecording && shouldResume) {
                // Reactivate audio session and resume recording
                try {
                    AVAudioSession.sharedInstance().setActive(true, error = null)
                    recorder?.record()
                    println("[AudioRecorder] Recording resumed after interruption")
                } catch (e: Exception) {
                    println("[AudioRecorder] Failed to resume recording: ${e.message}")
                }
            } else if (_isRecording) {
                // iOS didn't suggest resume, but we're still recording — try anyway
                try {
                    AVAudioSession.sharedInstance().setActive(true, error = null)
                    recorder?.record()
                    println("[AudioRecorder] Recording force-resumed after interruption (no shouldResume flag)")
                } catch (e: Exception) {
                    println("[AudioRecorder] Failed to force-resume recording: ${e.message}")
                }
            }
        }
    }

    /**
     * Read bytes from [url] starting at [fromOffset].
     * Returns null if no new data is available.
     */
    private fun readBytesFrom(url: NSURL, fromOffset: Long): ByteArray? {
        val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
        val totalSize = data.length.toLong()
        if (totalSize <= fromOffset) return null

        val newSize = (totalSize - fromOffset).toInt()

        // Copy entire data to ByteArray, then return slice from offset
        val allBytes = ByteArray(totalSize.toInt())
        allBytes.usePinned { pinned ->
            val srcPtr = data.bytes ?: return null
            platform.posix.memcpy(
                pinned.addressOf(0),
                srcPtr,
                totalSize.toULong(),
            )
        }

        // Return only the new bytes from offset
        return allBytes.sliceArray(fromOffset.toInt() until totalSize.toInt())
    }
}
