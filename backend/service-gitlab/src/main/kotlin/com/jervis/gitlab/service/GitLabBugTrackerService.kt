package com.jervis.gitlab.service

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.bugtracker.*
import mu.KotlinLogging

/**
 * GitLab Issues as Bug Tracker
 */
class GitLabBugTrackerService(
    private val apiClient: GitLabApiClient,
) : IBugTrackerClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val user = apiClient.getUser(request.baseUrl, token)

        return BugTrackerUserDto(
            id = user.id.toString(),
            username = user.username,
            displayName = user.name,
            email = user.email,
            avatarUrl = user.avatar_url,
        )
    }

    override suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // For GitLab, projectKey is the project ID or path
        val projectKey =
            request.projectKey ?: throw IllegalArgumentException("Project key (project ID or path) required")
        val issues = apiClient.listIssues(request.baseUrl, token, projectKey)

        return BugTrackerSearchResponse(
            issues =
                issues.map { issue ->
                    BugTrackerIssueDto(
                        id = issue.id.toString(),
                        key = "#${issue.iid}",
                        title = issue.title,
                        description = issue.description,
                        status = issue.state,
                        priority = null,
                        assignee = null,
                        reporter = null,
                        created = issue.created_at,
                        updated = issue.updated_at,
                        url = issue.web_url,
                        projectKey = projectKey,
                    )
                },
            total = issues.size,
        )
    }

    override suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // issueKey format: "projectPath#iid" or just "iid"
        val parts = request.issueKey.split("#")
        val projectPath =
            if (parts.size == 2) parts[0] else throw IllegalArgumentException("Issue key must include project path")
        val iid = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid issue IID: ${parts[1]}")

        val issue = apiClient.getIssue(request.baseUrl, token, projectPath, iid)

        return BugTrackerIssueResponse(
            issue =
                BugTrackerIssueDto(
                    id = issue.id.toString(),
                    key = "#${issue.iid}",
                    title = issue.title,
                    description = issue.description,
                    status = issue.state,
                    priority = null,
                    assignee = null,
                    reporter = null,
                    created = issue.created_at,
                    updated = issue.updated_at,
                    url = issue.web_url,
                    projectKey = issue.project_id.toString(),
                ),
        )
    }

    override suspend fun getComments(request: BugTrackerGetCommentsRequest): BugTrackerGetCommentsResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val (projectPath, iid) = parseIssueKey(request.issueKey)

        val notes = apiClient.listIssueNotes(
            baseUrl = request.baseUrl,
            token = token,
            projectId = projectPath,
            issueIid = iid,
        )

        return BugTrackerGetCommentsResponse(
            comments = notes.map { n ->
                BugTrackerCommentDto(
                    id = n.id.toString(),
                    author = n.author?.username ?: "unknown",
                    authorId = n.author?.id?.toString(),
                    body = n.body,
                    created = n.created_at,
                )
            },
        )
    }

    override suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val projects = apiClient.listProjects(request.baseUrl, token)

        return BugTrackerProjectsResponse(
            projects = projects.map { project ->
                BugTrackerProjectDto(
                    id = project.id.toString(),
                    key = project.path_with_namespace,
                    name = project.name,
                    description = project.description,
                    url = project.web_url,
                )
            },
        )
    }

    override suspend fun createIssue(request: BugTrackerCreateIssueRpcRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        val issue = apiClient.createIssue(
            baseUrl = request.baseUrl,
            token = token,
            projectId = request.projectKey,
            title = request.summary,
            description = request.description,
            labels = request.labels,
            assigneeId = request.assignee,
        )

        return BugTrackerIssueResponse(
            issue = issue.toBugTrackerDto(request.projectKey),
        )
    }

    override suspend fun updateIssue(request: BugTrackerUpdateIssueRpcRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val (projectPath, iid) = parseIssueKey(request.issueKey)

        val issue = apiClient.updateIssue(
            baseUrl = request.baseUrl,
            token = token,
            projectId = projectPath,
            issueIid = iid,
            title = request.summary,
            description = request.description,
            assigneeId = request.assignee,
            labels = request.labels,
        )

        return BugTrackerIssueResponse(
            issue = issue.toBugTrackerDto(projectPath),
        )
    }

    override suspend fun addComment(request: BugTrackerAddCommentRpcRequest): BugTrackerCommentResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val (projectPath, iid) = parseIssueKey(request.issueKey)

        val note = apiClient.addIssueNote(
            baseUrl = request.baseUrl,
            token = token,
            projectId = projectPath,
            issueIid = iid,
            body = request.body,
        )

        return BugTrackerCommentResponse(
            id = note.id.toString(),
            author = note.author?.username,
            body = note.body,
            created = note.created_at,
        )
    }

    override suspend fun transitionIssue(request: BugTrackerTransitionRpcRequest) {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val (projectPath, iid) = parseIssueKey(request.issueKey)

        // GitLab uses state_event: "close" or "reopen"
        val stateEvent = when (request.transitionName.lowercase()) {
            "close", "closed", "done", "resolved" -> "close"
            "open", "reopen", "reopened" -> "reopen"
            else -> throw IllegalArgumentException(
                "Unknown transition '${request.transitionName}'. GitLab supports: close, reopen"
            )
        }

        apiClient.updateIssue(
            baseUrl = request.baseUrl,
            token = token,
            projectId = projectPath,
            issueIid = iid,
            stateEvent = stateEvent,
        )
    }

    private fun GitLabIssue.toBugTrackerDto(projectKey: String) = BugTrackerIssueDto(
        id = id.toString(),
        key = "#$iid",
        title = title,
        description = description,
        status = state,
        priority = null,
        assignee = null,
        reporter = null,
        created = created_at,
        updated = updated_at,
        url = web_url,
        projectKey = projectKey,
    )

    /** Parse issue key in format "projectPath#iid" */
    private fun parseIssueKey(issueKey: String): Pair<String, Int> {
        val parts = issueKey.split("#")
        if (parts.size != 2) throw IllegalArgumentException("Issue key must be in format 'projectPath#iid', got: $issueKey")
        val iid = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid issue IID: ${parts[1]}")
        return parts[0] to iid
    }
}
