package com.jervis.service.listener.git.processor

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.ProjectDocument
import com.jervis.service.background.PendingTaskService
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
) {
    private val logger = KotlinLogging.logger {}

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

            // Build context map for task
            val context =
                mapOf(
                    "commitHash" to commitData.commitHash,
                    "author" to commitData.author,
                    "message" to commitData.message,
                    "branch" to commitData.branch,
                    "additions" to commitData.additions.toString(),
                    "deletions" to commitData.deletions.toString(),
                    "changedFilesCount" to commitData.changedFiles.size.toString(),
                    "projectPath" to projectPath.toString(),
                )

            // Build content string with detailed information
            val content =
                buildString {
                    appendLine("Commit Analysis Required")
                    appendLine("Commit: ${commitData.commitHash}")
                    appendLine("Author: ${commitData.author}")
                    appendLine("Message: ${commitData.message}")
                    appendLine("Branch: ${commitData.branch}")
                    appendLine("Changes: +${commitData.additions}/-${commitData.deletions}")
                    appendLine("Files changed: ${commitData.changedFiles.size}")
                    appendLine()
                    appendLine("Analysis Goals:")
                    appendLine("- Find potential bugs in code changes")
                    appendLine("- Detect gaps in application architecture")
                    appendLine("- Link to requirements from meetings, planning, documentation")
                    appendLine("- Verify commit doesn't break application")
                }

            val created =
                pendingTaskService.createTask(
                    taskType = PendingTaskTypeEnum.COMMIT_ANALYSIS,
                    content = content,
                    projectId = project.id,
                    clientId = project.clientId,
                    needsQualification = true,
                    context = context,
                )

            logger.info {
                "Created commit analysis task ${created.id} for commit ${commitData.commitHash.take(8)}"
            }

            created
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
