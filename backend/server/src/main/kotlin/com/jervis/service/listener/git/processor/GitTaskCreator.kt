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
     * Create pending task for commit analysis
     */
    suspend fun createCommitAnalysisTask(
        project: ProjectDocument,
        projectPath: Path,
        commitData: CommitData,
    ): PendingTask =
        withContext(Dispatchers.IO) {
            logger.info {
                "Creating commit analysis task for ${commitData.commitHash.take(8)} " +
                    "by ${commitData.author} in project ${project.name}"
            }

            // Everything in content - simple and clear
            val content =
                buildString {
                    appendLine("Commit Analysis Required")
                    appendLine()
                    appendLine("Project: ${project.name}")
                    appendLine("Commit: ${commitData.commitHash}")
                    appendLine("Author: ${commitData.author}")
                    appendLine("Branch: ${commitData.branch}")
                    appendLine("Message: ${commitData.message}")
                    appendLine("Changes: +${commitData.additions}/-${commitData.deletions}")
                    appendLine("Files changed: ${commitData.changedFiles.size}")
                    appendLine("Source: git://${project.id.toHexString()}/${commitData.commitHash}")
                    appendLine()
                    appendLine("Analysis Goals:")
                    appendLine("- Find potential bugs in code changes")
                    appendLine("- Detect gaps in application architecture")
                    appendLine("- Link to requirements from meetings, planning, documentation")
                    appendLine("- Verify commit doesn't break application")
                    appendLine()
                    appendLine("Changed Files – Full Content at Commit:")
                    for (path in commitData.changedFiles) {
                        appendLine()
                        appendLine("=== FILE: $path ===")
                        val fileContent =
                            try {
                                gitShowFile(projectPath, commitData.commitHash, path)
                            } catch (e: Exception) {
                                "[Could not retrieve content for $path at ${commitData.commitHash}: ${e.message}]"
                            }
                        appendLine(fileContent)
                        appendLine("=== END FILE: $path ===")
                    }
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
                    sourceUri = "git://${project.id.toHexString()}/${commitData.commitHash}",
                )

            logger.info {
                "Created commit analysis task ${created.id} for commit ${commitData.commitHash.take(8)}"
            }

            created
        }

    private fun gitShowFile(
        repoPath: Path,
        commitHash: String,
        filePath: String,
    ): String {
        val process =
            ProcessBuilder(
                "git",
                "show",
                "$commitHash:$filePath",
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
            throw IllegalStateException("git show failed for $filePath at $commitHash: $msg")
        }

        // Detect binary by presence of NUL or many control chars
        val sample = if (bytes.size > MAX_FILE_BYTES) bytes.copyOf(MAX_FILE_BYTES) else bytes
        val controlCount = sample.count { b -> b.toInt() in 0..8 || b.toInt() in 14..31 }
        val isBinary = sample.any { it == 0.toByte() } || controlCount > (sample.size / 10)
        if (isBinary) {
            return "[Binary file omitted at $commitHash:$filePath, size=${bytes.size} bytes]"
        }

        val rawText = sample.toString(Charsets.UTF_8)
        val normalizedText = textNormalizationService.normalizePreservingCode(rawText)

        return if (bytes.size > MAX_FILE_BYTES) {
            normalizedText + "\n\n[Truncated: ${bytes.size - MAX_FILE_BYTES} more bytes omitted]"
        } else {
            normalizedText
        }
    }

    /**
     * Create multiple commit analysis tasks in batch
     */
    suspend fun createCommitAnalysisTasks(
        project: ProjectDocument,
        projectPath: Path,
        commits: List<CommitData>,
    ): List<PendingTask> =
        withContext(Dispatchers.IO) {
            logger.info { "Creating ${commits.size} commit analysis tasks for project ${project.name}" }

            val tasks = mutableListOf<PendingTask>()

            for (commit in commits) {
                try {
                    val task = createCommitAnalysisTask(project, projectPath, commit)
                    tasks.add(task)
                } catch (e: Exception) {
                    logger.error(e) {
                        "Failed to create commit analysis task for ${commit.commitHash.take(8)}"
                    }
                }
            }

            logger.info {
                "Created ${tasks.size} commit analysis tasks for project ${project.name}"
            }

            tasks
        }
}
