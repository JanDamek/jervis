package com.jervis.service.polling.handler.documentation

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.polling.PollingStateDocument
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingHandler
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Base class for documentation/wiki polling handlers (Confluence, Notion, GitBook, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - Incremental polling using lastSeenUpdatedAt state
 * - Space/workspace filtering support
 * - Generic page processing and deduplication
 *
 * System-specific implementations (Confluence, Notion, etc.) only handle:
 * - API client calls
 * - Query building (CQL, Notion filters, etc.)
 * - Page data transformation to common format
 * - Repository-specific operations
 */
abstract class DocumentationPollingHandlerBase<TPage : Any, TRepository : Any>(
    protected val pollingStateRepository: PollingStateMongoRepository,
) : PollingHandler {
    protected val logger = KotlinLogging.logger {}

    override suspend fun poll(
        connection: Connection,
        clients: List<ClientDocument>,
    ): PollingResult {
        if (connection !is Connection.HttpConnection || connection.credentials == null) {
            logger.warn { "  → ${getSystemName()} handler: Invalid connection or credentials" }
            return PollingResult(errors = 1)
        }

        logger.info { "  → ${getSystemName()} handler polling ${clients.size} client(s)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                logger.debug { "    Polling ${getSystemName()} for client: ${client.name}" }
                val result = pollClientPages(connection, client)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors

                if (result.itemsCreated > 0 || result.itemsDiscovered > 0) {
                    logger.info {
                        "    ${client.name}: discovered=${result.itemsDiscovered}, " +
                        "created=${result.itemsCreated}, skipped=${result.itemsSkipped}"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "    Error polling ${getSystemName()} for client ${client.name}" }
                totalErrors++
            }
        }

        logger.info {
            "  ← ${getSystemName()} handler completed | " +
            "Total: discovered=$totalDiscovered, created=$totalCreated, skipped=$totalSkipped, errors=$totalErrors"
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors
        )
    }

    /**
     * Poll pages for a single client.
     */
    private suspend fun pollClientPages(
        connection: Connection.HttpConnection,
        client: ClientDocument,
    ): PollingResult {
        val credentials = requireNotNull(connection.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling
        val state = pollingStateRepository.findByConnectionIdAndTool(connection.id, getToolName())
        val spaceKey = getSpaceKey(client)

        logger.debug { "Polling ${getSystemName()} for client ${client.name}, space: $spaceKey" }

        // Fetch FULL page data from API (system-specific)
        val fullPages = fetchFullPages(
            connection = connection,
            credentials = credentials,
            clientId = client.id,
            spaceKey = spaceKey,
            maxResults = 100
        )

        logger.info { "Discovered ${fullPages.size} ${getSystemName()} pages for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullPage in fullPages) {
            val pageId = getPageId(fullPage)
            val pageUpdatedAt = getPageUpdatedAt(fullPage)

            // Check if already exists
            val existing = findExisting(connection.id, pageId)

            if (existing != null && getExistingUpdatedAt(existing) >= pageUpdatedAt) {
                // No changes since last poll
                skipped++
                continue
            }

            if (existing != null) {
                // Update existing document with new data, reset to NEW state
                val updated = updateExisting(existing, fullPage)
                savePage(updated)
                created++
                logger.debug { "Updated page $pageId (changed since last poll)" }
            } else {
                // New page - save with NEW state
                savePage(fullPage)
                created++
                logger.debug { "Created new page $pageId" }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to Mongo for incremental polling next time
        val latestUpdated = fullPages.maxOfOrNull { getPageUpdatedAt(it) }
        if (latestUpdated != null) {
            val updatedState =
                if (state == null) {
                    PollingStateDocument(
                        connectionId = connection.id,
                        tool = getToolName(),
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

    /**
     * Get documentation system name for logging (Confluence, Notion, etc.)
     */
    protected abstract fun getSystemName(): String

    /**
     * Get tool name for polling state storage (CONFLUENCE, NOTION, etc.)
     */
    protected abstract fun getToolName(): String

    /**
     * Get space/workspace key for the client
     */
    protected abstract fun getSpaceKey(client: ClientDocument): String?

    /**
     * Fetch full page data from API (system-specific).
     */
    protected abstract suspend fun fetchFullPages(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials,
        clientId: ObjectId,
        spaceKey: String?,
        maxResults: Int,
    ): List<TPage>

    /**
     * Get unique page identifier (page ID, slug, etc.)
     */
    protected abstract fun getPageId(page: TPage): String

    /**
     * Get page updated timestamp
     */
    protected abstract fun getPageUpdatedAt(page: TPage): Instant

    /**
     * Find existing page in repository
     */
    protected abstract suspend fun findExisting(connectionId: ObjectId, pageId: String): TPage?

    /**
     * Get updated timestamp from existing page
     */
    protected abstract fun getExistingUpdatedAt(existing: TPage): Instant

    /**
     * Update existing page with new data (reset state to NEW)
     */
    protected abstract fun updateExisting(existing: TPage, newData: TPage): TPage

    /**
     * Save page to repository
     */
    protected abstract suspend fun savePage(page: TPage)
}
