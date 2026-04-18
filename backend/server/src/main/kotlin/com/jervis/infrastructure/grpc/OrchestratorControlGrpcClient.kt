package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.ApproveAck
import com.jervis.contracts.orchestrator.ApproveRequest
import com.jervis.contracts.orchestrator.CancelAck
import com.jervis.contracts.orchestrator.HealthRequest
import com.jervis.contracts.orchestrator.HealthResponse
import com.jervis.contracts.orchestrator.InterruptAck
import com.jervis.contracts.orchestrator.OrchestratorControlServiceGrpcKt
import com.jervis.contracts.orchestrator.StatusRequest
import com.jervis.contracts.orchestrator.StatusResponse
import com.jervis.contracts.orchestrator.ThreadRequest
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorControlService.
// Phase 3 first slice: short-payload control RPCs (health, status,
// approve, cancel, interrupt). Long-running orchestrate / chat / voice
// streams land in follow-up slices.
@Component
class OrchestratorControlGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorControlServiceGrpcKt.OrchestratorControlServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun health(): HealthResponse =
        stub.health(HealthRequest.newBuilder().setCtx(ctx()).build())

    suspend fun getStatus(threadId: String): StatusResponse =
        stub.getStatus(
            StatusRequest.newBuilder()
                .setCtx(ctx())
                .setThreadId(threadId)
                .build(),
        )

    suspend fun approve(
        threadId: String,
        approved: Boolean,
        reason: String? = null,
        modification: String? = null,
    ): ApproveAck =
        stub.approve(
            ApproveRequest.newBuilder()
                .setCtx(ctx())
                .setThreadId(threadId)
                .setApproved(approved)
                .setReason(reason ?: "")
                .setModification(modification ?: "")
                .build(),
        )

    suspend fun cancel(threadId: String): CancelAck =
        stub.cancel(
            ThreadRequest.newBuilder()
                .setCtx(ctx())
                .setThreadId(threadId)
                .build(),
        )

    suspend fun interrupt(threadId: String): InterruptAck =
        stub.interrupt(
            ThreadRequest.newBuilder()
                .setCtx(ctx())
                .setThreadId(threadId)
                .build(),
        )
}
