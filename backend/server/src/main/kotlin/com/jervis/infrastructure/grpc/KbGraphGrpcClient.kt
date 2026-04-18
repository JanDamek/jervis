package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.GetNodeRequest
import com.jervis.contracts.knowledgebase.GraphNode
import com.jervis.contracts.knowledgebase.GraphNodeList
import com.jervis.contracts.knowledgebase.KnowledgeGraphServiceGrpcKt
import com.jervis.contracts.knowledgebase.SearchNodesRequest
import com.jervis.contracts.knowledgebase.TraversalRequest
import com.jervis.contracts.knowledgebase.TraversalSpec as ProtoTraversalSpec
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeGraphService.
// Graph traversal + node lookup land in slice 7. Alias RPCs already
// wired via GraphServicer on the server side; Kotlin has no current
// caller for alias, so no wrapper method here yet. Thought-map RPCs
// remain on FastAPI pending their slice.
@Component
class KbGraphGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeGraphServiceGrpcKt.KnowledgeGraphServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    suspend fun traverse(
        clientId: String,
        startKey: String,
        direction: String = "OUTBOUND",
        minDepth: Int = 1,
        maxDepth: Int = 1,
        edgeCollection: String = "",
    ): GraphNodeList =
        stub.traverse(
            TraversalRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setClientId(clientId)
                .setStartKey(startKey)
                .setSpec(
                    ProtoTraversalSpec.newBuilder()
                        .setDirection(direction)
                        .setMinDepth(minDepth)
                        .setMaxDepth(maxDepth)
                        .setEdgeCollection(edgeCollection)
                        .build(),
                )
                .build(),
        )

    suspend fun getNode(
        nodeKey: String,
        clientId: String = "",
        projectId: String = "",
        groupId: String = "",
    ): GraphNode =
        stub.getNode(
            GetNodeRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setNodeKey(nodeKey)
                .setClientId(clientId)
                .setProjectId(projectId)
                .setGroupId(groupId)
                .build(),
        )

    suspend fun searchNodes(
        query: String,
        clientId: String = "",
        projectId: String = "",
        groupId: String = "",
        nodeType: String = "",
        branchName: String = "",
        maxResults: Int = 20,
    ): GraphNodeList =
        stub.searchNodes(
            SearchNodesRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setQuery(query)
                .setClientId(clientId)
                .setProjectId(projectId)
                .setGroupId(groupId)
                .setMaxResults(maxResults)
                .setNodeType(nodeType)
                .setBranchName(branchName)
                .build(),
        )
}
