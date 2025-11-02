package com.jervis.service.mcp.tools.git

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * MCP Tool: git_commit_diff
 *
 * Get full Git diff for a specific commit.
 * Returns what changed: added/modified/deleted files with actual code changes.
 *
 * Usage:
 * - git_commit_diff(commitHash: "abc123")
 *
 * Returns:
 * - changedFiles: List of file paths
 * - additions: Total lines added
 * - deletions: Total lines deleted
 * - diffContent: Full diff text (git show output)
 */
@Service
class GitCommitDiffTool(
    private val projectMongoRepository: ProjectMongoRepository,
    private val directoryStructureService: DirectoryStructureService,
    private val gitRepositoryService: GitRepositoryService,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.GIT_COMMIT_DIFF_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "GIT_COMMIT_DIFF: Fetching diff for commit" }

        return try {
            val projectId =
                plan.projectDocument?.id
                    ?: return ToolResult.error("No project context available", "Project required for git_commit_diff")

            // Extract commitHash from task description
            val commitHash = extractCommitHash(taskDescription)

            // Get diff
            val diff = getCommitDiff(projectId, commitHash)

            ToolResult.success(
                toolName = name.name,
                summary = "Git diff for commit ${
                    commitHash.take(
                        8,
                    )
                }: ${diff.changedFiles.size} files, +${diff.additions}/-${diff.deletions}",
                content =
                    buildString {
                        appendLine("Commit: $commitHash")
                        appendLine("Changed files: ${diff.changedFiles.size}")
                        appendLine("Additions: +${diff.additions}")
                        appendLine("Deletions: -${diff.deletions}")
                        appendLine()
                        appendLine("Files changed:")
                        diff.changedFiles.forEach { appendLine("  - $it") }
                        appendLine()
                        appendLine("Full diff:")
                        appendLine(diff.diffContent)
                    },
            )
        } catch (e: Exception) {
            logger.error(e) { "GIT_COMMIT_DIFF: Failed to fetch diff" }
            ToolResult.error(
                output = "Failed to fetch commit diff: ${e.message}",
                message = e.message,
            )
        }
    }

    private fun extractCommitHash(taskDescription: String): String {
        // Extract commit hash from various formats:
        // "commitHash: abc123"
        // "commit: abc123"
        // "abc123"
        val hashPattern = Regex("""(?:commit(?:Hash)?:\s*)?([a-f0-9]{7,40})""", RegexOption.IGNORE_CASE)
        return hashPattern.find(taskDescription)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No commit hash found in: $taskDescription")
    }

    private suspend fun getCommitDiff(
        projectId: ObjectId,
        commitHash: String,
    ): DiffResult =
        withContext(Dispatchers.IO) {
            val project =
                projectMongoRepository.findById(projectId)
                    ?: throw IllegalStateException("Project not found: $projectId")

            val gitDir = directoryStructureService.projectGitDir(project)

            if (!gitDir.toFile().exists()) {
                throw IllegalStateException("Git repository not found for project: ${project.name}")
            }

            // Execute git show to get diff
            val process =
                ProcessBuilder(
                    "git",
                    "show",
                    "--no-color",
                    "--unified=5", // Show 5 lines of context
                    "--stat", // Include stats
                    commitHash,
                ).directory(gitDir.toFile())
                    .redirectErrorStream(true)
                    .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("git show failed (exit code $exitCode): $output")
            }

            // Parse output to extract file list and stats
            val changedFiles = mutableListOf<String>()
            var additions = 0
            var deletions = 0

            val statPattern = Regex("""(\d+)\s+insertions?\(\+\),\s+(\d+)\s+deletions?\(-\)""")
            val filePattern = Regex("""^\s+(\S+)\s+\|""", RegexOption.MULTILINE)

            output.lines().forEach { line ->
                // Extract files from stat section
                filePattern.find(line)?.let { match ->
                    changedFiles.add(match.groupValues[1])
                }

                // Extract total stats
                statPattern.find(line)?.let { match ->
                    additions = match.groupValues[1].toIntOrNull() ?: additions
                    deletions = match.groupValues[2].toIntOrNull() ?: deletions
                }
            }

            DiffResult(
                changedFiles = changedFiles,
                additions = additions,
                deletions = deletions,
                diffContent = output,
            )
        }

    private data class DiffResult(
        val changedFiles: List<String>,
        val additions: Int,
        val deletions: Int,
        val diffContent: String,
    )
}
