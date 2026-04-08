package com.jervis.rpc.internal

import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.rpc.NotificationRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Internal HTTP bridge for chat approval notifications.
 *
 * Python `handler_agentic.py` calls [/internal/chat/approvals/broadcast] as a
 * fire-and-forget side-effect whenever it raises an `ApprovalRequiredInterrupt`
 * and persists a pending approval. This routing fans the approval request out
 * through **all three remote notification transports** so the user sees it no
 * matter where they are:
 *
 * 1. kRPC event stream → [NotificationRpcImpl.emitUserTaskCreated] with
 *    `isApproval = true` → desktop, iOS, Android, watch apps that are currently
 *    connected get a live [JervisEvent.UserTaskCreated] and open
 *    `ApprovalNotificationDialog`.
 * 2. FCM push → Android devices (app foreground or backgrounded) get a native
 *    system notification, `data.type = "approval"` with `interruptAction` and
 *    `taskId = approvalId`.
 * 3. APNs push → iOS devices same as above, with `mutable-content` so the
 *    extension can render Approve/Deny action buttons.
 *
 * Any device can then resolve it via the existing
 * [com.jervis.service.chat.IChatService.approveChatAction] RPC (which goes to
 * `ChatService.approveChatAction` → `PythonChatClient.approveAction` → POST
 * `/chat/approve` to Python). The first one wins — `resolve_pending_approval`
 * in Python is idempotent (pops the asyncio future), and we also emit a
 * [JervisEvent.UserTaskCancelled] via [/internal/chat/approvals/resolved] so
 * stale dialogs on other devices self-dismiss.
 *
 * **Why a bridge and not direct kRPC?** Same reason as
 * [installInternalMeetingRecordingBridgeApi] — the Python orchestrator pod can't
 * link against Kotlin RPC stubs, so internal HTTP is the simplest interop path.
 */
fun Routing.installInternalChatApprovalApi(
    notificationRpcImpl: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
) {
    post("/internal/chat/approvals/broadcast") {
        try {
            val body = call.receive<ChatApprovalBroadcastRequest>()
            val clientId = body.clientId ?: ""
            if (clientId.isBlank()) {
                logger.warn { "CHAT_APPROVAL_BROADCAST | missing clientId — skipping remote emit (approvalId=${body.approvalId})" }
                call.respondText(
                    """{"status":"skipped","reason":"no clientId"}""",
                    ContentType.Application.Json,
                )
                return@post
            }

            val pushTitle = "Schválení vyžadováno"
            val pushBody = body.preview.take(120)
            val pushData = buildMap {
                put("taskId", body.approvalId)
                put("approvalId", body.approvalId)
                put("type", "approval")
                put("interruptAction", body.action)
                put("tool", body.tool)
                put("isApproval", "true")
                body.projectId?.let { put("projectId", it) }
                body.sessionId?.let { put("sessionId", it) }
            }

            // 1. kRPC event stream → live subscribers get JervisEvent.UserTaskCreated.
            //    UI maps isApproval=true to ApprovalNotificationDialog + brings window
            //    to front on macOS (PlatformNotificationManager.jvm.kt).
            //    `chatApprovalAction` tells the UI to route approve/deny through
            //    IChatService.approveChatAction (not sendToAgent) because taskId
            //    here is a synthetic approvalId, not a TaskDocument id.
            notificationRpcImpl.emitUserTaskCreated(
                clientId = clientId,
                taskId = body.approvalId,
                title = pushBody,
                interruptAction = body.action,
                interruptDescription = body.preview,
                isApproval = true,
                projectId = body.projectId,
                chatApprovalAction = body.action,
            )

            // 2. FCM → Android (works even when app is fully killed).
            try {
                fcmPushService.sendPushNotification(
                    clientId = clientId,
                    title = pushTitle,
                    body = pushBody,
                    data = pushData,
                )
            } catch (e: Exception) {
                logger.warn { "CHAT_APPROVAL_FCM | fcm push failed: ${e.message}" }
            }

            // 3. APNs → iOS (actionable category + Approve/Deny buttons in
            //    Notification Center when the app is not running).
            try {
                apnsPushService.sendPushNotification(
                    clientId = clientId,
                    title = pushTitle,
                    body = pushBody,
                    data = pushData,
                )
            } catch (e: Exception) {
                logger.warn { "CHAT_APPROVAL_APNS | apns push failed: ${e.message}" }
            }

            logger.info {
                "CHAT_APPROVAL_BROADCAST | approvalId=${body.approvalId} | action=${body.action} | " +
                    "tool=${body.tool} | client=$clientId | project=${body.projectId ?: "-"} | " +
                    "session=${body.sessionId ?: "-"}"
            }
            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_APPROVAL_BROADCAST_ERROR | ${e.message}" }
            call.respondText(
                """{"status":"error","error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Called by Python after it resolves a pending approval future — tells
    // all other clients to dismiss the (now stale) dialog. First-wins dedupe.
    post("/internal/chat/approvals/resolved") {
        try {
            val body = call.receive<ChatApprovalResolvedRequest>()
            val clientId = body.clientId ?: ""
            if (clientId.isBlank()) {
                call.respondText(
                    """{"status":"skipped","reason":"no clientId"}""",
                    ContentType.Application.Json,
                )
                return@post
            }
            // kRPC stream — live UIs close their dialog on matching taskId.
            notificationRpcImpl.emitUserTaskCancelled(
                clientId = clientId,
                taskId = body.approvalId,
                title = body.action,
            )

            // Silent FCM/APNs to clear mobile notification from tray.
            val pushData = mapOf(
                "taskId" to body.approvalId,
                "approvalId" to body.approvalId,
                "type" to "approval_resolved",
                "approved" to body.approved.toString(),
            )
            try {
                fcmPushService.sendPushNotification(
                    clientId = clientId,
                    title = "",
                    body = "",
                    data = pushData,
                )
            } catch (_: Exception) { /* silent cancel is best-effort */ }
            try {
                apnsPushService.sendPushNotification(
                    clientId = clientId,
                    title = "",
                    body = "",
                    data = pushData,
                )
            } catch (_: Exception) { /* silent cancel is best-effort */ }

            logger.info {
                "CHAT_APPROVAL_RESOLVED | approvalId=${body.approvalId} | approved=${body.approved} | " +
                    "client=$clientId | dismissed stale dialogs"
            }
            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_APPROVAL_RESOLVED_ERROR | ${e.message}" }
            call.respondText(
                """{"status":"error","error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class ChatApprovalBroadcastRequest(
    val approvalId: String,
    val action: String,
    val tool: String,
    val preview: String,
    val clientId: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
)

@Serializable
private data class ChatApprovalResolvedRequest(
    val approvalId: String,
    val approved: Boolean,
    val clientId: String? = null,
    val action: String,
)
