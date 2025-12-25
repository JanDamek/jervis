package com.jervis.service.confluence

import com.jervis.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Confluence integration service (READ + WRITE operations).
 * Currently a TODO/mock interface - implementation pending.
 */
interface ConfluenceService {
    /**
     * Search Confluence pages by CQL query.
     */
    suspend fun searchPages(
        clientId: ClientId,
        query: String,
        spaceKey: String? = null,
        maxResults: Int = 20,
    ): List<ConfluencePage>

    /**
     * Get specific Confluence page by ID.
     */
    suspend fun getPage(
        clientId: ClientId,
        pageId: String,
    ): ConfluencePage

    /**
     * List all Confluence spaces for client.
     */
    suspend fun listSpaces(clientId: ClientId): List<ConfluenceSpace>

    /**
     * Get child pages of specific page.
     */
    suspend fun getChildren(
        clientId: ClientId,
        pageId: String,
    ): List<ConfluencePage>

    /**
     * Create new Confluence page.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun createPage(
        clientId: ClientId,
        request: CreatePageRequest,
    ): ConfluencePage

    /**
     * Update existing Confluence page.
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun updatePage(
        clientId: ClientId,
        pageId: String,
        request: UpdatePageRequest,
    ): ConfluencePage
}

@Serializable
data class ConfluencePage(
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
data class ConfluenceSpace(
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
