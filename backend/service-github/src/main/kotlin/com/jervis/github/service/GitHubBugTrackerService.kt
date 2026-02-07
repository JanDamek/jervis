package com.jervis.github.service

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.bugtracker.*
import mu.KotlinLogging

/**
 * GitHub Issues as Bug Tracker
 */
class GitHubBugTrackerService(
    private val apiClient: GitHubApiClient,
) : IBugTrackerClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val user = apiClient.getUser(token, request.baseUrl.takeIf { it.isNotBlank() })

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

        val issues = apiClient.listIssues(token, parts[0], parts[1], request.baseUrl.takeIf { it.isNotBlank() })

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
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid issue key: ${request.issueKey}")

        // baseUrl is expected to contain owner/repo context in format "https://api.github.com"
        // or the issueKey could be in format "owner/repo#123"
        // We need projectKey context - parse from baseUrl or require it
        val baseUrl = request.baseUrl.trimEnd('/')

        // Try to extract owner/repo from the baseUrl if it's a GitHub web URL
        // e.g., "https://github.com/owner/repo" or the API equivalent
        val ownerRepo = extractOwnerRepo(baseUrl)
            ?: throw IllegalArgumentException("Cannot determine repository from baseUrl: $baseUrl. Use format 'https://github.com/owner/repo'")

        val issue = apiClient.getIssue(token, ownerRepo.first, ownerRepo.second, issueNumber)

        return BugTrackerIssueResponse(
            issue = BugTrackerIssueDto(
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
                projectKey = "${ownerRepo.first}/${ownerRepo.second}",
            ),
        )
    }

    override suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val repos = apiClient.listRepositories(token, request.baseUrl.takeIf { it.isNotBlank() })

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

    /**
     * Extract owner/repo from a GitHub URL.
     * Supports: "https://github.com/owner/repo", "owner/repo"
     */
    private fun extractOwnerRepo(baseUrl: String): Pair<String, String>? {
        // Direct "owner/repo" format
        val slashParts = baseUrl.split("/")
        if (slashParts.size == 2 && !baseUrl.contains("://")) {
            return slashParts[0] to slashParts[1]
        }

        // URL format: strip scheme and host, take first two path segments
        val path = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringAfter("/") // remove host
        val pathParts = path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size >= 2) {
            return pathParts[0] to pathParts[1]
        }

        return null
    }
}
