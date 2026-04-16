package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.ApprovalBroadcastRequest
import com.jervis.contracts.server.ApprovalBroadcastResponse
import com.jervis.contracts.server.ApprovalResolvedRequest
import com.jervis.contracts.server.ApprovalResolvedResponse
import com.jervis.contracts.server.ServerChatApprovalServiceGrpcKt
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.rpc.NotificationRpcImpl
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class ServerChatApprovalGrpcImpl(
    private val notificationRpcImpl: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
) : ServerChatApprovalServiceGrpcKt.ServerChatApprovalServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun broadcast(request: ApprovalBroadcastRequest): ApprovalBroadcastResponse {
        val clientId = request.clientId
        if (clientId.isBlank()) {
            logger.warn {
                "CHAT_APPROVAL_BROADCAST | missing clientId — skipping remote emit " +
                    "(approvalId=${request.approvalId})"
            }
            return ApprovalBroadcastResponse.newBuilder().setStatus("skipped").build()
        }

        val pushTitle = "Schválení vyžadováno"
        val pushBody = request.preview.take(120)
        val pushData = buildMap {
            put("taskId", request.approvalId)
            put("approvalId", request.approvalId)
            put("type", "approval")
            put("interruptAction", request.action)
            put("tool", request.tool)
            put("isApproval", "true")
            request.projectId.takeIf { it.isNotBlank() }?.let { put("projectId", it) }
            request.sessionId.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
        }

        notificationRpcImpl.emitUserTaskCreated(
            clientId = clientId,
            taskId = request.approvalId,
            title = pushBody,
            interruptAction = request.action,
            interruptDescription = request.preview,
            isApproval = true,
            projectId = request.projectId.takeIf { it.isNotBlank() },
            chatApprovalAction = request.action,
        )

        runCatching {
            fcmPushService.sendPushNotification(
                clientId = clientId,
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        }.onFailure { logger.warn { "CHAT_APPROVAL_FCM | fcm push failed: ${it.message}" } }

        runCatching {
            apnsPushService.sendPushNotification(
                clientId = clientId,
                title = pushTitle,
                body = pushBody,
                data = pushData,
            )
        }.onFailure { logger.warn { "CHAT_APPROVAL_APNS | apns push failed: ${it.message}" } }

        logger.info {
            "CHAT_APPROVAL_BROADCAST | approvalId=${request.approvalId} action=${request.action} " +
                "tool=${request.tool} client=$clientId"
        }
        return ApprovalBroadcastResponse.newBuilder().setStatus("ok").build()
    }

    override suspend fun resolved(request: ApprovalResolvedRequest): ApprovalResolvedResponse {
        val clientId = request.clientId
        if (clientId.isBlank()) {
            return ApprovalResolvedResponse.newBuilder().setStatus("skipped").build()
        }
        notificationRpcImpl.emitUserTaskCancelled(
            clientId = clientId,
            taskId = request.approvalId,
            title = request.action,
        )

        val pushData = mapOf(
            "taskId" to request.approvalId,
            "approvalId" to request.approvalId,
            "type" to "approval_resolved",
            "approved" to request.approved.toString(),
        )
        runCatching {
            fcmPushService.sendPushNotification(clientId, "", "", pushData)
        }
        runCatching {
            apnsPushService.sendPushNotification(clientId, "", "", pushData)
        }
        logger.info {
            "CHAT_APPROVAL_RESOLVED | approvalId=${request.approvalId} approved=${request.approved} " +
                "client=$clientId"
        }
        return ApprovalResolvedResponse.newBuilder().setStatus("ok").build()
    }
}
