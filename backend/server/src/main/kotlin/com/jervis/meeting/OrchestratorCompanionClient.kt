package com.jervis.meeting

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.OrchestratorCompanionServiceGrpcKt
import com.jervis.contracts.orchestrator.OutboxEvent
import com.jervis.contracts.orchestrator.SessionEventRequest as ProtoSessionEventRequest
import com.jervis.contracts.orchestrator.SessionRef
import com.jervis.contracts.orchestrator.SessionStartRequest as ProtoSessionStartRequest
import com.jervis.contracts.orchestrator.StreamSessionRequest
import com.jervis.infrastructure.grpc.GrpcChannels
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

// gRPC client for the Python orchestrator companion service. Starts /
// forwards transcript events / tails outbox server-streaming RPC for the
// live meeting assistant flow.
@Component
class OrchestratorCompanionClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorCompanionServiceGrpcKt.OrchestratorCompanionServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    @Serializable
    data class SessionStartRequest(
        val session_id: String? = null,
        val brief: String,
        val client_id: String = "",
        val project_id: String? = null,
        val language: String = "cs",
        val context: Map<String, String> = emptyMap(),
        val attachment_paths: List<String> = emptyList(),
    )

    @Serializable
    data class SessionStartResponse(
        val job_name: String,
        val workspace_path: String,
        val session_id: String,
    )

    @Serializable
    data class SessionEventRequest(
        val type: String,
        val content: String,
        val meta: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class OutboxEventDto(
        val ts: String? = null,
        val type: String,
        val content: String,
        val final: Boolean? = null,
    )

    suspend fun startSession(req: SessionStartRequest): SessionStartResponse {
        val builder = ProtoSessionStartRequest.newBuilder()
            .setCtx(ctx(req.client_id))
            .setSessionId(req.session_id ?: "")
            .setBrief(req.brief)
            .setClientId(req.client_id)
            .setProjectId(req.project_id ?: "")
            .setLanguage(req.language)
            .addAllAttachmentPaths(req.attachment_paths)
        req.context.forEach { (k, v) -> builder.putContext(k, v) }
        val resp = stub.startSession(builder.build())
        if (resp.error.isNotEmpty()) {
            throw IllegalStateException("Companion startSession rejected: ${resp.error}")
        }
        return SessionStartResponse(
            job_name = resp.jobName,
            workspace_path = resp.workspacePath,
            session_id = resp.sessionId,
        )
    }

    suspend fun sendEvent(sessionId: String, req: SessionEventRequest) {
        val builder = ProtoSessionEventRequest.newBuilder()
            .setCtx(ctx())
            .setSessionId(sessionId)
            .setType(req.type)
            .setContent(req.content)
        req.meta.forEach { (k, v) -> builder.putMeta(k, v) }
        val ack = stub.sessionEvent(builder.build())
        if (!ack.ok) {
            logger.warn { "Companion sendEvent failed for session=$sessionId: ${ack.error}" }
        }
    }

    suspend fun stopSession(sessionId: String) {
        runCatching {
            stub.stopSession(
                SessionRef.newBuilder().setCtx(ctx()).setSessionId(sessionId).build(),
            )
        }.onFailure { e ->
            logger.warn(e) { "Companion stopSession failed: $sessionId" }
        }
    }

    // Tail outbox via gRPC server streaming. Each emitted value is the
    // legacy OutboxEvent DTO shape.
    fun streamOutbox(sessionId: String, maxAgeSeconds: Int? = null): Flow<OutboxEventDto> {
        val maxAge = maxAgeSeconds?.toDouble() ?: 0.0
        val req = StreamSessionRequest.newBuilder()
            .setCtx(ctx())
            .setSessionId(sessionId)
            .setMaxAgeSeconds(maxAge)
            .build()
        return stub.streamSession(req).map { proto: OutboxEvent ->
            OutboxEventDto(
                ts = proto.ts.takeIf { it.isNotEmpty() },
                type = proto.type,
                content = proto.content,
                final = if (proto.final) true else null,
            )
        }
    }
}
