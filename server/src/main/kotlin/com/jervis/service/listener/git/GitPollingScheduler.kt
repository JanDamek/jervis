package com.jervis.service.listener.git

import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.listener.git.processor.GitIndexingOrchestrator
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Periodic scheduler for Git repository synchronization.
 * Polls projects one at a time and triggers complete Git indexing workflow.
 *
 * Responsibilities:
 * - Schedule periodic Git syncs (default: every 5 minutes)
 * - Select next project to sync based on lastGitSyncAt
 * - Clone or pull repository
 * - Delegate complete indexing to GitIndexingOrchestrator
 *
 * The orchestrator handles:
 * - Commit metadata indexing (TEXT embeddings)
 * - Code diff indexing (CODE embeddings)
 * - Pending task creation for commit analysis
 */
@Service
class GitPollingScheduler(
    private val projectMongoRepository: ProjectMongoRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val gitIndexingOrchestrator: GitIndexingOrchestrator,
) {
    @Scheduled(
        fixedDelayString = "\${git.sync.polling-interval-ms:300000}",
        initialDelayString = "\${git.sync.initial-delay-ms:60000}",
    )
    suspend fun syncNextProject() {
        runCatching {
            findNextProjectToSync()
                ?.let { project -> processProject(project) }
                ?: logger.debug { "No projects with Git configuration to sync" }
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled Git sync" }
        }
    }

    private suspend fun findNextProjectToSync() =
        projectMongoRepository
            .findFirstByOrderByLastGitSyncAtAscCreatedAtAsc()
            .awaitSingleOrNull()
            ?.takeIf { hasGitConfiguration(it) }

    private fun hasGitConfiguration(project: ProjectDocument): Boolean {
        // TODO: Git config now at client level only
        // GitRepositoryService will validate actual access
        return true
    }

    private suspend fun processProject(project: ProjectDocument) {
        logger.info { "GIT_SYNC: Processing project: ${project.name}" }

        try {
            // Step 1: Clone or pull repository
            val result = gitRepositoryService.cloneOrUpdateRepository(project)

            if (result.isFailure) {
                logger.warn { "GIT_SYNC: Failed to sync repository for project: ${project.name} - ${result.exceptionOrNull()?.message}" }
                updateProjectSyncTimestamp(project)
                return
            }

            val gitDir =
                result.getOrNull() ?: run {
                    logger.warn { "GIT_SYNC: No gitDir returned for project: ${project.name}" }
                    updateProjectSyncTimestamp(project)
                    return
                }

            logger.info { "GIT_SYNC: Successfully synced repository for project: ${project.name}" }

            // Step 2: Get current branch for vector store tracking
            val currentBranch = gitRepositoryService.getCurrentBranch(gitDir)
            logger.info { "GIT_SYNC: Current branch for project ${project.name}: $currentBranch" }

            // Step 3: Orchestrate complete Git indexing workflow
            if (gitDir.resolve(".git").toFile().exists()) {
                logger.info { "GIT_SYNC: Starting complete Git indexing for project: ${project.name}" }
                val indexingResult = gitIndexingOrchestrator.orchestrateGitIndexing(project, gitDir, currentBranch)
                logger.info {
                    "GIT_SYNC: Git indexing completed for project: ${project.name} - " +
                        "Commits: ${indexingResult.commitMetadataResult.processedCommits}, " +
                        "Code files: ${indexingResult.codeIndexingResult.indexedFiles}, " +
                        "Tasks created: ${indexingResult.tasksCreated}, " +
                        "Errors: ${indexingResult.errors}"
                }
            }

            // Update sync timestamp
            updateProjectSyncTimestamp(project)
        } catch (e: Exception) {
            logger.error(e) { "GIT_SYNC: Error processing project: ${project.name}" }
            updateProjectSyncTimestamp(project)
        }
    }

    private suspend fun updateProjectSyncTimestamp(project: ProjectDocument) {
        val updated = project.copy(lastGitSyncAt = java.time.Instant.now())
        projectMongoRepository.save(updated)
    }
}
