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
import com.jervis.types.ConnectionId
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
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
) : BugTrackerPollingHandlerBase<JiraIssueIndexDocument>(
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
    ): String =
        lastSeenUpdatedAt?.let {
            val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
            "updated >= \"${fmt.format(it)}\""
        } ?: "status NOT IN (Closed, Done, Resolved)"

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
            val authInfo =
                when (credentials) {
                    is HttpCredentials.Basic -> AuthInfo("BASIC", credentials.username, credentials.password, null)
                    is HttpCredentials.Bearer -> AuthInfo("BEARER", null, null, credentials.token)
                }

            val searchRequest =
                JiraSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = authInfo.authType,
                    basicUsername = authInfo.username,
                    basicPassword = authInfo.password,
                    bearerToken = authInfo.bearerToken,
                    jql = query,
                    maxResults = maxResults,
                    startAt = startAt,
                )

            val response = atlassianClient.searchJiraIssues(searchRequest)

            return response.issues.map { issue ->
                val fields = issue.fields
                val projectKey = issue.key.substringBefore("-")

                val syntheticChangelogId = "${issue.id}-${fields.updated ?: ""}"

                JiraIssueIndexDocument.New(
                    clientId = clientId,
                    connectionDocumentId = connectionDocument.id,
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
                    comments = emptyList(),
                    attachments = emptyList(),
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
            } catch (_: Exception) {
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

    override fun getIssueUpdatedAt(issue: JiraIssueIndexDocument): Instant = issue.jiraUpdatedAt

    override suspend fun findExisting(
        connectionId: ConnectionId,
        issue: JiraIssueIndexDocument,
    ): JiraIssueIndexDocument? =
        repository.findByConnectionDocumentIdAndIssueKeyAndLatestChangelogId(
            connectionDocumentId = connectionId,
            issueKey = issue.issueKey,
            latestChangelogId = issue.latestChangelogId,
        )

    override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
        try {
            repository.save(issue)
        } catch (_: org.springframework.dao.DuplicateKeyException) {
            logger.debug { "Issue ${issue.issueKey} with changelog ${issue.latestChangelogId} already exists, skipping" }
        }
    }
}
