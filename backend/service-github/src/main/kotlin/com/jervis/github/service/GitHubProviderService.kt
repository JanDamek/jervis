package com.jervis.github.service

import com.jervis.common.client.IProviderService
import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.repository.RepositoryListRequest
import com.jervis.dto.connection.AuthOption
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.FormField
import com.jervis.dto.connection.FormFieldType
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum

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
        protocols = setOf(ProtocolEnum.HTTP),
        defaultCloudBaseUrl = "https://api.github.com",
        supportsCloud = true,
        supportsSelfHosted = true,
        oauth2AuthorizationUrl = "https://github.com/login/oauth/authorize",
        oauth2TokenUrl = "https://github.com/login/oauth/access_token",
        oauth2Scopes = "repo user admin:org admin:public_key admin:repo_hook gist notifications workflow",
        authOptions = listOf(
            AuthOption(
                AuthTypeEnum.OAUTH2, "OAuth 2.0",
                fields = listOf(
                    FormField(FormFieldType.CLOUD_TOGGLE, "Cloud (veřejná instance)", defaultValue = "true"),
                    FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://github.example.com", required = false),
                ),
            ),
            AuthOption(
                AuthTypeEnum.BEARER, "Personal Access Token",
                fields = listOf(
                    FormField(FormFieldType.CLOUD_TOGGLE, "Cloud (github.com)", defaultValue = "true"),
                    FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://api.github.com", required = false),
                    FormField(FormFieldType.BEARER_TOKEN, "Personal Access Token", isSecret = true),
                ),
            ),
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
