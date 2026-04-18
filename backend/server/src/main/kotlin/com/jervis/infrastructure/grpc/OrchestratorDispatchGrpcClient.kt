package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.DispatchAck
import com.jervis.contracts.orchestrator.DispatchRequest
import com.jervis.contracts.orchestrator.OrchestratorDispatchServiceGrpcKt
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorDispatchService.
// Fire-and-forget dispatch for qualify + orchestrate. Carries the legacy
// request JSON inline so the rich kotlinx-serialization DTOs
// (QualifyRequestDto, OrchestrateRequestDto) flow unchanged end-to-end.
@Component
class OrchestratorDispatchGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorDispatchServiceGrpcKt.OrchestratorDispatchServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun qualify(taskId: String, clientId: String, payloadJson: String): DispatchAck =
        stub.qualify(
            DispatchRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setTaskId(taskId)
                .setPayloadJson(payloadJson)
                .build(),
        )

    suspend fun orchestrate(taskId: String, clientId: String, payloadJson: String): DispatchAck =
        stub.orchestrate(
            DispatchRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setTaskId(taskId)
                .setPayloadJson(payloadJson)
                .build(),
        )
}
