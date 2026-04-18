package com.jervis.chat

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.ApproveActionRequest
import com.jervis.contracts.orchestrator.ChatRequest as ProtoChatRequest
import com.jervis.contracts.orchestrator.OrchestratorChatServiceGrpcKt
import com.jervis.contracts.orchestrator.StopChatRequest
import com.jervis.infrastructure.grpc.GrpcChannels
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * gRPC client for the Python foreground chat pipeline.
 *
 * Wraps OrchestratorChatService.Chat (server-streaming agentic loop) +
 * ApproveAction + Stop. The request body still serialises through the
 * legacy `PythonChatRequest` DTO to stay source-compatible with the
 * Kotlin call sites; internally it rides across the proto's
 * `payload_json` field.
 */
@Component
class PythonChatClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorChatServiceGrpcKt.OrchestratorChatServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    /**
     * Send a chat message and stream events back.
     *
     * @return Flow of ChatStreamEvent (token, tool_call, tool_result, done, error)
     */
    fun chat(
        sessionId: String,
        message: String,
        messageSequence: Long,
        userId: String = "jan",
        activeClientId: String? = null,
        activeProjectId: String? = null,
        activeGroupId: String? = null,
        activeClientName: String? = null,
        activeProjectName: String? = null,
        activeGroupName: String? = null,
        contextTaskId: String? = null,
        maxOpenRouterTier: String = "NONE",
        attachments: List<com.jervis.dto.chat.AttachmentDto> = emptyList(),
        clientTimezone: String? = null,
    ): Flow<ChatStreamEvent> = flow {
        val request = PythonChatRequest(
            sessionId = sessionId,
            message = message,
            messageSequence = messageSequence.toInt(),
            userId = userId,
            activeClientId = activeClientId,
            activeProjectId = activeProjectId,
            activeGroupId = activeGroupId,
            activeClientName = activeClientName,
            activeProjectName = activeProjectName,
            activeGroupName = activeGroupName,
            contextTaskId = contextTaskId,
            maxOpenRouterTier = maxOpenRouterTier,
            attachments = attachments.map { PythonAttachment(filename = it.filename, mimeType = it.mimeType, sizeBytes = it.sizeBytes, contentBase64 = it.contentBase64) },
            clientTimezone = clientTimezone,
        )
        val payloadJson = jsonParser.encodeToString(PythonChatRequest.serializer(), request)
        val protoReq = ProtoChatRequest.newBuilder()
            .setCtx(ctx(activeClientId ?: ""))
            .setSessionId(sessionId)
            .setPayloadJson(payloadJson)
            .build()

        logger.info { "PYTHON_CHAT_START | session=$sessionId | message=${message.take(80)}" }

        try {
            stub.chat(protoReq).collect { proto ->
                if (proto.type == "approval_request") {
                    logger.info {
                        "PYTHON_CHAT_APPROVAL_EVENT | session=$sessionId | action=${proto.metadataMap["action"]}"
                    }
                }
                emit(
                    ChatStreamEvent(
                        type = proto.type,
                        content = proto.content,
                        metadata = proto.metadataMap.toMap(),
                    ),
                )
                if (proto.type == "done" || proto.type == "error") {
                    logger.info { "PYTHON_CHAT_END | session=$sessionId | type=${proto.type}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "PYTHON_CHAT_ERROR | session=$sessionId | error=${e.message}" }
            emit(ChatStreamEvent(type = "error", content = "Connection to orchestrator failed: ${e.message}"))
        }
    }

    /**
     * Approve or deny a pending chat tool action.
     * Called when user clicks Approve/Deny in the approval dialog.
     */
    suspend fun approveAction(sessionId: String, approved: Boolean, always: Boolean = false, action: String? = null) {
        try {
            val ack = stub.approveAction(
                ApproveActionRequest.newBuilder()
                    .setCtx(ctx())
                    .setSessionId(sessionId)
                    .setApproved(approved)
                    .setAlways(always)
                    .setAction(action ?: "")
                    .build(),
            )
            logger.info {
                "PYTHON_CHAT_APPROVE | session=$sessionId | approved=$approved | always=$always | ok=${ack.ok}"
            }
        } catch (e: Exception) {
            logger.warn(e) { "PYTHON_CHAT_APPROVE_ERROR | session=$sessionId | error=${e.message}" }
        }
    }

    /**
     * Stop an active chat session. Called when user presses Stop button.
     */
    suspend fun stopChat(sessionId: String) {
        try {
            val ack = stub.stop(
                StopChatRequest.newBuilder()
                    .setCtx(ctx())
                    .setSessionId(sessionId)
                    .build(),
            )
            logger.info { "PYTHON_CHAT_STOP | session=$sessionId | ok=${ack.ok}" }
        } catch (e: Exception) {
            logger.warn(e) { "PYTHON_CHAT_STOP_ERROR | session=$sessionId | error=${e.message}" }
        }
    }
}

/**
 * Attachment in chat request (base64-encoded content).
 */
@Serializable
private data class PythonAttachment(
    val filename: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("content_base64") val contentBase64: String? = null,
)

/**
 * Wire-serialised chat request. Kept as a Serializable class so the
 * field-naming rules land exactly where the Python Pydantic model
 * expects them (SerialName → snake_case).
 */
@Serializable
private data class PythonChatRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
    @SerialName("message_sequence") val messageSequence: Int,
    @SerialName("user_id") val userId: String = "jan",
    @SerialName("active_client_id") val activeClientId: String? = null,
    @SerialName("active_project_id") val activeProjectId: String? = null,
    @SerialName("active_group_id") val activeGroupId: String? = null,
    @SerialName("active_client_name") val activeClientName: String? = null,
    @SerialName("active_project_name") val activeProjectName: String? = null,
    @SerialName("active_group_name") val activeGroupName: String? = null,
    @SerialName("context_task_id") val contextTaskId: String? = null,
    @SerialName("max_openrouter_tier") val maxOpenRouterTier: String = "NONE",
    val attachments: List<PythonAttachment> = emptyList(),
    @SerialName("client_timezone") val clientTimezone: String? = null,
)
