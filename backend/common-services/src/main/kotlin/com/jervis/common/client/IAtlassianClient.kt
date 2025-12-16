package com.jervis.common.client

import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluencePageResponse
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchResponse
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraIssueResponse
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.dto.atlassian.JiraSearchResponse
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * Interface for communication with Atlassian services (Jira, Confluence, Bitbucket, etc.).
 *
 * This interface defines the contract between the server module and service-atlassian module.
 * It provides only the methods actually needed by the server (pollers, handlers, indexers).
 *
 * Implementation note:
 * - service-atlassian contains generated OpenAPI clients with full Atlassian APIs
 * - This interface exposes only a minimal subset needed for server operations
 * - Server depends on common-services (this interface), not on service-atlassian internals
 */
@HttpExchange("/api/atlassian")
interface IAtlassianClient {
    /**
     * Get current user information for authentication validation
     */
    @PostExchange("/myself")
    suspend fun getMyself(
        @RequestBody request: AtlassianMyselfRequest,
    ): AtlassianUserDto

    /**
     * Search Jira issues using JQL query
     * Used by JiraPollingHandler for incremental issue fetching
     */
    @PostExchange("/jira/search")
    suspend fun searchJiraIssues(
        @RequestBody request: JiraSearchRequest,
    ): JiraSearchResponse

    /**
     * Get detailed Jira issue by key
     * Used for fetching complete issue data including comments, attachments, history
     */
    @PostExchange("/jira/issue")
    suspend fun getJiraIssue(
        @RequestBody request: JiraIssueRequest,
    ): JiraIssueResponse

    /**
     * Search Confluence pages by space and/or CQL query
     * Used by ConfluencePollingHandler for incremental page fetching
     */
    @PostExchange("/confluence/search")
    suspend fun searchConfluencePages(
        @RequestBody request: ConfluenceSearchRequest,
    ): ConfluenceSearchResponse

    /**
     * Get detailed Confluence page by ID
     * Used for fetching complete page data including content, comments, attachments
     */
    @PostExchange("/confluence/page")
    suspend fun getConfluencePage(
        @RequestBody request: ConfluencePageRequest,
    ): ConfluencePageResponse

    /**
     * Download Jira attachment binary data
     * Used by indexers for vision augmentation (images, PDFs)
     * Returns raw binary data (ByteArray)
     */
    @PostExchange("/jira/attachment/download")
    suspend fun downloadJiraAttachment(
        @RequestBody request: JiraAttachmentDownloadRequest,
    ): ByteArray
}
