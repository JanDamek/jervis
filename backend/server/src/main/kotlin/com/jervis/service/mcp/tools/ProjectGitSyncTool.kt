package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
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
    private val gitRepositoryService: GitRepositoryService,
    override val promptRepository: PromptRepository,
) : McpTool<ProjectGitSyncTool.ProjectGitSyncParams> {
    private val logger = KotlinLogging.logger {}
    override val name = ToolTypeEnum.PROJECT_GIT_SYNC_TOOL

    @Serializable
    data class ProjectGitSyncParams(
        val action: String = "sync", // currently only "sync" is supported
        val validateAccess: Boolean = false,
        val branch: String? = null, // optional branch to checkout after sync
    )

    override val descriptionObject =
        ProjectGitSyncParams(
            action = "sync",
            validateAccess = false,
            branch = "main",
        )

    override suspend fun execute(
        plan: Plan,
        request: ProjectGitSyncParams,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val syncParams = request
                logger.info { "PROJECT_GIT_SYNC: Starting Git sync with params: $syncParams" }

                val project =
                    plan.projectDocument
                        ?: return@withContext ToolResult.error(
                            "No project context available",
                            "Project required for git sync",
                        )

                // Perform clone or update
                val result = gitRepositoryService.cloneOrUpdateRepository(project)
                val gitDir =
                    result.getOrElse { e ->
                        return@withContext ToolResult.error(
                            output = "❌ Git Synchronization Failed\n\n${e.message}",
                            message = e.message,
                        )
                    }

                // Optional branch checkout from request parameters
                val branch = syncParams.branch
                if (!branch.isNullOrBlank()) {
                    runCatching { ensureBranchCheckedOut(gitDir, branch) }
                        .onFailure { e ->
                            return@withContext ToolResult.error(
                                output = "❌ Git Synchronization Failed (branch checkout)\n\n${e.message}",
                                message = e.message,
                            )
                        }
                }

                ToolResult.success(
                    toolName = name.name,
                    summary = "Git repository synchronized",
                    content =
                        buildString {
                            appendLine("Path: $gitDir")
                            branch?.let { appendLine("Branch: $it") }
                            appendLine("Status: OK")
                        },
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

    private fun ensureBranchCheckedOut(
        gitDir: java.nio.file.Path,
        branch: String,
    ) {
        val fetch =
            ProcessBuilder("git", "fetch", "origin", branch)
                .directory(gitDir.toFile())
                .redirectErrorStream(true)
                .start()
        val fetchOut = fetch.inputStream.bufferedReader().use { it.readText() }
        val fetchExit = fetch.waitFor()
        if (fetchExit != 0) {
            throw IllegalStateException("git fetch failed for branch '$branch' in $gitDir: $fetchOut")
        }
        val checkout =
            ProcessBuilder("git", "checkout", branch).directory(gitDir.toFile()).redirectErrorStream(true).start()
        val checkoutOut = checkout.inputStream.bufferedReader().use { it.readText() }
        val checkoutExit = checkout.waitFor()
        if (checkoutExit != 0) {
            throw IllegalStateException("git checkout failed for branch '$branch' in $gitDir: $checkoutOut")
        }
    }
}
