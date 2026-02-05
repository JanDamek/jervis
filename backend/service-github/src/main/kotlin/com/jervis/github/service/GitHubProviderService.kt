package com.jervis.github.service

import com.jervis.common.client.IProviderService
import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.repository.RepositoryListRequest
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.connection.ProviderUiHints

class GitHubProviderService(
    private val repositoryService: GitHubRepositoryService,
    private val bugTrackerService: GitHubBugTrackerService,
) : IProviderService {

    override suspend fun getDescriptor(): ProviderDescriptor = ProviderDescriptor(
        provider = ProviderEnum.GITHUB,
        displayName = "GitHub",
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
        ),
        authTypes = listOf(AuthTypeEnum.BEARER, AuthTypeEnum.OAUTH2),
        protocols = setOf(ProtocolEnum.HTTP),
        defaultCloudBaseUrl = "https://api.github.com",
        supportsCloud = true,
        supportsSelfHosted = true,
        oauth2AuthorizationUrl = "https://github.com/login/oauth/authorize",
        oauth2TokenUrl = "https://github.com/login/oauth/access_token",
        oauth2Scopes = "repo user admin:org admin:public_key admin:repo_hook gist notifications workflow",
        uiHints = ProviderUiHints(
            showCloudToggle = true,
            baseUrlPlaceholder = "https://github.example.com",
        ),
    )

    override suspend fun testConnection(request: ProviderTestRequest): ConnectionTestResultDto {
        val user = repositoryService.getUser(request.toRepositoryUserRequest())
        return ConnectionTestResultDto(
            success = true,
            message = "GitHub connection successful! User: ${user.username}",
            details = mapOf(
                "username" to user.username,
                "id" to user.id,
                "displayName" to user.displayName,
            ),
        )
    }

    override suspend fun listResources(request: ProviderListResourcesRequest): List<ConnectionResourceDto> =
        when (request.capability) {
            ConnectionCapability.REPOSITORY -> listRepositories(request)
            ConnectionCapability.BUGTRACKER -> listBugtrackerProjects(request)
            ConnectionCapability.WIKI -> listWikiRepos(request)
            else -> emptyList()
        }

    private suspend fun listRepositories(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = repositoryService.listRepositories(request.toRepositoryListRequest())
        return response.repositories.map { repo ->
            ConnectionResourceDto(
                id = repo.fullName,
                name = repo.name,
                description = repo.description,
                capability = ConnectionCapability.REPOSITORY,
            )
        }
    }

    private suspend fun listBugtrackerProjects(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = bugTrackerService.listProjects(request.toBugTrackerProjectsRequest())
        return response.projects.map { project ->
            ConnectionResourceDto(
                id = project.key,
                name = "${project.name} (Issues)",
                description = project.description ?: "GitHub Issues for ${project.key}",
                capability = ConnectionCapability.BUGTRACKER,
            )
        }
    }

    private suspend fun listWikiRepos(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = repositoryService.listRepositories(request.toRepositoryListRequest())
        return response.repositories.map { repo ->
            ConnectionResourceDto(
                id = repo.fullName,
                name = "${repo.name} (Wiki)",
                description = repo.description ?: "GitHub Wiki for ${repo.fullName}",
                capability = ConnectionCapability.WIKI,
            )
        }
    }
}

private fun ProviderTestRequest.toRepositoryUserRequest() =
    com.jervis.common.dto.repository.RepositoryUserRequest(
        baseUrl = baseUrl,
        authType = AuthType.valueOf(authType.name),
        basicUsername = username,
        basicPassword = password,
        bearerToken = bearerToken,
    )

private fun ProviderListResourcesRequest.toRepositoryListRequest() =
    RepositoryListRequest(
        baseUrl = baseUrl,
        authType = AuthType.valueOf(authType.name),
        basicUsername = username,
        basicPassword = password,
        bearerToken = bearerToken,
    )

private fun ProviderListResourcesRequest.toBugTrackerProjectsRequest() =
    BugTrackerProjectsRequest(
        baseUrl = baseUrl,
        authType = AuthType.valueOf(authType.name),
        basicUsername = username,
        basicPassword = password,
        bearerToken = bearerToken,
    )
