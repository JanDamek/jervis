package com.jervis.desktop.meeting

import com.jervis.di.JervisRepository
import com.jervis.dto.events.JervisEvent
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Tiny println shim — desktop has no slf4j/kotlin-logging on the classpath. */
private object logger {
    inline fun info(msg: () -> String) = println("INFO  DesktopMeetingRecorder: ${msg()}")
    inline fun warn(msg: () -> String) = println("WARN  DesktopMeetingRecorder: ${msg()}")
    inline fun error(t: Throwable?, msg: () -> String) {
        println("ERROR DesktopMeetingRecorder: ${msg()}")
        t?.printStackTrace()
    }
}

/**
 * Desktop loopback recorder for approved Teams / Meet / Zoom meetings.
 *
 * Listens for [JervisEvent.MeetingRecordingTrigger] events on the desktop's
 * notification stream. For each trigger it spawns an `ffmpeg` process that
 * captures the OS audio loopback (BlackHole on macOS, WASAPI loopback on
 * Windows, PulseAudio monitor on Linux) into raw 16 kHz / 16-bit / mono PCM,
 * then streams that PCM to the server in 5-second base64 chunks via
 * `IMeetingService.uploadAudioChunk`. When the meeting's `endTime` elapses
 * (or [JervisEvent.MeetingRecordingStop] arrives) it terminates ffmpeg and
 * calls `finalizeRecording`.
 *
 * ## Read-only v1 invariants
 *
 * - **Never** auto-joins the meeting — the user joined it themselves; we only
 *   capture audio their machine is already playing through the loopback
 *   device. Per `feedback-meeting-consent.md`.
 * - **Never** sends a disclaimer message into the meeting chat.
 * - **Never** opens a microphone input — only the loopback monitor.
 *
 * ## Loopback device selection
 *
 * The loopback device name is read from the `audio.loopback.device` agent
 * preference if set, otherwise sensible OS defaults are used. Configuration
 * lives in user preferences so each machine can pick its own
 * BlackHole/PulseSink without code changes.
 *
 * ## Idempotency
 *
 * The recorder dedupes by `taskId` — if a trigger arrives twice (e.g. server
 * restart re-replayed an unacked event) the second one is dropped.
 * `MeetingRpc.startRecording` itself is also idempotent on the server side
 * (clientId, meetingType) so a duplicate cannot create two MeetingDocuments.
 */
