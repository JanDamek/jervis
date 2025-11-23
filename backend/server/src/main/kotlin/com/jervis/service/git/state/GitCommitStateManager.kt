package com.jervis.service.git.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Manages Git commit state tracking in MongoDB.
 * Analog to EmailMessageStateManager - tracks which commits have been indexed.
 *
 * Supports both:
 * - Standalone project commits (projectId + clientId)
 * - Client mono-repo commits (clientId + monoRepoId, projectId = null)
 */
@Service
class GitCommitStateManager(
    private val gitCommitRepository: GitCommitRepository,
) {
    // ========== Standalone Project Methods ==========

    /**
     * Save new commit hashes from Git log for a standalone project.
     */
    suspend fun saveNewCommits(
        clientId: ObjectId,
        projectId: ObjectId,
        commits: List<GitCommitInfo>,
        branch: String = "main",
    ) {
        logger.info { "Starting batch commit sync for project $projectId" }

        if (commits.isEmpty()) {
            logger.info { "No commits to process for project $projectId" }
            return
        }

        logger.info { "Found ${commits.size} commits in Git for project $projectId" }

        val existingCommitHashes = loadExistingCommitHashes(projectId)
        logger.info { "Found ${existingCommitHashes.size} existing commits in DB for project $projectId" }

        val newCommits = commits.filterNot { existingCommitHashes.contains(it.commitHash) }
        logger.info { "Identified ${newCommits.size} new commits to save for project $projectId" }

        newCommits.forEach { saveNewCommit(clientId, projectId, it, branch) }
        logger.info { "Batch sync completed for project $projectId" }
    }

    private suspend fun loadExistingCommitHashes(projectId: ObjectId): Set<String> {
        val existingCommits = mutableListOf<GitCommitDocument>()

        gitCommitRepository
            .findByProjectId(projectId)
            .collect { existingCommits.add(it) }

        return existingCommits.map { it.commitHash }.toSet()
    }

    private suspend fun saveNewCommit(
        clientId: ObjectId,
        projectId: ObjectId,
        commitInfo: GitCommitInfo,
        branch: String,
    ) {
        val newCommit =
            GitCommitDocument(
                clientId = clientId,
                projectId = projectId,
                monoRepoId = null,
                commitHash = commitInfo.commitHash,
                state = GitCommitState.NEW,
                author = commitInfo.author,
                message = commitInfo.message,
                commitDate = commitInfo.commitDate,
                branch = branch,
            )
        gitCommitRepository.save(newCommit)
        logger.debug { "Saved new commit ${commitInfo.commitHash.take(8)} by ${commitInfo.author} with state NEW" }
    }

    /**
     * Find commits that need to be indexed for a standalone project (state = NEW).
     */
    fun findNewCommits(projectId: ObjectId): Flow<GitCommitDocument> =
        gitCommitRepository
            .findByProjectIdAndStateOrderByCommitDateAsc(projectId, GitCommitState.NEW)

    // ========== Mono-Repo Methods ==========

    /**
     * Save new commit hashes from Git log for a client mono-repo.
     */
    suspend fun saveNewMonoRepoCommits(
        clientId: ObjectId,
        monoRepoId: String,
        commits: List<GitCommitInfo>,
        branch: String = "main",
    ) {
        logger.info { "Starting batch commit sync for mono-repo $monoRepoId" }

        if (commits.isEmpty()) {
            logger.info { "No commits to process for mono-repo $monoRepoId" }
            return
        }

        logger.info { "Found ${commits.size} commits in Git for mono-repo $monoRepoId" }

        val existingCommitHashes = loadExistingMonoRepoCommitHashes(clientId, monoRepoId)
        logger.info { "Found ${existingCommitHashes.size} existing commits in DB for mono-repo $monoRepoId" }

        val newCommits = commits.filterNot { existingCommitHashes.contains(it.commitHash) }
        logger.info { "Identified ${newCommits.size} new commits to save for mono-repo $monoRepoId" }

        newCommits.forEach { saveNewMonoRepoCommit(clientId, monoRepoId, it, branch) }
        logger.info { "Batch sync completed for mono-repo $monoRepoId" }
    }

    private suspend fun loadExistingMonoRepoCommitHashes(
        clientId: ObjectId,
        monoRepoId: String,
    ): Set<String> {
        val existingCommits = mutableListOf<GitCommitDocument>()

        gitCommitRepository
            .findByClientIdAndMonoRepoId(clientId, monoRepoId)
            .collect { existingCommits.add(it) }

        return existingCommits.map { it.commitHash }.toSet()
    }

    private suspend fun saveNewMonoRepoCommit(
        clientId: ObjectId,
        monoRepoId: String,
        commitInfo: GitCommitInfo,
        branch: String,
    ) {
        val newCommit =
            GitCommitDocument(
                clientId = clientId,
                projectId = null,
                monoRepoId = monoRepoId,
                commitHash = commitInfo.commitHash,
                state = GitCommitState.NEW,
                author = commitInfo.author,
                message = commitInfo.message,
                commitDate = commitInfo.commitDate,
                branch = branch,
            )
        gitCommitRepository.save(newCommit)
        logger.debug { "Saved new mono-repo commit ${commitInfo.commitHash.take(8)} by ${commitInfo.author} with state NEW" }
    }

    /**
     * Find commits that need to be indexed for a mono-repo (state = NEW).
     */
    fun findNewMonoRepoCommits(
        clientId: ObjectId,
        monoRepoId: String,
    ): Flow<GitCommitDocument> =
        gitCommitRepository
            .findByClientIdAndMonoRepoIdAndStateOrderByCommitDateAsc(clientId, monoRepoId, GitCommitState.NEW)

    // ========== Shared Methods ==========

    /**
     * Continuous stream of NEW commits across all projects (for single-instance AbstractContinuousIndexer).
     * Polls every 30s when queue empty. Ordered by commit date DESC (newest first).
     */
    fun continuousNewCommits(): Flow<GitCommitDocument> = flow {
        while (true) {
            var emittedAny = false
            gitCommitRepository
                .findByStateOrderByCommitDateDesc(GitCommitState.NEW)
                .collect { commit ->
                    emit(commit)
                    emittedAny = true
                }

            if (!emittedAny) {
                delay(30_000) // Wait 30s if queue empty
            }
        }
    }

    /**
     * Continuous stream of NEW commits for a specific project (legacy, if needed).
     * Polls every 30s when queue empty.
     */
    fun continuousNewCommits(projectId: ObjectId): Flow<GitCommitDocument> = flow {
        while (true) {
            var emittedAny = false
            gitCommitRepository
                .findByProjectIdAndStateOrderByCommitDateAsc(projectId, GitCommitState.NEW)
                .collect { commit ->
                    emit(commit)
                    emittedAny = true
                }

            if (!emittedAny) {
                delay(30_000) // Wait 30s if queue empty
            }
        }
    }

    /**
     * Continuous stream of NEW commits for a mono-repo (for AbstractContinuousIndexer).
     * Polls every 30s when queue empty.
     */
    fun continuousNewMonoRepoCommits(clientId: ObjectId, monoRepoId: String): Flow<GitCommitDocument> = flow {
        while (true) {
            var emittedAny = false
            gitCommitRepository
                .findByClientIdAndMonoRepoIdAndStateOrderByCommitDateAsc(clientId, monoRepoId, GitCommitState.NEW)
                .collect { commit ->
                    emit(commit)
                    emittedAny = true
                }

            if (!emittedAny) {
                delay(30_000) // Wait 30s if queue empty
            }
        }
    }

    /**
     * Mark commit as INDEXING to prevent concurrent processing.
     */
    suspend fun markAsIndexing(commitDocument: GitCommitDocument) {
        val updated = commitDocument.copy(state = GitCommitState.INDEXING)
        gitCommitRepository.save(updated)
        logger.debug { "Marked commit ${commitDocument.commitHash.take(8)} as INDEXING" }
    }

    /**
     * Mark commit as indexed after successful processing.
     * Works for both standalone and mono-repo commits.
     */
    suspend fun markAsIndexed(commitDocument: GitCommitDocument) {
        val updated = commitDocument.copy(state = GitCommitState.INDEXED)
        gitCommitRepository.save(updated)
        logger.debug { "Marked commit ${commitDocument.commitHash.take(8)} as INDEXED" }
    }

    /**
     * Mark commit as failed after error during processing.
     * Fail-fast: error logged, no retry - errors tracked in ErrorLogService.
     */
    suspend fun markAsFailed(commitDocument: GitCommitDocument, reason: String) {
        val updated = commitDocument.copy(state = GitCommitState.FAILED)
        gitCommitRepository.save(updated)
        logger.warn { "Marked commit ${commitDocument.commitHash.take(8)} as FAILED: $reason" }
    }

    /**
     * Get counts of INDEXED and NEW commits for UI status.
     * Returns (indexedCount, newCount).
     */
    suspend fun getIndexedAndNewCounts(): Pair<Long, Long> {
        val indexedCount = gitCommitRepository.countByState(GitCommitState.INDEXED)
        val newCount = gitCommitRepository.countByState(GitCommitState.NEW)
        return indexedCount to newCount
    }
}

/**
 * Basic commit info from Git log parsing.
 */
data class GitCommitInfo(
    val commitHash: String,
    val author: String?,
    val message: String?,
    val commitDate: Instant?,
)
