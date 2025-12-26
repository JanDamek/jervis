package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.rag.HybridSearchRequest
import com.jervis.rag.KnowledgeService
import com.jervis.rag.StoreChunkRequest
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.rag.internal.graphdb.model.Direction
import com.jervis.rag.internal.graphdb.model.TraversalSpec
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * Unified knowledge storage and retrieval tools.
 * Combines RAG (vector search), Graph DB (relationships), and hybrid storage operations.
 */
@LLMDescription("Storage and retrieval tools for RAG (vector search) and Graph DB (relationships)")
class KnowledgeStorageTools(
    private val task: TaskDocument,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Hybrid search combining BM25 (keyword) + Vector (semantic). Use alpha=0.0 for exact matching, 0.5 for balanced, 1.0 for semantic only.",
    )
    suspend fun searchKnowledge(
        @LLMDescription("Search query")
        query: String,
        @LLMDescription("Balance: 0.0=keywords only, 0.5=balanced, 1.0=semantic only. 0.8=standard")
        alpha: Float = 0.8f,
        @LLMDescription("Max results> 10=standard")
        limit: Int = 10,
    ): SearchResult =
        try {
            val result =
                knowledgeService.searchHybrid(
                    HybridSearchRequest(
                        query = query,
                        clientId = task.clientId,
                        projectId = task.projectId,
                        maxResults = limit,
                        alpha = alpha,
                    ),
                )
            logger.info { "SEARCH_COMPLETE: query='$query', alpha=$alpha" }
            SearchResult(success = true, results = result.text, query = query)
        } catch (e: Exception) {
            logger.error(e) { "Search failed" }
            SearchResult(success = false, results = "", query = query, error = e.message)
        }

    @Tool
    @LLMDescription("Get related nodes via graph relationships")
    suspend fun getRelated(
        @LLMDescription("Starting node key")
        nodeKey: String,
        @LLMDescription("Edge types to follow (empty = all)")
        edgeTypes: List<String> = emptyList(),
        @LLMDescription("Direction: OUTBOUND, INBOUND, ANY")
        direction: String = "ANY",
        @LLMDescription("Max results")
        limit: Int = 20,
    ): RelatedNodesResult =
        try {
            val dir =
                when (direction.uppercase()) {
                    "OUTBOUND" -> Direction.OUTBOUND
                    "INBOUND" -> Direction.INBOUND
                    else -> Direction.ANY
                }

            val nodes =
                graphDBService.getRelated(
                    clientId = task.clientId,
                    nodeKey = nodeKey,
                    edgeTypes = edgeTypes,
                    direction = dir,
                    limit = limit,
                )

            logger.info { "GET_RELATED: found ${nodes.size} nodes for $nodeKey" }
            RelatedNodesResult(
                success = true,
                sourceNode = nodeKey,
                relatedNodes = nodes.map { NodeInfo(it.key, it.entityType, it.ragChunks.size) },
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get related nodes" }
            RelatedNodesResult(success = false, sourceNode = nodeKey, relatedNodes = emptyList(), error = e.message)
        }

    @Tool
    @LLMDescription("Traverse graph from starting node to discover connected entities")
    suspend fun traverse(
        @LLMDescription("Starting node key")
        startKey: String,
        @LLMDescription("Max depth (1-5)")
        maxDepth: Int = 2,
        @LLMDescription("Edge types to follow (empty = all)")
        edgeTypes: List<String> = emptyList(),
        @LLMDescription("Max total nodes")
        limit: Int = 50,
    ): TraversalResult =
        try {
            val spec =
                TraversalSpec(
                    maxDepth = maxDepth.coerceIn(1, 5),
                    edgeTypes = edgeTypes,
                )

            val nodes =
                graphDBService
                    .traverse(task.clientId, startKey, spec)
                    .take(limit)
                    .toList()

            logger.info { "TRAVERSAL: found ${nodes.size} nodes from $startKey" }
            TraversalResult(
                success = true,
                startKey = startKey,
                nodes = nodes.map { NodeInfo(it.key, it.entityType, it.ragChunks.size) },
                totalFound = nodes.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Traversal failed" }
            TraversalResult(
                success = false,
                startKey = startKey,
                nodes = emptyList(),
                totalFound = 0,
                error = e.message,
            )
        }

    @Tool
    @LLMDescription("Store knowledge chunk in RAG and create graph structure atomically")
    suspend fun storeKnowledgeWithGraph(
        @LLMDescription("Text content to store")
        content: String,
        @LLMDescription("Main node key for this content")
        mainNodeKey: String,
        @LLMDescription("Graph relationships as triple 'from|edge|to'.")
        relationships: List<String> = emptyList(),
    ): CombinedStoreResult =
        try {
            val chunkId =
                knowledgeService.storeChunk(
                    StoreChunkRequest(
                        content = content,
                        clientId = task.clientId,
                        projectId = task.projectId,
                        sourceUrn = task.sourceUrn,
                        mainNodeKey = mainNodeKey,
                        relationships = relationships,
                    ),
                )

            logger.info { "STORE_WITH_GRAPH: chunkId=$chunkId, mainNodeKey=$mainNodeKey, relationships=${relationships.size}" }
            CombinedStoreResult(
                success = true,
                chunkId = chunkId,
            )
        } catch (e: Exception) {
            logger.error(e) { "Store with graph failed" }
            CombinedStoreResult(
                success = false,
                chunkId = "",
                error = e.message,
            )
        }

    @Serializable
    data class SearchResult(
        val success: Boolean,
        val results: String,
        val query: String,
        val error: String? = null,
    )

    @Serializable
    data class NodeInfo(
        val key: String,
        val entityType: String,
        val ragChunksCount: Int,
    )

    @Serializable
    data class RelatedNodesResult(
        val success: Boolean,
        val sourceNode: String,
        val relatedNodes: List<NodeInfo>,
        val error: String? = null,
    )

    @Serializable
    data class TraversalResult(
        val success: Boolean,
        val startKey: String,
        val nodes: List<NodeInfo>,
        val totalFound: Int,
        val error: String? = null,
    )

    @Serializable
    data class CombinedStoreResult(
        val success: Boolean,
        val chunkId: String,
        val error: String? = null,
    )
}
