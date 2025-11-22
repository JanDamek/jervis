package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
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
    override val promptRepository: PromptRepository,
) : McpTool<IndexLinkTool.IndexLinkParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.SYSTEM_INDEX_LINK_TOOL
    override val descriptionObject =
        IndexLinkParams(
            url = "URL to index (required, will be validated)\nhttps://example.com/docs/page",
            reason = "Why this link is safe to index. Agent validated link safety",
            sourceType = "RagSourceType enum name ${RagSourceType.URL.name}",
        )

    @Serializable
    data class IndexLinkParams(
        val url: String,
        val reason: String?,
        val sourceType: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: IndexLinkParams,
    ): ToolResult =
        try {
            logger.info { "Executing link indexing: $request" }
            handleIndexLink(request, plan)
        } catch (e: Exception) {
            logger.error(e) { "Error indexing link" }
            ToolResult.error("Failed to index link: ${e.message}")
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
            } catch (_: IllegalArgumentException) {
                logger.warn { "Invalid sourceType '${params.sourceType}', using URL" }
                RagSourceType.URL
            }

        // Index the link (skip safety check - agent already validated)
        val result =
            linkIndexingService.indexUrl(
                url = url,
                projectId = plan.projectDocument?.id,
                clientId = plan.clientDocument.id,
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
