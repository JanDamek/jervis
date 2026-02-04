package com.jervis.gitlab.service

import com.jervis.common.client.IGitLabClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.wiki.*
import mu.KotlinLogging

/**
 * GitLab Wiki implementation
 */
class GitLabWikiService(
    private val apiClient: GitLabApiClient,
) : IWikiClient,
    IGitLabClient {
    private val log = KotlinLogging.logger {}

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
}
