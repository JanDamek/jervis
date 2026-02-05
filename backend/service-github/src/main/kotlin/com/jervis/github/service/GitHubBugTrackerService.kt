package com.jervis.github.service

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IGitHubClient
import com.jervis.common.dto.bugtracker.*
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ServiceCapabilitiesDto
import mu.KotlinLogging

/**
 * GitHub Issues as Bug Tracker
 */
class GitHubBugTrackerService(
    private val apiClient: GitHubApiClient,
) : IBugTrackerClient,
    IGitHubClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getCapabilities(): ServiceCapabilitiesDto = ServiceCapabilitiesDto(
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
            ConnectionCapability.GIT,
        )
    )

    override suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val user = apiClient.getUser(token)

        return BugTrackerUserDto(
            id = user.id.toString(),
            username = user.login,
            displayName = user.name ?: user.login,
            email = user.email,
            avatarUrl = user.avatar_url,
        )
    }

    override suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")

        // For GitHub, projectKey should be in format "owner/repo"
        val projectKey = request.projectKey ?: throw IllegalArgumentException("Project key (owner/repo) required")
        val parts = projectKey.split("/")
        if (parts.size != 2) throw IllegalArgumentException("Project key must be in format 'owner/repo'")

        val issues = apiClient.listIssues(token, parts[0], parts[1])

        return BugTrackerSearchResponse(
            issues =
                issues.map { issue ->
                    BugTrackerIssueDto(
                        id = issue.id.toString(),
                        key = "#${issue.number}",
                        title = issue.title,
                        description = issue.body,
                        status = issue.state,
                        priority = null,
                        assignee = null,
                        reporter = null,
                        created = issue.created_at,
                        updated = issue.updated_at,
                        url = issue.html_url,
                        projectKey = projectKey,
                    )
                },
            total = issues.size,
        )
    }

    override suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse {
        // GitHub issue key is in format "#123" or just "123"
        val issueNumber =
            request.issueKey.removePrefix("#").toIntOrNull()
                ?: throw IllegalArgumentException("Invalid issue key: ${request.issueKey}")

        // For now, we need to get owner/repo from baseUrl or another source
        // This is a limitation - in real implementation, we'd need additional context
        throw NotImplementedError("getIssue requires repository context - use searchIssues instead")
    }

    override suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val repos = apiClient.listRepositories(token)

        return BugTrackerProjectsResponse(
            projects = repos.map { repo ->
                BugTrackerProjectDto(
                    id = repo.id.toString(),
                    key = repo.full_name,
                    name = repo.name,
                    description = repo.description,
                    url = repo.html_url,
                )
            },
        )
    }
}
