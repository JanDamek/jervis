package com.jervis.github.service

import com.jervis.common.client.IGitHubClient
import com.jervis.common.client.IRepositoryClient
import com.jervis.common.dto.repository.*
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ServiceCapabilitiesDto
import mu.KotlinLogging

class GitHubRepositoryService(
    private val apiClient: GitHubApiClient
) : IRepositoryClient, IGitHubClient {
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
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val user = apiClient.getUser(token)

        return RepositoryUserDto(
            id = user.id.toString(),
            username = user.login,
            displayName = user.name ?: user.login,
            email = user.email
        )
    }

    override suspend fun listRepositories(request: RepositoryListRequest): RepositoryListResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val repos = apiClient.listRepositories(token)

        return RepositoryListResponse(
            repositories = repos.map { repo ->
                RepositoryDto(
                    id = repo.id.toString(),
                    name = repo.name,
                    fullName = repo.full_name,
                    description = repo.description,
                    cloneUrl = repo.clone_url,
                    sshUrl = repo.ssh_url,
                    webUrl = repo.html_url,
                    defaultBranch = repo.default_branch,
                    isPrivate = repo.private
                )
            }
        )
    }

    override suspend fun getRepository(request: RepositoryRequest): RepositoryResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val repo = apiClient.getRepository(token, request.owner, request.repo)

        return RepositoryResponse(
            repository = RepositoryDto(
                id = repo.id.toString(),
                name = repo.name,
                fullName = repo.full_name,
                description = repo.description,
                cloneUrl = repo.clone_url,
                sshUrl = repo.ssh_url,
                webUrl = repo.html_url,
                defaultBranch = repo.default_branch,
                isPrivate = repo.private
            )
        )
    }

    override suspend fun listIssues(request: RepositoryIssuesRequest): RepositoryIssuesResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val issues = apiClient.listIssues(token, request.owner, request.repo)

        return RepositoryIssuesResponse(
            issues = issues.map { issue ->
                RepositoryIssueDto(
                    id = issue.id.toString(),
                    number = issue.number,
                    title = issue.title,
                    description = issue.body,
                    state = issue.state,
                    url = issue.html_url,
                    created = issue.created_at,
                    updated = issue.updated_at
                )
            }
        )
    }

    override suspend fun getFile(request: RepositoryFileRequest): RepositoryFileResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitHub")
        val file = apiClient.getFile(token, request.owner, request.repo, request.path, request.ref)

        return RepositoryFileResponse(
            content = file.content ?: "",
            encoding = file.encoding ?: "base64",
            size = file.size,
            path = file.path
        )
    }
}
