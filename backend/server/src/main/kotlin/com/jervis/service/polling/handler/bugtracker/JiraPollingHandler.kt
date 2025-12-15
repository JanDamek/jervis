package com.jervis.service.polling.handler.bugtracker

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ClientId
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Jira issue polling handler.
 *
 * System-specific implementation:
 * - Uses Atlassian REST API via service-atlassian
 * - Builds JQL queries for filtering
 * - Fetches complete issue data (comments, attachments, history)
 * - Supports incremental polling via lastSeenUpdatedAt
 *
 * Shared logic (orchestration, deduplication, state management) is in BugTrackerPollingHandlerBase.
 */
@Component
class JiraPollingHandler(
    private val repository: JiraIssueIndexMongoRepository,
    private val atlassianClient: IAtlassianClient,
    connectionService: ConnectionService,
) : BugTrackerPollingHandlerBase<JiraIssueIndexDocument, JiraIssueIndexMongoRepository>(
        connectionService = connectionService,
    ) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument is ConnectionDocument.HttpConnectionDocument &&
            connectionDocument.baseUrl.contains("atlassian.net")

    override fun getSystemName(): String = "Jira"

    override fun getToolName(): String = "JIRA"

    override fun buildQuery(
        client: ClientDocument,
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        lastSeenUpdatedAt: Instant?,
    ): String {
        // Build JQL query with time-based incremental filtering
        val timeFilter =
            if (lastSeenUpdatedAt != null) {
                // INCREMENTAL SYNC: Fetch only issues updated since last poll
                val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
                "updated >= \"${fmt.format(lastSeenUpdatedAt)}\""
            } else {
                // INITIAL SYNC: Fetch only OPEN issues (skip Closed, Done, Resolved)
                "status NOT IN (Closed, Done, Resolved)"
            }

        // Future: add per-connectionDocument filters from connectionDocument.filters
        return timeFilter
    }

    override suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        query: String,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int,
    ): List<JiraIssueIndexDocument> {
        try {
            logger.debug { "Fetching Jira issues: baseUrl=${connectionDocument.baseUrl} jql='$query' maxResults=$maxResults startAt=$startAt" }

            // Prepare auth info
            val authInfo =
                when (credentials) {
                    is HttpCredentials.Basic -> AuthInfo("BASIC", credentials.username, credentials.password, null)
                    is HttpCredentials.Bearer -> AuthInfo("BEARER", null, null, credentials.token)
                }
            val authType = authInfo.authType
            val username = authInfo.username
            val password = authInfo.password
            val bearerToken = authInfo.bearerToken

            // Call service-atlassian API with pagination support
            val searchRequest =
                JiraSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = authType,
                    basicUsername = username,
                    basicPassword = password,
                    bearerToken = bearerToken,
                    jql = query,
                    maxResults = maxResults,
                    startAt = startAt,
                )

            val response = atlassianClient.searchJiraIssues("connection-${connectionDocument.id}", searchRequest)

            logger.info { "Fetched ${response.issues.size} Jira issues (total=${response.total})" }

            // Convert to JiraIssueIndexDocument.New (sealed class)
            return response.issues.map { issue ->
                val fields = issue.fields
                val projectKey = issue.key.substringBefore("-")

                // Comments and attachments will be fetched separately if needed
                // For now, empty lists (Jira API doesn't return them in search by default)
                val comments = emptyList<com.jervis.entity.jira.JiraComment>()
                val attachments = emptyList<com.jervis.entity.jira.JiraAttachment>()

                // Generate unique changelog ID: issueId + updatedAt timestamp
                // Search API doesn't return changelog, so we use this composite key
                val syntheticChangelogId = "${issue.id}-${fields.updated ?: ""}"

                JiraIssueIndexDocument.New(
                    clientId = clientId,
                    connectionDocumentId = com.jervis.types.ConnectionId(connectionDocument.id),
                    issueKey = issue.key,
                    latestChangelogId = syntheticChangelogId,
                    projectKey = projectKey,
                    summary = fields.summary ?: "",
                    description = extractDescription(fields.description),
                    issueType = "Task",
                    status = fields.status?.name ?: "Unknown",
                    priority = fields.priority?.name ?: "Medium",
                    assignee = fields.assignee?.displayName,
                    reporter = fields.reporter?.displayName,
                    labels = emptyList(),
                    createdAt = parseInstant(fields.created),
                    jiraUpdatedAt = parseInstant(fields.updated),
                    comments = comments,
                    attachments = attachments,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Jira issues from ${connectionDocument.baseUrl}: ${e.message}" }
            return emptyList()
        }
    }

    private fun extractDescription(description: kotlinx.serialization.json.JsonElement?): String =
        when (description) {
            is JsonPrimitive -> description.content
            else -> description?.toString() ?: ""
        }

    private fun parseInstant(isoString: String?): Instant =
        if (isoString != null) {
            try {
                Instant.parse(isoString)
            } catch (e: Exception) {
                Instant.now()
            }
        } else {
            Instant.now()
        }

    private data class AuthInfo(
        val authType: String,
        val username: String?,
        val password: String?,
        val bearerToken: String?,
    )

    override fun getIssueId(issue: JiraIssueIndexDocument): String = issue.issueKey

    override fun getIssueUpdatedAt(issue: JiraIssueIndexDocument): Instant = issue.jiraUpdatedAt

    override suspend fun findExisting(
        connectionId: ObjectId,
        issueId: String,
    ): JiraIssueIndexDocument? = repository.findByConnectionDocumentIdAndIssueKey(connectionId, issueId)

    override fun getExistingUpdatedAt(existing: JiraIssueIndexDocument): Instant = existing.jiraUpdatedAt

    override fun updateExisting(
        existing: JiraIssueIndexDocument,
        newData: JiraIssueIndexDocument,
    ): JiraIssueIndexDocument {
        // New version of issue = new document with NEW _id
        // Each version (different latestChangelogId) is a separate row in collection
        // Old version stays in DB until cleaned up
        require(newData is JiraIssueIndexDocument.New) { "newData must be JiraIssueIndexDocument.New" }
        return newData.copy(
            id = org.bson.types.ObjectId.get(), // NEW _id for new version
            // Keep .New state to trigger re-indexing
        )
    }

    override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
        // Each version of issue gets new _id + new latestChangelogId
        // Unique constraint: (connectionId, issueKey, latestChangelogId)
        // Simply insert - MongoDB will reject duplicates via unique index

        repository.save(issue)

        logger.debug {
            "Saved Jira issue: issueKey=${issue.issueKey}, changelogId=${issue.latestChangelogId}, _id=${issue.id}, state=${issue.state}"
        }
    }
}
