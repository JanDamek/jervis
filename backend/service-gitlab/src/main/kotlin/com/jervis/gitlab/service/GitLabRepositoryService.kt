package com.jervis.gitlab.service

import com.jervis.common.client.IGitLabClient
import com.jervis.common.client.IRepositoryClient
import com.jervis.common.dto.repository.*
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ServiceCapabilitiesDto
import mu.KotlinLogging

class GitLabRepositoryService(
    private val apiClient: GitLabApiClient
) : IRepositoryClient, IGitLabClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getCapabilities(): ServiceCapabilitiesDto = ServiceCapabilitiesDto(
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
            ConnectionCapability.GIT,
        )
    )

    override suspend fun getUser(request: RepositoryUserRequest): RepositoryUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val user = apiClient.getUser(request.baseUrl, token)

        return RepositoryUserDto(
            id = user.id.toString(),
            username = user.username,
            displayName = user.name,
            email = user.email
        )
    }

    override suspend fun listRepositories(request: RepositoryListRequest): RepositoryListResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val projects = apiClient.listProjects(request.baseUrl, token)

        return RepositoryListResponse(
            repositories = projects.map { project ->
                RepositoryDto(
                    id = project.id.toString(),
                    name = project.name,
                    fullName = project.path_with_namespace,
                    description = project.description,
                    cloneUrl = project.http_url_to_repo,
                    sshUrl = project.ssh_url_to_repo,
                    webUrl = project.web_url,
                    defaultBranch = project.default_branch ?: "main",
                    isPrivate = project.visibility != "public"
                )
            }
        )
    }

    override suspend fun getRepository(request: RepositoryRequest): RepositoryResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        // For GitLab, owner/repo format needs to be converted to project ID or path
        val projectPath = "${request.owner}/${request.repo}"
        val project = apiClient.getProject(request.baseUrl, token, projectPath)

        return RepositoryResponse(
            repository = RepositoryDto(
                id = project.id.toString(),
                name = project.name,
                fullName = project.path_with_namespace,
                description = project.description,
                cloneUrl = project.http_url_to_repo,
                sshUrl = project.ssh_url_to_repo,
                webUrl = project.web_url,
                defaultBranch = project.default_branch ?: "main",
                isPrivate = project.visibility != "public"
            )
        )
    }

    override suspend fun listIssues(request: RepositoryIssuesRequest): RepositoryIssuesResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val projectPath = "${request.owner}/${request.repo}"
        val issues = apiClient.listIssues(request.baseUrl, token, projectPath)

        return RepositoryIssuesResponse(
            issues = issues.map { issue ->
                RepositoryIssueDto(
                    id = issue.id.toString(),
                    number = issue.iid,
                    title = issue.title,
                    description = issue.description,
                    state = issue.state,
                    url = issue.web_url,
                    created = issue.created_at,
                    updated = issue.updated_at
                )
            }
        )
    }

    override suspend fun getFile(request: RepositoryFileRequest): RepositoryFileResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val projectPath = "${request.owner}/${request.repo}"
        val file = apiClient.getFile(request.baseUrl, token, projectPath, request.path, request.ref)

        return RepositoryFileResponse(
            content = file.content,
            encoding = file.encoding,
            size = file.size,
            path = file.file_path
        )
    }
}
