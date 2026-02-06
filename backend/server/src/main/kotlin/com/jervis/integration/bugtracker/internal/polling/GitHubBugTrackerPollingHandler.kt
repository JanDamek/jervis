package com.jervis.integration.bugtracker.internal.polling

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.bugtracker.internal.repository.BugTrackerIssueIndexRepository
import com.jervis.service.client.ClientService
import com.jervis.service.polling.PollingStateService
import com.jervis.service.polling.handler.ResourceFilter
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * GitHub Issues polling handler.
 *
 * System-specific implementation:
 * - Uses GitHub REST API via service-github
 * - Supports per-repo filtering via ResourceFilter
 * - Fetches issues (state=all) for each selected repository
 * - Supports incremental polling via lastSeenUpdatedAt
 *
 * Shared logic (orchestration, deduplication, state management) is in BugTrackerPollingHandlerBase.
 */
@Component
class GitHubBugTrackerPollingHandler(
    private val repository: BugTrackerIssueIndexRepository,
    private val githubBugTrackerClient: IBugTrackerClient,
    clientService: ClientService,
    pollingStateService: PollingStateService,
) : BugTrackerPollingHandlerBase<BugTrackerIssueIndexDocument>(
        pollingStateService = pollingStateService,
        clientService = clientService,
    ) {
    fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.provider == com.jervis.dto.connection.ProviderEnum.GITHUB

    override fun getSystemName(): String = "GitHub Issues"

    override fun getToolName(): String = "GITHUB_BUGTRACKER"

    override fun buildQuery(
        client: ClientDocument?,
        connectionDocument: ConnectionDocument,
        lastSeenUpdatedAt: Instant?,
        resourceFilter: ResourceFilter,
    ): String {
        // For GitHub, the "query" encodes which repos to fetch issues from.
        // The actual GitHub API filtering is done in fetchFullIssues.
        return when (resourceFilter) {
            is ResourceFilter.IndexAll -> "*"
            is ResourceFilter.IndexSelected -> {
                if (resourceFilter.resources.isNotEmpty()) {
                    resourceFilter.resources.joinToString(",")
                } else {
                    "*"
                }
            }
        }
    }

    override suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument,
        clientId: ClientId,
        projectId: ProjectId?,
        query: String,
        lastSeenUpdatedAt: Instant?,
    ): List<BugTrackerIssueIndexDocument> {
        try {
            // Determine which repos to poll
            val repos = if (query == "*") {
                // Fetch all repos for this connection
                val projectsRequest = BugTrackerProjectsRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = AuthType.BEARER,
                    bearerToken = connectionDocument.bearerToken,
                )
                val projectsResponse = githubBugTrackerClient.listProjects(projectsRequest)
                projectsResponse.projects.map { it.key } // key = "owner/repo"
            } else {
                query.split(",").map { it.trim() }
            }

            logger.info { "Fetching GitHub Issues from ${repos.size} repos: ${repos.joinToString(", ")}" }

            val allIssues = mutableListOf<BugTrackerIssueIndexDocument>()

            for (repoKey in repos) {
                try {
                    val searchRequest = BugTrackerSearchRequest(
                        baseUrl = connectionDocument.baseUrl,
                        authType = AuthType.BEARER,
                        bearerToken = connectionDocument.bearerToken,
                        projectKey = repoKey,
                    )

                    val response = githubBugTrackerClient.searchIssues(searchRequest)

                    val repoIssues = response.issues.map { issue ->
                        val syntheticChangelogId = "${issue.id}-${issue.updated}"

                        BugTrackerIssueIndexDocument(
                            id = ObjectId.get(),
                            clientId = clientId,
                            connectionId = connectionDocument.id,
                            issueKey = "${repoKey}${issue.key}", // e.g. "owner/repo#123"
                            latestChangelogId = syntheticChangelogId,
                            summary = issue.title,
                            status = PollingStatusEnum.NEW,
                            bugtrackerUpdatedAt = parseInstant(issue.updated),
                            projectId = projectId,
                        )
                    }

                    allIssues.addAll(repoIssues)
                    logger.debug { "  Fetched ${repoIssues.size} issues from $repoKey" }
                } catch (e: Exception) {
                    logger.error(e) { "  Failed to fetch issues from GitHub repo $repoKey: ${e.message}" }
                    // Continue with other repos
                }
            }

            return allIssues
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch GitHub Issues from ${connectionDocument.baseUrl}: ${e.message}" }
            throw e
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
}
