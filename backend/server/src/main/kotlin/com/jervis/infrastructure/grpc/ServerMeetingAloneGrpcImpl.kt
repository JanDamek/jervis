package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.LeaveRequest
import com.jervis.contracts.server.LeaveResponse
import com.jervis.contracts.server.ServerMeetingAloneServiceGrpcKt
import com.jervis.contracts.server.StayRequest
import com.jervis.contracts.server.StayResponse
import com.jervis.meeting.AloneSuppressionRegistry
import com.jervis.meeting.BrowserPodMeetingClient
import com.jervis.meeting.MeetingRepository
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerMeetingAloneGrpcImpl(
    private val meetingRepository: MeetingRepository,
    private val browserPodMeetingClient: BrowserPodMeetingClient,
    private val aloneSuppression: AloneSuppressionRegistry,
) : ServerMeetingAloneServiceGrpcKt.ServerMeetingAloneServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun leave(request: LeaveRequest): LeaveResponse {
        val raw = request.meetingId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("meeting_id required"))
        val meetingObjectId = try {
            ObjectId(raw)
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid meeting_id"))
        }
        val meeting = meetingRepository.findById(meetingObjectId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("meeting not found"))
        val connectionId = meeting.clientId?.toString()
            ?: throw StatusException(Status.FAILED_PRECONDITION.withDescription("meeting has no clientId"))
        val reason = request.reason.ifBlank { "user_asked_to_leave" }
        val ok = browserPodMeetingClient.dispatchLeave(
            connectionId = connectionId,
            meetingId = raw,
            reason = reason,
        )
        aloneSuppression.release(raw)
        return LeaveResponse.newBuilder()
            .setMeetingId(raw)
            .setState(if (ok) "DISPATCHED" else "FAILED")
            .setReason(reason)
            .build()
    }

    override suspend fun stay(request: StayRequest): StayResponse {
        val raw = request.meetingId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("meeting_id required"))
        val minutes = (if (request.suppressMinutes > 0) request.suppressMinutes else 30).coerceIn(1, 180)
        aloneSuppression.suppress(raw, minutes)
        logger.info { "ALONE_STAY | meeting=$raw suppressMinutes=$minutes" }
        return StayResponse.newBuilder()
            .setMeetingId(raw)
            .setState("SUPPRESSED")
            .setSuppressMinutes(minutes)
            .build()
    }
}
