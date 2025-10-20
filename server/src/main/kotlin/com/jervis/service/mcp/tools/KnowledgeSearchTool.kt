package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.rag.RagService
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.rag.domain.RagResult
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Knowledge search tool implementing RAG (Retrieval Augmented Generation) strategy.
 *
 * Responsibilities:
 * - Parse task description into structured search queries using LLM
 * - Delegate RAG pipeline execution to RagService
 * - Format and return results
 *
 * The RAG processing (search, deduplication, filtering, synthesis) is handled by RagService.
 */
@Service
class KnowledgeSearchTool(
    private val llmGateway: LlmGateway,
    private val ragService: RagService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "KNOWLEDGE_SEARCH_START: Executing knowledge search" }

        val searchQueries = parseSearchQueries(taskDescription, context, stepContext)
        require(searchQueries.isNotEmpty()) { "No search queries generated from task description" }

        val ragResult = ragService.executeRagPipeline(searchQueries, taskDescription, context)

        logger.info { "KNOWLEDGE_SEARCH_COMPLETE: Processed ${ragResult.queriesProcessed} queries" }

        return ToolResult.success(
            toolName = name.name,
            summary = buildSummary(ragResult),
            content = ragResult.answer,
        )
    }

    private suspend fun parseSearchQueries(
        taskDescription: String,
        context: TaskContext,
        stepContext: String,
    ): List<RagQuery> {
        logger.debug { "Parsing search queries from task description" }

        val mappingValue =
            mapOf(
                "taskDescription" to taskDescription,
                "stepContext" to stepContext,
                "clientName" to context.clientDocument.name,
                "projectName" to context.projectDocument.name,
            )

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                responseSchema = listOf(RagQuery(searchTerms = "")),
                quick = context.quick,
                mappingValue = mappingValue,
            )

        require(llmResponse.result.isNotEmpty()) { "LLM returned empty search queries list" }
        return llmResponse.result
    }

    private fun buildSummary(ragResult: RagResult): String =
        "Knowledge search completed: ${ragResult.queriesProcessed} queries processed, " +
            "${ragResult.totalChunksFound} chunks found, " +
            "${ragResult.totalChunksFiltered} relevant chunks selected"
}
