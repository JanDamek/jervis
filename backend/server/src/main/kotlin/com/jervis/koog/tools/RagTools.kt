package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.SearchRequest
import com.jervis.rag.StoreRequest
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * RAG (Retrieval-Augmented Generation) tools for knowledge management.
 * Native Koog implementation - no MCP dependencies.
 */
@LLMDescription("Search and store knowledge in RAG (embeddings, semantic search)")
class RagTools(
    private val plan: Plan,
    private val knowledgeService: KnowledgeService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Search knowledge base using semantic embeddings.
Searches across all indexed content: docs, code, emails, meetings, memories.
Returns raw deduplicated chunks without LLM synthesis.""")
    fun searchKnowledge(
        @LLMDescription("Search query text")
        query: String,

        @LLMDescription("Maximum number of results to return (default: 25)")
        maxResults: Int = 25,

        @LLMDescription("Minimum similarity score 0.0-1.0 (default: 0.65)")
        minScore: Double = 0.65,

        @LLMDescription("Filter by knowledge types: DOCUMENT, MEMORY, RULE, CODE, etc.")
        knowledgeTypes: Set<KnowledgeType>? = null,
    ): String = runBlocking {
        logger.info { "KNOWLEDGE_SEARCH: query='$query', maxResults=$maxResults, minScore=$minScore" }

        val searchRequest = SearchRequest(
            query = query,
            clientId = plan.clientDocument.id,
            projectId = plan.projectDocument?.id,
            maxResults = maxResults,
            minScore = minScore,
            embeddingType = EmbeddingType.TEXT,
            knowledgeTypes = knowledgeTypes,
        )

        val result = knowledgeService.search(searchRequest)
        logger.info { "KNOWLEDGE_SEARCH_COMPLETE: Found results" }

        result.text
    }

    @Tool
    @LLMDescription("""Store content as MEMORY in knowledge base.
Memories are contextual facts stored directly without approval.
Use for: meeting notes, client preferences, environment details, learned context.""")
    fun storeMemory(
        @LLMDescription("Content to store as memory")
        content: String,
    ): String = runBlocking {
        logger.info { "KNOWLEDGE_STORE: Storing MEMORY (${content.length} chars)" }

        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Content cannot be blank")
        }

        val documentToStore = DocumentToStore(
            documentId = "memory:${plan.correlationId}:${System.currentTimeMillis()}",
            content = trimmed,
            clientId = plan.clientDocument.id,
            projectId = plan.projectDocument?.id,
            type = KnowledgeType.MEMORY,
            embeddingType = EmbeddingType.TEXT,
            title = null,
            location = null,
            relatedDocs = emptyList(),
        )

        val result = knowledgeService.store(StoreRequest(listOf(documentToStore)))
        val stored = result.documents.firstOrNull()
            ?: throw IllegalStateException("Store operation returned no documents")

        buildString {
            appendLine("Successfully stored MEMORY")
            appendLine("  Document ID: ${stored.documentId}")
            appendLine("  Total Chunks: ${stored.totalChunks}")
        }
    }
}
