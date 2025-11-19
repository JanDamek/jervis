package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * MCP Tool for indexing safe links into RAG.
 * Used by agents after determining a link is safe to index.
 *
 * This tool bypasses safety checks (skipSafetyCheck=true) because the agent
 * has already analyzed the link and determined it's safe.
 *
 * Use cases:
 * - Agent reviews UNCERTAIN link from pending task → decides SAFE → calls this tool
 * - Agent discovers new documentation link → verifies safety → indexes it
 */
@Service
class IndexLinkTool(
    private val linkIndexingService: LinkIndexingService,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.SYSTEM_INDEX_LINK_TOOL

    @Serializable
    data class IndexLinkParams(
        val url: String = "", // URL to index (required, will be validated)
        val reason: String? = null, // Why this link is safe to index
        val sourceType: String = "URL", // RagSourceType enum name
    )

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        try {
            logger.info { "Executing link indexing: $taskDescription" }

            val params = parseTaskDescription(taskDescription, plan, stepContext)
            logger.debug { "Parsed index link params: $params" }

            handleIndexLink(params, plan)
        } catch (e: Exception) {
            logger.error(e) { "Error indexing link" }
            ToolResult.error("Failed to index link: ${e.message}")
        }

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String,
    ): IndexLinkParams {
        return llmGateway
            .callLlm(
                type = PromptTypeEnum.SYSTEM_INDEX_LINK_TOOL,
                responseSchema = IndexLinkParams(),
                quick = plan.quick,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                correlationId = plan.correlationId,
                backgroundMode = plan.backgroundMode,
            ).result
    }

    private suspend fun handleIndexLink(
        params: IndexLinkParams,
        plan: Plan,
    ): ToolResult {
        val url = params.url.trim()
        if (url.isBlank()) {
            return ToolResult.error("URL cannot be blank")
        }

        // Validate URL format
        val urlRegex = Regex("^https?://[^\\s]+$")
        if (!urlRegex.matches(url)) {
            return ToolResult.error("Invalid URL format: $url")
        }

        // Parse sourceType enum
        val sourceType =
            try {
                RagSourceType.valueOf(params.sourceType.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warn { "Invalid sourceType '${params.sourceType}', using URL" }
                RagSourceType.URL
            }

        // Index the link (skip safety check - agent already validated)
        val result =
            linkIndexingService.indexUrl(
                url = url,
                projectId = plan.projectDocument?.id,
                clientId = plan.clientDocument.id,
                sourceType = sourceType,
                skipSafetyCheck = true, // Agent already validated safety
            )

        return if (result.skipped) {
            ToolResult.ok(
                """
                Link was not indexed (may already exist or failed to fetch):
                - URL: $url
                - Reason: ${params.reason ?: "Agent determined safe"}
                """.trimIndent(),
            )
        } else {
            logger.info { "Successfully indexed link: $url (${result.processedChunks} chunks)" }
            ToolResult.success(
                toolName = name.name,
                summary = "Indexed link: $url",
                content =
                    """
                    Successfully indexed link into RAG:
                    - URL: $url
                    - Chunks indexed: ${result.processedChunks}
                    - Source type: $sourceType
                    - Reason: ${params.reason ?: "Agent determined safe"}
                    """.trimIndent(),
            )
        }
    }
}
