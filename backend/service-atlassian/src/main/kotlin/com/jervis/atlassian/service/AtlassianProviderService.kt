package com.jervis.atlassian.service

import com.jervis.common.client.IProviderService
import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.bugtracker.BugTrackerUserRequest
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

class AtlassianProviderService(
    private val atlassianService: AtlassianServiceImpl,
) : IProviderService {

    override suspend fun getDescriptor(): ProviderDescriptor = ProviderDescriptor(
        provider = ProviderEnum.ATLASSIAN,
        displayName = "Atlassian (Jira + Confluence + Bitbucket)",
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
        ),
        protocols = setOf(ProtocolEnum.HTTP),
        supportsCloud = true,
        supportsSelfHosted = true,
        oauth2AuthorizationUrl = "https://auth.atlassian.com/authorize",
        oauth2TokenUrl = "https://auth.atlassian.com/oauth/token",
        oauth2Scopes = "read:jira-user read:jira-work write:jira-work read:confluence-content.all read:confluence-space.summary offline_access",
        authOptions = listOf(
            AuthOption(
                AuthTypeEnum.OAUTH2, "OAuth 2.0",
                fields = listOf(
                    FormField(FormFieldType.BASE_URL, "Atlassian URL", placeholder = "https://yourcompany.atlassian.net"),
                ),
            ),
            AuthOption(
                AuthTypeEnum.BASIC, "API Token",
                fields = listOf(
                    FormField(FormFieldType.BASE_URL, "Atlassian URL", placeholder = "https://yourcompany.atlassian.net"),
                    FormField(FormFieldType.USERNAME, "Email"),
                    FormField(FormFieldType.PASSWORD, "API Token", isSecret = true),
                ),
            ),
        ),
    )

    override suspend fun testConnection(request: ProviderTestRequest): ConnectionTestResultDto {
        val user = atlassianService.getUser(request.toBugTrackerUserRequest())
        return ConnectionTestResultDto(
            success = true,
            message = "Atlassian connection successful! User: ${user.displayName}",
            details = mapOf(
                "id" to user.id,
                "displayName" to user.displayName,
                "email" to (user.email ?: "N/A"),
            ),
        )
    }

    override suspend fun listResources(request: ProviderListResourcesRequest): List<ConnectionResourceDto> =
        when (request.capability) {
            ConnectionCapability.BUGTRACKER -> listBugtrackerProjects(request)
            ConnectionCapability.WIKI -> listWikiSpaces(request)
            ConnectionCapability.REPOSITORY -> emptyList()
            else -> emptyList()
        }

    private suspend fun listBugtrackerProjects(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = atlassianService.listProjects(request.toBugTrackerProjectsRequest())
        return response.projects.map { project ->
            ConnectionResourceDto(
                id = project.key,
                name = project.name,
                description = project.description,
                capability = ConnectionCapability.BUGTRACKER,
            )
        }
    }

    private suspend fun listWikiSpaces(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val response = atlassianService.listSpaces(request.toWikiSpacesRequest())
        return response.spaces.map { space ->
            ConnectionResourceDto(
                id = space.key,
                name = space.name,
                description = space.description,
                capability = ConnectionCapability.WIKI,
            )
        }
    }
}

private fun ProviderTestRequest.toBugTrackerUserRequest() =
    BugTrackerUserRequest(
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
