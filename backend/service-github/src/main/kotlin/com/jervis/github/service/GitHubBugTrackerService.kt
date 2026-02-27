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
            issues = issues.map { it.toBugTrackerDto(projectKey) },
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
            issue = issue.toBugTrackerDto("${ownerRepo.first}/${ownerRepo.second}"),
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

    override suspend fun createIssue(request: BugTrackerCreateIssueRpcRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val (owner, repo) = parseOwnerRepo(request.projectKey)

        val issue = apiClient.createIssue(
            token = token,
            owner = owner,
            repo = repo,
            title = request.summary,
            body = request.description,
            labels = request.labels,
            assignee = request.assignee,
            baseUrl = request.baseUrl.takeIf { it.isNotBlank() },
        )

        return BugTrackerIssueResponse(
            issue = issue.toBugTrackerDto("$owner/$repo"),
        )
    }

    override suspend fun updateIssue(request: BugTrackerUpdateIssueRpcRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid issue key: ${request.issueKey}")
        val (owner, repo) = parseOwnerRepoFromBaseUrl(request.baseUrl)

        val issue = apiClient.updateIssue(
            token = token,
            owner = owner,
            repo = repo,
            issueNumber = issueNumber,
            title = request.summary,
            body = request.description,
            assignee = request.assignee,
            labels = request.labels,
            baseUrl = request.baseUrl.takeIf { it.isNotBlank() },
        )

        return BugTrackerIssueResponse(
            issue = issue.toBugTrackerDto("$owner/$repo"),
        )
    }

    override suspend fun addComment(request: BugTrackerAddCommentRpcRequest): BugTrackerCommentResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid issue key: ${request.issueKey}")
        val (owner, repo) = parseOwnerRepoFromBaseUrl(request.baseUrl)

        val comment = apiClient.addComment(
            token = token,
            owner = owner,
            repo = repo,
            issueNumber = issueNumber,
            body = request.body,
            baseUrl = request.baseUrl.takeIf { it.isNotBlank() },
        )

        return BugTrackerCommentResponse(
            id = comment.id.toString(),
            author = comment.user?.login,
            body = comment.body,
            created = comment.created_at,
        )
    }

    override suspend fun transitionIssue(request: BugTrackerTransitionRpcRequest) {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid issue key: ${request.issueKey}")
        val (owner, repo) = parseOwnerRepoFromBaseUrl(request.baseUrl)

        // GitHub Issues only have two states: "open" and "closed"
        val state = when (request.transitionName.lowercase()) {
            "close", "closed", "done", "resolved" -> "closed"
            "open", "reopen", "reopened" -> "open"
            else -> throw IllegalArgumentException(
                "Unknown transition '${request.transitionName}'. GitHub supports: open, close"
            )
        }

        apiClient.updateIssue(
            token = token,
            owner = owner,
            repo = repo,
            issueNumber = issueNumber,
            state = state,
            baseUrl = request.baseUrl.takeIf { it.isNotBlank() },
        )
    }

    private fun GitHubIssue.toBugTrackerDto(projectKey: String) = BugTrackerIssueDto(
        id = id.toString(),
        key = "#$number",
        title = title,
        description = body,
        status = state,
        priority = null,
        assignee = null,
        reporter = null,
        created = created_at,
        updated = updated_at,
        url = html_url,
        projectKey = projectKey,
    )

    private fun parseOwnerRepo(projectKey: String): Pair<String, String> {
        val parts = projectKey.split("/")
        if (parts.size != 2) throw IllegalArgumentException("Project key must be in format 'owner/repo', got: $projectKey")
        return parts[0] to parts[1]
    }

    private fun parseOwnerRepoFromBaseUrl(baseUrl: String): Pair<String, String> {
        return extractOwnerRepo(baseUrl.trimEnd('/'))
            ?: throw IllegalArgumentException("Cannot determine repository from baseUrl: $baseUrl. Use format 'https://github.com/owner/repo'")
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
