package com.jervis.service.jira.state

import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.repository.JiraIssueIndexMongoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Manages Jira issue indexing state transitions.
 * Similar to EmailMessageStateManager but for Jira issues.
 */
@Service
class JiraStateManager(
    private val repository: JiraIssueIndexMongoRepository,
) {
    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW issues
    }

    /**
     * Continuous flow of NEW issues for given connection.
     * Polls DB every 30s when empty, never ends.
     */
    fun continuousNewIssues(connectionId: ObjectId): Flow<JiraIssueIndexDocument> =
        flow {
            while (true) {
                val issues =
                    repository.findByConnectionIdAndStateAndArchivedFalseOrderByUpdatedAtAsc(
                        connectionId,
                        JiraIssueState.NEW.name,
                    )

                var emittedAny = false
                issues.collect { issue ->
                    emit(issue)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW Jira issues for connection $connectionId, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    // Immediately check for more
                    logger.debug { "Processed NEW issues, immediately checking for more..." }
                }
            }
        }

    /**
     * Continuous flow of NEW issues across ALL accounts (newest first).
     * Single indexer instance processes issues from all accounts,
     * ordered by updatedAt descending (newest issues prioritized).
     */
    fun continuousNewIssuesAllAccounts(): Flow<JiraIssueIndexDocument> =
        flow {
            while (true) {
                val issues =
                    repository.findByStateAndArchivedFalseOrderByUpdatedAtDesc(
                        JiraIssueState.NEW.name,
                    )

                var emittedAny = false
                issues.collect { issue ->
                    emit(issue)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW Jira issues across all accounts, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed NEW issues across all accounts, immediately checking for more..." }
                }
            }
        }

    /**
     * Mark issue as INDEXING (claim it for processing).
     */
    suspend fun markAsIndexing(issue: JiraIssueIndexDocument) {
        val updated =
            issue.copy(
                state = JiraIssueState.INDEXING.name,
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.debug { "Marked Jira issue ${issue.issueKey} as INDEXING" }
    }

    /**
     * Mark issue as INDEXED after successful indexing.
     */
    suspend fun markAsIndexed(
        issue: JiraIssueIndexDocument,
        summaryChunks: Int = 0,
        commentChunks: Int = 0,
        commentCount: Int = 0,
        attachmentCount: Int = 0,
    ) {
        val updated =
            issue.copy(
                state = JiraIssueState.INDEXED.name,
                lastIndexedAt = Instant.now(),
                totalRagChunks = summaryChunks + commentChunks,
                commentChunkCount = commentChunks,
                attachmentCount = attachmentCount,
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.info { "Marked Jira issue ${issue.issueKey} as INDEXED (${updated.totalRagChunks} chunks)" }
    }

    /**
     * Mark issue as FAILED with error message.
     */
    suspend fun markAsFailed(
        issue: JiraIssueIndexDocument,
        reason: String,
    ) {
        val updated =
            issue.copy(
                state = JiraIssueState.FAILED.name,
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.warn { "Marked Jira issue ${issue.issueKey} as FAILED: $reason" }
    }

    // OLD METHODS - Deprecated, kept for reference only
    // These methods used old structure with accountId and metadata-only approach
    // New approach: CentralPoller fetches FULL data and saves directly to MongoDB
}
