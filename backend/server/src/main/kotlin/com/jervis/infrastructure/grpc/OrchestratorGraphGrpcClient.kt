package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.GetTaskGraphRequest
import com.jervis.contracts.orchestrator.MaintenanceRunRequest
import com.jervis.contracts.orchestrator.MaintenanceRunResult
import com.jervis.contracts.orchestrator.OrchestratorGraphServiceGrpcKt
import com.jervis.contracts.orchestrator.TaskGraphResponse
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.orchestrator.OrchestratorGraphService.
// Covers GetTaskGraph (UI data feed) + RunMaintenance (BackgroundEngine
// idle dispatch). Admin vertex CRUD stays on FastAPI for now — no
// cross-service consumer.
@Component
class OrchestratorGraphGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorGraphServiceGrpcKt.OrchestratorGraphServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun getTaskGraph(taskId: String, clientId: String? = null): TaskGraphResponse =
        stub.getTaskGraph(
            GetTaskGraphRequest.newBuilder()
                .setCtx(ctx(clientId ?: ""))
                .setTaskId(taskId)
                .setClientId(clientId ?: "")
                .build(),
        )

    suspend fun runMaintenance(phase: Int, clientId: String? = null): MaintenanceRunResult =
        stub.runMaintenance(
            MaintenanceRunRequest.newBuilder()
                .setCtx(ctx(clientId ?: ""))
                .setPhase(phase)
                .setClientId(clientId ?: "")
                .build(),
        )
}
