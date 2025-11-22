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
     * Continuous flow of NEW issues for given client.
     * Polls DB every 30s when empty, never ends.
     */
    fun continuousNewIssues(clientId: ObjectId): Flow<JiraIssueIndexDocument> =
        flow {
            while (true) {
                val issues =
                    repository.findByClientIdAndStateAndArchivedFalseOrderByUpdatedAtAsc(
                        clientId,
                        JiraIssueState.NEW.name,
                    )

                var emittedAny = false
                issues.collect { issue ->
                    emit(issue)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW Jira issues for client $clientId, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    // Immediately check for more
                    logger.debug { "Processed NEW issues, immediately checking for more..." }
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
                summaryChunkCount = summaryChunks,
                commentChunkCount = commentChunks,
                commentCount = commentCount,
                attachmentCount = attachmentCount,
                totalRagChunks = summaryChunks + commentChunks,
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

    /**
     * Create or update issue index document from Jira API search results.
     * Returns the document in NEW state.
     */
    suspend fun upsertIssueFromApi(
        clientId: ObjectId,
        issueKey: String,
        projectKey: String,
        summary: String,
        status: String,
        assignee: String?,
        updated: Instant,
        contentHash: String,
        statusHash: String,
    ): JiraIssueIndexDocument {
        val existing = repository.findByClientIdAndIssueKey(clientId, issueKey)

        val doc =
            if (existing == null) {
                // New issue - create with NEW state
                JiraIssueIndexDocument(
                    clientId = clientId,
                    issueKey = issueKey,
                    projectKey = projectKey,
                    lastSeenUpdated = updated,
                    contentHash = contentHash,
                    statusHash = statusHash,
                    state = JiraIssueState.NEW.name,
                    issueSummary = summary.take(200),
                    currentStatus = status,
                    currentAssignee = assignee,
                    updatedAt = Instant.now(),
                )
            } else {
                // Existing issue - check if changed
                val contentChanged = existing.contentHash != contentHash
                val statusChanged = existing.statusHash != statusHash

                if (contentChanged || statusChanged) {
                    // Content changed - mark as NEW to re-index
                    existing.copy(
                        lastSeenUpdated = updated,
                        contentHash = contentHash,
                        statusHash = statusHash,
                        state = JiraIssueState.NEW.name,
                        issueSummary = summary.take(200),
                        currentStatus = status,
                        currentAssignee = assignee,
                        updatedAt = Instant.now(),
                    )
                } else {
                    // No changes - keep existing state
                    existing.copy(
                        lastSeenUpdated = updated,
                        updatedAt = Instant.now(),
                    )
                }
            }

        return repository.save(doc)
    }
}
