package com.jervis.service.polling.handler.bugtracker

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
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
    clientService: ClientService,
    connectionService: ConnectionService,
) : BugTrackerPollingHandlerBase<JiraIssueIndexDocument>(
        connectionService = connectionService,
        clientService = clientService,
    ) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP &&
            connectionDocument.baseUrl?.contains("atlassian.net") == true

    override fun getSystemName(): String = "Jira"

    override fun getToolName(): String = "JIRA"

    override fun buildQuery(
        client: ClientDocument?,
        connectionDocument: ConnectionDocument,
        lastSeenUpdatedAt: Instant?,
    ): String =
        lastSeenUpdatedAt?.let {
            val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
            "updated >= \"${fmt.format(it)}\""
        } ?: "status NOT IN (Closed, Done, Resolved)"

    override suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        projectId: ProjectId?,
        query: String,
        lastSeenUpdatedAt: Instant?,
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
                )

            val response = atlassianClient.searchJiraIssues(searchRequest)

            return response.issues.map { issue ->
                val fields = issue.fields
                val syntheticChangelogId = "${issue.id}-${fields.updated ?: ""}"

                val jiraIssueIndexDocument =
                    JiraIssueIndexDocument(
                        id = ObjectId.get(),
                        clientId = clientId,
                        connectionId = connectionDocument.id,
                        issueKey = issue.key,
                        latestChangelogId = syntheticChangelogId,
                        summary = fields.summary ?: "",
                        status = PollingStatusEnum.NEW,
                        jiraUpdatedAt = parseInstant(fields.updated),
                        projectId = projectId,
                    )
                jiraIssueIndexDocument
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Jira issues from ${connectionDocument.baseUrl}: ${e.message}" }
            return emptyList()
        }
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
    ): Boolean =
        repository.existsByConnectionIdAndIssueKeyAndLatestChangelogId(
            connectionId = connectionId,
            issueKey = issue.issueKey,
            latestChangelogId = issue.latestChangelogId,
        )

    override suspend fun saveIssue(issue: JiraIssueIndexDocument) {
        try {
            repository.save(issue)
        } catch (_: DuplicateKeyException) {
            logger.debug { "Issue ${issue.issueKey} with changelog ${issue.latestChangelogId} already exists, skipping" }
        }
    }
}
