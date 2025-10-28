package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.rag.RagService
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.rag.domain.RagResult
import com.jervis.service.text.TextChunkingService
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
    private val ragService: RagService,
    override val promptRepository: PromptRepository,
    private val textChunkingService: TextChunkingService,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "KNOWLEDGE_SEARCH_START: Executing knowledge search" }

        val searchQueries = parseSearchQueries(taskDescription)
        val ragResult = ragService.executeRagPipeline(searchQueries, taskDescription, plan)

        logger.info { "KNOWLEDGE_SEARCH_COMPLETE: Processed ${ragResult.queriesProcessed} queries" }

        return ToolResult.success(
            toolName = name.name,
            summary = buildSummary(ragResult),
            content = ragResult.answer,
        )
    }

    private suspend fun parseSearchQueries(taskDescription: String): List<RagQuery> {
        logger.debug { "Parsing search queries from task description" }
        return textChunkingService.splitText(taskDescription).map {
            RagQuery(searchTerms = it.text())
        }
    }

    private fun buildSummary(ragResult: RagResult): String =
        "Knowledge search completed: ${ragResult.queriesProcessed} queries processed, " +
            "${ragResult.totalChunksFound} chunks found, " +
            "${ragResult.totalChunksFiltered} relevant chunks selected"
}
