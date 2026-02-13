package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingUploadStateDto
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.entity.meeting.CorrectionChatMessage
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.repository.MeetingRepository
import com.jervis.service.IMeetingService
import com.jervis.configuration.CorrectionInstructRequestDto
import com.jervis.configuration.CorrectionSegmentDto
import com.jervis.configuration.CorrectionSubmitRequestDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.service.meeting.TranscriptCorrectionService
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Component
class MeetingRpcImpl(
    private val meetingRepository: MeetingRepository,
    private val directoryStructureService: DirectoryStructureService,
    private val knowledgeService: com.jervis.knowledgebase.KnowledgeService,
    private val transcriptCorrectionService: TranscriptCorrectionService,
    private val orchestratorClient: PythonOrchestratorClient,
    private val correctionClient: com.jervis.configuration.CorrectionClient,
    private val whisperJobRunner: com.jervis.service.meeting.WhisperJobRunner,
    private val notificationRpc: NotificationRpcImpl,
) : IMeetingService {

    override suspend fun startRecording(request: MeetingCreateDto): MeetingDto {
        val clientId = ClientId.fromString(request.clientId)
        val projectId = request.projectId?.let { ProjectId.fromString(it) }

        // Resolve storage directory
        val meetingsDir = if (projectId != null) {
            directoryStructureService.projectMeetingsDir(clientId, projectId)
        } else {
            directoryStructureService.clientAudioDir(clientId)
        }

        val meetingId = ObjectId.get()
        val audioFileName = "meeting_${meetingId.toHexString()}.wav"
        val audioFilePath = meetingsDir.resolve(audioFileName)

        // Create audio file with WAV header (clients send raw PCM chunks, server owns the header)
        withContext(Dispatchers.IO) {
            Files.write(audioFilePath, createWavHeader(), StandardOpenOption.CREATE_NEW)
        }

        val document = MeetingDocument(
            id = meetingId,
            clientId = clientId,
            projectId = projectId,
            title = request.title,
            meetingType = request.meetingType,
            audioInputType = request.audioInputType,
            state = MeetingStateEnum.RECORDING,
            audioFilePath = audioFilePath.toString(),
            startedAt = Instant.now(),
        )

        val saved = meetingRepository.save(document)
        logger.info { "Started meeting recording: ${saved.id} at $audioFilePath" }
        return saved.toDto()
    }

    override suspend fun uploadAudioChunk(chunk: AudioChunkDto): Boolean {
        val meetingId = ObjectId(chunk.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalStateException("Meeting not found: ${chunk.meetingId}")

        if (meeting.state != MeetingStateEnum.RECORDING && meeting.state != MeetingStateEnum.UPLOADING) {
            throw IllegalStateException("Meeting ${chunk.meetingId} is not in recording/uploading state: ${meeting.state}")
        }

        // Idempotency: skip chunks already received (prevents duplicates on retry)
        if (chunk.chunkIndex < meeting.chunkCount) {
            logger.info { "Skipping duplicate chunk ${chunk.chunkIndex} for meeting ${chunk.meetingId} (already have ${meeting.chunkCount} chunks)" }
            return true
        }

        val audioFilePath = meeting.audioFilePath
            ?: throw IllegalStateException("Meeting ${chunk.meetingId} has no audio file path")

        // Decode Base64 and append to file
        val audioBytes = Base64.getDecoder().decode(chunk.data)
        withContext(Dispatchers.IO) {
            Files.write(
                java.nio.file.Paths.get(audioFilePath),
                audioBytes,
                StandardOpenOption.APPEND,
            )
        }

        // Update chunk count and size
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.UPLOADING,
                chunkCount = meeting.chunkCount + 1,
                audioSizeBytes = meeting.audioSizeBytes + audioBytes.size,
            ),
        )

        logger.info { "Received chunk ${chunk.chunkIndex} for meeting ${chunk.meetingId} (${audioBytes.size} bytes)" }
        return true
    }

    override suspend fun finalizeRecording(request: MeetingFinalizeDto): MeetingDto {
        val meetingId = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalStateException("Meeting not found: ${request.meetingId}")

        // Compute duration from actual WAV file size (16kHz, 16-bit, mono = 32000 bytes/sec)
        // and fix the WAV header with actual data size
        val fileDuration = withContext(Dispatchers.IO) {
            meeting.audioFilePath?.let { path ->
                val file = java.nio.file.Paths.get(path)
                if (Files.exists(file)) {
                    val fileSize = Files.size(file)
                    if (fileSize > 44) {
                        fixWavHeader(file, fileSize)
                        (fileSize - 44) / 32000
                    } else {
                        request.durationSeconds
                    }
                } else {
                    request.durationSeconds
                }
            } ?: request.durationSeconds
        }

        val updated = meeting.copy(
            state = MeetingStateEnum.UPLOADED,
            title = request.title,
            meetingType = request.meetingType,
            durationSeconds = fileDuration,
            stoppedAt = Instant.now(),
        )

        val saved = meetingRepository.save(updated)
        logger.info { "Finalized meeting ${request.meetingId}: type=${request.meetingType}, duration=${fileDuration}s (file-based)" }
        return saved.toDto()
    }

    override suspend fun cancelRecording(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        // Delete audio file
        meeting.audioFilePath?.let { path ->
            withContext(Dispatchers.IO) {
                val file = java.nio.file.Paths.get(path)
                Files.deleteIfExists(file)
            }
        }

        meetingRepository.deleteById(id)
        logger.info { "Cancelled and deleted meeting recording: $meetingId" }
        return true
    }

    override suspend fun listMeetings(clientId: String, projectId: String?): List<MeetingDto> {
        val cid = ClientId.fromString(clientId)
        val meetings = if (projectId != null) {
            val pid = ProjectId.fromString(projectId)
            meetingRepository.findByClientIdAndProjectIdAndDeletedIsFalseOrderByStartedAtDesc(cid, pid)
        } else {
            meetingRepository.findByClientIdAndDeletedIsFalseOrderByStartedAtDesc(cid)
        }
        return meetings.toList().map { it.toDto() }
    }

    override suspend fun getMeeting(meetingId: String): MeetingDto {
        val meeting = meetingRepository.findById(ObjectId(meetingId))
            ?: throw IllegalStateException("Meeting not found: $meetingId")
        return meeting.toDto()
    }

    override suspend fun getAudioData(meetingId: String): String {
        val meeting = meetingRepository.findById(ObjectId(meetingId))
            ?: throw IllegalStateException("Meeting not found: $meetingId")
        val audioPath = meeting.audioFilePath
            ?: throw IllegalStateException("Meeting $meetingId has no audio file")

        return withContext(Dispatchers.IO) {
            val file = java.nio.file.Paths.get(audioPath)
            if (!Files.exists(file)) {
                throw IllegalStateException("Audio file not found: $audioPath")
            }
            Base64.getEncoder().encodeToString(Files.readAllBytes(file))
        }
    }

    override suspend fun deleteMeeting(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        // Purge KB data if meeting was indexed
        if (meeting.state == MeetingStateEnum.INDEXED) {
            try {
                val sourceUrn = com.jervis.common.types.SourceUrn.meeting(
                    meetingId = meeting.id.toHexString(),
                    title = meeting.title,
                )
                knowledgeService.purge(sourceUrn.toString())
                logger.info { "Purged KB data for meeting $meetingId on soft delete" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to purge KB data for meeting $meetingId" }
            }
        }

        // Soft delete — move to trash
        meetingRepository.save(meeting.copy(deleted = true, deletedAt = Instant.now()))
        logger.info { "Soft-deleted meeting: $meetingId (moved to trash)" }
        return true
    }

    override suspend fun restoreMeeting(meetingId: String): MeetingDto {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")
        require(meeting.deleted) { "Meeting $meetingId is not in trash" }

        val restored = meetingRepository.save(meeting.copy(deleted = false, deletedAt = null))
        logger.info { "Restored meeting from trash: $meetingId" }
        return restored.toDto()
    }

    override suspend fun permanentlyDeleteMeeting(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")
        require(meeting.deleted) { "Meeting $meetingId must be in trash before permanent deletion" }

        // Delete audio file from disk
        meeting.audioFilePath?.let { path ->
            withContext(Dispatchers.IO) {
                val file = java.nio.file.Paths.get(path)
                Files.deleteIfExists(file)
            }
        }

        // Purge KB data (belt-and-suspenders)
        if (meeting.state == MeetingStateEnum.INDEXED) {
            try {
                val sourceUrn = com.jervis.common.types.SourceUrn.meeting(
                    meetingId = meeting.id.toHexString(),
                    title = meeting.title,
                )
                knowledgeService.purge(sourceUrn.toString())
            } catch (_: Exception) {}
        }

        meetingRepository.deleteById(id)
        logger.info { "Permanently deleted meeting: $meetingId" }
        return true
    }

    override suspend fun listDeletedMeetings(clientId: String, projectId: String?): List<MeetingDto> {
        val cid = ClientId.fromString(clientId)
        val meetings = if (projectId != null) {
            val pid = ProjectId.fromString(projectId)
            meetingRepository.findByClientIdAndProjectIdAndDeletedIsTrueOrderByDeletedAtDesc(cid, pid)
        } else {
            meetingRepository.findByClientIdAndDeletedIsTrueOrderByDeletedAtDesc(cid)
        }
        return meetings.toList().map { it.toDto() }
    }

    override suspend fun retranscribeMeeting(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state !in listOf(
                MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTING,
                MeetingStateEnum.CORRECTION_REVIEW, MeetingStateEnum.CORRECTED,
                MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED,
            )
        ) {
            throw IllegalStateException("Cannot re-transcribe meeting in state ${meeting.state}")
        }

        // Purge KB if was indexed
        if (meeting.state == MeetingStateEnum.INDEXED) {
            try {
                val sourceUrn = com.jervis.common.types.SourceUrn.meeting(
                    meetingId = meeting.id.toHexString(),
                    title = meeting.title,
                )
                knowledgeService.purge(sourceUrn.toString())
                logger.info { "Purged KB data for meeting $meetingId on re-transcribe" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to purge KB data for meeting $meetingId" }
            }
        }

        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.UPLOADED,
                transcriptText = null,
                transcriptSegments = emptyList(),
                correctedTranscriptText = null,
                correctedTranscriptSegments = emptyList(),
                correctionQuestions = emptyList(),
                errorMessage = null,
            ),
        )
        logger.info { "Reset meeting $meetingId to UPLOADED for re-transcription" }
        return true
    }

    override suspend fun recorrectMeeting(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state !in listOf(
                MeetingStateEnum.CORRECTED, MeetingStateEnum.CORRECTION_REVIEW,
                MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED,
            )
        ) {
            throw IllegalStateException("Cannot re-correct meeting in state ${meeting.state}")
        }

        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.TRANSCRIBED,
                correctedTranscriptText = null,
                correctedTranscriptSegments = emptyList(),
                correctionQuestions = emptyList(),
                errorMessage = null,
            ),
        )
        logger.info { "Reset meeting $meetingId to TRANSCRIBED for re-correction" }
        return true
    }

    override suspend fun answerCorrectionQuestions(
        meetingId: String,
        answers: List<CorrectionAnswerDto>,
    ): Boolean {
        return transcriptCorrectionService.answerQuestions(meetingId, answers)
    }

    override suspend fun reindexMeeting(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state != MeetingStateEnum.INDEXED) {
            throw IllegalStateException("Can only re-index INDEXED meetings, got: ${meeting.state}")
        }

        // Purge old KB data before re-indexing
        val sourceUrn = com.jervis.common.types.SourceUrn.meeting(
            meetingId = meeting.id.toHexString(),
            title = meeting.title,
        )
        val purged = knowledgeService.purge(sourceUrn.toString())
        logger.info { "Purged KB data for meeting $meetingId: success=$purged" }

        meetingRepository.save(meeting.copy(state = MeetingStateEnum.CORRECTED))
        logger.info { "Reset meeting $meetingId to CORRECTED for re-indexing" }
        return true
    }

    override suspend fun dismissMeetingError(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state != MeetingStateEnum.FAILED) {
            throw IllegalStateException("Meeting is not in FAILED state")
        }
        if (meeting.transcriptSegments.isEmpty()) {
            throw IllegalStateException("Cannot dismiss: no transcript exists. Use retranscribe instead.")
        }

        val targetState = when {
            meeting.correctedTranscriptSegments.isNotEmpty() -> MeetingStateEnum.CORRECTED
            else -> MeetingStateEnum.TRANSCRIBED
        }

        meetingRepository.save(
            meeting.copy(
                state = targetState,
                errorMessage = null,
                stateChangedAt = Instant.now(),
            ),
        )
        logger.info { "Dismissed error for meeting $meetingId, reset to $targetState" }
        return true
    }

    override suspend fun retranscribeSegments(meetingId: String, segmentIndices: List<Int>): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state !in listOf(
                MeetingStateEnum.TRANSCRIBED, MeetingStateEnum.CORRECTION_REVIEW,
                MeetingStateEnum.CORRECTED, MeetingStateEnum.INDEXED, MeetingStateEnum.FAILED,
            )
        ) {
            throw IllegalStateException("Cannot retranscribe segments in state ${meeting.state}")
        }

        transcriptCorrectionService.retranscribeSelectedSegments(meetingId, segmentIndices)
        return true
    }

    override suspend fun applySegmentCorrection(
        meetingId: String,
        segmentIndex: Int,
        correctedText: String,
    ): MeetingDto {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        // Use corrected segments if available, otherwise raw
        val segments = meeting.correctedTranscriptSegments.ifEmpty {
            meeting.transcriptSegments
        }

        require(segmentIndex in segments.indices) {
            "Segment index $segmentIndex out of range (0..${segments.size - 1})"
        }

        val originalText = segments[segmentIndex].text

        // Update the segment text
        val updatedSegments = segments.toMutableList()
        updatedSegments[segmentIndex] = updatedSegments[segmentIndex].copy(text = correctedText)

        val updatedText = updatedSegments.joinToString(" ") { it.text.trim() }

        val saved = meetingRepository.save(
            meeting.copy(
                correctedTranscriptSegments = updatedSegments,
                correctedTranscriptText = updatedText,
            ),
        )

        // Save as KB correction rule in background
        try {
            correctionClient.submitCorrection(
                CorrectionSubmitRequestDto(
                    clientId = meeting.clientId.toString(),
                    projectId = meeting.projectId?.toString(),
                    original = originalText,
                    corrected = correctedText,
                ),
            )
            logger.info { "Saved correction rule: '$originalText' -> '$correctedText'" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save correction rule to KB" }
        }

        logger.info { "Applied segment correction for meeting $meetingId: segment $segmentIndex" }
        return saved.toDto()
    }

    override suspend fun stopTranscription(meetingId: String): Boolean {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        if (meeting.state != MeetingStateEnum.TRANSCRIBING) {
            throw IllegalStateException("Meeting is not in TRANSCRIBING state: ${meeting.state}")
        }

        // Delete K8s job (best-effort)
        whisperJobRunner.deleteJobForMeeting(meetingId)

        // Reset to UPLOADED for re-transcription
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.UPLOADED,
                stateChangedAt = Instant.now(),
                errorMessage = null,
            ),
        )

        notificationRpc.emitMeetingStateChanged(
            meetingId, meeting.clientId.toString(), MeetingStateEnum.UPLOADED.name, meeting.title,
        )
        logger.info { "Stopped transcription for meeting $meetingId, reset to UPLOADED" }
        return true
    }

    override suspend fun getUploadState(meetingId: String): MeetingUploadStateDto {
        val meeting = meetingRepository.findById(ObjectId(meetingId))
            ?: throw IllegalStateException("Meeting not found: $meetingId")
        return MeetingUploadStateDto(
            meetingId = meeting.id.toHexString(),
            state = meeting.state,
            chunkCount = meeting.chunkCount,
            audioSizeBytes = meeting.audioSizeBytes,
        )
    }

    override suspend fun correctWithInstruction(meetingId: String, instruction: String): MeetingDto {
        val id = ObjectId(meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        // Use corrected segments if available, otherwise raw
        val segments = meeting.correctedTranscriptSegments.ifEmpty {
            meeting.transcriptSegments
        }

        if (segments.isEmpty()) {
            throw IllegalStateException("Meeting $meetingId has no transcript segments")
        }

        // Persist user message in chat history
        val userMessage = CorrectionChatMessage(
            role = "user",
            text = instruction,
        )
        val chatHistory = meeting.correctionChatHistory + userMessage
        meetingRepository.save(meeting.copy(correctionChatHistory = chatHistory))

        val requestSegments = segments.mapIndexed { i, seg ->
            CorrectionSegmentDto(
                i = i,
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
            )
        }

        val result = try {
            correctionClient.correctWithInstruction(
                CorrectionInstructRequestDto(
                    clientId = meeting.clientId.toString(),
                    projectId = meeting.projectId?.toString(),
                    segments = requestSegments,
                    instruction = instruction,
                ),
            )
        } catch (e: Exception) {
            // Persist error agent message
            val errorMessage = CorrectionChatMessage(
                role = "agent",
                text = "Chyba pri oprave: ${e.message}",
                status = "error",
            )
            val updatedMeeting = meetingRepository.findById(id)!!
            meetingRepository.save(updatedMeeting.copy(
                correctionChatHistory = updatedMeeting.correctionChatHistory + errorMessage,
            ))
            throw e
        }

        val correctedSegments = result.segments.mapIndexed { i, corrSeg ->
            val original = segments.getOrNull(i)
            com.jervis.entity.meeting.TranscriptSegment(
                startSec = original?.startSec ?: corrSeg.startSec,
                endSec = original?.endSec ?: corrSeg.endSec,
                text = corrSeg.text,
                speaker = original?.speaker ?: corrSeg.speaker,
            )
        }

        val correctedText = correctedSegments.joinToString(" ") { it.text.trim() }

        // Persist agent response message
        val summaryText = result.summary ?: "Opraveno. Pravidel vytvoreno: ${result.newRules.size}."
        val agentMessage = CorrectionChatMessage(
            role = "agent",
            text = summaryText,
            rulesCreated = result.newRules.size,
        )

        // Re-read to get latest chat history (includes user message saved earlier)
        val latestMeeting = meetingRepository.findById(id)!!
        val saved = meetingRepository.save(
            latestMeeting.copy(
                correctedTranscriptSegments = correctedSegments,
                correctedTranscriptText = correctedText,
                correctionChatHistory = latestMeeting.correctionChatHistory + agentMessage,
            ),
        )

        logger.info {
            "Instruction correction for meeting $meetingId: " +
                "${result.newRules.size} new rules saved, ${correctedText.length} chars"
        }
        return saved.toDto()
    }
}

