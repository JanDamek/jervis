package com.jervis.meeting

import com.jervis.dto.meeting.MeetingStateEnum
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Mid-recording live urgency probe for ongoing meetings.
 *
 * While a Teams/Meet/Zoom meeting is being captured by either the desktop
 * loopback recorder or the k8s `service-meeting-attender` pod, its
 * `MeetingDocument` stays in state `RECORDING` / `UPLOADING` until
 * `finalizeRecording` flips it to `UPLOADED`. The normal transcription
 * pipeline (`MeetingContinuousIndexer.transcribeContinuously`) only runs
 * AFTER `UPLOADED`, so without this probe the urgency detector (name mentions,
 * direct questions, decision keywords) cannot fire until the meeting has
 * ended.
 *
 * This service polls every [PROBE_INTERVAL_MS] for meetings in
 * RECORDING/UPLOADING, carves off just the bytes that have been appended
 * since the previous probe (≥ [MIN_NEW_AUDIO_BYTES] ≈ 20 s of audio so the
 * Whisper call is not wasted on silence), wraps them in a fresh 44-byte WAV
 * header, and transcribes them through [WhisperTranscriptionClient] with a
 * `null` meetingId so the existing `MeetingDocument` state is NOT mutated.
 * Each returned segment is then fed to [MeetingUrgencyDetector], which has
 * its own per-meeting cooldown and will push an `UrgencyNotification` if
 * the user's name, a question or a decision keyword is detected.
 *
 * ## Invariants
 *
 * - Never mutates `MeetingDocument.state`, `transcriptText` or
 *   `transcriptSegments`. The finalizeRecording → UPLOADED → transcribe
 *   pipeline remains the sole writer of those fields.
 * - Serialised with a mutex so only one Whisper probe runs at a time across
 *   the whole server — GPU budget is shared with the post-meeting pipeline.
 * - A meeting's probe offset is kept in memory only; on server restart the
 *   next probe starts from `WAV_HEADER_SIZE` again and will re-analyse
 *   already-seen audio once. That is harmless — the urgency detector
 *   de-duplicates on a rolling text window anyway.
 */
