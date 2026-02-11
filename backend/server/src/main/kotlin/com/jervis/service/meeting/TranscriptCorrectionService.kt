package com.jervis.service.meeting

import com.jervis.configuration.CorrectionAnswerItemDto
import com.jervis.configuration.CorrectionAnswerRequestDto
import com.jervis.configuration.CorrectionRequestDto
import com.jervis.configuration.CorrectionSegmentDto
import com.jervis.configuration.CorrectionTargetedRequestDto
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
    private val whisperJobRunner: WhisperJobRunner,
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
     *
     * Split flow:
     * - Known answers (corrected.isNotBlank) → save as KB rules
     * - Unknown answers (corrected.isBlank, "Nevim") → re-transcribe audio ranges + targeted correction
     * - All known → existing flow (reset to TRANSCRIBED for full re-correction with new rules)
     * - Any unknown → retranscribeAndCorrect()
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

        val knownAnswers = answers.filter { it.corrected.isNotBlank() }
        val unknownAnswers = answers.filter { it.corrected.isBlank() }

        // 1. Save known answers as KB correction rules
        if (knownAnswers.isNotEmpty()) {
            val answerItems = knownAnswers.map { a ->
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
            logger.info { "Saved ${knownAnswers.size} known answers as KB rules for meeting $meetingId" }
        }

        // 2. All known → existing flow (reset to TRANSCRIBED for full re-correction)
        if (unknownAnswers.isEmpty()) {
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
            logger.info { "All answers known for meeting $meetingId, reset to TRANSCRIBED for re-correction" }
            return true
        }

        // 3. Has "Nevim" answers → retranscribe + targeted correction
        logger.info {
            "Meeting $meetingId: ${knownAnswers.size} known, ${unknownAnswers.size} unknown (Nevim) → retranscribe"
        }
        retranscribeAndCorrect(meeting, unknownAnswers, knownAnswers)
        return true
    }

    /**
     * Re-transcribe audio for "Nevim" segments, then run targeted correction.
     *
     * Flow:
     * 1. Set state → CORRECTING
     * 2. Extract audio ±10s around unclear segments
     * 3. Re-transcribe with Whisper large-v3, beam_size=10
     * 4. Merge: user corrections + new Whisper text + untouched segments
     * 5. Targeted correction via Python agent (only affected segments)
     * 6. State → CORRECTED (or CORRECTION_REVIEW if new questions)
     */
    private suspend fun retranscribeAndCorrect(
        meeting: MeetingDocument,
        unknownAnswers: List<com.jervis.dto.meeting.CorrectionAnswerDto>,
        knownAnswers: List<com.jervis.dto.meeting.CorrectionAnswerDto>,
    ) {
        val meetingIdStr = meeting.id.toHexString()
        val clientIdStr = meeting.clientId.toString()

        // Set CORRECTING state
        val correcting = meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.CORRECTING,
                stateChangedAt = java.time.Instant.now(),
            ),
        )
        notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.CORRECTING.name, meeting.title)

        try {
            // Use corrected segments as base (best-effort from first correction pass)
            val baseSegments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }

            // 1. Build extraction ranges with ±10s padding for "Nevim" segments
            val paddingSec = 10.0
            val extractionRanges = unknownAnswers.map { answer ->
                val seg = baseSegments.getOrNull(answer.segmentIndex)
                    ?: throw IllegalStateException("Segment index ${answer.segmentIndex} out of range")
                ExtractionRange(
                    start = (seg.startSec - paddingSec).coerceAtLeast(0.0),
                    end = seg.endSec + paddingSec,
                    segmentIndex = answer.segmentIndex,
                )
            }

            val audioFilePath = meeting.audioFilePath
                ?: throw IllegalStateException("Meeting ${meeting.id} has no audio file")
            val workspacePath = java.nio.file.Paths.get(audioFilePath).parent.toString()

            // 2. Re-transcribe with high-accuracy settings
            logger.info {
                "Retranscribing ${extractionRanges.size} ranges for meeting $meetingIdStr " +
                    "(segments: ${extractionRanges.map { it.segmentIndex }})"
            }
            val whisperResult = whisperJobRunner.retranscribe(
                audioFilePath = audioFilePath,
                workspacePath = workspacePath,
                extractionRanges = extractionRanges,
                meetingId = meetingIdStr,
                clientId = clientIdStr,
                projectId = meeting.projectId?.toString(),
            )

            if (whisperResult.error != null) {
                logger.error { "Retranscription failed for meeting $meetingIdStr: ${whisperResult.error}" }
                throw RuntimeException("Retranscription failed: ${whisperResult.error}")
            }

            // 3. Map re-transcribed text back to segment indices
            val retranscribedSegments = whisperResult.textBySegment.mapKeys { it.key.toInt() }

            logger.info {
                "Retranscription result: ${retranscribedSegments.size} segments re-transcribed " +
                    "(indices: ${retranscribedSegments.keys})"
            }

            // 4. Build merged segment list for targeted correction
            val knownAnswersByIndex = knownAnswers.associateBy { it.segmentIndex }
            val allSegments = baseSegments.mapIndexed { i, seg ->
                val corrSeg = CorrectionSegmentDto(
                    i = i,
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    text = seg.text,
                    speaker = seg.speaker,
                )
                when {
                    // "Nevim" → use new Whisper re-transcription
                    retranscribedSegments.containsKey(i) ->
                        corrSeg.copy(text = retranscribedSegments[i]!!.trim())
                    // Known answer → use user's correction directly
                    knownAnswersByIndex.containsKey(i) ->
                        corrSeg.copy(text = knownAnswersByIndex[i]!!.corrected)
                    // Untouched → keep best-effort correction
                    else -> corrSeg
                }
            }

            // 5. Send to targeted correction endpoint
            val correctionResult = orchestratorClient.correctTargeted(
                CorrectionTargetedRequestDto(
                    clientId = clientIdStr,
                    projectId = meeting.projectId?.toString(),
                    meetingId = meetingIdStr,
                    segments = allSegments,
                    retranscribedIndices = retranscribedSegments.keys.toList(),
                    userCorrectedIndices = knownAnswers.associate {
                        it.segmentIndex.toString() to it.corrected
                    },
                ),
            )

            // 6. Save result
            val correctedSegments = correctionResult.segments.mapIndexed { i, corrSeg ->
                val original = baseSegments.getOrNull(i)
                TranscriptSegment(
                    startSec = original?.startSec ?: corrSeg.startSec,
                    endSec = original?.endSec ?: corrSeg.endSec,
                    text = corrSeg.text,
                    speaker = original?.speaker ?: corrSeg.speaker,
                )
            }
            val correctedText = correctedSegments.joinToString(" ") { it.text.trim() }

            val questions = correctionResult.questions.map { q ->
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

            meetingRepository.save(
                correcting.copy(
                    state = newState,
                    stateChangedAt = java.time.Instant.now(),
                    correctedTranscriptText = correctedText,
                    correctedTranscriptSegments = correctedSegments,
                    correctionQuestions = questions,
                    errorMessage = null,
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, newState.name, meeting.title)

            logger.info {
                "Retranscribe+correct complete for meeting $meetingIdStr: " +
                    "state=$newState, ${correctedText.length} chars, " +
                    "${correctedSegments.size} segments, ${questions.size} questions"
            }
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                // Connection error → reset to CORRECTION_REVIEW so user can retry
                logger.warn(e) {
                    "Connection error during retranscription for meeting $meetingIdStr, " +
                        "resetting to CORRECTION_REVIEW for retry"
                }
                meetingRepository.save(
                    correcting.copy(
                        state = MeetingStateEnum.CORRECTION_REVIEW,
                        stateChangedAt = java.time.Instant.now(),
                    ),
                )
                notificationRpc.emitMeetingStateChanged(
                    meetingIdStr, clientIdStr, MeetingStateEnum.CORRECTION_REVIEW.name, meeting.title,
                )
            } else {
                logger.error(e) { "Retranscription+correction failed for meeting $meetingIdStr" }
                meetingRepository.save(
                    correcting.copy(
                        state = MeetingStateEnum.FAILED,
                        stateChangedAt = java.time.Instant.now(),
                        errorMessage = "Retranscription error: ${e.message}",
                    ),
                )
                notificationRpc.emitMeetingStateChanged(
                    meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, e.message,
                )
            }
        }
    }

    /**
     * Retranscribe specific segments by index (user-initiated from UI).
     * Similar to retranscribeAndCorrect but with explicit segment indices.
     */
    suspend fun retranscribeSelectedSegments(meetingId: String, segmentIndices: List<Int>) {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        val meetingIdStr = meeting.id.toHexString()
        val clientIdStr = meeting.clientId.toString()

        val baseSegments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }
        require(baseSegments.isNotEmpty()) { "Meeting has no transcript segments" }

        // Set CORRECTING state
        val correcting = meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.CORRECTING,
                stateChangedAt = java.time.Instant.now(),
            ),
        )
        notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.CORRECTING.name, meeting.title)

        try {
            // Build extraction ranges with ±10s padding
            val paddingSec = 10.0
            val extractionRanges = segmentIndices.map { idx ->
                val seg = baseSegments.getOrNull(idx)
                    ?: throw IllegalStateException("Segment index $idx out of range")
                ExtractionRange(
                    start = (seg.startSec - paddingSec).coerceAtLeast(0.0),
                    end = seg.endSec + paddingSec,
                    segmentIndex = idx,
                )
            }

            val audioFilePath = meeting.audioFilePath
                ?: throw IllegalStateException("Meeting ${meeting.id} has no audio file")
            val workspacePath = java.nio.file.Paths.get(audioFilePath).parent.toString()

            logger.info {
                "Retranscribing ${extractionRanges.size} segments for meeting $meetingIdStr " +
                    "(indices: $segmentIndices)"
            }

            val whisperResult = whisperJobRunner.retranscribe(
                audioFilePath = audioFilePath,
                workspacePath = workspacePath,
                extractionRanges = extractionRanges,
                meetingId = meetingIdStr,
                clientId = clientIdStr,
                projectId = meeting.projectId?.toString(),
            )

            if (whisperResult.error != null) {
                throw RuntimeException("Retranscription failed: ${whisperResult.error}")
            }

            val retranscribedSegments = whisperResult.textBySegment.mapKeys { it.key.toInt() }

            // Merge: retranscribed text replaces original, rest unchanged
            val allSegments = baseSegments.mapIndexed { i, seg ->
                val corrSeg = CorrectionSegmentDto(
                    i = i,
                    startSec = seg.startSec,
                    endSec = seg.endSec,
                    text = seg.text,
                    speaker = seg.speaker,
                )
                if (retranscribedSegments.containsKey(i)) {
                    corrSeg.copy(text = retranscribedSegments[i]!!.trim())
                } else {
                    corrSeg
                }
            }

            // Targeted correction
            val correctionResult = orchestratorClient.correctTargeted(
                CorrectionTargetedRequestDto(
                    clientId = clientIdStr,
                    projectId = meeting.projectId?.toString(),
                    meetingId = meetingIdStr,
                    segments = allSegments,
                    retranscribedIndices = retranscribedSegments.keys.toList(),
                    userCorrectedIndices = emptyMap(),
                ),
            )

            val correctedSegments = correctionResult.segments.mapIndexed { i, corrSeg ->
                val original = baseSegments.getOrNull(i)
                TranscriptSegment(
                    startSec = original?.startSec ?: corrSeg.startSec,
                    endSec = original?.endSec ?: corrSeg.endSec,
                    text = corrSeg.text,
                    speaker = original?.speaker ?: corrSeg.speaker,
                )
            }
            val correctedText = correctedSegments.joinToString(" ") { it.text.trim() }

            val questions = correctionResult.questions.map { q ->
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

            meetingRepository.save(
                correcting.copy(
                    state = newState,
                    stateChangedAt = java.time.Instant.now(),
                    correctedTranscriptText = correctedText,
                    correctedTranscriptSegments = correctedSegments,
                    correctionQuestions = questions,
                    errorMessage = null,
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, newState.name, meeting.title)

            logger.info {
                "Segment retranscription complete for meeting $meetingIdStr: " +
                    "state=$newState, segments=${segmentIndices}, ${correctedText.length} chars"
            }
        } catch (e: Exception) {
            if (isConnectionError(e)) {
                logger.warn(e) { "Connection error during segment retranscription for meeting $meetingIdStr" }
                val previousState = if (meeting.correctedTranscriptSegments.isNotEmpty()) {
                    MeetingStateEnum.CORRECTED
                } else {
                    MeetingStateEnum.TRANSCRIBED
                }
                meetingRepository.save(
                    correcting.copy(
                        state = previousState,
                        stateChangedAt = java.time.Instant.now(),
                    ),
                )
                notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, previousState.name, meeting.title)
            } else {
                logger.error(e) { "Segment retranscription failed for meeting $meetingIdStr" }
                meetingRepository.save(
                    correcting.copy(
                        state = MeetingStateEnum.FAILED,
                        stateChangedAt = java.time.Instant.now(),
                        errorMessage = "Segment retranscription error: ${e.message}",
                    ),
                )
                notificationRpc.emitMeetingStateChanged(
                    meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, e.message,
                )
            }
        }
    }

    private fun isConnectionError(e: Exception): Boolean {
        val cause = e.cause ?: e
        return cause is ConnectException ||
            (cause is IOException && cause.message?.let {
                "Connection refused" in it || "Connection reset" in it
            } == true)
    }
}
