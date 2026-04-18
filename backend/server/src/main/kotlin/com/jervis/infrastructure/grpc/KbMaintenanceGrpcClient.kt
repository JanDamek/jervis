package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.KnowledgeMaintenanceServiceGrpcKt
import com.jervis.contracts.knowledgebase.MaintenanceBatchRequest
import com.jervis.contracts.knowledgebase.MaintenanceBatchResult
import com.jervis.contracts.knowledgebase.RetagGroupRequest
import com.jervis.contracts.knowledgebase.RetagProjectRequest
import com.jervis.contracts.knowledgebase.RetagResult
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeMaintenanceService.
// Matches the shape of RouterAdminGrpcClient — one coroutine stub bound to
// the KB gRPC channel, one-method-per-RPC with RequestContext populated.
@Component
class KbMaintenanceGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeMaintenanceServiceGrpcKt.KnowledgeMaintenanceServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun runBatch(
        maintenanceType: String,
        clientId: String,
        cursor: String?,
        batchSize: Int,
    ): MaintenanceBatchResult =
        stub.runBatch(
            MaintenanceBatchRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setMaintenanceType(maintenanceType)
                .setClientId(clientId)
                .setCursor(cursor ?: "")
                .setBatchSize(batchSize)
                .build(),
        )

    suspend fun retagProject(sourceProjectId: String, targetProjectId: String): RetagResult =
        stub.retagProject(
            RetagProjectRequest.newBuilder()
                .setCtx(ctx())
                .setSourceProjectId(sourceProjectId)
                .setTargetProjectId(targetProjectId)
                .build(),
        )

    suspend fun retagGroup(projectId: String, newGroupId: String?): RetagResult =
        stub.retagGroup(
            RetagGroupRequest.newBuilder()
                .setCtx(ctx())
                .setProjectId(projectId)
                .setNewGroupId(newGroupId ?: "")
                .build(),
        )
}
