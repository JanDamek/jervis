package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * MCP Tool for manual Git repository synchronization.
 * Performs git clone (if not exists) or git pull (if exists) for a project.
 *
 * Usage scenarios:
 * - Manual refresh of project codebase before analysis
 * - Recovery from failed automatic sync
 * - Forced update after known remote changes
 */
@Service
class ProjectGitSyncTool(
    private val llmGateway: LlmGateway,
    private val gitRepositoryService: GitRepositoryService,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}
    override val name: PromptTypeEnum = PromptTypeEnum.PROJECT_GIT_SYNC_TOOL

    @Serializable
    data class ProjectGitSyncParams(
        val action: String = "sync",
        val validateAccess: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String,
    ): ProjectGitSyncParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PROJECT_GIT_SYNC_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = plan.quick,
                responseSchema = ProjectGitSyncParams(),
                backgroundMode = plan.backgroundMode,
            )
        return llmResponse.result
    }

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val syncParams = parseTaskDescription(taskDescription, plan, stepContext)
                logger.info { "PROJECT_GIT_SYNC: Starting Git sync with params: $syncParams" }

                // TODO: Check client mono-repo URL instead of project URL
                logger.warn { "Git sync temporarily disabled - needs refactoring to use client mono-repo" }
                return@withContext ToolResult.error(
                    output =
                        "❌ Git Synchronization Temporarily Unavailable\n\n" +
                            "Git operations are being refactored to use client-level mono-repository configuration. " +
                            "This feature will be available soon.",
                )
            } catch (e: Exception) {
                logger.error(e) { "PROJECT_GIT_SYNC: Unexpected error" }
                return@withContext ToolResult.error(
                    output =
                        "❌ Git Synchronization Failed\n\n" +
                            "Unexpected error: ${e.message}",
                )
            }
        }
}
