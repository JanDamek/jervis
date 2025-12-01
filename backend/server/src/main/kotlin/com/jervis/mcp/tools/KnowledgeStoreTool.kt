package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.StoreRequest
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Intelligent Knowledge Store tool.
 *
 * Automatically classifies content into RULE or MEMORY using LLM.
 * - RULES: Create UserTask for approval before storing
 * - MEMORIES: Store directly using KnowledgeManagementService
 *
 * Uses Knowledge Engine infrastructure for proper tagging and indexing.
 */
@Service
class KnowledgeStoreTool(
    private val knowledgeService: KnowledgeService,
    override val promptRepository: PromptRepository,
) : McpTool<KnowledgeStoreTool.Description> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.KNOWLEDGE_STORE_TOOL

    @Serializable
    data class Description(
        val content: String = "Plain text to store as MEMORY in knowledge base",
    )

    override val descriptionObject =
        Description(
            content = "Meeting notes about client X environment and constraints.",
        )

    override suspend fun execute(
        plan: Plan,
        request: Description,
    ): ToolResult {
        logger.info { "KNOWLEDGE_STORE_START: Storing content as MEMORY (${request.content.length} chars)" }

        val content = request.content.trim()
        if (content.isBlank()) {
            return ToolResult.error("Content cannot be blank")
        }

        // Store directly as MEMORY without any classification
        val documentToStore =
            DocumentToStore(
                documentId = "memory:${plan.correlationId}:${System.currentTimeMillis()}",
                content = content,
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                type = KnowledgeType.MEMORY,
                embeddingType = EmbeddingType.TEXT,
                title = null,
                location = null,
                relatedDocs = emptyList(),
            )

        val result = knowledgeService.store(StoreRequest(listOf(documentToStore)))

        val stored =
            result.documents.firstOrNull()
                ?: return ToolResult.error("Store operation returned no documents")

        return ToolResult.success(
            toolName = name.name,
            summary = "Memory stored (ID: ${stored.documentId})",
            content =
                buildString {
                    appendLine("Successfully stored MEMORY")
                    appendLine("  Document ID: ${stored.documentId}")
                    appendLine("  Total Chunks: ${stored.totalChunks}")
                },
        )
    }

    // No classification or rule approval is performed anymore.
}
