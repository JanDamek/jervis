package com.jervis.infrastructure.grpc

import com.google.protobuf.ByteString
import com.jervis.contracts.server.FinalizeRecordingRequest
import com.jervis.contracts.server.FinalizeRecordingResponse
import com.jervis.contracts.server.FinalizeVideoRequest
import com.jervis.contracts.server.FinalizeVideoResponse
import com.jervis.contracts.server.ServerMeetingRecordingBridgeServiceGrpcKt
import com.jervis.contracts.server.StartRecordingRequest
import com.jervis.contracts.server.StartRecordingResponse
import com.jervis.contracts.server.UploadChunkRequest
import com.jervis.contracts.server.UploadChunkResponse
import com.jervis.contracts.server.VideoChunkAck
import com.jervis.contracts.server.VideoChunkRequest
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.infrastructure.storage.DirectoryStructureService
import com.jervis.meeting.MeetingRepository
import com.jervis.meeting.MeetingRpcImpl
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

@Component
class ServerMeetingRecordingBridgeGrpcImpl(
    private val meetingRpcImpl: MeetingRpcImpl,
    private val meetingRepository: MeetingRepository,
    private val directoryStructureService: DirectoryStructureService,
) : ServerMeetingRecordingBridgeServiceGrpcKt.ServerMeetingRecordingBridgeServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun startRecording(request: StartRecordingRequest): StartRecordingResponse {
        val meetingType = request.meetingType.takeIf { it.isNotBlank() }
            ?.let { runCatching { MeetingTypeEnum.valueOf(it) }.getOrNull() }
            ?: MeetingTypeEnum.MEETING
        val dto = MeetingCreateDto(
            clientId = request.clientId.takeIf { it.isNotBlank() },
            projectId = request.projectId.takeIf { it.isNotBlank() },
            title = request.title.takeIf { it.isNotBlank() },
            meetingType = meetingType,
            deviceSessionId = request.deviceSessionId.takeIf { it.isNotBlank() },
        )
        val meeting = meetingRpcImpl.startRecording(dto)
        request.taskId.takeIf { it.isNotBlank() }?.let { taskId ->
            runCatching { meetingRpcImpl.linkMeetingToTask(taskId, meeting.id) }
        }
        return StartRecordingResponse.newBuilder()
            .setId(meeting.id)
            .setState(meeting.state.name)
            .build()
    }

    override suspend fun uploadChunk(request: UploadChunkRequest): UploadChunkResponse {
        val count = meetingRpcImpl.uploadAudioChunk(
            AudioChunkDto(
                meetingId = request.meetingId,
                chunkIndex = request.chunkIndex,
                data = request.data,
                mimeType = request.mimeType.ifBlank { "audio/pcm" },
                isLast = request.isLast,
            ),
        )
        return UploadChunkResponse.newBuilder()
            .setMeetingId(request.meetingId)
            .setChunkCount(count)
            .build()
    }

    override suspend fun finalizeRecording(request: FinalizeRecordingRequest): FinalizeRecordingResponse {
        val meetingType = request.meetingType.takeIf { it.isNotBlank() }
            ?.let { runCatching { MeetingTypeEnum.valueOf(it) }.getOrDefault(MeetingTypeEnum.MEETING) }
            ?: MeetingTypeEnum.MEETING
        val meeting = meetingRpcImpl.finalizeRecording(
            MeetingFinalizeDto(
                meetingId = request.meetingId,
                title = request.title.takeIf { it.isNotBlank() },
                meetingType = meetingType,
                durationSeconds = request.durationSeconds,
            ),
        )
        return FinalizeRecordingResponse.newBuilder()
            .setId(meeting.id)
            .setState(meeting.state.name)
            .setDurationSeconds(meeting.durationSeconds ?: 0L)
            .build()
    }

    override suspend fun uploadVideoChunk(request: VideoChunkRequest): VideoChunkAck {
        val meetingId = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalArgumentException("meeting not found: ${request.meetingId}")
        val chunkDir = directoryStructureService.meetingVideoChunkDir(
            meetingId, meeting.clientId, meeting.projectId,
        )
        val chunkPath = chunkDir.resolve("chunk_%06d.webm".format(request.chunkIndex))

        // Idempotency: if the chunk already exists with non-zero size, skip.
        if (Files.exists(chunkPath) && Files.size(chunkPath) > 0) {
            return VideoChunkAck.newBuilder()
                .setMeetingId(request.meetingId)
                .setChunkIndex(request.chunkIndex)
                .setDeduped(true)
                .setChunksReceived(meeting.chunksReceived)
                .setBytes(0)
                .build()
        }

        val bytes = request.data.toByteArray()
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("empty video chunk payload")
        }
        Files.write(
            chunkPath, bytes,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        val now = Instant.now()
        val updated = meeting.copy(
            chunksReceived = meeting.chunksReceived + 1,
            lastChunkAt = now,
            chunkCount = meeting.chunkCount + 1,
        )
        meetingRepository.save(updated)
        return VideoChunkAck.newBuilder()
            .setMeetingId(request.meetingId)
            .setChunkIndex(request.chunkIndex)
            .setDeduped(false)
            .setChunksReceived(updated.chunksReceived)
            .setBytes(bytes.size)
            .build()
    }

    override suspend fun finalizeVideo(request: FinalizeVideoRequest): FinalizeVideoResponse {
        val meetingId = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(meetingId)
            ?: throw IllegalArgumentException("meeting not found: ${request.meetingId}")
        val chunkDir = directoryStructureService.meetingVideoChunkDir(
            meetingId, meeting.clientId, meeting.projectId,
        )
        val webmPath = directoryStructureService
            .meetingVideoFile(meetingId, meeting.clientId, meeting.projectId)
            .toString()
        // Retention — 365 days. Nightly cleanup drops only the WebM after this
        // cutoff; metadata + transcript survive indefinitely.
        val retentionUntil = Instant.now().plus(Duration.ofDays(365))
        val now = Instant.now()
        val updated = meeting.copy(
            state = MeetingStateEnum.UPLOADED,
            stoppedAt = now,
            stateChangedAt = now,
            joinedByAgent = request.joinedBy.equals("agent", ignoreCase = true),
            webmPath = webmPath,
            videoRetentionUntil = retentionUntil,
        )
        meetingRepository.save(updated)
        logger.info {
            "MEETING_VIDEO_FINALIZED | meeting=${request.meetingId} chunks=${updated.chunksReceived} " +
                "dir=$chunkDir webm=$webmPath joinedByAgent=${updated.joinedByAgent}"
        }
        return FinalizeVideoResponse.newBuilder()
            .setMeetingId(request.meetingId)
            .setState(updated.state.name)
            .setChunksReceived(updated.chunksReceived)
            .setWebmPath(webmPath)
            .setRetentionUntil(retentionUntil.toString())
            .build()
    }
}
