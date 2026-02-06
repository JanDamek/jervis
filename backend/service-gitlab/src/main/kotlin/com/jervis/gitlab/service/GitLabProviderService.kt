package com.jervis.gitlab.service

import com.jervis.common.client.IProviderService
import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.repository.RepositoryListRequest
import com.jervis.common.dto.wiki.WikiSpacesRequest
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

class GitLabProviderService(
    private val repositoryService: GitLabRepositoryService,
    private val bugTrackerService: GitLabBugTrackerService,
    private val wikiService: GitLabWikiService,
) : IProviderService {

    override suspend fun getDescriptor(): ProviderDescriptor = ProviderDescriptor(
        provider = ProviderEnum.GITLAB,
        displayName = "GitLab",
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
        ),
        protocols = setOf(ProtocolEnum.HTTP),
        defaultCloudBaseUrl = "https://gitlab.com",
        supportsCloud = true,
        supportsSelfHosted = true,
        oauth2AuthorizationUrl = "https://gitlab.com/oauth/authorize",
        oauth2TokenUrl = "https://gitlab.com/oauth/token",
        oauth2Scopes = "api read_user read_api read_repository write_repository",
        authOptions = listOf(
            AuthOption(
                AuthTypeEnum.OAUTH2, "OAuth 2.0",
                fields = listOf(
                    FormField(FormFieldType.CLOUD_TOGGLE, "Cloud (veřejná instance)", defaultValue = "true"),
                    FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://gitlab.example.com", required = false),
                ),
            ),
            AuthOption(
                AuthTypeEnum.BEARER, "Personal Access Token",
                fields = listOf(
                    FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://gitlab.example.com"),
                    FormField(FormFieldType.BEARER_TOKEN, "Personal Access Token", isSecret = true),
                ),
            ),
        ),
    )

    override suspend fun testConnection(request: ProviderTestRequest): ConnectionTestResultDto {
        val user = repositoryService.getUser(request.toRepositoryUserRequest())
        return ConnectionTestResultDto(
            success = true,
            message = "GitLab connection successful! User: ${user.username}",
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
            ConnectionCapability.WIKI -> listWikiSpaces(request)
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
                description = project.description ?: "GitLab Issues for ${project.key}",
                capability = ConnectionCapability.BUGTRACKER,
            )
        }
    }

    private suspend fun listWikiSpaces(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = wikiService.listSpaces(request.toWikiSpacesRequest())
        return response.spaces.map { space ->
            ConnectionResourceDto(
                id = space.key,
                name = "${space.name} (Wiki)",
                description = space.description ?: "GitLab Wiki for ${space.key}",
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

private fun ProviderListResourcesRequest.toWikiSpacesRequest() =
    WikiSpacesRequest(
        baseUrl = baseUrl,
        authType = authType.name,
        basicUsername = username,
        basicPassword = password,
        bearerToken = bearerToken,
    )
