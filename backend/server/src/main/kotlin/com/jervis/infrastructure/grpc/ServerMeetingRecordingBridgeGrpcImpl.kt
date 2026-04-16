package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.FinalizeRecordingRequest
import com.jervis.contracts.server.FinalizeRecordingResponse
import com.jervis.contracts.server.ServerMeetingRecordingBridgeServiceGrpcKt
import com.jervis.contracts.server.StartRecordingRequest
import com.jervis.contracts.server.StartRecordingResponse
import com.jervis.contracts.server.UploadChunkRequest
import com.jervis.contracts.server.UploadChunkResponse
import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.meeting.MeetingRpcImpl
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerMeetingRecordingBridgeGrpcImpl(
    private val meetingRpcImpl: MeetingRpcImpl,
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
}
