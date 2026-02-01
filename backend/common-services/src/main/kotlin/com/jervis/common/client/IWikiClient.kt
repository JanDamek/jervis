package com.jervis.common.client

import com.jervis.common.dto.wiki.*
import kotlinx.rpc.annotations.Rpc

/**
 * Generic Wiki/Documentation interface.
 * Can be implemented by Confluence, GitHub Wiki, GitLab Wiki, etc.
 */
@Rpc
interface IWikiClient {
    /**
     * Get authenticated user info
     */
    suspend fun getUser(request: WikiUserRequest): WikiUserDto

    /**
     * Search for pages/documents
     */
    suspend fun searchPages(request: WikiSearchRequest): WikiSearchResponse

    /**
     * Get single page/document details
     */
    suspend fun getPage(request: WikiPageRequest): WikiPageResponse

    /**
     * List spaces/wikis available
     */
    suspend fun listSpaces(request: WikiSpacesRequest): WikiSpacesResponse

    /**
     * Download attachment from page
     */
    suspend fun downloadAttachment(request: WikiAttachmentRequest): ByteArray?
}
