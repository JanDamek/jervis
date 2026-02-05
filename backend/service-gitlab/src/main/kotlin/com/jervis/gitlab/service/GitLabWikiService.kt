package com.jervis.gitlab.service

import com.jervis.common.client.IGitLabClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.wiki.*
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ServiceCapabilitiesDto
import mu.KotlinLogging

/**
 * GitLab Wiki implementation
 */
class GitLabWikiService(
    private val apiClient: GitLabApiClient,
) : IWikiClient,
    IGitLabClient {
    private val log = KotlinLogging.logger {}

    override suspend fun getCapabilities(): ServiceCapabilitiesDto = ServiceCapabilitiesDto(
        capabilities = setOf(
            ConnectionCapability.REPOSITORY,
            ConnectionCapability.BUGTRACKER,
            ConnectionCapability.WIKI,
            ConnectionCapability.GIT,
        )
    )

    override suspend fun getUser(request: WikiUserRequest): WikiUserDto {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        val user = apiClient.getUser(request.baseUrl, token)

        return WikiUserDto(
            id = user.id.toString(),
            username = user.username,
            displayName = user.name,
            email = user.email,
        )
    }

    override suspend fun searchPages(request: WikiSearchRequest): WikiSearchResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // For GitLab, spaceKey is the project ID or path
        val projectKey = request.spaceKey ?: throw IllegalArgumentException("Space key (project ID or path) required")
        val pages = apiClient.listWikis(request.baseUrl, token, projectKey)

        return WikiSearchResponse(
            pages =
                pages.map { page ->
                    WikiPageDto(
                        id = page.slug,
                        title = page.title,
                        content = page.content,
                        spaceKey = projectKey,
                        url = "", // GitLab wiki pages don't have a direct URL in API response
                        created = "", // Not available in list response
                        updated = "", // Not available in list response
                    )
                },
            total = pages.size,
        )
    }

    override suspend fun getPage(request: WikiPageRequest): WikiPageResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")

        // pageId format: "projectPath/slug"
        val parts = request.pageId.split("/", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Page ID must be in format 'projectPath/slug'")

        val projectPath = parts[0]
        val slug = parts[1]

        val page = apiClient.getWikiPage(request.baseUrl, token, projectPath, slug)

        return WikiPageResponse(
            page =
                WikiPageDto(
                    id = page.slug,
                    title = page.title,
                    content = page.content,
                    spaceKey = projectPath,
                    url = "",
                    created = "",
                    updated = "",
                ),
        )
    }

    override suspend fun listSpaces(request: WikiSpacesRequest): WikiSpacesResponse {
        val token = request.bearerToken ?: throw IllegalArgumentException("Bearer token required for GitLab")
        // For GitLab, "spaces" are projects with wikis enabled
        val projects = apiClient.listProjects(request.baseUrl, token)

        return WikiSpacesResponse(
            spaces = projects.map { project ->
                WikiSpaceDto(
                    id = project.id.toString(),
                    key = project.path_with_namespace,
                    name = project.name,
                    description = project.description,
                    url = project.web_url,
                )
            },
        )
    }
}
