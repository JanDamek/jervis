package com.jervis.service.polling.handler.bugtracker

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Jira issue polling handler.
 *
 * System-specific implementation:
 * - Uses Atlassian REST API
 * - Builds JQL queries for filtering
 * - Fetches complete issue data (comments, attachments, history)
 * - Supports incremental polling via lastSeenUpdatedAt
 *
 * Shared logic (orchestration, deduplication, state management) is in BugTrackerPollingHandlerBase.
 */
@Component
class JiraPollingHandler(
    private val apiClient: AtlassianApiClient,
    private val repository: JiraIssueIndexMongoRepository,
    pollingStateRepository: PollingStateMongoRepository,
) : BugTrackerPollingHandlerBase<JiraIssueIndexDocument, JiraIssueIndexMongoRepository>(
    pollingStateRepository = pollingStateRepository,
) {

    override fun canHandle(connection: Connection): Boolean =
        connection is Connection.HttpConnection &&
            connection.baseUrl.contains("atlassian.net")

    override fun getSystemName(): String = "Jira"

    override fun getToolName(): String = TOOL_JIRA

    override fun buildQuery(
        client: ClientDocument,
        connection: Connection.HttpConnection,
        lastSeenUpdatedAt: Instant?,
    ): String {
        // Build JQL query with time-based incremental filtering
        val timeFilter =
            lastSeenUpdatedAt?.let { ts ->
                val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
                "updated >= \"${fmt.format(ts)}\""
            } ?: "updated >= -7d"

        // Future: add per-connection filters from connection.filters
        return timeFilter
    }

    override suspend fun fetchFullIssues(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials,
        clientId: ObjectId,
        query: String,
        maxResults: Int,
    ): List<JiraIssueIndexDocument> {
        return apiClient.searchAndFetchFullIssues(
            connection = connection,
            credentials = credentials,
            clientId = clientId,
            jql = query,
            maxResults = maxResults,
        )
    }

    override fun getIssueId(issue: JiraIssueIndexDocument): String = issue.issueKey

    override fun getIssueUpdatedAt(issue: JiraIssueIndexDocument): Instant = issue.jiraUpdatedAt

    override suspend fun findExisting(connectionId: ObjectId, issueId: String): JiraIssueIndexDocument? {
        return repository.findByConnectionIdAndIssueKey(connectionId, issueId)
    }

    override fun getExistingUpdatedAt(existing: JiraIssueIndexDocument): Instant = existing.jiraUpdatedAt

    override fun updateExisting(existing: JiraIssueIndexDocument, newData: JiraIssueIndexDocument): JiraIssueIndexDocument {
        return newData.copy(
            id = existing.id,
            state = "NEW", // Re-index because data changed
        )
    }

    override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
        repository.save(issue)
    }

    companion object {
        private const val TOOL_JIRA = "JIRA"
    }
}
