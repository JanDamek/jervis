package com.jervis.integration.bugtracker.internal.state

import com.jervis.domain.PollingStatusEnum
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.bugtracker.internal.repository.BugTrackerIssueIndexRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Manages Jira issue indexing state transitions with a sealed class pattern.
 *
 * State transitions:
 * - NEW → INDEXED (success, delete full content)
 * - NEW → FAILED (error, keep full content)
 *
 * Content cleanup:
 * - NEW/FAILED: Full document in MongoDB
 * - INDEXED: Delete old doc, insert minimal tracking doc
 */
@Service
class BugTrackerStateManager(
    private val repository: BugTrackerIssueIndexRepository,
) {
    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW issues
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Continuous flow of NEW issues across ALL accounts (newest first).
     * Single indexer instance processes issues from all accounts,
     * ordered by jiraUpdatedAt descending (newest issues prioritized).
     */
    fun continuousNewIssuesAllAccounts(): Flow<BugTrackerIssueIndexDocument> =
        flow {
            while (true) {
                val issues = repository.findAllByStatusIs()

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
     * Mark issue as INDEXED after successful PendingTask creation.
     * Deletes old NEW document, inserts minimal INDEXED document.
     *
     * This is the content cleanup step - removes full description, comments, attachments.
     */
    suspend fun markAsIndexed(issue: BugTrackerIssueIndexDocument) {
        repository.save(issue.copy(status = PollingStatusEnum.INDEXED))
    }

    /**
     * Mark the issue as FAILED with an error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        issue: BugTrackerIssueIndexDocument,
        reason: String,
    ) {
        repository.save(issue.copy(status = PollingStatusEnum.FAILED, indexingError = reason))
    }
}
