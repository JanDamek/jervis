package com.jervis.integration.wiki

import com.jervis.common.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Wiki integration service (READ + WRITE operations).
 * Generic interface supporting Confluence, MediaWiki, Notion, etc.
 * Vendor-specific implementations are in microservices (service-atlassian, service-notion, etc.)
 */
interface WikiService {
    /**
     * Search wiki pages by query (e.g., CQL for Confluence, title search for MediaWiki).
     */
    suspend fun searchPages(
        clientId: ClientId,
        query: String,
        spaceKey: String? = null,
        maxResults: Int = 20,
    ): List<WikiPage>

    /**
     * Get specific wiki page by ID.
     */
    suspend fun getPage(
        clientId: ClientId,
        pageId: String,
    ): WikiPage

    /**
     * List all wiki spaces for client.
     */
    suspend fun listSpaces(clientId: ClientId): List<WikiSpace>

    /**
     * Get child pages of specific page.
     */
    suspend fun getChildren(
        clientId: ClientId,
        pageId: String,
    ): List<WikiPage>

    /**
     * Create new wiki page.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun createPage(
        clientId: ClientId,
        request: CreatePageRequest,
    ): WikiPage

    /**
     * Update existing wiki page.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun updatePage(
        clientId: ClientId,
        pageId: String,
        request: UpdatePageRequest,
    ): WikiPage
}

@Serializable
data class WikiPage(
    val id: String,
    val title: String,
    val content: String,
    val spaceKey: String,
    val created: String,
    val updated: String,
    val version: Int,
    val parentId: String?,
)

@Serializable
data class WikiSpace(
    val key: String,
    val name: String,
    val description: String?,
)

@Serializable
data class CreatePageRequest(
    val spaceKey: String,
    val title: String,
    val content: String,
    val parentId: String? = null,
)

@Serializable
data class UpdatePageRequest(
    val title: String? = null,
    val content: String? = null,
    val version: Int,
)