class DesktopMeetingRecorder(
    private val repository: JervisRepository,
    private val loopbackDeviceProvider: () -> String? = { null },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** taskId -> active recording session. */
    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()

    private data class RecordingSession(
        val taskId: String,
        val meetingId: String,
        val process: Process,
        val pumpJob: Job,
        val watchdogJob: Job,
        val startedAt: Instant,
        val endTime: Instant,
    )

    /**
     * Entry point — call from `ConnectionState.handleEvent` for both the
     * trigger and the stop event.
     */
    fun handleEvent(event: JervisEvent) {
        when (event) {
            is JervisEvent.MeetingRecordingTrigger -> startRecording(event)
            is JervisEvent.MeetingRecordingStop -> stopRecording(event.taskId, event.reason)
            else -> { /* not ours */ }
        }
    }

    private fun startRecording(trigger: JervisEvent.MeetingRecordingTrigger) {
        if (activeSessions.containsKey(trigger.taskId)) {
            logger.info { "DesktopMeetingRecorder: trigger for taskId=${trigger.taskId} ignored — already recording" }
            return
        }
        scope.launch {
            try {
                logger.info {
                    "DesktopMeetingRecorder: starting capture for task=${trigger.taskId} " +
                        "title='${trigger.title}' provider=${trigger.provider}"
                }

                val meeting = repository.meetings.startRecording(
                    MeetingCreateDto(
                        clientId = trigger.clientId,
                        projectId = trigger.projectId,
                        title = trigger.title,
                        meetingType = MeetingTypeEnum.MEETING,
                        deviceSessionId = "desktop-loopback-${trigger.taskId}",
                    ),
                )

                // Persist the link from the source CALENDAR_PROCESSING task to
                // this MeetingDocument so the rest of the pipeline can resolve
                // either direction without parsing deviceSessionId strings.
                runCatching {
                    repository.meetings.linkMeetingToTask(
                        taskId = trigger.taskId,
                        meetingId = meeting.id,
                    )
                }.onFailure { e ->
                    logger.warn { "DesktopMeetingRecorder: linkMeetingToTask failed: ${e.message}" }
                }

                val process = spawnFfmpeg()
                val endTime = parseInstantOrNow(trigger.endTime)

                val pumpJob = scope.launch { pumpAudio(meeting.id, process.inputStream) }
                val watchdog = scope.launch {
                    while (isActive && Instant.now().isBefore(endTime) && process.isAlive) {
                        delay(WATCHDOG_INTERVAL_MS)
                    }
                    if (process.isAlive) {
                        logger.info { "DesktopMeetingRecorder: end time reached for task=${trigger.taskId}" }
                        stopRecording(trigger.taskId, "END_TIME")
                    }
                }

                activeSessions[trigger.taskId] = RecordingSession(
                    taskId = trigger.taskId,
                    meetingId = meeting.id,
                    process = process,
                    pumpJob = pumpJob,
                    watchdogJob = watchdog,
                    startedAt = Instant.now(),
                    endTime = endTime,
                )
            } catch (e: Exception) {
                logger.error(e) { "DesktopMeetingRecorder: failed to start recording for task=${trigger.taskId}" }
            }
        }
    }

    private fun stopRecording(taskId: String, reason: String) {
        val session = activeSessions.remove(taskId) ?: return
        scope.launch {
            try {
                logger.info { "DesktopMeetingRecorder: stopping recording task=$taskId reason=$reason" }
                runCatching { session.process.destroy() }
                // Give ffmpeg ~2s to flush, then force-kill.
                if (session.process.isAlive) {
                    delay(2_000)
                    runCatching { session.process.destroyForcibly() }
                }
                session.pumpJob.cancel()
                session.watchdogJob.cancel()

                val durationSeconds = (Instant.now().epochSecond - session.startedAt.epochSecond)
                    .coerceAtLeast(1L)
                repository.meetings.finalizeRecording(
                    MeetingFinalizeDto(
                        meetingId = session.meetingId,
                        meetingType = MeetingTypeEnum.MEETING,
                        durationSeconds = durationSeconds,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "DesktopMeetingRecorder: error during stop for task=$taskId" }
            }
        }
    }

    /**
     * Read raw PCM from ffmpeg stdout in fixed-size frames and forward each
     * frame as a base64 [AudioChunkDto] to the server. Frame size is chosen
     * to give ~5s of audio: `16000 Hz * 2 bytes * 5s = 160000 B`.
     */
    private suspend fun pumpAudio(meetingId: String, stream: InputStream) {
        val frame = ByteArray(CHUNK_SIZE_BYTES)
        var chunkIndex = 0
        try {
            while (true) {
                val read = readFully(stream, frame)
                if (read <= 0) break
                val payload = if (read == frame.size) frame else frame.copyOf(read)
                val b64 = Base64.getEncoder().encodeToString(payload)
                runCatching {
                    repository.meetings.uploadAudioChunk(
                        AudioChunkDto(
                            meetingId = meetingId,
                            chunkIndex = chunkIndex,
                            data = b64,
                            mimeType = "audio/pcm",
                        ),
                    )
                }.onFailure { e ->
                    logger.warn { "DesktopMeetingRecorder: chunk upload failed (idx=$chunkIndex): ${e.message}" }
                }
                chunkIndex++
            }
        } catch (e: Exception) {
            logger.error(e) { "DesktopMeetingRecorder: pumpAudio failed for meeting=$meetingId" }
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) -1 else off
            off += n
        }
        return off
    }

    /**
     * Spawn an ffmpeg process configured for the current OS's loopback device.
     * Output is raw signed 16-bit little-endian PCM, mono, 16 kHz on stdout.
     */
    private fun spawnFfmpeg(): Process {
        val os = System.getProperty("os.name").lowercase()
        val configuredDevice = loopbackDeviceProvider()

        val inputArgs: List<String> = when {
            "mac" in os || "darwin" in os -> {
                // BlackHole 2ch device, configured by user. avfoundation index
                // form is `:<idx>` for audio-only. We accept device name and let
                // ffmpeg resolve it via `-i ":${name}"`.
                val device = configuredDevice ?: "BlackHole 2ch"
                listOf("-f", "avfoundation", "-i", ":$device")
            }
            "win" in os -> {
                // Windows: WASAPI loopback on the default render device.
                // Override via preference: full DirectShow audio device name.
                val device = configuredDevice
                if (device != null) {
                    listOf("-f", "dshow", "-i", "audio=$device")
                } else {
                    listOf("-f", "wasapi", "-i", "loopback")
                }
            }
            else -> {
                // Linux: PulseAudio monitor source. Override via preference.
                val device = configuredDevice ?: "default.monitor"
                listOf("-f", "pulse", "-i", device)
            }
        }

        val command = mutableListOf("ffmpeg", "-loglevel", "warning", "-nostdin")
        command += inputArgs
        command += listOf(
            "-ac", "1",            // mono
            "-ar", "16000",        // 16 kHz
            "-f", "s16le",         // raw signed 16-bit LE PCM
            "pipe:1",
        )

        logger.info { "DesktopMeetingRecorder: spawning ffmpeg: ${command.joinToString(" ")}" }
        return ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
    }

    private fun parseInstantOrNow(raw: String): Instant =
        runCatching { Instant.parse(raw) }.getOrElse { Instant.now().plusSeconds(3600) }

    fun shutdown() {
        activeSessions.keys.toList().forEach { stopRecording(it, "USER_STOP") }
    }

    companion object {
        // 16000 Hz * 2 B/sample * 1 channel * 5s = 160000 B per chunk
        private const val CHUNK_SIZE_BYTES = 160_000
        private const val WATCHDOG_INTERVAL_MS = 5_000L
    }
}
