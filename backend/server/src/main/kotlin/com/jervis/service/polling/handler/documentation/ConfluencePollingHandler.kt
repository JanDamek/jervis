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
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

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
) : DocumentationPollingHandlerBase<ConfluencePageIndexDocument, ConfluencePageIndexMongoRepository>(
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
            logger.debug {
                "Fetching Confluence pages: baseUrl=${connectionDocument.baseUrl} spaceKey=$spaceKey maxResults=$maxResults startAt=$startAt"
            }

            // Prepare auth info
            val authInfo =
                when (credentials) {
                    is HttpCredentials.Basic -> AuthInfo("BASIC", credentials.username, credentials.password, null)
                    is HttpCredentials.Bearer -> AuthInfo("BEARER", null, null, credentials.token)
                }
            val authType = authInfo.authType
            val username = authInfo.username
            val password = authInfo.password
            val bearerToken = authInfo.bearerToken

            // Call service-atlassian API with pagination support
            // Date formatting to CQL is handled by AtlassianApiClient
            val searchRequest =
                ConfluenceSearchRequest(
                    baseUrl = connectionDocument.baseUrl,
                    authType = authType,
                    basicUsername = username,
                    basicPassword = password,
                    bearerToken = bearerToken,
                    spaceKey = spaceKey,
                    cql = null,
                    lastModifiedSince = lastSeenUpdatedAt, // Instant type - no conversion needed
                    maxResults = maxResults,
                    startAt = startAt,
                )

            val response = atlassianClient.searchConfluencePages("connection-${connectionDocument.id}", searchRequest)

            logger.info { "Fetched ${response.pages.size} Confluence pages (total=${response.total})" }

            // Convert to ConfluencePageIndexDocument.New
            return response.pages.map { page ->
                ConfluencePageIndexDocument.New(
                    clientId = clientId,
                    connectionDocumentId = com.jervis.types.ConnectionId(connectionDocument.id),
                    pageId = page.id,
                    title = page.title,
                    spaceKey = page.spaceKey ?: "",
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
            } catch (e: Exception) {
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

    override fun getPageId(page: ConfluencePageIndexDocument): String = page.pageId

    override fun getPageUpdatedAt(page: ConfluencePageIndexDocument): Instant = page.confluenceUpdatedAt

    override suspend fun findExisting(
        connectionId: ObjectId,
        pageId: String,
    ): ConfluencePageIndexDocument? = repository.findByConnectionDocumentIdAndPageId(connectionId, pageId)

    override fun getExistingUpdatedAt(existing: ConfluencePageIndexDocument): Instant = existing.confluenceUpdatedAt

    override fun updateExisting(
        existing: ConfluencePageIndexDocument,
        newData: ConfluencePageIndexDocument,
    ): ConfluencePageIndexDocument {
        // Both existing and newData should be .New instances
        require(newData is ConfluencePageIndexDocument.New) { "newData must be ConfluencePageIndexDocument.New" }
        return newData.copy(
            id = existing.id,
            // Keep .New state to trigger re-indexing
        )
    }

    override suspend fun savePage(page: ConfluencePageIndexDocument) {
        repository.save(page)
    }
}
