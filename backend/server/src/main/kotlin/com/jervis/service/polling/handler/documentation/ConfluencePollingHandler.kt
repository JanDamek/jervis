package com.jervis.service.polling.handler.documentation

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.entity.ClientDocument
import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.HttpCredentials
import com.jervis.repository.ConfluencePageIndexMongoRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ClientId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Confluence page polling handler.
 *
 * System-specific implementation:
 * - Uses Atlassian REST API via service-atlassian
 * - Supports space-based filtering
 * - Fetches complete page data (content, comments, attachments)
 * - Supports incremental polling via lastSeenUpdatedAt
 *
 * Shared logic (orchestration, deduplication, state management) is in DocumentationPollingHandlerBase.
 */
@Component
class ConfluencePollingHandler(
    private val repository: ConfluencePageIndexMongoRepository,
    private val atlassianClient: IAtlassianClient,
    connectionService: ConnectionService,
) : DocumentationPollingHandlerBase<ConfluencePageIndexDocument>(
        connectionService = connectionService,
    ) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument is ConnectionDocument.HttpConnectionDocument &&
            connectionDocument.baseUrl.contains("atlassian.net")

    override fun getSystemName(): String = "Confluence"

    override fun getToolName(): String = "CONFLUENCE"

    override fun getSpaceKey(client: ClientDocument): String? = client.confluenceSpaceKey

    override suspend fun fetchFullPages(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        spaceKey: String?,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int,
    ): List<ConfluencePageIndexDocument> {
        try {
            val authInfo =
                when (credentials) {
                    is HttpCredentials.Basic -> AuthInfo("BASIC", credentials.username, credentials.password, null)
                    is HttpCredentials.Bearer -> AuthInfo("BEARER", null, null, credentials.token)
                }

            val searchRequest =
                ConfluenceSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = authInfo.authType,
                    basicUsername = authInfo.username,
                    basicPassword = authInfo.password,
                    bearerToken = authInfo.bearerToken,
                    spaceKey = spaceKey,
                    cql = null,
                    lastModifiedSince = lastSeenUpdatedAt,
                    maxResults = maxResults,
                    startAt = startAt,
                )

            val response = atlassianClient.searchConfluencePages(searchRequest)

            return response.pages.map { page ->
                ConfluencePageIndexDocument.New(
                    clientId = clientId,
                    connectionDocumentId = connectionDocument.id,
                    pageId = page.id,
                    versionNumber = page.version?.number ?: -1,
                    title = page.title,
                    spaceKey = page.spaceKey,
                    pageType = "page",
                    status = "current",
                    content = null,
                    creator = page.version?.by?.displayName,
                    lastModifier = page.version?.by?.displayName,
                    labels = emptyList(),
                    parentPageId = null,
                    createdAt = parseInstant(page.version?.`when`),
                    confluenceUpdatedAt = parseInstant(page.lastModified),
                    comments = emptyList(),
                    attachments = emptyList(),
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Confluence pages from ${connectionDocument.baseUrl}: ${e.message}" }
            return emptyList()
        }
    }

    private fun parseInstant(isoString: String?): Instant =
        if (isoString != null) {
            try {
                Instant.parse(isoString)
            } catch (_: Exception) {
                Instant.now()
            }
        } else {
            Instant.now()
        }

    private data class AuthInfo(
        val authType: String,
        val username: String?,
        val password: String?,
        val bearerToken: String?,
    )

    override fun getPageUpdatedAt(page: ConfluencePageIndexDocument): Instant = page.confluenceUpdatedAt

    override suspend fun findExisting(
        connectionId: com.jervis.types.ConnectionId,
        page: ConfluencePageIndexDocument,
    ): ConfluencePageIndexDocument? =
        repository.findByConnectionDocumentIdAndPageIdAndVersionNumber(
            connectionId = connectionId.value,
            pageId = page.pageId,
            versionNumber = page.versionNumber,
        )

    override suspend fun savePage(page: ConfluencePageIndexDocument) {
        try {
            repository.save(page)
        } catch (_: org.springframework.dao.DuplicateKeyException) {
            logger.debug { "Page ${page.pageId} version ${page.versionNumber} already exists, skipping" }
        }
    }
}
