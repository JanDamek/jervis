package com.jervis.service.git.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Manages Git commit state tracking in MongoDB.
 * Analog to EmailMessageStateManager - tracks which commits have been indexed.
 */
@Service
class GitCommitStateManager(
    private val gitCommitRepository: GitCommitRepository,
) {
    /**
     * Save new commit hashes from Git log that haven't been indexed yet.
     */
    suspend fun saveNewCommits(
        projectId: ObjectId,
        commits: List<GitCommitInfo>,
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

        newCommits.forEach { saveNewCommit(projectId, it) }
        logger.info { "Batch sync completed for project $projectId" }
    }

    private suspend fun loadExistingCommitHashes(projectId: ObjectId): Set<String> {
        val existingCommits = mutableListOf<GitCommitDocument>()

        gitCommitRepository
            .findByProjectId(projectId)
            .asFlow()
            .collect { existingCommits.add(it) }

        return existingCommits.map { it.commitHash }.toSet()
    }

    private suspend fun saveNewCommit(
        projectId: ObjectId,
        commitInfo: GitCommitInfo,
    ) {
        val newCommit =
            GitCommitDocument(
                projectId = projectId,
                commitHash = commitInfo.commitHash,
                state = GitCommitState.NEW,
                author = commitInfo.author,
                message = commitInfo.message,
                commitDate = commitInfo.commitDate,
            )
        gitCommitRepository.save(newCommit).awaitSingleOrNull()
        logger.debug { "Saved new commit ${commitInfo.commitHash.take(8)} by ${commitInfo.author} with state NEW" }
    }

    /**
     * Find commits that need to be indexed (state = NEW).
     */
    fun findNewCommits(projectId: ObjectId): Flow<GitCommitDocument> =
        gitCommitRepository
            .findByProjectIdAndStateOrderByCommitDateAsc(projectId, GitCommitState.NEW)
            .asFlow()

    /**
     * Mark commit as indexed after successful processing.
     */
    suspend fun markAsIndexed(commitDocument: GitCommitDocument) {
        val updated = commitDocument.copy(state = GitCommitState.INDEXED)
        gitCommitRepository.save(updated).awaitSingleOrNull()
        logger.debug { "Marked commit ${commitDocument.commitHash.take(8)} as INDEXED" }
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
