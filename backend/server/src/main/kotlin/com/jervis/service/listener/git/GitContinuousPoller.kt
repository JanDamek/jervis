package com.jervis.service.listener.git

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ProjectMongoRepository
import com.jervis.service.git.GitRepositoryService
import com.jervis.service.git.state.GitCommitStateManager
import com.jervis.service.indexing.AbstractPeriodicPoller
import com.jervis.service.listener.git.processor.GitCommitMetadataIndexer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Continuous Git poller using AbstractPeriodicPoller pattern.
 * Handles BOTH client mono-repos AND standalone project repositories.
 *
 * Pattern (same as Email/Jira/Confluence):
 * 1. GitContinuousPoller (this) → discovers commits from Git log → saves to DB as NEW
 * 2. GitContinuousIndexer → reads NEW commits → indexes to RAG → marks INDEXED
 *
 * Polling strategy:
 * - Fetch/pull from remote
 * - Read Git log to discover new commits
 * - Save commit hashes to MongoDB with state NEW
 * - NO indexing here - that's done by GitContinuousIndexer
 */
@Service
class GitContinuousPoller(
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val stateManager: GitCommitStateManager,
    private val metadataIndexer: GitCommitMetadataIndexer, // For fetching commit info only
) : AbstractPeriodicPoller<GitContinuousPoller.GitTarget>() {

    override val pollerName = "GitContinuousPoller"
    override val pollingIntervalMs: Long = 600_000L // 10 minutes
    override val initialDelayMs: Long = 120_000L // 2 minutes
    override val cycleDelayMs: Long = 120_000L // Check all repos every 2 minutes

    sealed class GitTarget {
        data class MonoRepo(
            val client: ClientDocument,
            val config: MonoRepoConfig,
        ) : GitTarget()

        data class StandaloneProject(
            val project: ProjectDocument,
        ) : GitTarget()
    }

    override fun accountsFlow(): Flow<GitTarget> = flow {
        // Emit all mono-repos
        val clients = clientMongoRepository.findAll().toList()
        for (client in clients) {
            for (monoRepo in client.monoRepos) {
                emit(GitTarget.MonoRepo(client, monoRepo))
            }
        }

        // Emit all standalone projects
        val projects = projectMongoRepository.findAll().toList()
        for (project in projects) {
            if (hasStandaloneGitConfiguration(project)) {
                emit(GitTarget.StandaloneProject(project))
            }
        }
    }

    override suspend fun getLastPollTime(account: GitTarget): Long? = when (account) {
        is GitTarget.MonoRepo -> null // MonoRepoConfig doesn't track lastSyncAt yet
        is GitTarget.StandaloneProject -> account.project.lastGitSyncAt?.toEpochMilli()
    }

    override suspend fun executePoll(account: GitTarget): Boolean = when (account) {
        is GitTarget.MonoRepo -> pollMonoRepo(account.client, account.config)
        is GitTarget.StandaloneProject -> pollStandaloneProject(account.project)
    }

    override suspend fun updateLastPollTime(account: GitTarget, timestamp: Long) {
        val instant = Instant.ofEpochMilli(timestamp)
        when (account) {
            is GitTarget.MonoRepo -> {
                // MonoRepoConfig doesn't track lastSyncAt yet - could be added later
                logger.debug { "MonoRepo ${account.config.name} polled successfully" }
            }
            is GitTarget.StandaloneProject -> {
                val updated = account.project.copy(lastGitSyncAt = instant)
                projectMongoRepository.save(updated)
            }
        }
    }

    override fun accountLogLabel(account: GitTarget): String = when (account) {
        is GitTarget.MonoRepo -> "MonoRepo:${account.config.name}"
        is GitTarget.StandaloneProject -> "Project:${account.project.name}"
    }

    // ========== MonoRepo Polling ==========

    private suspend fun pollMonoRepo(client: ClientDocument, config: MonoRepoConfig): Boolean = runCatching {
        logger.info { "GIT_POLL: Discovering commits in mono-repo: ${config.name}" }

        coroutineScope {
            // Step 1: Fetch from remote (updates local repo)
            val fetchResult = gitRepositoryService.fetchMonoRepo(client, config)

            if (fetchResult.isFailure) {
                logger.warn { "GIT_POLL: Failed to fetch mono-repo ${config.name}" }
                return@coroutineScope
            }

            val gitDir = fetchResult.getOrNull() ?: run {
                logger.warn { "GIT_POLL: No gitDir returned for mono-repo ${config.name}" }
                return@coroutineScope
            }

            logger.info { "GIT_POLL: Successfully fetched mono-repo: ${config.name}" }

            // Step 2: Discover commits from Git log and save as NEW
            if (gitDir.resolve(".git").toFile().exists()) {
                val commits = metadataIndexer.fetchCommitInfoFromGit(gitDir, maxCommits = 1000)
                stateManager.saveNewMonoRepoCommits(
                    clientId = client.id,
                    monoRepoId = config.id,
                    commits = commits,
                    branch = config.defaultBranch,
                )

                logger.info {
                    "GIT_POLL: Discovered ${commits.size} commits in mono-repo ${config.name}, " +
                        "saved new ones to DB with state NEW"
                }
            }
        }
    }.isSuccess

    // ========== Standalone Project Polling ==========

    private suspend fun pollStandaloneProject(project: ProjectDocument): Boolean = runCatching {
        logger.info { "GIT_POLL: Discovering commits in project: ${project.name}" }

        // Step 1: Clone or pull repository
        val result = gitRepositoryService.cloneOrUpdateRepository(project)

        if (result.isFailure) {
            logger.warn { "GIT_POLL: Failed to sync project ${project.name}" }
            return@runCatching
        }

        val gitDir = result.getOrNull() ?: run {
            logger.warn { "GIT_POLL: No gitDir returned for project ${project.name}" }
            return@runCatching
        }

        logger.info { "GIT_POLL: Successfully synced project: ${project.name}" }

        // Step 2: Get current branch
        val currentBranch = gitRepositoryService.getCurrentBranch(gitDir, project.clientId)

        // Step 3: Discover commits from Git log and save as NEW
        if (gitDir.resolve(".git").toFile().exists()) {
            val commits = metadataIndexer.fetchCommitInfoFromGit(gitDir, maxCommits = 1000)
            stateManager.saveNewCommits(
                clientId = project.clientId,
                projectId = project.id,
                commits = commits,
                branch = currentBranch,
            )

            logger.info {
                "GIT_POLL: Discovered ${commits.size} commits in project ${project.name}, " +
                    "saved new ones to DB with state NEW"
            }
        }
    }.isSuccess

    private suspend fun hasStandaloneGitConfiguration(project: ProjectDocument): Boolean {
        // Project has its own Git URL override (not mono-repo reference)
        if (project.overrides?.gitRemoteUrl != null) {
            return true
        }

        // Project references mono-repo - not standalone
        if (project.monoRepoId != null) {
            return false
        }

        return false
    }
}
