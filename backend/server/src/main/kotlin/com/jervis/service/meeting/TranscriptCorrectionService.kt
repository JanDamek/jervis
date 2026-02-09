package com.jervis.service.meeting

import com.jervis.configuration.CorrectionAnswerItemDto
import com.jervis.configuration.CorrectionAnswerRequestDto
import com.jervis.configuration.CorrectionRequestDto
import com.jervis.configuration.CorrectionSegmentDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.entity.meeting.CorrectionQuestion
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.entity.meeting.TranscriptSegment
import com.jervis.repository.MeetingRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.ConnectException

private val logger = KotlinLogging.logger {}

/**
 * Transcript correction service delegating to the Python orchestrator agent.
 *
 * Interactive flow:
 * - TRANSCRIBED → CORRECTING → agent returns corrections + optional questions
 * - If questions: save best-effort corrections + questions → CORRECTION_REVIEW
 * - If no questions: save corrected transcript → CORRECTED
 * - User answers questions → answers saved as KB rules → reset to TRANSCRIBED → re-run
 */
@Service
class TranscriptCorrectionService(
    private val meetingRepository: MeetingRepository,
    private val orchestratorClient: PythonOrchestratorClient,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {
    suspend fun correct(meeting: MeetingDocument): MeetingDocument {
        require(meeting.state in listOf(MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTION_REVIEW)) {
            "Can only correct TRANSCRIBED or CORRECTION_REVIEW meetings, got: ${meeting.state}"
        }

        val meetingIdStr = meeting.id.toHexString()
        val clientIdStr = meeting.clientId.toString()

        logger.info { "Starting transcript correction for meeting ${meeting.id}" }

        val correcting = meetingRepository.save(meeting.copy(state = MeetingStateEnum.CORRECTING, stateChangedAt = java.time.Instant.now()))
        notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.CORRECTING.name, meeting.title)

        try {
            if (meeting.transcriptSegments.isEmpty() && meeting.transcriptText.isNullOrBlank()) {
                logger.warn { "Meeting ${meeting.id} has no transcript, skipping correction" }
                val corrected = meetingRepository.save(
                    correcting.copy(
                        state = MeetingStateEnum.CORRECTED,
                        stateChangedAt = java.time.Instant.now(),
                        correctedTranscriptText = meeting.transcriptText,
                        correctedTranscriptSegments = meeting.transcriptSegments,
                        correctionQuestions = emptyList(),
                    ),
                )
                notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.CORRECTED.name, meeting.title)
                return corrected
            }

            val segments = if (meeting.transcriptSegments.isNotEmpty()) {
                meeting.transcriptSegments
            } else {
                listOf(TranscriptSegment(0.0, 0.0, meeting.transcriptText ?: ""))
            }

            val requestSegments = segments.mapIndexed { i, seg ->
                CorrectionSegmentDto(
                    i = i,
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    text = seg.text,
                    speaker = seg.speaker,
                )
            }

            val result = orchestratorClient.correctTranscript(
                CorrectionRequestDto(
                    clientId = meeting.clientId.toString(),
                    projectId = meeting.projectId?.toString(),
                    meetingId = meetingIdStr,
                    segments = requestSegments,
                ),
            )

            val correctedSegments = result.segments.mapIndexed { i, corrSeg ->
                val original = segments.getOrNull(i)
                TranscriptSegment(
                    startSec = original?.startSec ?: corrSeg.startSec,
                    endSec = original?.endSec ?: corrSeg.endSec,
                    text = corrSeg.text,
                    speaker = original?.speaker ?: corrSeg.speaker,
                )
            }

            val correctedText = correctedSegments.joinToString(" ") { it.text.trim() }

            // Map questions from Python response
            val questions = result.questions.map { q ->
                CorrectionQuestion(
                    questionId = q.id,
                    segmentIndex = q.i,
                    originalText = q.original,
                    correctionOptions = q.options,
                    question = q.question,
                    context = q.context,
                )
            }

            val newState = if (questions.isNotEmpty()) {
                MeetingStateEnum.CORRECTION_REVIEW
            } else {
                MeetingStateEnum.CORRECTED
            }

            val corrected = meetingRepository.save(
                correcting.copy(
                    state = newState,
                    stateChangedAt = java.time.Instant.now(),
                    correctedTranscriptText = correctedText,
                    correctedTranscriptSegments = correctedSegments,
                    correctionQuestions = questions,
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, newState.name, meeting.title)

            logger.info {
                "Correction complete for meeting ${meeting.id}: " +
                    "state=$newState, ${correctedText.length} chars, " +
                    "${correctedSegments.size} segments, ${questions.size} questions"
            }
            return corrected
        } catch (e: Exception) {
            // Connection errors → reset to TRANSCRIBED for auto-retry (orchestrator may be restarting)
            if (isConnectionError(e)) {
                logger.warn(e) { "Connection error during correction for meeting ${meeting.id}, resetting to TRANSCRIBED for retry" }
                val retryable = meetingRepository.save(
                    correcting.copy(
                        state = MeetingStateEnum.TRANSCRIBED,
                        stateChangedAt = java.time.Instant.now(),
                        errorMessage = null,
                    ),
                )
                notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.TRANSCRIBED.name, meeting.title)
                return retryable
            }

            logger.error(e) { "Correction failed for meeting ${meeting.id}" }
            val failed = meetingRepository.save(
                correcting.copy(
                    state = MeetingStateEnum.FAILED,
                    stateChangedAt = java.time.Instant.now(),
                    errorMessage = "Correction error: ${e.message}",
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, e.message)
            return failed
        }
    }

    /**
     * Process user answers to correction questions.
     * Saves answers as KB correction rules, then resets meeting to TRANSCRIBED
     * so the pipeline re-runs correction (this time with rules → no questions).
     */
    suspend fun answerQuestions(
        meetingId: String,
        answers: List<com.jervis.dto.meeting.CorrectionAnswerDto>,
    ): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        require(meeting.state == MeetingStateEnum.CORRECTION_REVIEW) {
            "Can only answer questions for CORRECTION_REVIEW meetings, got: ${meeting.state}"
        }

        // Save answers as KB correction rules via Python orchestrator
        val answerItems = answers.map { a ->
            CorrectionAnswerItemDto(
                original = a.original,
                corrected = a.corrected,
                category = a.category,
            )
        }

        orchestratorClient.answerCorrectionQuestions(
            CorrectionAnswerRequestDto(
                clientId = meeting.clientId.toString(),
                projectId = meeting.projectId?.toString(),
                answers = answerItems,
            ),
        )

        // Reset to TRANSCRIBED so pipeline re-runs correction
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.TRANSCRIBED,
                correctedTranscriptText = null,
                correctedTranscriptSegments = emptyList(),
                correctionQuestions = emptyList(),
                errorMessage = null,
            ),
        )
        notificationRpc.emitMeetingStateChanged(
            meetingId, meeting.clientId.toString(), MeetingStateEnum.TRANSCRIBED.name, meeting.title,
        )

        logger.info { "Answered ${answers.size} questions for meeting $meetingId, reset to TRANSCRIBED" }
        return true
    }

    private fun isConnectionError(e: Exception): Boolean {
        val cause = e.cause ?: e
        return cause is ConnectException ||
            (cause is IOException && cause.message?.let {
                "Connection refused" in it || "Connection reset" in it
            } == true)
    }
}
