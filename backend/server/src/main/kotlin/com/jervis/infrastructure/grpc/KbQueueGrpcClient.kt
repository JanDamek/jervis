package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.KnowledgeQueueServiceGrpcKt
import com.jervis.contracts.knowledgebase.QueueList
import com.jervis.contracts.knowledgebase.QueueListRequest
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeQueueService.
// Read-only — pairs with IndexingQueueRpcImpl which renders the LLM
// extraction queue in the UI.
@Component
class KbQueueGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeQueueServiceGrpcKt.KnowledgeQueueServiceCoroutineStub(channel)

    private fun ctx(): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun listQueue(limit: Int = 200): QueueList =
        stub.listQueue(
            QueueListRequest.newBuilder()
                .setCtx(ctx())
                .setLimit(limit)
                .build(),
        )
}
