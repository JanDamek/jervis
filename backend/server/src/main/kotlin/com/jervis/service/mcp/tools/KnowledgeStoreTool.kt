package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Knowledge Store tool for storing content into the vector database.
 * This is a PARAMETRIC tool (NO LLM) - accepts PLAIN TEXT directly.
 * Uses TextChunkingService for automatic chunking of large content.
 */
@Service
class KnowledgeStoreTool(
    private val ragIndexingService: RagIndexingService,
    private val textChunkingService: TextChunkingService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_STORE_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "KNOWLEDGE_STORE_START: Storing content (${taskDescription.length} chars)" }

        if (taskDescription.isBlank()) {
            return ToolResult.error("Content cannot be blank")
        }

        return executeKnowledgeStoreOperation(taskDescription, plan)
    }

    private suspend fun executeKnowledgeStoreOperation(
        content: String,
        plan: Plan,
    ): ToolResult {
        logger.debug {
            "KNOWLEDGE_STORE_OPERATION: content_length=${content.length}"
        }

        // Chunk the content if it's large
        val chunks = textChunkingService.splitText(content).map { it.text() }

        logger.info { "KNOWLEDGE_STORE_CHUNKS: Processing ${chunks.size} chunk(s)" }

        val storedIds = mutableListOf<String>()

        chunks.forEach { chunk ->
            val ragDocument =
                RagDocument(
                    projectId = plan.projectDocument?.id,
                    ragSourceType = RagSourceType.AGENT,
                    text = chunk,
                    clientId = plan.clientDocument.id,
                    createdAt = Instant.now(),
                )

            // Use new RagIndexingService
            val result =
                ragIndexingService
                    .indexDocument(ragDocument, ModelTypeEnum.EMBEDDING_TEXT)
                    .getOrElse { error ->
                        logger.error { "Failed to index chunk: ${error.message}" }
                        return ToolResult.error("Failed to store chunk: ${error.message}")
                    }

            storedIds.add(result.vectorStoreId)
        }

        val contextInfo = "client=${plan.clientDocument.name}, project=${plan.projectDocument?.name ?: "none"}"
        val result =
            buildString {
                appendLine("Successfully stored ${chunks.size} chunk(s) to knowledge base")
                appendLine("Total content: ${content.length} chars")
                appendLine("Context: $contextInfo")
                appendLine("IDs: ${storedIds.joinToString(", ")}")
            }

        logger.info { "KNOWLEDGE_STORE_SUCCESS: Stored ${chunks.size} chunks, IDs: ${storedIds.joinToString()}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Stored ${chunks.size} chunk(s) in knowledge base",
            content = result,
        )
    }
}