@Service
@Order(13)
class MeetingLiveUrgencyProbe(
    private val meetingRepository: MeetingRepository,
    private val whisperClient: WhisperTranscriptionClient,
    private val urgencyDetector: MeetingUrgencyDetector,
    private val companionAssistant: MeetingCompanionAssistant,
    private val helperService: MeetingHelperService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    /** meetingId → byte offset already probed (inclusive of 44-byte WAV header). */
    private val probedOffsets = ConcurrentHashMap<String, Long>()

    /** meetingId → absolute end second of the last pushed transcript segment.
     *  Used to drop overlapping segments when probe uses backward audio overlap. */
    private val lastEmittedEndSec = ConcurrentHashMap<String, Double>()

    /** Single active probe at a time — GPU is shared with the main pipeline. */
    private val probeMutex = Mutex()

    @PostConstruct
    fun start() {
        logger.info { "MeetingLiveUrgencyProbe: starting live urgency probe loop (interval=${PROBE_INTERVAL_MS}ms)" }
        scope.launch {
            runCatching { probeLoop() }
                .onFailure { e -> logger.error(e) { "MeetingLiveUrgencyProbe: loop crashed" } }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    private suspend fun probeLoop() {
        while (true) {
            try {
                cycle()
            } catch (e: Exception) {
                logger.warn(e) { "MeetingLiveUrgencyProbe: cycle failed" }
            }
            // Dynamic interval: if any meeting has a live companion session,
            // shorten probe cadence to keep the assistant responsive.
            val interval = if (companionAssistant.hasAnyActiveSession())
                PROBE_INTERVAL_FAST_MS else PROBE_INTERVAL_MS
            delay(interval)
        }
    }

    private suspend fun cycle() {
        val active = mutableListOf<MeetingDocument>()
        active += meetingRepository
            .findByStateAndDeletedIsFalseOrderByStoppedAtAsc(MeetingStateEnum.RECORDING)
            .toList()
        active += meetingRepository
            .findByStateAndDeletedIsFalseOrderByStoppedAtAsc(MeetingStateEnum.UPLOADING)
            .toList()

        if (active.isEmpty()) {
            // Purge stale offset entries so the map doesn't leak across long idle periods.
            if (probedOffsets.isNotEmpty()) probedOffsets.clear()
            if (lastEmittedEndSec.isNotEmpty()) lastEmittedEndSec.clear()
            return
        }

        for (meeting in active) {
            probeOne(meeting)
        }
    }

    private suspend fun probeOne(meeting: MeetingDocument) {
        val meetingIdStr = meeting.id.toHexString()
        val audioPathStr = meeting.audioFilePath ?: return
        val audioPath = Path.of(audioPathStr)
        if (!Files.exists(audioPath)) return

        val fileSize = withContext(Dispatchers.IO) { runCatching { Files.size(audioPath) }.getOrDefault(0L) }
        if (fileSize <= WAV_HEADER_SIZE) return

        val lastOffset = probedOffsets[meetingIdStr] ?: WAV_HEADER_SIZE.toLong()
        val newBytes = fileSize - lastOffset
        val companionActive = companionAssistant.hasAnyActiveSession()
        val minNewBytes = if (companionActive) MIN_NEW_AUDIO_BYTES_FAST else MIN_NEW_AUDIO_BYTES
        if (newBytes < minNewBytes) return

        // Backward audio overlap: re-transcribe the trailing OVERLAP_BYTES so Whisper
        // can see the full context of words that were straddling the previous chunk
        // boundary. Segments whose absolute end falls inside the already-emitted
        // range are dropped via the lastEmittedEndSec dedup map below.
        val overlapBytes = if (companionActive) OVERLAP_BYTES_FAST else OVERLAP_BYTES
        val startOffset = maxOf(WAV_HEADER_SIZE.toLong(), lastOffset - overlapBytes)
        val clipStartSecAbs = (startOffset - WAV_HEADER_SIZE).toDouble() / BYTES_PER_SECOND

        probeMutex.withLock {
            val tempPath = withContext(Dispatchers.IO) {
                Files.createTempFile("jervis-live-probe-", ".wav")
            }
            try {
                writeTailAsWav(audioPath, startOffset, fileSize, tempPath)
                val result = whisperClient.transcribe(
                    audioFilePath = tempPath.toString(),
                    meetingId = null, // no state mutation
                    clientId = meeting.clientId?.toString(),
                    projectId = meeting.projectId?.toString(),
                    diarize = false, // live tail probe — speakers irrelevant, avoid pyannote latency
                )
                if (result.error != null) {
                    logger.warn { "MeetingLiveUrgencyProbe: whisper error for $meetingIdStr: ${result.error}" }
                    return@withLock
                }
                val clientIdStr = meeting.clientId?.toString().orEmpty()
                val lastEndSec = lastEmittedEndSec[meetingIdStr] ?: 0.0
                var maxEndSec = lastEndSec
                var emitted = 0
                var skipped = 0
                for (segment in result.segments) {
                    val text = segment.text.trim().takeIf { it.isNotBlank() } ?: continue
                    val absStart = clipStartSecAbs + segment.start
                    val absEnd = clipStartSecAbs + segment.end
                    // Drop segments fully covered by the previously emitted range (0.3s tolerance).
                    if (absEnd <= lastEndSec + 0.3) { skipped++; continue }
                    runCatching { urgencyDetector.analyzeSegment(meetingIdStr, clientIdStr, text) }
                        .onFailure { e -> logger.debug { "urgencyDetector failed: ${e.message}" } }
                    runCatching { companionAssistant.forwardSegment(meetingIdStr, text) }
                        .onFailure { e -> logger.debug { "companionAssistant.forwardSegment failed: ${e.message}" } }
                    runCatching {
                        helperService.pushMessage(
                            meetingIdStr,
                            com.jervis.dto.meeting.HelperMessageDto(
                                type = com.jervis.dto.meeting.HelperMessageType.TRANSCRIPT,
                                text = text,
                                timestamp = java.time.Instant.now().toString(),
                            ),
                        )
                    }.onFailure { e -> logger.debug { "helperService.pushMessage TRANSCRIPT failed: ${e.message}" } }
                    if (absEnd > maxEndSec) maxEndSec = absEnd
                    emitted++
                }
                if (maxEndSec > lastEndSec) lastEmittedEndSec[meetingIdStr] = maxEndSec
                probedOffsets[meetingIdStr] = fileSize
                logger.debug {
                    "MeetingLiveUrgencyProbe: probed ${newBytes}B tail (overlap=${overlapBytes}B) of $meetingIdStr, " +
                        "${result.segments.size} segs (emit=$emitted skip=$skipped) lang=${result.language}"
                }
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching { Files.deleteIfExists(tempPath) }
                }
            }
        }
    }

    /**
     * Reads `[fromOffset, toOffset)` raw PCM bytes from the live recording
     * WAV file and writes them into [tempPath] with a fresh 44-byte WAV
     * header so the Whisper service can decode the standalone clip.
     *
     * The recorder always writes 16 kHz / 16-bit / mono — see
     * [DesktopMeetingRecorder] and `service-meeting-attender/main.py`.
     */
    private suspend fun writeTailAsWav(
        source: Path,
        fromOffset: Long,
        toOffset: Long,
        tempPath: Path,
    ) = withContext(Dispatchers.IO) {
        val dataLen = (toOffset - fromOffset).toInt()
        val header = buildWavHeader(dataLen)
        Files.newOutputStream(
            tempPath,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { out ->
            out.write(header)
            Files.newInputStream(source, StandardOpenOption.READ).use { input ->
                // Skip past already-written content (WAV header + previously probed bytes)
                var remaining = fromOffset
                val skipBuf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val toSkip = minOf(remaining, skipBuf.size.toLong()).toInt()
                    val read = input.read(skipBuf, 0, toSkip)
                    if (read <= 0) return@use
                    remaining -= read
                }
                val copyBuf = ByteArray(64 * 1024)
                var left = dataLen.toLong()
                while (left > 0) {
                    val toRead = minOf(left, copyBuf.size.toLong()).toInt()
                    val read = input.read(copyBuf, 0, toRead)
                    if (read <= 0) break
                    out.write(copyBuf, 0, read)
                    left -= read
                }
            }
        }
    }

    private fun buildWavHeader(dataLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)             // PCM fmt chunk size
        buf.putShort(1)            // PCM format
        buf.putShort(1)            // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2) // byte rate (sr * channels * bytesPerSample)
        buf.putShort(2)            // block align (channels * bytesPerSample)
        buf.putShort(16)           // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        return buf.array()
    }

    companion object {
        /** Baseline probe cadence (no live assistant). */
        private const val PROBE_INTERVAL_MS = 45_000L
        /** Fast cadence when a companion assistant session is active. */
        private const val PROBE_INTERVAL_FAST_MS = 12_000L
        private const val WAV_HEADER_SIZE = 44
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = 32_000 // 16kHz * 2B * 1ch
        /** Skip baseline probe if less than ~20 s of new audio accumulated. */
        private const val MIN_NEW_AUDIO_BYTES = 20 * BYTES_PER_SECOND
        /** Skip fast probe if less than ~8 s of new audio accumulated. */
        private const val MIN_NEW_AUDIO_BYTES_FAST = 8 * BYTES_PER_SECOND
        /** Backward overlap (baseline) — Whisper needs context to not cut words off. */
        private const val OVERLAP_BYTES = 2 * BYTES_PER_SECOND
        /** Fast-mode overlap — tighter to keep per-chunk cost low. */
        private const val OVERLAP_BYTES_FAST = 2 * BYTES_PER_SECOND
    }
}
