package com.jervis.service.meeting

import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.entity.meeting.TranscriptSegment
import com.jervis.repository.MeetingRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates Whisper transcription for uploaded meeting recordings.
 *
 * Called by MeetingContinuousIndexer when a meeting reaches UPLOADED state.
 * Uses WhisperJobRunner (K8s Job in-cluster, subprocess locally).
 */
@Service
class MeetingTranscriptionService(
    private val meetingRepository: MeetingRepository,
    private val whisperJobRunner: WhisperJobRunner,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {

    /**
     * Transcribe a meeting's audio file using Whisper.
     *
     * @param meeting MeetingDocument in UPLOADED state
     * @return Updated MeetingDocument in TRANSCRIBED or FAILED state
     */
    suspend fun transcribe(meeting: MeetingDocument): MeetingDocument {
        require(meeting.state == MeetingStateEnum.UPLOADED) {
            "Can only transcribe UPLOADED meetings, got: ${meeting.state}"
        }
        require(!meeting.audioFilePath.isNullOrBlank()) {
            "Meeting ${meeting.id} has no audio file path"
        }

        logger.info { "Starting transcription for meeting ${meeting.id} (audio: ${meeting.audioFilePath})" }

        val meetingIdStr = meeting.id.toHexString()
        val clientIdStr = meeting.clientId.toString()

        // Mark as TRANSCRIBING
        val transcribing = meetingRepository.save(meeting.copy(state = MeetingStateEnum.TRANSCRIBING))
        notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.TRANSCRIBING.name, meeting.title)

        try {
            // Derive workspace path from audio file path (parent of the audio file)
            val audioPath = java.nio.file.Paths.get(meeting.audioFilePath)
            val workspacePath = audioPath.parent.toString()

            val result = whisperJobRunner.transcribe(
                audioFilePath = meeting.audioFilePath,
                workspacePath = workspacePath,
                meetingId = meetingIdStr,
                clientId = clientIdStr,
            )

            if (!result.error.isNullOrBlank()) {
                logger.error { "Whisper returned error for meeting ${meeting.id}: ${result.error}" }
                val failed = meetingRepository.save(
                    transcribing.copy(
                        state = MeetingStateEnum.FAILED,
                        errorMessage = "Whisper error: ${result.error}",
                    ),
                )
                notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, result.error)
                return failed
            }

            val segments = result.segments.map { seg ->
                TranscriptSegment(
                    startSec = seg.start,
                    endSec = seg.end,
                    text = seg.text.trim(),
                )
            }

            val transcribed = meetingRepository.save(
                transcribing.copy(
                    state = MeetingStateEnum.TRANSCRIBED,
                    transcriptText = result.text,
                    transcriptSegments = segments,
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.TRANSCRIBED.name, meeting.title)

            logger.info {
                "Transcription complete for meeting ${meeting.id}: " +
                    "${result.text.length} chars, ${segments.size} segments"
            }

            return transcribed
        } catch (e: Exception) {
            logger.error(e) { "Transcription failed for meeting ${meeting.id}" }
            val failed = meetingRepository.save(
                transcribing.copy(
                    state = MeetingStateEnum.FAILED,
                    errorMessage = "Transcription error: ${e.message}",
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, e.message)
            return failed
        }
    }
}
