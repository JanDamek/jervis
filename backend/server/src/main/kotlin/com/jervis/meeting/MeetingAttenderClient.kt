package com.jervis.meeting

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.meeting_attender.AttendRequest
import com.jervis.contracts.meeting_attender.MeetingAttenderServiceGrpcKt
import com.jervis.contracts.meeting_attender.StopRequest
import com.jervis.dto.events.JervisEvent
import com.jervis.infrastructure.grpc.GrpcChannels
import io.grpc.ManagedChannel
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * gRPC client for the K8s `service-meeting-attender` pod (Etapa 2B).
 *
 * Fallback path of MeetingRecordingDispatcher when the approved client
 * has no live desktop event subscriber. Previously a REST client; now
 * dials MeetingAttenderService over gRPC on :5501.
 */
@Component
class MeetingAttenderClient(
    @Qualifier(GrpcChannels.MEETING_ATTENDER_CHANNEL) channel: ManagedChannel,
) {
    private val stub = MeetingAttenderServiceGrpcKt
        .MeetingAttenderServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun attend(trigger: JervisEvent.MeetingRecordingTrigger): Boolean {
        return try {
            val resp = stub.attend(
                AttendRequest.newBuilder()
                    .setCtx(ctx())
                    .setTaskId(trigger.taskId)
                    .setClientId(trigger.clientId)
                    .setProjectId(trigger.projectId ?: "")
                    .setTitle(trigger.title)
                    .setJoinUrl(trigger.joinUrl ?: "")
                    .setEndTimeIso(trigger.endTime)
                    .setProvider(trigger.provider)
                    .build(),
            )
            if (resp.ok) {
                logger.info { "MeetingAttenderClient: pod accepted attend for task=${trigger.taskId}" }
                true
            } else {
                logger.warn { "MeetingAttenderClient: pod rejected attend for task=${trigger.taskId}: ${resp.error}" }
                false
            }
        } catch (e: Exception) {
            logger.warn(e) { "MeetingAttenderClient: failed to call attender pod for task=${trigger.taskId}" }
            false
        }
    }

    suspend fun stop(taskId: String, reason: String): Boolean {
        return try {
            val resp = stub.stop(
                StopRequest.newBuilder()
                    .setCtx(ctx())
                    .setTaskId(taskId)
                    .setReason(reason)
                    .build(),
            )
            resp.ok
        } catch (e: Exception) {
            logger.warn(e) { "MeetingAttenderClient: failed to stop session task=$taskId" }
            false
        }
    }
}
