package com.jervis.service.listener.git

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.listener.git.processor.GitIndexingOrchestrator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Unified periodic scheduler for Git repository synchronization.
 * Handles BOTH client mono-repos AND standalone project repositories.
 *
 * Architecture:
 * - Phase 1: Sync ALL client mono-repos (parallel processing per mono-repo)
 * - Phase 2: Sync ALL standalone projects (parallel processing per project)
 *
 * Parallel processing workflow per repository:
 * 1. git fetch (fast, only history)
 * 2. Index commit metadata (parallel with clone/pull)
 * 3. git clone/pull (full, with physical files)
 * 4. Index code content
 *
 * This enables maximum parallelization while maintaining correct order.
 *
 * RAG Strategy:
 * - Mono-repo commits: indexed with clientId + monoRepoId (no projectId)
 * - Standalone commits: indexed with clientId + projectId
 * This enables cross-project code discovery within mono-repos.
 */
@Service
class GitPollingScheduler(
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val gitIndexingOrchestrator: GitIndexingOrchestrator,
) {
    @Scheduled(
        fixedDelayString = "\${git.sync.polling-interval-ms:300000}",
        initialDelayString = "\${git.sync.initial-delay-ms:60000}",
    )
    fun syncAllRepositoriesScheduled() {
        // Spring @Scheduled does not support suspend directly; bridge via runBlocking
        kotlinx.coroutines.runBlocking {
            syncAllRepositories()
        }
    }

    private suspend fun syncAllRepositories() {
        runCatching {
            logger.info { "=== GIT_SYNC: Starting unified Git synchronization ===" }

            // Phase 1: Sync all client mono-repos sequentially (Git operations are serialized via mutex)
            syncAllClientMonoRepos()

            // Phase 2: Sync all standalone projects sequentially (Git operations are serialized via mutex)
            syncAllStandaloneProjects()

            logger.info { "=== GIT_SYNC: Unified Git synchronization completed ===" }
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled Git sync" }
        }
    }

    // ========== Phase 1: Client Mono-Repos ==========

    private suspend fun syncAllClientMonoRepos() {
        logger.info { "GIT_SYNC Phase 1: Starting client mono-repo synchronization..." }

        val clients = clientMongoRepository.findAll().toList()
        var totalMonoRepos = 0
        var processedMonoRepos = 0

        for (client in clients) {
            if (client.monoRepos.isEmpty()) {
                continue
            }

            totalMonoRepos += client.monoRepos.size
            logger.info { "GIT_SYNC: Client ${client.name} has ${client.monoRepos.size} mono-repo(s)" }

            // Process all mono-repos for this client sequentially (Git mutex serializes remote ops)
            for (monoRepoConfig in client.monoRepos) {
                processMonoRepo(client, monoRepoConfig)
                processedMonoRepos++
            }
        }

        logger.info {
            "GIT_SYNC Phase 1: Completed - processed $processedMonoRepos/$totalMonoRepos mono-repo(s)"
        }
    }

    private suspend fun processMonoRepo(
        client: ClientDocument,
        monoRepoConfig: MonoRepoConfig,
    ) {
        logger.info { "GIT_SYNC: Processing mono-repo: ${monoRepoConfig.name}" }

        try {
            // Parallel workflow: fetch → index metadata (parallel) → clone/pull → index code
            coroutineScope {
                // Step 1: Fast fetch (only history, no files)
                val fetchResult = gitRepositoryService.fetchMonoRepo(client, monoRepoConfig)

                if (fetchResult.isFailure) {
                    logger.warn {
                        "GIT_SYNC: Failed to fetch mono-repo ${monoRepoConfig.name} - ${fetchResult.exceptionOrNull()?.message}"
                    }
                    return@coroutineScope
                }

                val gitDir =
                    fetchResult.getOrNull() ?: run {
                        logger.warn { "GIT_SYNC: No gitDir returned for mono-repo ${monoRepoConfig.name}" }
                        return@coroutineScope
                    }

                logger.info { "GIT_SYNC: Successfully fetched mono-repo: ${monoRepoConfig.name}" }

                // Step 2: Index commit metadata (can run in parallel with clone/pull)
                // Step 3: Clone/pull full repository (with physical files)
                // Step 4: Index code content
                // All orchestrated by GitIndexingOrchestrator with parallel processing

                if (gitDir.resolve(".git").toFile().exists()) {
                    logger.info { "GIT_SYNC: Starting complete indexing for mono-repo: ${monoRepoConfig.name}" }

                    val indexingResult =
                        gitIndexingOrchestrator.orchestrateMonoRepoIndexing(
                            clientId = client.id,
                            monoRepoId = monoRepoConfig.id,
                            monoRepoPath = gitDir,
                            branch = monoRepoConfig.defaultBranch,
                        )

                    logger.info {
                        "GIT_SYNC: Mono-repo indexing completed for ${monoRepoConfig.name} - " +
                            "Commits: ${indexingResult.commitMetadataResult.processedCommits}, " +
                            "Code files: ${indexingResult.codeIndexingResult.indexedFiles}, " +
                            "Errors: ${indexingResult.errors}"
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "GIT_SYNC: Error processing mono-repo: ${monoRepoConfig.name}" }
        }
    }

    // ========== Phase 2: Standalone Projects ==========

    private suspend fun syncAllStandaloneProjects() {
        logger.info { "GIT_SYNC Phase 2: Starting standalone project synchronization..." }

        val projects = projectMongoRepository.findAll().toList()
        val standaloneProjects = projects.filter { hasStandaloneGitConfiguration(it) }

        if (standaloneProjects.isEmpty()) {
            logger.info { "GIT_SYNC Phase 2: No standalone projects with Git configuration" }
            return
        }

        logger.info { "GIT_SYNC: Found ${standaloneProjects.size} standalone project(s) with Git configuration" }

        // Process all standalone projects sequentially (Git mutex serializes remote ops)
        for (project in standaloneProjects) {
            processStandaloneProject(project)
        }

        logger.info { "GIT_SYNC Phase 2: Completed - processed ${standaloneProjects.size} project(s)" }
    }

    /**
     * Check if project has standalone Git configuration.
     * A project is standalone if it has its own Git URL override (not using client mono-repo).
     */
    private suspend fun hasStandaloneGitConfiguration(project: ProjectDocument): Boolean {
        // Project has its own Git URL override (not mono-repo reference)
        if (project.overrides?.gitRemoteUrl != null) {
            logger.debug { "Project ${project.name} has standalone Git URL" }
            return true
        }

        // Project references mono-repo - not standalone
        if (project.monoRepoId != null) {
            logger.debug { "Project ${project.name} uses mono-repo, not standalone" }
            return false
        }

        // No Git configuration
        logger.debug { "Project ${project.name} has no Git configuration" }
        return false
    }

    private suspend fun processStandaloneProject(project: ProjectDocument) {
        logger.info { "GIT_SYNC: Processing standalone project: ${project.name}" }

        try {
            // Step 1: Clone or pull repository
            val result = gitRepositoryService.cloneOrUpdateRepository(project)

            if (result.isFailure) {
                logger.warn {
                    "GIT_SYNC: Failed to sync repository for project ${project.name} - ${result.exceptionOrNull()?.message}"
                }
                updateProjectSyncTimestamp(project)
                return
            }

            val gitDir =
                result.getOrNull() ?: run {
                    logger.warn { "GIT_SYNC: No gitDir returned for project ${project.name}" }
                    updateProjectSyncTimestamp(project)
                    return
                }

            logger.info { "GIT_SYNC: Successfully synced repository for project: ${project.name}" }

            // Step 2: Get current branch for vector store tracking
            val currentBranch = gitRepositoryService.getCurrentBranch(gitDir)
            logger.info { "GIT_SYNC: Current branch for project ${project.name}: $currentBranch" }

            // Step 3: Orchestrate complete Git indexing workflow
            if (gitDir.resolve(".git").toFile().exists()) {
                logger.info { "GIT_SYNC: Starting complete indexing for project: ${project.name}" }

                val indexingResult = gitIndexingOrchestrator.orchestrateGitIndexing(project, gitDir, currentBranch)

                logger.info {
                    "GIT_SYNC: Project indexing completed for ${project.name} - " +
                        "Commits: ${indexingResult.commitMetadataResult.processedCommits}, " +
                        "Code files: ${indexingResult.codeIndexingResult.indexedFiles}, " +
                        "Commit tasks: ${indexingResult.tasksCreated}, " +
                        "File tasks: ${indexingResult.fileAnalysisTasksCreated}, " +
                        "Desc update: ${indexingResult.descriptionUpdateTaskCreated}, " +
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
        val updated = project.copy(lastGitSyncAt = Instant.now())
        projectMongoRepository.save(updated)
    }
}
