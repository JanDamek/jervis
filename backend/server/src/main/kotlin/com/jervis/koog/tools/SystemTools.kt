package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagSourceType
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.service.background.PendingTaskService
import com.jervis.service.link.LinkIndexingService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * System-level operations for link indexing and background task creation.
 * Native Koog implementation - no MCP dependencies.
 */
@LLMDescription("System operations: index safe links, create background analysis tasks")
class SystemTools(
    private val plan: Plan,
    private val linkIndexingService: LinkIndexingService,
    private val pendingTaskService: PendingTaskService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Index a safe link into RAG knowledge base.
Use after verifying link safety (docs, articles, product pages).
Bypasses safety checks - only use after manual validation.""")
    fun indexLink(
        @LLMDescription("URL to index (must start with http:// or https://)")
        url: String,

        @LLMDescription("Why this link is safe to index - your validation reasoning")
        reason: String? = null,

        @LLMDescription("Source type: URL, CONFLUENCE, JIRA, EMAIL, etc.")
        sourceType: String = "URL",
    ): String = runBlocking {
        logger.info { "Executing link indexing: url=$url" }

        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            throw IllegalArgumentException("URL cannot be blank")
        }

        // Validate URL format
        val urlRegex = Regex("^https?://[^\\s]+$")
        if (!urlRegex.matches(trimmedUrl)) {
            throw IllegalArgumentException("Invalid URL format: $trimmedUrl")
        }

        // Parse sourceType enum
        val ragSourceType = try {
            RagSourceType.valueOf(sourceType.uppercase())
        } catch (_: IllegalArgumentException) {
            logger.warn { "Invalid sourceType '$sourceType', using URL" }
            RagSourceType.URL
        }

        // Index the link (skip safety check - agent already validated)
        val result = linkIndexingService.indexUrl(
            url = trimmedUrl,
            projectId = plan.projectDocument?.id,
            clientId = plan.clientDocument.id,
            skipSafetyCheck = true, // Agent already validated safety
        )

        if (result.skipped) {
            """
            Link was not indexed (may already exist or failed to fetch):
            - URL: $trimmedUrl
            - Reason: ${reason ?: "Agent determined safe"}
            """.trimIndent()
        } else {
            logger.info { "Successfully indexed link: $trimmedUrl (${result.processedChunks} chunks)" }
            """
            Successfully indexed link into RAG:
            - URL: $trimmedUrl
            - Chunks indexed: ${result.processedChunks}
            - Source type: $ragSourceType
            - Reason: ${reason ?: "Agent determined safe"}
            """.trimIndent()
        }
    }

    @Tool
    @LLMDescription("""Create a pending task for background processing by another agent.
Use when discovering anomalies, bugs, issues requiring focused investigation.
Task will be queued for async processing.""")
    fun createPendingTask(
        @LLMDescription("Description of what needs to be analyzed or processed")
        description: String,
    ): String = runBlocking {
        logger.info { "CREATE_PENDING_TASK: Creating task with description length=${description.length}" }

        if (description.isBlank()) {
            throw IllegalArgumentException("Task description cannot be blank")
        }

        val task = pendingTaskService.createTask(
            taskType = PendingTaskTypeEnum.AGENT_ANALYSIS,
            content = description,
            projectId = plan.projectDocument?.id,
            clientId = plan.clientDocument.id,
            correlationId = plan.correlationId,
        )

        logger.info { "CREATE_PENDING_TASK_SUCCESS: Created task ${task.id}" }

        "Successfully created background analysis task. Task ID: ${task.id}"
    }
}
