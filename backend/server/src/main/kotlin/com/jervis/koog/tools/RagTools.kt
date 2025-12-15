package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.rag.HybridSearchRequest
import com.jervis.rag.KnowledgeService
import com.jervis.rag.StoreChunkRequest
import mu.KotlinLogging

/**
 * RAG (Retrieval-Augmented Generation) tools for knowledge management.
 * Atomic chunking architecture - agent extracts context, service embeds and stores.
 */
@LLMDescription("Atomic RAG storage and hybrid search (BM25 + Vector)")
class RagTools(
    private val task: PendingTaskDocument,
    private val knowledgeService: KnowledgeService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """ATOMIC chunk storage - YOU extract the entity context, service only embeds and stores.
This is the NEW recommended way for structured data extraction (Confluence, Email, Jira).

WORKFLOW (Agent-driven chunking):
1. YOU identify the main entity in the input text (e.g., Confluence page, Email message)
2. YOU extract the exact text snippet that defines this entity
3. YOU construct the nodeKey pattern from the schema (e.g., "confluence::<pageId>")
4. Call this tool with the extracted chunk
5. Get back chunkId for graph linking

This enables Hybrid Search (BM25 + Vector) - keyword matching + semantic similarity.

Parameters:
- documentId: Same as nodeKey pattern (e.g., "confluence::33095749")
- content: The EXACT text snippet you extracted (NOT the full input)
- nodeKey: Graph node key for cross-reference (MUST match documentId)
- entityTypes: Entity types present (e.g., ["confluence", "page", "space"])
- graphRefs: Related graph nodes (e.g., ["space::MySpace"])
""",
    )
    suspend fun storeChunk(
        @LLMDescription("Extracted entity context - the exact text defining this entity")
        content: String,
        @LLMDescription("Graph node key (MUST match documentId)")
        nodeKey: String,
        @LLMDescription("Related graph node keys (e.g., ['space::MySpace'])")
        graphRefs: List<String> = emptyList(),
    ): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Content cannot be blank")
        }

        logger.info {
            "ATOMIC_STORE_CHUNK: nodeKey='$nodeKey', size=${trimmed.length}"
        }

        val chunkRequest =
            StoreChunkRequest(
                content = trimmed,
                clientId = task.clientId,
                projectId = task.projectId,
                sourceUrn = task.sourceUrn,
                graphRefs = graphRefs,
            )

        val chunkId = knowledgeService.storeChunk(chunkRequest)

        return buildString {
            appendLine("Successfully stored CHUNK")
            appendLine("  Chunk ID: $chunkId")
            appendLine("  Node Key: $nodeKey")
            appendLine("  Size: ${trimmed.length} chars")
        }
    }

    @Tool
    @LLMDescription(
        """Hybrid search combining BM25 (keyword) + Vector (semantic) similarity.
Use this to check for existing entities before creating duplicates.

The alpha parameter controls the search balance:
- alpha=0.0: Pure BM25 (exact keyword matching) - best for finding specific IDs/codes
- alpha=0.5: Balanced hybrid (default) - combines keywords + meaning
- alpha=1.0: Pure vector (semantic similarity) - best for concept matching

Example: Before creating "confluence::123", search with alpha=0.3 to find exact page ID matches.
""",
    )
    suspend fun searchHybrid(
        @LLMDescription("Search query text")
        query: String,
        @LLMDescription("Balance: 0.0=BM25 only, 0.5=hybrid, 1.0=vector only (default: 0.5)")
        alpha: Float = 0.5f,
        @LLMDescription("Maximum results (default: 10)")
        maxResults: Int = 10,
    ): String {
        logger.info { "HYBRID_SEARCH: query='$query', alpha=$alpha, maxResults=$maxResults" }

        val searchRequest =
            HybridSearchRequest(
                query = query,
                clientId = task.clientId,
                projectId = task.projectId,
                maxResults = maxResults,
                alpha = alpha,
            )

        val result = knowledgeService.searchHybrid(searchRequest)
        logger.info { "HYBRID_SEARCH_COMPLETE" }

        return result.text
    }
}
