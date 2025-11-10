package com.jervis.service.mcp.tools.git

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.ProjectMongoRepository
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
 * MCP Tool: git_commit_files_list
 *
 * Get list of files changed in a commit with change types.
 * Faster than git_commit_diff if you only need file list.
 *
 * Usage:
 * - git_commit_files_list(commitHash: "abc123")
 *
 * Returns:
 * - files: List of changed files
 *   - path: File path
 *   - changeType: ADDED/MODIFIED/DELETED
 *   - additions: Lines added in this file
 *   - deletions: Lines deleted in this file
 */
@Service
class GitCommitFilesListTool(
    private val projectMongoRepository: ProjectMongoRepository,
    private val directoryStructureService: DirectoryStructureService,
    private val gitRepositoryService: com.jervis.service.git.GitRepositoryService,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.GIT_COMMIT_FILES_LIST_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "GIT_COMMIT_FILES_LIST: Fetching file list for commit" }

        return try {
            val projectId =
                plan.projectDocument?.id
                    ?: return ToolResult.error(
                        "No project context available",
                        "Project required for git_commit_files_list",
                    )

            // Extract commitHash
            val commitHash = extractCommitHash(taskDescription)

            // Optional branch parameter
            val branch = extractBranch(taskDescription)

            // Get file list
            val files = getCommitFilesList(projectId, commitHash, branch)

            ToolResult.success(
                toolName = name.name,
                summary = "Commit ${commitHash.take(8)}: ${files.size} files changed",
                content =
                    buildString {
                        appendLine("Commit: $commitHash")
                        appendLine("Files changed: ${files.size}")
                        appendLine()

                        val added = files.count { it.changeType == "ADDED" }
                        val modified = files.count { it.changeType == "MODIFIED" }
                        val deleted = files.count { it.changeType == "DELETED" }

                        appendLine("Summary:")
                        appendLine("  Added: $added files")
                        appendLine("  Modified: $modified files")
                        appendLine("  Deleted: $deleted files")
                        appendLine()

                        appendLine("File list:")
                        files.forEach { file ->
                            appendLine("  ${file.changeType.padEnd(10)} ${file.path}")
                            if (file.additions > 0 || file.deletions > 0) {
                                appendLine("                +${file.additions}/-${file.deletions}")
                            }
                        }
                    },
            )
        } catch (e: Exception) {
            logger.error(e) { "GIT_COMMIT_FILES_LIST: Failed to fetch file list" }
            ToolResult.error(
                output = "Failed to fetch commit files list: ${e.message}",
                message = e.message,
            )
        }
    }

    private fun extractCommitHash(taskDescription: String): String {
        val hashPattern = Regex("""(?:commit(?:Hash)?:\s*)?([a-f0-9]{7,40})""", RegexOption.IGNORE_CASE)
        return hashPattern.find(taskDescription)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No commit hash found in: $taskDescription")
    }

    private suspend fun getCommitFilesList(
        projectId: ObjectId,
        commitHash: String,
        branch: String?,
    ): List<FileChange> =
        withContext(Dispatchers.IO) {
            val project =
                projectMongoRepository.findById(projectId)
                    ?: throw IllegalStateException("Project not found: $projectId")

            var gitDir = directoryStructureService.projectGitDir(project)

            // Ensure repository exists and is a valid Git repository
            if (!gitDir.toFile().exists() || !gitDir.resolve(".git").toFile().exists()) {
                val cloneResult = gitRepositoryService.cloneOrUpdateRepository(project)
                gitDir =
                    cloneResult.getOrElse {
                        throw IllegalStateException("Git repository not available for project: ${project.name}: ${it.message}")
                    }
            }

            // If branch specified, ensure checkout to that branch
            if (!branch.isNullOrBlank()) {
                ensureBranchCheckedOut(gitDir, branch)
            }

            // Execute git show --numstat to get file changes
            val process =
                ProcessBuilder(
                    "git",
                    "show",
                    "--numstat",
                    "--pretty=format:",
                    commitHash,
                ).directory(gitDir.toFile())
                    .redirectErrorStream(true)
                    .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val branchLabel = branch ?: "(current)"
                throw RuntimeException("git show failed (exit code $exitCode) in $gitDir for branch '$branchLabel': $output")
            }

            // Parse output
            // Format: <additions>\t<deletions>\t<filename>
            // "-" means binary file or new/deleted file
            val files = mutableListOf<FileChange>()

            output
                .lines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 3) {
                        val additions = parts[0].toIntOrNull() ?: 0
                        val deletions = parts[1].toIntOrNull() ?: 0
                        val path = parts[2]

                        // Determine change type
                        val changeType =
                            when {
                                additions > 0 && deletions == 0 -> "ADDED"
                                additions == 0 && deletions > 0 -> "DELETED"
                                else -> "MODIFIED"
                            }

                        files.add(
                            FileChange(
                                path = path,
                                changeType = changeType,
                                additions = additions,
                                deletions = deletions,
                            ),
                        )
                    }
                }

            files
        }

    private fun extractBranch(taskDescription: String): String? {
        val pattern = Regex("""branch:\s*([^\s,]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(taskDescription)?.groupValues?.get(1)
    }

    private fun ensureBranchCheckedOut(
        gitDir: java.nio.file.Path,
        branch: String,
    ) {
        // fetch remote branch then checkout
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

    private data class FileChange(
        val path: String,
        val changeType: String,
        val additions: Int,
        val deletions: Int,
    )
}
