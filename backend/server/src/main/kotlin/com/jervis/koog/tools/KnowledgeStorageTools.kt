package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.rag.IngestRequest
import com.jervis.rag.KnowledgeService
import com.jervis.rag.RetrievalRequest
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
        "Search knowledge base (RAG + GraphDB) for relevant information. Use this FIRST before any other research tool!",
    )
    suspend fun searchKnowledgeBase(
        @LLMDescription("Search query - be specific (e.g. 'NTB nákup Alza', 'email purchase notebook')")
        query: String,
        @LLMDescription("Max results (10=standard, 20=detailed)")
        limit: Int = 10,
    ): SearchResult {
        val result =
            knowledgeService.retrieve(
                RetrievalRequest(
                    query = query,
                    clientId = task.clientId,
                    projectId = task.projectId,
                ),
            )
        logger.info { "SEARCH_KNOWLEDGEBASE_COMPLETE: query='$query' | resultsLength=${result.combinedSummary().length}" }
        return SearchResult(success = true, results = result.combinedSummary(), query = query)
    }

    @Tool
    @LLMDescription(
        "Alias for searchKnowledgeBase - search for relevant knowledge chunks.",
    )
    suspend fun searchKnowledge(
        @LLMDescription("Search query")
        query: String,
        @LLMDescription("Max results> 10=standard")
        limit: Int = 10,
    ): SearchResult = searchKnowledgeBase(query, limit)

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
    ): RelatedNodesResult {
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
        return RelatedNodesResult(
            success = true,
            sourceNode = nodeKey,
            relatedNodes = nodes.map { NodeInfo(it.key, it.entityType, it.ragChunks.size) },
        )
    }

    @Tool
    @LLMDescription("Traverse graph from starting node to discover connected entities. Returns nodes with their basic info.")
    suspend fun traverse(
        @LLMDescription("Starting node key")
        startKey: String,
        @LLMDescription("Max depth (1-5)")
        maxDepth: Int = 2,
        @LLMDescription("Edge types to follow (empty = all)")
        edgeTypes: List<String> = emptyList(),
        @LLMDescription("Max total nodes")
        limit: Int = 50,
    ): TraversalResult {
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
        return TraversalResult(
            success = true,
            startKey = startKey,
            nodes = nodes.map { NodeInfo(it.key, it.entityType, it.ragChunks.size) },
            totalFound = nodes.size,
        )
    }

    @Tool
    @LLMDescription("Store knowledge in RAG and create graph structure. Use for permanent knowledge.")
    suspend fun storeKnowledge(
        @LLMDescription("Text content to store")
        content: String,
        @LLMDescription("Type of knowledge (e.g. TEXT, TABLE, CODE, PDF_TEXT, IMAGE_OCR, IMAGE_CAPTION)")
        kind: String = "TEXT",
        @LLMDescription("Section path for structured sources (e.g. 'Project/Subproject/Section')")
        sectionPath: String = "",
        @LLMDescription("Asset ID if this comes from an attachment/image")
        assetId: String? = null,
        @LLMDescription("Stable identifier for the source (confluence:123, jira:456, file:abc)")
        sourceUrn: String? = null,
        @LLMDescription("Optional graph references to link this chunk to existing entities")
        graphRefs: List<String> = emptyList(),
        @LLMDescription("Optional order index for reconstructing the document")
        orderIndex: Int? = null,
        @LLMDescription("Optional version identifier")
        version: String? = null,
    ): CombinedStoreResult {
        // JERVIS: Před uložením velkého obsahu (Confluence, logy) provádíme extrakci podstatných faktů.
        // Do promptu jde jen shrnutí, do RAG/Graph jde plný obsah (EvidencePack komprese).
        val result =
            knowledgeService.ingest(
                IngestRequest(
                    clientId = task.clientId,
                    projectId = task.projectId,
                    sourceUrn = sourceUrn?.let { com.jervis.types.SourceUrn(it) } ?: task.sourceUrn,
                    kind = kind,
                    content = content,
                    metadata =
                        buildMap {
                            if (sectionPath.isNotBlank()) put("sectionPath", sectionPath)
                            assetId?.let { put("assetId", it) }
                            if (graphRefs.isNotEmpty()) put("graphRefs", graphRefs)
                            orderIndex?.let { put("orderIndex", it) }
                            version?.let { put("version", it) }
                            put("hashNormalized", sha256(content))
                        },
                ),
            )

        logger.info { "STORE_KNOWLEDGE: success=${result.success}, nodes=${result.ingestedNodes.size}" }
        return CombinedStoreResult(
            success = result.success,
            chunkId = "ingest-batch",
            error = result.error,
        )
    }

    private fun sha256(input: String): String {
        val bytes =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Tool
    @LLMDescription("Retrieve full evidence pack (RAG + Graph context) for a query.")
    suspend fun retrieveEvidence(
        @LLMDescription("Search query")
        query: String,
    ): EvidencePackResult {
        val pack =
            knowledgeService.retrieve(
                RetrievalRequest(
                    query = query,
                    clientId = task.clientId,
                    projectId = task.projectId,
                ),
            )
        return EvidencePackResult(
            success = true,
            summary = pack.summary,
            itemsCount = pack.items.size,
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

    @Serializable
    data class EvidencePackResult(
        val success: Boolean,
        val summary: String,
        val itemsCount: Int,
        val error: String? = null,
    )
}
