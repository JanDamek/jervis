package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.DispatchAck
import com.jervis.contracts.orchestrator.OrchestrateRequest
import com.jervis.contracts.orchestrator.OrchestratorDispatchServiceGrpcKt
import com.jervis.contracts.orchestrator.QualifyRequest
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorDispatchService.
// Fire-and-forget dispatch for qualify + orchestrate, fully typed — the
// caller (PythonOrchestratorClient) converts its internal DTOs into the
// proto request messages right before dialing.
@Component
class OrchestratorDispatchGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorDispatchServiceGrpcKt.OrchestratorDispatchServiceCoroutineStub(channel)

    /**
     * Build a RequestContext with the given client_id populated on the scope.
     * Callers attach this to every proto request before sending.
     */
    fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun qualify(request: QualifyRequest): DispatchAck = stub.qualify(request)

    suspend fun orchestrate(request: OrchestrateRequest): DispatchAck = stub.orchestrate(request)
}
