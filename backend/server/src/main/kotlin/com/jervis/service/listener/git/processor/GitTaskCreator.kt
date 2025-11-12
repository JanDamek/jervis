package com.jervis.service.listener.git.processor

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.ProjectDocument
import com.jervis.service.background.PendingTaskService
import com.jervis.service.text.TextNormalizationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Creates pending tasks for Git commit analysis.
 * Each commit gets a task for deep analysis: finding bugs, gaps, requirement traceability.
 *
 * Responsibilities:
 * - Create COMMIT_ANALYSIS tasks for each processed commit
 * - Set appropriate context data (commit hash, files, author)
 * - Schedule tasks for background execution
 *
 * Task goals (defined in background-task-goals.yaml):
 * - Find potential bugs in code changes
 * - Detect architectural gaps
 * - Link to requirements from meetings, planning, documentation
 * - Verify commit doesn't break application
 */
@Service
class GitTaskCreator(
    private val pendingTaskService: PendingTaskService,
    private val textNormalizationService: TextNormalizationService,
) {
    private val logger = KotlinLogging.logger {}

    private companion object {
        // Cap per-file content to avoid oversized tasks
        const val MAX_FILE_BYTES: Int = 512 * 1024 // 512 KB per file

        // Cap total task content to keep Mongo document under 16MB and reasonable for transport
        const val MAX_TASK_CONTENT_CHARS: Int = 4_000_000 // ~4M chars (~8MB UTF-16), safe headroom
    }

    /**
     * Commit data for task creation
     */
    data class CommitData(
        val commitHash: String,
        val author: String,
        val message: String,
        val branch: String,
        val changedFiles: List<String>,
        val additions: Int,
        val deletions: Int,
    )

    /**
     * Create pending task for single file analysis in commit.
     * One task per file = cleaner, more focused analysis.
     */
    suspend fun createFileAnalysisTask(
        project: ProjectDocument,
        projectPath: Path,
        commitData: CommitData,
        filePath: String,
    ): PendingTask =
        withContext(Dispatchers.IO) {
            logger.info {
                "Creating file analysis task for ${commitData.commitHash.take(8)}:$filePath " +
                    "in project ${project.name}"
            }

            // Get diff for this specific file
            val fileDiff =
                try {
                    gitDiffFile(projectPath, commitData.commitHash, filePath)
                } catch (e: Exception) {
                    "[Could not retrieve diff for $filePath at ${commitData.commitHash}: ${e.message}]"
                }

            // Everything in content - simple and clear
            val content =
                buildString {
                    appendLine("File Change Analysis Required")
                    appendLine()
                    appendLine("Project: ${project.name}")
                    appendLine("Commit: ${commitData.commitHash}")
                    appendLine("Author: ${commitData.author}")
                    appendLine("Branch: ${commitData.branch}")
                    appendLine("Message: ${commitData.message}")
                    appendLine("File: $filePath")
                    appendLine("Source: git://${project.id.toHexString()}/${commitData.commitHash}/$filePath")
                    appendLine()
                    appendLine("Analysis Goals:")
                    appendLine("- Find potential bugs in code changes")
                    appendLine("- Detect gaps in application architecture")
                    appendLine("- Link to requirements from meetings, planning, documentation")
                    appendLine("- Verify changes don't break application")
                    appendLine()
                    appendLine("=== DIFF ===")
                    appendLine(fileDiff)
                    appendLine("=== END DIFF ===")
                }

            val finalContent =
                if (content.length > MAX_TASK_CONTENT_CHARS) {
                    content.take(MAX_TASK_CONTENT_CHARS) + "\n\n[... task content truncated to keep under storage limits ...]"
                } else {
                    content
                }

            val created =
                pendingTaskService.createTask(
                    taskType = PendingTaskTypeEnum.COMMIT_ANALYSIS,
                    content = finalContent,
                    projectId = project.id,
                    clientId = project.clientId,
                    sourceUri = "git://${project.id.toHexString()}/${commitData.commitHash}/$filePath",
                )

            logger.info {
                "Created file analysis task ${created.id} for ${commitData.commitHash.take(8)}:$filePath"
            }

            created
        }

    private fun gitDiffFile(
        repoPath: Path,
        commitHash: String,
        filePath: String,
    ): String {
        val process =
            ProcessBuilder(
                "git",
                "diff",
                "$commitHash^",
                commitHash,
                "--",
                filePath,
            ).directory(repoPath.toFile())
                .redirectErrorStream(true)
                .start()

        val bytes = process.inputStream.readAllBytes()
        val exit = process.waitFor()
        if (exit != 0) {
            val msg =
                try {
                    String(bytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    "exit=$exit, ${bytes.size} bytes"
                }
            throw IllegalStateException("git diff failed for $filePath at $commitHash: $msg")
        }

        // Detect binary by presence of NUL or many control chars
        val sample = if (bytes.size > MAX_FILE_BYTES) bytes.copyOf(MAX_FILE_BYTES) else bytes
        val controlCount = sample.count { b -> b.toInt() in 0..8 || b.toInt() in 14..31 }
        val isBinary = sample.any { it == 0.toByte() } || controlCount > (sample.size / 10)
        if (isBinary) {
            return "[Binary file omitted at $commitHash:$filePath, size=${bytes.size} bytes]"
        }

        val rawDiff = sample.toString(Charsets.UTF_8)
        val normalizedDiff = textNormalizationService.normalizePreservingCode(rawDiff)

        return if (bytes.size > MAX_FILE_BYTES) {
            normalizedDiff + "\n\n[Truncated: ${bytes.size - MAX_FILE_BYTES} more bytes omitted]"
        } else {
            normalizedDiff
        }
    }

    /**
     * Create file analysis tasks for all changed files in commits.
     * One task per file for focused analysis.
     */
    suspend fun createCommitAnalysisTasks(
        project: ProjectDocument,
        projectPath: Path,
        commits: List<CommitData>,
    ): List<PendingTask> =
        withContext(Dispatchers.IO) {
            logger.info { "Creating file analysis tasks for ${commits.size} commits in project ${project.name}" }

            val tasks = mutableListOf<PendingTask>()

            for (commit in commits) {
                for (filePath in commit.changedFiles) {
                    // Skip lock files and generated files
                    if (shouldSkipFileInCommitAnalysis(filePath)) {
                        logger.debug { "Skipping file $filePath in commit ${commit.commitHash.take(8)}" }
                        continue
                    }

                    try {
                        val task = createFileAnalysisTask(project, projectPath, commit, filePath)
                        tasks.add(task)
                    } catch (e: Exception) {
                        logger.error(e) {
                            "Failed to create file analysis task for ${commit.commitHash.take(8)}:$filePath"
                        }
                    }
                }
            }

            logger.info {
                "Created ${tasks.size} file analysis tasks for project ${project.name}"
            }

            tasks
        }

    /**
     * Check if file should be skipped in commit analysis.
     * Skip lock files, large generated files that pollute the analysis.
     */
    private fun shouldSkipFileInCommitAnalysis(filePath: String): Boolean {
        val lowerPath = filePath.lowercase()
        return lowerPath.endsWith("package-lock.json") ||
            lowerPath.endsWith("yarn.lock") ||
            lowerPath.endsWith("pnpm-lock.yaml") ||
            lowerPath.endsWith("composer.lock") ||
            lowerPath.endsWith("gemfile.lock") ||
            lowerPath.endsWith("cargo.lock") ||
            lowerPath.endsWith("poetry.lock") ||
            lowerPath.endsWith("pipfile.lock") ||
            lowerPath.contains("/.gradle/") ||
            lowerPath.contains("/build/") ||
            lowerPath.contains("/target/") ||
            lowerPath.contains("/node_modules/") ||
            lowerPath.contains("/dist/") ||
            lowerPath.contains("/out/")
    }
}
