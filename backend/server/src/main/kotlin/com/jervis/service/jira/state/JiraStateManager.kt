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
 * Manages Jira issue indexing state transitions with sealed class pattern.
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
                    repository.findByConnectionDocumentIdAndStateOrderByJiraUpdatedAtDesc(
                        connectionId,
                        "NEW",
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
     * ordered by jiraUpdatedAt descending (newest issues prioritized).
     */
    fun continuousNewIssuesAllAccounts(): Flow<JiraIssueIndexDocument> =
        flow {
            while (true) {
                val issues =
                    repository.findByStateOrderByJiraUpdatedAtDesc(
                        "NEW",
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
     * Mark issue as INDEXED after successful PendingTask creation.
     * Deletes old NEW document, inserts minimal INDEXED document.
     *
     * This is the content cleanup step - removes full description, comments, attachments.
     */
    suspend fun markAsIndexed(issue: JiraIssueIndexDocument.New) {
        repository.deleteById(issue.id)

        val indexed = JiraIssueIndexDocument.Indexed(
            id = ObjectId(),
            clientId = issue.clientId,
            projectId = issue.projectId,
            connectionDocumentId = issue.connectionDocumentId,
            issueKey = issue.issueKey,
            latestChangelogId = issue.latestChangelogId,
            jiraUpdatedAt = issue.jiraUpdatedAt,
        )
        repository.save(indexed)
    }

    /**
     * Mark issue as FAILED with error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        issue: JiraIssueIndexDocument,
        reason: String,
    ) {
        when (issue) {
            is JiraIssueIndexDocument.New -> {
                repository.deleteById(issue.id)

                val failed = JiraIssueIndexDocument.Failed(
                        id = ObjectId(),
                        clientId = issue.clientId,
                        projectId = issue.projectId,
                        connectionDocumentId = issue.connectionDocumentId,
                        issueKey = issue.issueKey,
                        latestChangelogId = issue.latestChangelogId,
                        projectKey = issue.projectKey,
                        summary = issue.summary,
                        description = issue.description,
                        issueType = issue.issueType,
                        status = issue.status,
                        priority = issue.priority,
                        assignee = issue.assignee,
                        reporter = issue.reporter,
                        labels = issue.labels,
                        comments = issue.comments,
                        attachments = issue.attachments,
                        linkedIssues = issue.linkedIssues,
                        createdAt = issue.createdAt,
                        jiraUpdatedAt = issue.jiraUpdatedAt,
                        indexingError = reason,
                    )
                repository.save(failed)
            }

            is JiraIssueIndexDocument.Failed -> {
                repository.deleteById(issue.id)
                repository.save(issue.copy(id = ObjectId(), indexingError = "${issue.indexingError}; $reason"))
            }

            is JiraIssueIndexDocument.Indexed -> {
                logger.error { "Cannot mark INDEXED issue as FAILED: ${issue.issueKey}" }
            }
        }
    }

    /**
     * Reset FAILED issue back to NEW for retry.
     */
    suspend fun resetFailedToNew(issue: JiraIssueIndexDocument.Failed) {
        repository.deleteById(issue.id)

        val newDoc = JiraIssueIndexDocument.New(
                id = ObjectId(),
                clientId = issue.clientId,
                projectId = issue.projectId,
                connectionDocumentId = issue.connectionDocumentId,
                issueKey = issue.issueKey,
                latestChangelogId = issue.latestChangelogId,
                projectKey = issue.projectKey,
                summary = issue.summary,
                description = issue.description,
                issueType = issue.issueType,
                status = issue.status,
                priority = issue.priority,
                assignee = issue.assignee,
                reporter = issue.reporter,
                labels = issue.labels,
                comments = issue.comments,
                attachments = issue.attachments,
                linkedIssues = issue.linkedIssues,
                createdAt = issue.createdAt,
                jiraUpdatedAt = issue.jiraUpdatedAt,
            )
        repository.save(newDoc)
        logger.info { "Reset FAILED Jira issue ${issue.issueKey} back to NEW for retry" }
    }
}
