package com.jervis.infrastructure.grpc

import com.jervis.common.types.TaskId
import com.jervis.contracts.server.AttendDecisionRequest
import com.jervis.contracts.server.AttendDecisionResponse
import com.jervis.contracts.server.ListUpcomingRequest
import com.jervis.contracts.server.ListUpcomingResponse
import com.jervis.contracts.server.PresenceRequest
import com.jervis.contracts.server.PresenceResponse
import com.jervis.contracts.server.ServerMeetingAttendServiceGrpcKt
import com.jervis.contracts.server.UpcomingMeeting
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.meeting.MeetingAttendApprovalService
import com.jervis.task.TaskRepository
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class ServerMeetingAttendGrpcImpl(
    private val taskRepository: TaskRepository,
    private val meetingAttendApprovalService: MeetingAttendApprovalService,
) : ServerMeetingAttendServiceGrpcKt.ServerMeetingAttendServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun listUpcoming(request: ListUpcomingRequest): ListUpcomingResponse {
        val hoursAhead = if (request.hoursAhead > 0) request.hoursAhead else 24L
        val limit = if (request.limit > 0) request.limit else 50
        val now = Instant.now()
        val window = now.plus(Duration.ofHours(hoursAhead))

        val clientId = request.clientId.takeIf { it.isNotBlank() }
        val projectId = request.projectId.takeIf { it.isNotBlank() }

        val due = taskRepository.findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc(
            scheduledAt = window,
            type = TaskTypeEnum.SYSTEM,
            state = TaskStateEnum.NEW,
        )

        val items = mutableListOf<UpcomingMeeting>()
        due.collect { task ->
            val meta = task.meetingMetadata ?: return@collect
            if (task.scheduledAt == null || task.scheduledAt!!.isBefore(now)) return@collect
            if (clientId != null && task.clientId.toString() != clientId) return@collect
            if (projectId != null && task.projectId?.toString() != projectId) return@collect
            if (items.size >= limit) return@collect
            items += UpcomingMeeting.newBuilder()
                .setTaskId(task.id.toString())
                .setTitle(task.taskName)
                .setClientId(task.clientId.toString())
                .setProjectId(task.projectId?.toString() ?: "")
                .setStartTimeIso(meta.startTime.toString())
                .setEndTimeIso(meta.endTime.toString())
                .setProvider(meta.provider.name)
                .setJoinUrl(meta.joinUrl ?: "")
                .setOrganizer(meta.organizer ?: "")
                .setIsRecurring(meta.isRecurring)
                .build()
        }
        return ListUpcomingResponse.newBuilder().addAllMeetings(items).build()
    }

    override suspend fun approve(request: AttendDecisionRequest): AttendDecisionResponse =
        decide(request, approved = true)

    override suspend fun deny(request: AttendDecisionRequest): AttendDecisionResponse =
        decide(request, approved = false)

    private suspend fun decide(request: AttendDecisionRequest, approved: Boolean): AttendDecisionResponse {
        val rawId = request.taskId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("task_id required"))
        val task = taskRepository.getById(TaskId.fromString(rawId))
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Task not found"))
        if (task.meetingMetadata == null) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription("Task is not a meeting-attend task"),
            )
        }
        val updated = meetingAttendApprovalService.handleApprovalResponse(
            task = task,
            approved = approved,
            reason = if (!approved) request.reason.takeIf { it.isNotBlank() } else null,
        )
        return AttendDecisionResponse.newBuilder()
            .setTaskId(updated.id.toString())
            .setStatus(if (approved) "APPROVED" else "DENIED")
            .setState(updated.state.name)
            .setReason(request.reason)
            .build()
    }

    override suspend fun reportPresence(request: PresenceRequest): PresenceResponse {
        meetingAttendApprovalService.recordUserPresence(
            connectionId = request.connectionId,
            clientId = request.clientId.takeIf { it.isNotBlank() },
            present = request.present,
        )
        return PresenceResponse.newBuilder()
            .setOk(true)
            .setPresent(request.present)
            .build()
    }
}
