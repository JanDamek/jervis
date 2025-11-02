package com.jervis.service.listener.git.processor

import com.jervis.domain.task.PendingTask
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.entity.ProjectDocument
import com.jervis.service.background.PendingTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Creates PROJECT_DESCRIPTION_UPDATE tasks to keep project descriptions current.
 *
 * Purpose:
 * - Update shortDescription and fullDescription after commits
 * - Use file descriptions from RAG (not source code) to avoid context limits
 * - Handle both normal updates (few files) and initial updates (many files)
 *
 * Strategy:
 * - After N commits OR if descriptions are empty → create update task
 * - Task uses FILE_DESCRIPTION from RAG to understand what changed
 * - Agent decides strategy: normal update vs initial generation
 *
 * Process:
 * 1. Check if update is needed (commits since last update, empty descriptions)
 * 2. Create PROJECT_DESCRIPTION_UPDATE task with context
 * 3. Task goes through qualification (skip if trivial changes)
 * 4. Background engine processes qualified task
 * 5. Agent updates ProjectDocument with new descriptions
 */
@Service
class ProjectDescriptionUpdater(
    private val pendingTaskService: PendingTaskService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        // Create update task after this many commits
        private const val COMMITS_THRESHOLD = 10

        // Always update if descriptions are empty (initial setup)
        private const val FORCE_UPDATE_IF_EMPTY = true
    }

    /**
     * Check if project descriptions need updating and create task if needed.
     * Returns task ID if created, null otherwise.
     */
    suspend fun checkAndCreateUpdateTask(
        project: ProjectDocument,
        recentCommitHashes: List<String>,
        commitsSinceLastUpdate: Int,
    ): PendingTask? =
        withContext(Dispatchers.IO) {
            logger.debug {
                "Checking if project ${project.name} needs description update: " +
                    "$commitsSinceLastUpdate commits since last update"
            }

            // Check if update is needed
            val needsUpdate = shouldUpdateDescriptions(project, commitsSinceLastUpdate)

            if (!needsUpdate) {
                logger.debug { "Project ${project.name} descriptions are up to date" }
                return@withContext null
            }

            // Determine if this is initial update (large) or normal update
            val isInitialUpdate = project.shortDescription.isNullOrBlank() || project.fullDescription.isNullOrBlank()

            logger.info {
                "Creating description update task for project ${project.name} " +
                    "(initial=$isInitialUpdate, commits=$commitsSinceLastUpdate)"
            }

            createDescriptionUpdateTask(project, recentCommitHashes, isInitialUpdate)
        }

    /**
     * Determine if project descriptions need updating.
     */
    private fun shouldUpdateDescriptions(
        project: ProjectDocument,
        commitsSinceLastUpdate: Int,
    ): Boolean {
        // Always update if descriptions are empty (initial setup)
        if (FORCE_UPDATE_IF_EMPTY) {
            if (project.shortDescription.isNullOrBlank() || project.fullDescription.isNullOrBlank()) {
                return true
            }
        }

        // Update after threshold number of commits
        if (commitsSinceLastUpdate >= COMMITS_THRESHOLD) {
            return true
        }

        return false
    }

    /**
     * Create PROJECT_DESCRIPTION_UPDATE task.
     */
    private suspend fun createDescriptionUpdateTask(
        project: ProjectDocument,
        recentCommitHashes: List<String>,
        isInitialUpdate: Boolean,
    ): PendingTask {
        val context =
            mapOf(
                "projectId" to project.id.toHexString(),
                "recentCommitHashes" to recentCommitHashes.joinToString(","),
                "currentShortDescription" to (project.shortDescription ?: ""),
                "currentFullDescription" to (project.fullDescription ?: ""),
                "isInitialUpdate" to isInitialUpdate.toString(),
                "commitCount" to recentCommitHashes.size.toString(),
            )

        val content =
            buildString {
                appendLine("Project Description Update Required")
                appendLine()
                appendLine("Project: ${project.name}")
                appendLine("Recent commits: ${recentCommitHashes.size}")
                if (isInitialUpdate) {
                    appendLine("Type: INITIAL UPDATE (descriptions empty or missing)")
                } else {
                    appendLine("Type: NORMAL UPDATE (incremental changes)")
                }
                appendLine()
                appendLine("Current descriptions:")
                appendLine("  Short: ${project.shortDescription?.take(100) ?: "(empty)"}")
                appendLine("  Full: ${project.fullDescription?.take(200) ?: "(empty)"}")
                appendLine()
                appendLine("Recent commit hashes:")
                recentCommitHashes.take(10).forEach { appendLine("  - ${it.take(8)}") }
                if (recentCommitHashes.size > 10) {
                    appendLine("  ... and ${recentCommitHashes.size - 10} more")
                }
                appendLine()
                appendLine("Task:")
                if (isInitialUpdate) {
                    appendLine("- Use STRATEGY B: Initial/Large Update")
                    appendLine("- DO NOT read source code (too large)")
                    appendLine("- Use git_file_description for all files from RAG")
                    appendLine("- Group by package, infer structure")
                    appendLine("- Generate complete descriptions from file descriptions")
                } else {
                    appendLine("- Use STRATEGY A: Normal Update")
                    appendLine("- Get file list for recent commits")
                    appendLine("- Get descriptions for new/changed files")
                    appendLine("- Merge into existing descriptions")
                }
                appendLine()
                appendLine("Update both shortDescription and fullDescription fields.")
            }

        return pendingTaskService.createTask(
            taskType = PendingTaskTypeEnum.PROJECT_DESCRIPTION_UPDATE,
            content = content,
            projectId = project.id,
            clientId = project.clientId,
            needsQualification = true,
            context = context,
        )
    }
}