/** Create a 44-byte WAV header for 16kHz mono 16-bit PCM. Data size is 0 (fixed on finalize). */
private fun createWavHeader(sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteArray(44)

    fun putInt(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        header[offset + 2] = ((value shr 16) and 0xFF).toByte()
        header[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
    fun putShort(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // RIFF header
    "RIFF".toByteArray().copyInto(header, 0)
    putInt(4, 0) // placeholder — fixed on finalize
    "WAVE".toByteArray().copyInto(header, 8)

    // fmt sub-chunk
    "fmt ".toByteArray().copyInto(header, 12)
    putInt(16, 16) // sub-chunk size
    putShort(20, 1) // PCM format
    putShort(22, channels)
    putInt(24, sampleRate)
    putInt(28, byteRate)
    putShort(32, blockAlign)
    putShort(34, bitsPerSample)

    // data sub-chunk
    "data".toByteArray().copyInto(header, 36)
    putInt(40, 0) // placeholder — fixed on finalize

    return header
}

/** Fix WAV header sizes in-place for a file with known total size. */
private fun fixWavHeader(file: java.nio.file.Path, fileSize: Long) {
    val dataSize = (fileSize - 44).toInt()
    val riffSize = (fileSize - 8).toInt()

    val raf = java.io.RandomAccessFile(file.toFile(), "rw")
    raf.use {
        // RIFF chunk size at offset 4
        it.seek(4)
        it.write(riffSize and 0xFF)
        it.write((riffSize shr 8) and 0xFF)
        it.write((riffSize shr 16) and 0xFF)
        it.write((riffSize shr 24) and 0xFF)

        // data chunk size at offset 40
        it.seek(40)
        it.write(dataSize and 0xFF)
        it.write((dataSize shr 8) and 0xFF)
        it.write((dataSize shr 16) and 0xFF)
        it.write((dataSize shr 24) and 0xFF)
    }
}

private fun MeetingDocument.toDto(): MeetingDto =
    MeetingDto(
        id = id.toHexString(),
        clientId = clientId.toString(),
        projectId = projectId?.toString(),
        title = title,
        meetingType = meetingType,
        audioInputType = audioInputType,
        state = state,
        durationSeconds = durationSeconds,
        startedAt = startedAt.toString(),
        stoppedAt = stoppedAt?.toString(),
        transcriptText = transcriptText,
        transcriptSegments = transcriptSegments.map { seg ->
            TranscriptSegmentDto(
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
            )
        },
        correctedTranscriptText = correctedTranscriptText,
        correctedTranscriptSegments = correctedTranscriptSegments.map { seg ->
            TranscriptSegmentDto(
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
            )
        },
        correctionQuestions = correctionQuestions.map { q ->
            CorrectionQuestionDto(
                questionId = q.questionId,
                segmentIndex = q.segmentIndex,
                originalText = q.originalText,
                correctionOptions = q.correctionOptions,
                question = q.question,
                context = q.context,
            )
        },
        correctionChatHistory = correctionChatHistory.map { msg ->
            CorrectionChatMessageDto(
                role = if (msg.role == "user") com.jervis.dto.meeting.CorrectionChatRole.USER else com.jervis.dto.meeting.CorrectionChatRole.AGENT,
                text = msg.text,
                timestamp = msg.timestamp.toString(),
                rulesCreated = msg.rulesCreated,
                status = msg.status,
            )
        },
        stateChangedAt = stateChangedAt?.toString(),
        errorMessage = errorMessage,
        deleted = deleted,
        deletedAt = deletedAt?.toString(),
    )
