package com.jervis.service.polling.handler.documentation

import com.jervis.entity.ClientDocument
import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.repository.ConfluencePageIndexMongoRepository
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Confluence page polling handler.
 *
 * System-specific implementation:
 * - Uses Atlassian REST API
 * - Supports space-based filtering
 * - Fetches complete page data (content, comments, attachments)
 * - Supports incremental polling via lastSeenUpdatedAt
 *
 * Shared logic (orchestration, deduplication, state management) is in DocumentationPollingHandlerBase.
 */
@Component
class ConfluencePollingHandler(
    private val apiClient: AtlassianApiClient,
    private val repository: ConfluencePageIndexMongoRepository,
    pollingStateRepository: PollingStateMongoRepository,
) : DocumentationPollingHandlerBase<ConfluencePageIndexDocument, ConfluencePageIndexMongoRepository>(
    pollingStateRepository = pollingStateRepository,
) {

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.HttpConnection &&
            connection.baseUrl.contains("atlassian.net")
    }

    override fun getSystemName(): String = "Confluence"

    override fun getToolName(): String = TOOL_CONFLUENCE

    override fun getSpaceKey(client: ClientDocument): String? = client.confluenceSpaceKey

    override suspend fun fetchFullPages(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials,
        clientId: ObjectId,
        spaceKey: String?,
        maxResults: Int,
    ): List<ConfluencePageIndexDocument> {
        return apiClient.searchAndFetchFullPages(
            connection = connection,
            credentials = credentials,
            clientId = clientId,
            spaceKey = spaceKey,
            maxResults = maxResults
        )
    }

    override fun getPageId(page: ConfluencePageIndexDocument): String = page.pageId

    override fun getPageUpdatedAt(page: ConfluencePageIndexDocument): Instant = page.confluenceUpdatedAt

    override suspend fun findExisting(connectionId: ObjectId, pageId: String): ConfluencePageIndexDocument? {
        return repository.findByConnectionIdAndPageId(connectionId, pageId)
    }

    override fun getExistingUpdatedAt(existing: ConfluencePageIndexDocument): Instant = existing.confluenceUpdatedAt

    override fun updateExisting(
        existing: ConfluencePageIndexDocument,
        newData: ConfluencePageIndexDocument,
    ): ConfluencePageIndexDocument {
        return newData.copy(
            id = existing.id,
            state = "NEW", // Re-index because data changed
        )
    }

    override suspend fun savePage(page: ConfluencePageIndexDocument) {
        repository.save(page)
    }

    companion object {
        private const val TOOL_CONFLUENCE = "CONFLUENCE"
    }
}
