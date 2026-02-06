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
 * GitLab Issues polling handler.
 *
 * Uses GitLab REST API via service-gitlab.
 * Supports per-project filtering via ResourceFilter.
 * Fetches issues (state=all) for each selected project.
 * Supports incremental polling via lastSeenUpdatedAt.
 */
@Component
class GitLabBugTrackerPollingHandler(
    private val repository: BugTrackerIssueIndexRepository,
    private val gitlabBugTrackerClient: IBugTrackerClient,
    clientService: ClientService,
    pollingStateService: PollingStateService,
) : BugTrackerPollingHandlerBase<BugTrackerIssueIndexDocument>(
        pollingStateService = pollingStateService,
        clientService = clientService,
    ) {
    override fun getSystemName(): String = "GitLab Issues"

    override fun getToolName(): String = "GITLAB_BUGTRACKER"

    override fun buildQuery(
        client: ClientDocument?,
        connectionDocument: ConnectionDocument,
        lastSeenUpdatedAt: Instant?,
        resourceFilter: ResourceFilter,
    ): String {
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
            val projects = if (query == "*") {
                val projectsRequest = BugTrackerProjectsRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = AuthType.BEARER,
                    bearerToken = connectionDocument.bearerToken,
                )
                val projectsResponse = gitlabBugTrackerClient.listProjects(projectsRequest)
                projectsResponse.projects.map { it.key } // key = "owner/project"
            } else {
                query.split(",").map { it.trim() }
            }

            logger.info { "Fetching GitLab Issues from ${projects.size} projects: ${projects.joinToString(", ")}" }

            val allIssues = mutableListOf<BugTrackerIssueIndexDocument>()

            for (projectKey in projects) {
                try {
                    val searchRequest = BugTrackerSearchRequest(
                        baseUrl = connectionDocument.baseUrl,
                        authType = AuthType.BEARER,
                        bearerToken = connectionDocument.bearerToken,
                        projectKey = projectKey,
                    )

                    val response = gitlabBugTrackerClient.searchIssues(searchRequest)

                    val projectIssues = response.issues.map { issue ->
                        val syntheticChangelogId = "${issue.id}-${issue.updated}"

                        BugTrackerIssueIndexDocument(
                            id = ObjectId.get(),
                            clientId = clientId,
                            connectionId = connectionDocument.id,
                            issueKey = "${projectKey}${issue.key}", // e.g. "owner/project#123"
                            latestChangelogId = syntheticChangelogId,
                            summary = issue.title,
                            status = PollingStatusEnum.NEW,
                            bugtrackerUpdatedAt = parseInstant(issue.updated),
                            projectId = projectId,
                        )
                    }

                    allIssues.addAll(projectIssues)
                    logger.debug { "  Fetched ${projectIssues.size} issues from $projectKey" }
                } catch (e: Exception) {
                    if (e.message?.contains("404") == true) {
                        logger.warn { "  GitLab project $projectKey: Issues feature likely disabled (404)" }
                    } else {
                        logger.error(e) { "  Failed to fetch issues from GitLab project $projectKey: ${e.message}" }
                    }
                }
            }

            return allIssues
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch GitLab Issues from ${connectionDocument.baseUrl}: ${e.message}" }
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
