package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.SearchRequest
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Knowledge search tool for direct vector store queries.
 *
 * Responsibilities:
 * - Parse task description into structured search queries using LLM
 * - Execute direct vector store searches
 * - Return raw deduplicated chunks without LLM synthesis
 *
 * The calling LLM formulates exact queries and receives raw data for processing.
 */
@Service
class KnowledgeSearchTool(
    private val knowledgeService: KnowledgeService,
    override val promptRepository: PromptRepository,
) : McpTool<KnowledgeSearchTool.Description> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Serializable
    data class Description(
        val query: String,
        val maxResult: Int,
        val minScore: Double,
        val knowledgeTypes: Set<KnowledgeType>? = null,
    )

    override val name = ToolTypeEnum.KNOWLEDGE_SEARCH_TOOL

    override val descriptionObject =
        Description(
            query = "The search terms to query the knowledge base.",
            maxResult = 25,
            minScore = 0.65,
            knowledgeTypes = setOf(KnowledgeType.DOCUMENT, KnowledgeType.MEMORY),
        )

    override suspend fun execute(
        plan: Plan,
        request: Description,
    ): ToolResult {
        logger.info { "KNOWLEDGE_SEARCH_START: Executing knowledge search for: ${request.query}" }

        // Call new KnowledgeService API
        val searchRequest =
            SearchRequest(
                query = request.query,
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                maxResults = request.maxResult,
                minScore = request.minScore,
                embeddingType = EmbeddingType.TEXT,
                knowledgeTypes = request.knowledgeTypes,
            )

        val searchResult = knowledgeService.search(searchRequest)
        logger.info { "KNOWLEDGE_SEARCH_COMPLETE: Found results" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Knowledge search completed",
            content = searchResult.text,
        )
    }
}
