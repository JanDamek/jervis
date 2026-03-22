package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.AutoSpeakerMatchDto
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.CorrectionQuestionDto
import com.jervis.dto.meeting.MeetingClassifyDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.CorrectionChatMessageDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingGroupDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingSummaryDto
import com.jervis.dto.meeting.MeetingTimelineDto
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
    private val notificationRpc: NotificationRpcImpl,
    private val speakerRepository: com.jervis.repository.SpeakerRepository,
) : IMeetingService {

    override suspend fun startRecording(request: MeetingCreateDto): MeetingDto {
        val clientId = request.clientId?.let { ClientId.fromString(it) }
        val projectId = request.projectId?.let { ProjectId.fromString(it) }

        // Deduplication: return existing active meeting with same (client, type)
        if (clientId != null && request.meetingType != null) {
            val existing = meetingRepository.findActiveByClientIdAndMeetingType(clientId, request.meetingType!!)
            if (existing != null) {
                logger.info { "Dedup: returning existing active meeting ${existing.id} for client=$clientId type=${request.meetingType}" }
                return existing.toDto()
            }
        }

        val meetingId = ObjectId.get()
        val audioFilePath = directoryStructureService.meetingAudioFile(meetingId, clientId, projectId)

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
            deviceSessionId = request.deviceSessionId,
        )

        val saved = meetingRepository.save(document)
        logger.info { "Started meeting recording: ${saved.id} at $audioFilePath (clientId=${clientId ?: "unclassified"})" }
        return saved.toDto()
    }

    override suspend fun uploadAudioChunk(chunk: AudioChunkDto): Int {
        val meetingId = ObjectId(chunk.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalStateException("Meeting not found: ${chunk.meetingId}")

        if (meeting.state != MeetingStateEnum.RECORDING && meeting.state != MeetingStateEnum.UPLOADING) {
            // Re-open meeting for additional data — mobile may send delayed chunks after transcription started
            logger.info { "Meeting ${chunk.meetingId} is ${meeting.state}, re-opening to UPLOADING for additional audio data" }
            meetingRepository.save(meeting.copy(state = MeetingStateEnum.UPLOADING))
        }

        // Idempotency: skip chunks already received (prevents duplicates on retry)
        if (chunk.chunkIndex < meeting.chunkCount) {
            logger.info { "Skipping duplicate chunk ${chunk.chunkIndex} for meeting ${chunk.meetingId} (already have ${meeting.chunkCount} chunks)" }
            return meeting.chunkCount
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
        val newChunkCount = meeting.chunkCount + 1
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.UPLOADING,
                chunkCount = newChunkCount,
                audioSizeBytes = meeting.audioSizeBytes + audioBytes.size,
            ),
        )

        logger.info { "Received chunk ${chunk.chunkIndex} for meeting ${chunk.meetingId} (${audioBytes.size} bytes)" }
        return newChunkCount
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
        val speakerMap = buildSpeakerMap(meeting)
        return meeting.toDto(speakerMap)
    }

    private suspend fun buildSpeakerMap(meeting: com.jervis.entity.meeting.MeetingDocument): Map<String, com.jervis.entity.SpeakerDocument> {
        if (meeting.clientId == null) return emptyMap()
        // Load speakers when there's a mapping OR embeddings (for auto-match display)
        if (meeting.speakerMapping.isEmpty() && meeting.speakerEmbeddings.isNullOrEmpty()) return emptyMap()
        val speakers = speakerRepository.findByClientIdsContainingOrderByNameAsc(meeting.clientId).toList()
        return speakers.associateBy { it.id.toHexString() }
    }

    override suspend fun getAudioData(meetingId: String): String {
        val meeting = meetingRepository.findById(ObjectId(meetingId))
            ?: throw IllegalStateException("Meeting not found: $meetingId")

        val file = directoryStructureService.meetingAudioFile(meeting.id, meeting.clientId, meeting.projectId)
        return withContext(Dispatchers.IO) {
            if (!Files.exists(file)) {
                throw IllegalStateException("Audio file not found: $file")
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
            } catch (e: Exception) {
                logger.warn { "Failed to purge KB data for meeting ${meeting.id}: ${e.message}" }
            }
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
                    clientId = meeting.clientId?.toString().orEmpty(),
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

        // Reset to UPLOADED for re-transcription
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.UPLOADED,
                stateChangedAt = Instant.now(),
                errorMessage = null,
            ),
        )

        notificationRpc.emitMeetingStateChanged(
            meetingId, meeting.clientId?.toString().orEmpty(), MeetingStateEnum.UPLOADED.name, meeting.title,
        )
        logger.info { "Stopped transcription for meeting $meetingId, reset to UPLOADED" }
        return true
    }

    override suspend fun classifyMeeting(request: MeetingClassifyDto): MeetingDto {
        val id = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: ${request.meetingId}")

        val clientId = ClientId.fromString(request.clientId)
        val projectId = request.projectId?.let { ProjectId.fromString(it) }
        val groupId = request.groupId?.let { com.jervis.common.types.ProjectGroupId.fromString(it) }

        // Move audio file and compute correct path via DirectoryStructureService
        val newAudioPath = directoryStructureService.meetingAudioFile(meeting.id, clientId, projectId)
        meeting.audioFilePath?.let { oldPath ->
            val oldFile = java.nio.file.Paths.get(oldPath)
            withContext(Dispatchers.IO) {
                if (Files.exists(oldFile) && oldFile != newAudioPath) {
                    Files.move(oldFile, newAudioPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        // If meeting was INDEXED without clientId, reset to TRANSCRIBED so Pipeline 2
        // re-indexes with the new clientId (KB indexing + qualified=true)
        val needsReindex = meeting.clientId == null && meeting.state == MeetingStateEnum.INDEXED
        val newState = if (needsReindex) MeetingStateEnum.TRANSCRIBED else meeting.state

        val updated = meeting.copy(
            clientId = clientId,
            projectId = projectId,
            groupId = groupId,
            title = request.title ?: meeting.title,
            meetingType = request.meetingType ?: meeting.meetingType,
            audioFilePath = newAudioPath.toString(),
            state = newState,
            stateChangedAt = if (needsReindex) Instant.now() else meeting.stateChangedAt,
        )

        val saved = meetingRepository.save(updated)
        if (needsReindex) {
            logger.info { "Meeting ${request.meetingId} classified and reset to TRANSCRIBED for KB indexing" }
        }
        logger.info { "Classified meeting ${request.meetingId} to client=${request.clientId} project=${request.projectId} group=${request.groupId}" }
        val suggestion = findMergeSuggestion(saved)
        return saved.toDto(mergeSuggestion = suggestion)
    }

    override suspend fun updateMeeting(request: MeetingClassifyDto): MeetingDto {
        val id = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(id)
            ?: throw IllegalStateException("Meeting not found: ${request.meetingId}")

        val newClientId = ClientId.fromString(request.clientId)
        val newProjectId = request.projectId?.let { ProjectId.fromString(it) }
        val newGroupId = request.groupId?.let { com.jervis.common.types.ProjectGroupId.fromString(it) }

        val firstClassification = meeting.clientId == null && newClientId != null
        val clientChanged = meeting.clientId != null && newClientId != meeting.clientId
        val projectChanged = newProjectId != meeting.projectId
        val reassign = clientChanged || projectChanged

        // If reassigning and meeting was indexed, purge old KB data
        if (reassign && !firstClassification && meeting.state == MeetingStateEnum.INDEXED) {
            try {
                val sourceUrn = com.jervis.common.types.SourceUrn.meeting(
                    meetingId = meeting.id.toHexString(),
                    title = meeting.title,
                )
                knowledgeService.purge(sourceUrn.toString())
                logger.info { "Purged KB data for meeting ${request.meetingId} before reassignment" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to purge KB data for meeting ${request.meetingId}" }
            }
        }

        // Move audio file if client/project changed or first classification
        val newAudioPath = directoryStructureService.meetingAudioFile(meeting.id, newClientId, newProjectId)
        if (reassign || firstClassification) {
            meeting.audioFilePath?.let { oldPath ->
                val oldFile = java.nio.file.Paths.get(oldPath)
                withContext(Dispatchers.IO) {
                    if (Files.exists(oldFile) && oldFile != newAudioPath) {
                        Files.move(oldFile, newAudioPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        // Determine new state:
        // - First classification of INDEXED meeting → TRANSCRIBED (needs full KB indexing via Pipeline 2)
        // - Reassignment of INDEXED meeting → CORRECTED (re-index via Pipeline 3.5)
        // - Otherwise → keep current state
        val newState = when {
            firstClassification && meeting.state == MeetingStateEnum.INDEXED -> MeetingStateEnum.TRANSCRIBED
            reassign && meeting.state == MeetingStateEnum.INDEXED -> MeetingStateEnum.CORRECTED
            else -> meeting.state
        }

        val updated = meeting.copy(
            clientId = newClientId,
            projectId = newProjectId,
            groupId = newGroupId,
            title = request.title ?: meeting.title,
            meetingType = request.meetingType ?: meeting.meetingType,
            audioFilePath = newAudioPath.toString(),
            state = newState,
            stateChangedAt = if (newState != meeting.state) Instant.now() else meeting.stateChangedAt,
        )

        val saved = meetingRepository.save(updated)
        if (firstClassification && meeting.state == MeetingStateEnum.INDEXED) {
            logger.info { "Meeting ${request.meetingId} first classified, reset to TRANSCRIBED for KB indexing" }
        }
        logger.info {
            "Updated meeting ${request.meetingId}: title=${request.title}, " +
                "client=${request.clientId}, project=${request.projectId}, group=${request.groupId}, reassign=$reassign"
        }
        val suggestion = findMergeSuggestion(saved)
        return saved.toDto(mergeSuggestion = suggestion)
    }

    /**
     * Find a merge candidate — another meeting with same (clientId, projectId, meetingType)
     * whose startedAt is within ±10 minutes of [meeting].startedAt.
     */
    private suspend fun findMergeSuggestion(meeting: MeetingDocument): com.jervis.dto.meeting.MergeSuggestionDto? {
        val clientId = meeting.clientId ?: return null
        val meetingType = meeting.meetingType ?: return null
        val startedAt = meeting.startedAt
        val windowMinutes = 10L
        val from = startedAt.minusSeconds(windowMinutes * 60)
        val to = startedAt.plusSeconds(windowMinutes * 60)

        val candidates = meetingRepository.findOverlapping(
            clientId = clientId,
            projectId = meeting.projectId,
            meetingType = meetingType,
            excludeId = meeting.id,
            from = from,
            to = to,
        ).toList()

        val candidate = candidates.firstOrNull() ?: return null
        return com.jervis.dto.meeting.MergeSuggestionDto(
            targetMeetingId = candidate.id.toHexString(),
            targetTitle = candidate.title,
            targetStartedAt = candidate.startedAt.toString(),
            targetDurationSeconds = candidate.durationSeconds,
        )
    }

    override suspend fun listUnclassifiedMeetings(): List<MeetingDto> {
        return meetingRepository.findByClientIdIsNullAndDeletedIsFalseOrderByStartedAtDesc()
            .toList()
            .map { it.toDto() }
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
                    clientId = meeting.clientId?.toString().orEmpty(),
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

    override suspend fun getMeetingTimeline(clientId: String, projectId: String?): MeetingTimelineDto {
        val cid = ClientId.fromString(clientId)
        val pid = projectId?.let { ProjectId.fromString(it) }
        val now = Instant.now()

        val weekStart = now.atZone(java.time.ZoneOffset.UTC)
            .with(java.time.DayOfWeek.MONDAY)
            .toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()

        val currentWeekMeetings = if (pid != null) {
            meetingRepository.findByClientIdAndProjectIdAndDeletedIsFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(cid, pid, weekStart)
        } else {
            meetingRepository.findByClientIdAndDeletedIsFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(cid, weekStart)
        }.toList().map { it.toSummaryDto() }

        // Fetch older meetings (before current week) — DB-filtered, not full scan
        val olderMeetings = if (pid != null) {
            meetingRepository.findByClientIdAndProjectIdAndDeletedIsFalseAndStartedAtLessThanOrderByStartedAtDesc(cid, pid, weekStart)
        } else {
            meetingRepository.findByClientIdAndDeletedIsFalseAndStartedAtLessThanOrderByStartedAtDesc(cid, weekStart)
        }.toList()

        val thirtyDaysAgo = now.minus(java.time.Duration.ofDays(30))
        val oneYearAgo = now.minus(java.time.Duration.ofDays(365))
        val groups = mutableListOf<MeetingGroupDto>()
        val monthNames = arrayOf("", "Leden", "Únor", "Březen", "Duben", "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec")

        // Weekly groups (last 30 days before current week)
        val weeklyMeetings = olderMeetings.filter { !it.startedAt.isBefore(thirtyDaysAgo) }
        val byWeek = weeklyMeetings.groupBy { doc ->
            doc.startedAt.atZone(java.time.ZoneOffset.UTC)
                .with(java.time.DayOfWeek.MONDAY).toLocalDate()
        }
        for ((mondayDate, docs) in byWeek.entries.sortedByDescending { it.key }) {
            val start = mondayDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            val end = mondayDate.plusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            val label = "Týden ${mondayDate.dayOfMonth}.${mondayDate.monthValue}. – ${mondayDate.plusDays(6).dayOfMonth}.${mondayDate.plusDays(6).monthValue}."
            groups.add(MeetingGroupDto(label = label, periodStart = start.toString(), periodEnd = end.toString(), count = docs.size))
        }

        // Monthly groups (older than 30 days, within last year)
        val monthlyMeetings = olderMeetings.filter { it.startedAt.isBefore(thirtyDaysAgo) && !it.startedAt.isBefore(oneYearAgo) }
        val byMonth = monthlyMeetings.groupBy { doc ->
            val zdt = doc.startedAt.atZone(java.time.ZoneOffset.UTC)
            java.time.YearMonth.of(zdt.year, zdt.month)
        }
        for ((ym, docs) in byMonth.entries.sortedByDescending { it.key }) {
            val start = ym.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            val end = ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            val label = "${monthNames[ym.monthValue]} ${ym.year}"
            groups.add(MeetingGroupDto(label = label, periodStart = start.toString(), periodEnd = end.toString(), count = docs.size))
        }

        // Yearly groups (older than 1 year)
        val yearlyMeetings = olderMeetings.filter { it.startedAt.isBefore(oneYearAgo) }
        val byYear = yearlyMeetings.groupBy { doc ->
            doc.startedAt.atZone(java.time.ZoneOffset.UTC).year
        }
        for ((year, docs) in byYear.entries.sortedByDescending { it.key }) {
            val start = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            val end = java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
            groups.add(MeetingGroupDto(label = "Rok $year", periodStart = start.toString(), periodEnd = end.toString(), count = docs.size))
        }

        return MeetingTimelineDto(currentWeek = currentWeekMeetings, olderGroups = groups)
    }

    override suspend fun listMeetingsByRange(
        clientId: String,
        projectId: String?,
        fromIso: String,
        toIso: String,
    ): List<MeetingSummaryDto> {
        val cid = ClientId.fromString(clientId)
        val from = Instant.parse(fromIso)
        val to = Instant.parse(toIso)
        logger.debug { "listMeetingsByRange client=$clientId project=$projectId from=$fromIso to=$toIso" }
        val meetings = if (projectId != null) {
            val pid = ProjectId.fromString(projectId)
            meetingRepository.listMeetingsInRangeForProject(cid, pid, from, to)
        } else {
            meetingRepository.listMeetingsInRange(cid, from, to)
        }
        val result = meetings.toList().map { it.toSummaryDto() }
        logger.debug { "listMeetingsByRange returned ${result.size} items" }
        return result
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

private fun MeetingDocument.toDto(
    speakerMap: Map<String, com.jervis.entity.SpeakerDocument> = emptyMap(),
    mergeSuggestion: com.jervis.dto.meeting.MergeSuggestionDto? = null,
): MeetingDto =
    MeetingDto(
        id = id.toHexString(),
        clientId = clientId?.toString(),
        projectId = projectId?.toString(),
        groupId = groupId?.toString(),
        title = title,
        meetingType = meetingType,
        audioInputType = audioInputType,
        state = state,
        durationSeconds = durationSeconds,
        startedAt = startedAt.toString(),
        stoppedAt = stoppedAt?.toString(),
        transcriptText = transcriptText,
        transcriptSegments = transcriptSegments.map { seg ->
            val resolved = seg.speaker?.let { speakerMapping[it] }?.let { speakerMap[it] }
            TranscriptSegmentDto(
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
                speakerName = resolved?.name,
                speakerId = resolved?.id?.toHexString(),
            )
        },
        correctedTranscriptText = correctedTranscriptText,
        correctedTranscriptSegments = correctedTranscriptSegments.map { seg ->
            val resolved = seg.speaker?.let { speakerMapping[it] }?.let { speakerMap[it] }
            TranscriptSegmentDto(
                startSec = seg.startSec,
                endSec = seg.endSec,
                text = seg.text,
                speaker = seg.speaker,
                speakerName = resolved?.name,
                speakerId = resolved?.id?.toHexString(),
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
        speakerMapping = speakerMapping,
        speakerEmbeddings = speakerEmbeddings,
        autoSpeakerMapping = buildAutoSpeakerMapping(speakerEmbeddings, speakerMap),
        stateChangedAt = stateChangedAt?.toString(),
        errorMessage = errorMessage,
        deleted = deleted,
        deletedAt = deletedAt?.toString(),
        mergeSuggestion = mergeSuggestion,
    )

/**
 * Build auto-speaker mapping with confidence scores for the UI.
 * Compares meeting speaker embeddings against known speaker voiceprints.
 */
private fun buildAutoSpeakerMapping(
    embeddings: Map<String, List<Float>>?,
    speakerMap: Map<String, com.jervis.entity.SpeakerDocument>,
): Map<String, AutoSpeakerMatchDto>? {
    if (embeddings.isNullOrEmpty()) return null
    val knownWithEmbeddings = speakerMap.values.filter { it.allEmbeddings().isNotEmpty() }
    if (knownWithEmbeddings.isEmpty()) return null

    val result = mutableMapOf<String, AutoSpeakerMatchDto>()
    for ((label, embedding) in embeddings) {
        var bestSpeaker: com.jervis.entity.SpeakerDocument? = null
        var bestSim = 0f
        var bestEmbeddingLabel: String? = null
        for (speaker in knownWithEmbeddings) {
            for (entry in speaker.allEmbeddings()) {
                val sim = cosineSimilarity(embedding, entry.embedding)
                if (sim > bestSim) {
                    bestSim = sim
                    bestSpeaker = speaker
                    bestEmbeddingLabel = entry.label
                }
            }
        }
        if (bestSpeaker != null && bestSim > 0.50f) { // lower threshold for display (UI shows confidence)
            result[label] = AutoSpeakerMatchDto(
                speakerId = bestSpeaker.id.toHexString(),
                speakerName = bestSpeaker.name,
                confidence = bestSim,
                matchedEmbeddingLabel = bestEmbeddingLabel,
            )
        }
    }
    return result.ifEmpty { null }
}

private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size) return 0f
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
    return if (denom > 0f) dot / denom else 0f
}

private fun MeetingDocument.toSummaryDto(): MeetingSummaryDto =
    MeetingSummaryDto(
        id = id.toHexString(),
        title = title,
        meetingType = meetingType,
        state = state,
        durationSeconds = durationSeconds,
        startedAt = startedAt.toString(),
        errorMessage = errorMessage,
    )
