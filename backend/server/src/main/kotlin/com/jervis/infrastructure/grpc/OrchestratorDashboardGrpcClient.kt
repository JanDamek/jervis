package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.orchestrator.GetSessionSnapshotRequest
import com.jervis.contracts.orchestrator.OrchestratorDashboardServiceGrpcKt
import com.jervis.contracts.orchestrator.SessionSnapshotResponse
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Kotlin-side client for `jervis.orchestrator.OrchestratorDashboardService`.
 *
 * Pulls the SessionBroker snapshot once per call. The Kotlin server polls
 * this every few seconds into a kRPC `MutableSharedFlow(replay=1)` so the
 * UI sees a push-only stream (rule #9). Pull cadence is an internal
 * implementation detail — the broker is in-process Python state with no
 * server-streaming push channel today.
 */
@Component
class OrchestratorDashboardGrpcClient(
    @Qualifier(GrpcChannels.ORCHESTRATOR_CHANNEL) channel: ManagedChannel,
) {
    private val stub = OrchestratorDashboardServiceGrpcKt.OrchestratorDashboardServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun getSessionSnapshot(): SessionSnapshotResponse =
        stub.getSessionSnapshot(
            GetSessionSnapshotRequest.newBuilder()
                .setCtx(ctx())
                .build(),
        )
}
