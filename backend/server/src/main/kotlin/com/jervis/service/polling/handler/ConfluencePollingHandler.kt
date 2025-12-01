package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.repository.ConfluencePageIndexMongoRepository
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.polling.PollingResult
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Polling handler for Confluence pages.
 *
 * Fetches COMPLETE page data from Confluence API and stores in MongoDB as NEW.
 * ContinuousIndexer then reads from MongoDB (no API calls) and indexes to RAG.
 */
@Component
class ConfluencePollingHandler(
    private val apiClient: AtlassianApiClient,
    private val repository: ConfluencePageIndexMongoRepository,
    private val pollingStateRepository: PollingStateMongoRepository,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.HttpConnection &&
            connection.baseUrl.contains("atlassian.net")
    }

    override suspend fun poll(
        connection: Connection,
        clients: List<ClientDocument>,
    ): PollingResult {
        if (connection !is Connection.HttpConnection || connection.credentials == null) {
            logger.warn { "Invalid connection or credentials for Confluence polling" }
            return PollingResult(errors = 1)
        }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                val result = pollClientPages(connection, client)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling Confluence for client ${client.name}" }
                totalErrors++
            }
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors
        )
    }

    private suspend fun pollClientPages(
        connection: Connection.HttpConnection,
        client: ClientDocument,
    ): PollingResult {
        val credentials = requireNotNull(connection.credentials) { "HTTP credentials required for Confluence polling" }
        
        // Build CQL query based on last successful poll state (per connection)
        val state = pollingStateRepository.findByConnectionIdAndTool(connection.id, TOOL_CONFLUENCE)
        val spaceKey = client.confluenceSpaceKey

        logger.debug { "Polling Confluence for client ${client.name}, space: $spaceKey" }

        // Fetch FULL page data from API (includes content, comments, attachments, everything)
        val fullPages = apiClient.searchAndFetchFullPages(
            connection = connection,
            credentials = credentials,
            clientId = client.id,
            spaceKey = spaceKey,
            maxResults = 100
        )

        logger.info { "Discovered ${fullPages.size} Confluence pages for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullPage in fullPages) {
            // Check if already exists
            val existing = repository.findByConnectionIdAndPageId(connection.id, fullPage.pageId)

            if (existing != null && existing.confluenceUpdatedAt >= fullPage.confluenceUpdatedAt) {
                // No changes since last poll
                skipped++
                continue
            }

            if (existing != null) {
                // Update existing document with new data, reset to NEW state
                val updated = fullPage.copy(
                    id = existing.id,
                    state = "NEW", // Re-index because data changed
                )
                repository.save(updated)
                created++
                logger.debug { "Updated page ${fullPage.pageId} (changed since last poll)" }
            } else {
                // New page - save with NEW state
                repository.save(fullPage)
                created++
                logger.debug { "Created new page ${fullPage.pageId}" }
            }
        }

        logger.info { "Confluence polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to Mongo for incremental polling next time
        val latestUpdated = fullPages.maxOfOrNull { it.confluenceUpdatedAt }
        if (latestUpdated != null) {
            val updatedState =
                if (state == null) {
                    com.jervis.entity.polling.PollingStateDocument(
                        connectionId = connection.id,
                        tool = TOOL_CONFLUENCE,
                        lastSeenUpdatedAt = latestUpdated,
                    )
                } else {
                    state.copy(
                        lastSeenUpdatedAt = maxOf(state.lastSeenUpdatedAt ?: Instant.EPOCH, latestUpdated),
                        updatedAt = Instant.now(),
                    )
                }
            pollingStateRepository.save(updatedState)
        }

        return PollingResult(
            itemsDiscovered = fullPages.size,
            itemsCreated = created,
            itemsSkipped = skipped
        )
    }

    companion object {
        private const val TOOL_CONFLUENCE = "CONFLUENCE"
    }
}
