package com.jervis.gitlab.service

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IGitLabClient
import com.jervis.common.dto.bugtracker.*
import mu.KotlinLogging

/**
 * GitLab Issues as Bug Tracker
 */
class GitLabBugTrackerService(
    private val apiClient: GitLabApiClient
) : IBugTrackerClient, IGitLabClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val user = apiClient.getUser(request.baseUrl, token)

        return BugTrackerUserDto(
            id = user.id.toString(),
            username = user.username,
            displayName = user.name,
            email = user.email,
            avatarUrl = user.avatar_url
        )
    }

    override suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // For GitLab, projectKey is the project ID or path
        val projectKey = request.projectKey ?: throw IllegalArgumentException("Project key (project ID or path) required")
        val issues = apiClient.listIssues(request.baseUrl, token, projectKey)

        return BugTrackerSearchResponse(
            issues = issues.map { issue ->
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
                    projectKey = projectKey
                )
            },
            total = issues.size
        )
    }

    override suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // issueKey format: "projectPath#iid" or just "iid"
        val parts = request.issueKey.split("#")
        val projectPath = if (parts.size == 2) parts[0] else throw IllegalArgumentException("Issue key must include project path")
        val iid = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid issue IID: ${parts[1]}")

        val issue = apiClient.getIssue(request.baseUrl, token, projectPath, iid)

        return BugTrackerIssueResponse(
            issue = BugTrackerIssueDto(
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
                projectKey = issue.project_id.toString()
            )
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
                    url = project.web_url
                )
            }
        )
    }

    override suspend fun downloadAttachment(request: BugTrackerAttachmentRequest): ByteArray? {
        // GitLab issue attachments would need specific handling
        // For now, return null
        return null
    }
}
