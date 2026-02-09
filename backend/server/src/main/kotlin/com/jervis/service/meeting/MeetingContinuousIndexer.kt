package com.jervis.service.meeting

import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.repository.MeetingRepository
import com.jervis.service.background.TaskService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for meeting recordings.
 *
 * FLOW:
 * 1. Poll for UPLOADED meetings -> run Whisper transcription -> TRANSCRIBED
 * 2. Poll for TRANSCRIBED meetings -> LLM correction -> CORRECTED
 * 3. Poll for CORRECTED meetings -> create MEETING_PROCESSING task for KB ingest -> INDEXED
 *
 * Follows the same pattern as EmailContinuousIndexer.
 */
@Service
@Order(12)
class MeetingContinuousIndexer(
    private val meetingRepository: MeetingRepository,
    private val meetingTranscriptionService: MeetingTranscriptionService,
    private val transcriptCorrectionService: TranscriptCorrectionService,
    private val taskService: TaskService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting MeetingContinuousIndexer..." }

        // Pipeline 1: UPLOADED -> transcribe -> TRANSCRIBED
        scope.launch {
            runCatching { transcribeContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting transcription pipeline crashed" } }
        }

        // Pipeline 2: TRANSCRIBED -> LLM correction -> CORRECTED
        scope.launch {
            runCatching { correctContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting correction pipeline crashed" } }
        }

        // Pipeline 3: CORRECTED -> create task -> INDEXED
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting indexing pipeline crashed" } }
        }
    }

    // ===== Pipeline 1: Transcription =====

    private suspend fun transcribeContinuously() {
        continuousMeetingsInState(MeetingStateEnum.UPLOADED).collect { meeting ->
            try {
                meetingTranscriptionService.transcribe(meeting)
            } catch (e: Exception) {
                logger.error(e) { "Failed to transcribe meeting ${meeting.id}" }
                markAsFailed(meeting, "Transcription error: ${e.message}")
            }
        }
    }

    // ===== Pipeline 2: LLM Correction =====

    private suspend fun correctContinuously() {
        continuousMeetingsInState(MeetingStateEnum.TRANSCRIBED).collect { meeting ->
            try {
                transcriptCorrectionService.correct(meeting)
            } catch (e: Exception) {
                logger.error(e) { "Failed to correct meeting ${meeting.id}" }
                markAsFailed(meeting, "Correction error: ${e.message}")
            }
        }
    }

    // ===== Pipeline 3: KB Indexing =====

    private suspend fun indexContinuously() {
        continuousMeetingsInState(MeetingStateEnum.CORRECTED).collect { meeting ->
            try {
                indexMeeting(meeting)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index meeting ${meeting.id}" }
                markAsFailed(meeting, "Indexing error: ${e.message}")
            }
        }
    }

    private suspend fun indexMeeting(meeting: MeetingDocument) {
        require(meeting.state == MeetingStateEnum.CORRECTED) {
            "Can only index CORRECTED meetings, got: ${meeting.state}"
        }

        // Use corrected transcript, fall back to raw
        val transcript = meeting.correctedTranscriptText ?: meeting.transcriptText
        if (transcript.isNullOrBlank()) {
            logger.warn { "Meeting ${meeting.id} has no transcript text, marking as FAILED" }
            markAsFailed(meeting, "No transcript text after correction")
            return
        }

        val meetingContent = buildMeetingContent(meeting)

        taskService.createTask(
            taskType = TaskTypeEnum.MEETING_PROCESSING,
            content = meetingContent,
            clientId = meeting.clientId,
            correlationId = "meeting:${meeting.id}",
            sourceUrn = SourceUrn.meeting(
                meetingId = meeting.id.toHexString(),
                title = meeting.title,
            ),
            projectId = meeting.projectId,
        )

        meetingRepository.save(meeting.copy(state = MeetingStateEnum.INDEXED))
        logger.info { "Created MEETING_PROCESSING task for meeting: ${meeting.title ?: meeting.id}" }
    }

    private fun buildMeetingContent(meeting: MeetingDocument): String = buildString {
        val title = meeting.title ?: "Meeting ${meeting.id}"
        append("# $title\n\n")

        append("**Date:** ${meeting.startedAt}\n")
        meeting.durationSeconds?.let { dur ->
            val duration = Duration.ofSeconds(dur)
            val hours = duration.toHours()
            val minutes = duration.toMinutesPart()
            val seconds = duration.toSecondsPart()
            val formatted = if (hours > 0) {
                "${hours}h ${minutes}m ${seconds}s"
            } else {
                "${minutes}m ${seconds}s"
            }
            append("**Duration:** $formatted\n")
        }
        meeting.meetingType?.let { append("**Type:** ${it.name}\n") }
        meeting.audioInputType.let { append("**Audio Input:** ${it.name}\n") }
        append("\n---\n\n")

        append("## Transcript\n\n")

        // Prefer corrected segments over raw
        val segments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }
        if (segments.isNotEmpty()) {
            segments.forEach { seg ->
                val timestamp = formatTimestamp(seg.startSec)
                val speaker = seg.speaker?.let { "**$it:** " } ?: ""
                append("[$timestamp] $speaker${seg.text}\n")
            }
        } else {
            append(meeting.correctedTranscriptText ?: meeting.transcriptText ?: "")
        }

        append("\n\n## Source Metadata\n")
        append("- **Source Type:** Meeting\n")
        append("- **Meeting ID:** ${meeting.id}\n")
        append("- **Client ID:** ${meeting.clientId}\n")
        meeting.projectId?.let { append("- **Project ID:** $it\n") }
        meeting.title?.let { append("- **Title:** $it\n") }
        meeting.meetingType?.let { append("- **Meeting Type:** ${it.name}\n") }
        append("- **Started At:** ${meeting.startedAt}\n")
        meeting.stoppedAt?.let { append("- **Stopped At:** $it\n") }
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalSec = seconds.toLong()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    // ===== Shared Utilities =====

    private fun continuousMeetingsInState(state: MeetingStateEnum) =
        flow {
            while (true) {
                val meetings = meetingRepository.findByStateOrderByStoppedAtAsc(state)

                var emittedAny = false
                meetings.collect { meeting ->
                    emit(meeting)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No $state meetings, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed $state meetings, immediately checking for more..." }
                }
            }
        }

    private suspend fun markAsFailed(meeting: MeetingDocument, error: String) {
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.FAILED,
                errorMessage = error,
            ),
        )
        logger.warn { "Marked meeting as FAILED: ${meeting.title ?: meeting.id}" }
    }
}
