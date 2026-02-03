package com.jervis.integration.bugtracker.internal.polling

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.entity.connection.basicPassword
import com.jervis.entity.connection.basicUsername
import com.jervis.entity.connection.bearerToken
import com.jervis.entity.connection.toAuthType
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.bugtracker.internal.repository.BugTrackerIssueIndexRepository
import com.jervis.service.client.ClientService
import com.jervis.service.polling.PollingStateService
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
class BugTrackerPollingHandler(
    private val repository: BugTrackerIssueIndexRepository,
    private val atlassianClient: IAtlassianClient,
    clientService: ClientService,
    pollingStateService: PollingStateService,
) : BugTrackerPollingHandlerBase<BugTrackerIssueIndexDocument>(
        pollingStateService = pollingStateService,
        clientService = clientService,
    ) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP &&
            (connectionDocument.baseUrl.contains("atlassian.net") || connectionDocument.baseUrl.contains("atlassian"))

    override fun getSystemName(): String = "Jira"

    override fun getToolName(): String = "JIRA"

    override fun buildQuery(
        client: ClientDocument?,
        connectionDocument: ConnectionDocument,
        lastSeenUpdatedAt: Instant?,
    ): String {
        val query =
            lastSeenUpdatedAt?.let {
                val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
                "updated >= \"${fmt.format(it)}\""
            } ?: "status NOT IN (Closed, Done, Resolved)"

        return if (!connectionDocument.jiraProjectKey.isNullOrBlank()) {
            "project = \"${connectionDocument.jiraProjectKey}\" AND ($query)"
        } else {
            query
        }
    }

    override suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        projectId: ProjectId?,
        query: String,
        lastSeenUpdatedAt: Instant?,
    ): List<BugTrackerIssueIndexDocument> {
        try {
            val searchRequest =
                BugTrackerSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = credentials.toAuthType(),
                    basicUsername = credentials.basicUsername(),
                    basicPassword = credentials.basicPassword(),
                    bearerToken = credentials.bearerToken(),
                    query = query,
                )

            val response = bugTrackerClient.searchIssues(searchRequest)

            return response.issues.map { issue ->
                val syntheticChangelogId = "${issue.id}-${issue.updated}"

                val bugtrackerIssueIndexDocument =
                    BugTrackerIssueIndexDocument(
                        id = ObjectId.get(),
                        clientId = clientId,
                        connectionId = connectionDocument.id,
                        issueKey = issue.key,
                        latestChangelogId = syntheticChangelogId,
                        summary = issue.title,
                        status = PollingStatusEnum.NEW,
                        bugtrackerUpdatedAt = parseInstant(issue.updated),
                        projectId = projectId,
                    )
                bugtrackerIssueIndexDocument
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch BugTracker issues from ${connectionDocument.baseUrl}: ${e.message}" }
            throw e
        }
    }

    private fun parseInstant(isoString: String?): Instant =
        if (isoString != null) {
            try {
                // Jira uses format like 2024-03-21T15:43:02.000+0000 or 2024-03-21T15:43:02.000Z
                // Instant.parse handles Z, but not +0000
                val normalized = isoString.replace(Regex("(\\+\\d{2})(\\d{2})$"), "$1:$2")
                Instant.parse(normalized)
            } catch (_: Exception) {
                try {
                    // Try OffsetDateTime for formats like +0000
                    java.time.OffsetDateTime
                        .parse(
                            isoString,
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                        ).toInstant()
                } catch (__: Exception) {
                    Instant.now()
                }
            }
        } else {
            Instant.now()
        }

    override fun getIssueUpdatedAt(issue: BugTrackerIssueIndexDocument): Instant = issue.bugtrackerUpdatedAt

    override suspend fun findExisting(
        connectionId: ConnectionId,
        issue: BugTrackerIssueIndexDocument,
    ): Boolean =
        repository.existsByConnectionIdAndIssueKeyAndLatestChangelogId(
            connectionId = connectionId,
            issueKey = issue.issueKey,
            latestChangelogId = issue.latestChangelogId,
        )

    override suspend fun saveIssue(issue: BugTrackerIssueIndexDocument) {
        try {
            repository.save(issue)
        } catch (_: DuplicateKeyException) {
            logger.debug { "Issue ${issue.issueKey} with changelog ${issue.latestChangelogId} already exists, skipping" }
        }
    }

    private data class AuthInfo(
        val authType: String,
        val username: String?,
        val password: String?,
        val bearerToken: String?,
    )
}
