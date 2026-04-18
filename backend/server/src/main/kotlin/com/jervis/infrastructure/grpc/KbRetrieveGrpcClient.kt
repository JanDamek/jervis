package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.EvidencePack
import com.jervis.contracts.knowledgebase.KnowledgeRetrieveServiceGrpcKt
import com.jervis.contracts.knowledgebase.RetrievalRequest
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeRetrieveService.
// Retrieve (hybrid default) + RetrieveSimple (RAG-only) land first; the
// other RPCs (RetrieveHybrid, AnalyzeCode, JoernScan, ListChunksByKind)
// are added as their consumer slices land.
@Component
class KbRetrieveGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeRetrieveServiceGrpcKt.KnowledgeRetrieveServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun retrieve(
        query: String,
        clientId: String = "",
        projectId: String = "",
        groupId: String = "",
        asOfIso: String = "",
        maxResults: Int = 5,
        minConfidence: Double = 0.0,
        expandGraph: Boolean = true,
        kinds: List<String> = emptyList(),
    ): EvidencePack =
        stub.retrieve(
            RetrievalRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setQuery(query)
                .setClientId(clientId)
                .setProjectId(projectId)
                .setGroupId(groupId)
                .setAsOfIso(asOfIso)
                .setMinConfidence(minConfidence)
                .setMaxResults(maxResults)
                .setExpandGraph(expandGraph)
                .addAllKinds(kinds)
                .build(),
        )

    suspend fun retrieveSimple(
        query: String,
        clientId: String = "",
        projectId: String = "",
        groupId: String = "",
        maxResults: Int = 5,
        minConfidence: Double = 0.0,
    ): EvidencePack =
        stub.retrieveSimple(
            RetrievalRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setQuery(query)
                .setClientId(clientId)
                .setProjectId(projectId)
                .setGroupId(groupId)
                .setMaxResults(maxResults)
                .setMinConfidence(minConfidence)
                .build(),
        )
}
