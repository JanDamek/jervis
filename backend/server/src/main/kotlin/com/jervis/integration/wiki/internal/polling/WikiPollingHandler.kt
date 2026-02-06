package com.jervis.integration.wiki.internal.polling

import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.integration.wiki.internal.repository.WikiPageIndexRepository
import com.jervis.service.polling.PollingStateService
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
 * Shared logic (orchestration, deduplication, state management) is in WikiPollingHandlerBase.
 */
@Component
class WikiPollingHandler(
    private val repository: WikiPageIndexRepository,
    private val wikiClient: IWikiClient,
    pollingStateService: PollingStateService,
) : WikiPollingHandlerBase<WikiPageIndexDocument>(
        pollingStateService = pollingStateService,
    ) {
    fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.protocol == com.jervis.dto.connection.ProtocolEnum.HTTP &&
            (connectionDocument.baseUrl.contains("atlassian.net") || connectionDocument.baseUrl.contains("atlassian"))

    override fun getSystemName(): String = "Atlassian Wiki"

    override fun getToolName(): String = "WIKI"

    override fun getSpaceKey(client: ClientDocument): String? = null // Inherited from connection

    override suspend fun fetchFullPages(
        connectionDocument: ConnectionDocument,
        clientId: ClientId,
        spaceKey: String?,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int,
    ): List<WikiPageIndexDocument> {
        try {
            val searchRequest =
                WikiSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = AuthType.valueOf(connectionDocument.authType.name),
                    basicUsername = connectionDocument.username,
                    basicPassword = connectionDocument.password,
                    bearerToken = connectionDocument.bearerToken,
                    cloudId = connectionDocument.cloudId,
                    spaceKey = spaceKey ?: connectionDocument.confluenceSpaceKey,
                    query = null,
                    lastModifiedSince = lastSeenUpdatedAt?.toString(),
                    maxResults = maxResults,
                    startAt = startAt,
                )

            val response = wikiClient.searchPages(searchRequest)

            return response.pages.map { page ->
                WikiPageIndexDocument(
                    id = org.bson.types.ObjectId(),
                    clientId = clientId,
                    connectionDocumentId = connectionDocument.id,
                    pageId = page.id,
                    versionNumber = -1,
                    title = page.title,
                    wikiUpdatedAt = parseInstant(page.updated),
                    indexingError = null,
                    status = com.jervis.domain.PollingStatusEnum.NEW,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Confluence pages from ${connectionDocument.baseUrl}: ${e.message}" }
            throw e
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

    override fun getPageUpdatedAt(page: WikiPageIndexDocument): Instant = page.wikiUpdatedAt

    override suspend fun findExisting(
        connectionId: ConnectionId,
        page: WikiPageIndexDocument,
    ): Boolean =
        repository.existsByConnectionDocumentIdAndPageIdAndVersionNumber(
            connectionId = connectionId,
            pageId = page.pageId,
            versionNumber = page.versionNumber,
        )

    override suspend fun savePage(page: WikiPageIndexDocument) {
        try {
            repository.save(page)
        } catch (_: org.springframework.dao.DuplicateKeyException) {
            logger.debug { "Page ${page.pageId} version ${page.versionNumber} already exists, skipping" }
        }
    }
}
