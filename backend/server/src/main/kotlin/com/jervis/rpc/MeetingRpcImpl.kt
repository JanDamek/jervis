package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.TranscriptSegmentDto
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

        // Create empty audio file
        withContext(Dispatchers.IO) {
            Files.createFile(audioFilePath)
        }

        val document = MeetingDocument(
            id = meetingId,
            clientId = clientId,
            projectId = projectId,
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
        val fileDuration = withContext(Dispatchers.IO) {
            meeting.audioFilePath?.let { path ->
                val file = java.nio.file.Paths.get(path)
                if (Files.exists(file)) {
                    val fileSize = Files.size(file)
                    if (fileSize > 44) (fileSize - 44) / 32000 else request.durationSeconds
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
            meetingRepository.findByClientIdAndProjectIdOrderByStartedAtDesc(cid, pid)
        } else {
            meetingRepository.findByClientIdOrderByStartedAtDesc(cid)
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

        // Delete audio file
        meeting.audioFilePath?.let { path ->
            withContext(Dispatchers.IO) {
                val file = java.nio.file.Paths.get(path)
                Files.deleteIfExists(file)
            }
        }

        meetingRepository.deleteById(id)
        logger.info { "Deleted meeting: $meetingId" }
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
            orchestratorClient.submitCorrection(
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

        val requestSegments = segments.mapIndexed { i, seg ->
            CorrectionSegmentDto(
                i = i,
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
            )
        }

        val result = orchestratorClient.correctWithInstruction(
            CorrectionInstructRequestDto(
                clientId = meeting.clientId.toString(),
                projectId = meeting.projectId?.toString(),
                segments = requestSegments,
                instruction = instruction,
            ),
        )

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

        val saved = meetingRepository.save(
            meeting.copy(
                correctedTranscriptSegments = correctedSegments,
                correctedTranscriptText = correctedText,
            ),
        )

        logger.info {
            "Instruction correction for meeting $meetingId: " +
                "${result.newRules.size} new rules saved, ${correctedText.length} chars"
        }
        return saved.toDto()
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
        errorMessage = errorMessage,
    )
