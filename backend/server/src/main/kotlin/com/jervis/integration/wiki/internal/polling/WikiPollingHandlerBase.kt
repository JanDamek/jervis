package com.jervis.integration.wiki.internal.polling

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import mu.KotlinLogging
import java.time.Instant

/**
 * Base class for documentation/wiki polling handlers (Atlassian Confluence, Notion, GitBook, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - **Pagination for initial sync** (fetch ALL pages)
 * - Incremental polling using lastSeenUpdatedAt state
 * - Space/workspace filtering support
 * - Generic page processing and deduplication
 *
 * System-specific implementations (Atlassian Confluence, Notion, etc.) only handle:
 * - API client calls
 * - Query building (CQL, Notion filters, etc.)
 * - Page data transformation to common format
 * - Repository-specific operations
 */
abstract class WikiPollingHandlerBase<TPage : Any>(
    protected val pollingStateService: PollingStateService,
) : PollingHandler {
    protected val logger = KotlinLogging.logger {}

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        if (connectionDocument.connectionType != ConnectionDocument.ConnectionTypeEnum.HTTP || connectionDocument.credentials == null) {
            logger.warn { "  → ${getSystemName()} handler: Invalid connectionDocument or credentials" }
            return PollingResult(errors = 1)
        }

        logger.info { "  → ${getSystemName()} handler polling ${context.clients.size} client(s)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in context.clients) {
            try {
                logger.debug { "    Polling ${getSystemName()} for client: ${client.name}" }
                val result = pollClientPages(connectionDocument, client)
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
            errors = totalErrors,
        )
    }

    /**
     * Poll pages for a single client.
     */
    private suspend fun pollClientPages(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
    ): PollingResult {
        val credentials =
            requireNotNull(connectionDocument.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling
        val state = pollingStateService.getState(connectionDocument.id, getToolName())
        val spaceKey = getSpaceKey(client)

        logger.debug { "Polling ${getSystemName()} for client ${client.name}, space: $spaceKey" }

        val fullPages =
            fetchFullPages(
                connectionDocument = connectionDocument,
                credentials = credentials,
                clientId = client.id,
                spaceKey = spaceKey,
                lastSeenUpdatedAt = state?.lastSeenUpdatedAt,
                maxResults = 1000,
            )

        logger.info { "Discovered ${fullPages.size} ${getSystemName()} pages for client ${client.name}" }

        var created = 0
        var skipped = 0
        var latestUpdatedAt: Instant? = null

        for ((index, fullPage) in fullPages.withIndex()) {
            if (findExisting(connectionDocument.id, fullPage)) {
                skipped++
                continue
            }

            savePage(fullPage)
            created++

            // Track latest updated timestamp
            val pageUpdated = getPageUpdatedAt(fullPage)
            latestUpdatedAt = latestUpdatedAt?.let { maxOf(it, pageUpdated) } ?: pageUpdated

            // Save progress every 100 items to prevent re-downloading on interruption
            if ((index + 1) % 100 == 0) {
                val maxUpdated = state?.lastSeenUpdatedAt?.let { maxOf(it, latestUpdatedAt) } ?: latestUpdatedAt
                pollingStateService.updateWithTimestamp(connectionDocument.id, getToolName(), maxUpdated)
                logger.debug { "${getSystemName()} progress saved: processed ${index + 1}/${fullPages.size}" }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Always update lastSeenUpdatedAt - even if no pages found
        // Use latest page timestamp if available, otherwise use current time to mark polling completion
        val finalUpdatedAt = latestUpdatedAt ?: state?.lastSeenUpdatedAt ?: Instant.now()
        val maxUpdated = state?.lastSeenUpdatedAt?.let { maxOf(it, finalUpdatedAt) } ?: finalUpdatedAt
        pollingStateService.updateWithTimestamp(connectionDocument.id, getToolName(), maxUpdated)
        logger.debug { "${getSystemName()} polling state saved: lastSeenUpdatedAt=$maxUpdated" }

        return PollingResult(
            itemsDiscovered = fullPages.size,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    /**
     * Get documentation system name for logging (Atlassian Confluence, Notion, etc.)
     */
    protected abstract fun getSystemName(): String

    /**
     * Get tool name for polling state storage (BUGTRACKER, WIKI, etc.)
     */
    protected abstract fun getToolName(): String

    /**
     * Get space/workspace key for the client
     */
    protected abstract fun getSpaceKey(client: ClientDocument): String?

    /**
     * Fetch full page data from API (system-specific).
     *
     * @param lastSeenUpdatedAt Last seen update timestamp for incremental polling.
     *                          null = first sync (fetch all pages)
     * @param startAt Pagination offset (default 0). Used during initial sync pagination.
     */
    protected abstract suspend fun fetchFullPages(
        connectionDocument: ConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        spaceKey: String?,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int = 0,
    ): List<TPage>

    /**
     * Get page updated timestamp
     */
    protected abstract fun getPageUpdatedAt(page: TPage): Instant

    /**
     * Find existing page in repository by full unique key.
     * For Atlassian Confluence: must use (connectionId, pageId, versionNumber)
     * because each page version is a separate record.
     */
    protected abstract suspend fun findExisting(
        connectionId: ConnectionId,
        page: TPage,
    ): Boolean

    /**
     * Save page to repository.
     * Each page version is saved as a separate record.
     * If same version already exists, MongoDB unique index will prevent duplicate.
     */
    protected abstract suspend fun savePage(page: TPage)
}
