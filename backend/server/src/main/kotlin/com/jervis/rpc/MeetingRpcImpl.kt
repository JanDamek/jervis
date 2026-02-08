package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.TranscriptSegmentDto
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.repository.MeetingRepository
import com.jervis.service.IMeetingService
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
        val audioFileName = "meeting_${meetingId.toHexString()}.webm"
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

        logger.debug { "Received chunk ${chunk.chunkIndex} for meeting ${chunk.meetingId} (${audioBytes.size} bytes)" }
        return true
    }

    override suspend fun finalizeRecording(request: MeetingFinalizeDto): MeetingDto {
        val meetingId = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalStateException("Meeting not found: ${request.meetingId}")

        val updated = meeting.copy(
            state = MeetingStateEnum.UPLOADED,
            title = request.title,
            meetingType = request.meetingType,
            durationSeconds = request.durationSeconds,
            stoppedAt = Instant.now(),
        )

        val saved = meetingRepository.save(updated)
        logger.info { "Finalized meeting ${request.meetingId}: type=${request.meetingType}, duration=${request.durationSeconds}s" }
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
        errorMessage = errorMessage,
    )
