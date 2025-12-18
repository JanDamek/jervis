package com.jervis.service.indexing.git.state

import kotlinx.coroutines.flow.Flow
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
    /**
     * Save new commit hashes from the Git log for a standalone project.
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

    // Mono-repo feature removed from the project

    /**
     * Mark the commit as indexed after successful processing.
     * Works for both standalone and mono-repo commits.
     */
    suspend fun markAsIndexed(commitDocument: GitCommitDocument) {
        val updated = commitDocument.copy(state = GitCommitState.INDEXED)
        gitCommitRepository.save(updated)
        logger.debug { "Marked commit ${commitDocument.commitHash.take(8)} as INDEXED" }
    }

    /**
     * Mark commit as failed after an error during processing.
     * Fail-fast: error logged, no retry - errors tracked in ErrorLogService.
     */
    suspend fun markAsFailed(
        commitDocument: GitCommitDocument,
        reason: String,
    ) {
        val updated = commitDocument.copy(state = GitCommitState.FAILED)
        gitCommitRepository.save(updated)
        logger.warn { "Marked commit ${commitDocument.commitHash.take(8)} as FAILED: $reason" }
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
