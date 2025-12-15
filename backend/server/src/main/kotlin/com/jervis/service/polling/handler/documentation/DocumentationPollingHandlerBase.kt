package com.jervis.service.polling.handler.documentation

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.connection.PollingState
import com.jervis.service.connection.ConnectionService
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.types.ClientId
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Base class for documentation/wiki polling handlers (Confluence, Notion, GitBook, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - **Pagination for initial sync** (fetch ALL pages)
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
    protected val connectionService: ConnectionService,
) : PollingHandler {
    protected val logger = KotlinLogging.logger {}

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        // Convert to old signature for backwards compat
        return poll(connectionDocument, context.clients)
    }

    @Deprecated("Use PollingContext version")
    suspend fun poll(
        connectionDocument: ConnectionDocument,
        clients: List<ClientDocument>,
    ): PollingResult {
        if (connectionDocument !is ConnectionDocument.HttpConnectionDocument || connectionDocument.credentials == null) {
            logger.warn { "  → ${getSystemName()} handler: Invalid connectionDocument or credentials" }
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
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        client: ClientDocument,
    ): PollingResult {
        val credentials =
            requireNotNull(connectionDocument.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling from connectionDocument
        val state = connectionDocument.pollingStates[getToolName()]
        val spaceKey = getSpaceKey(client)

        logger.debug { "Polling ${getSystemName()} for client ${client.name}, space: $spaceKey" }

        // Fetch FULL page data from API (system-specific)
        // INITIAL SYNC (lastSeenUpdatedAt == null): Pagination to fetch ALL pages
        // INCREMENTAL SYNC: Single call for changes only (no pagination needed)
        val isInitialSync = state?.lastSeenUpdatedAt == null
        val fullPages =
            if (isInitialSync) {
                // INITIAL SYNC: Fetch ALL pages with pagination
                logger.info { "Initial sync for ${getSystemName()} client ${client.name} - fetching all pages with pagination" }
                fetchAllPagesWithPagination(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = client.id,
                    spaceKey = spaceKey,
                    batchSize = 100,
                )
            } else {
                // INCREMENTAL SYNC: Fetch only changes (no pagination, max 1000 changes per poll)
                fetchFullPages(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = client.id,
                    spaceKey = spaceKey,
                    lastSeenUpdatedAt = state.lastSeenUpdatedAt,
                    maxResults = 1000,
                )
            }

        logger.info { "Discovered ${fullPages.size} ${getSystemName()} pages for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullPage in fullPages) {
            val pageId = getPageId(fullPage)
            val pageUpdatedAt = getPageUpdatedAt(fullPage)

            // Check if already exists
            val existing = findExisting(connectionDocument.id, pageId)

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
                // New page - double-check before save (race condition protection)
                val doubleCheck = findExisting(connectionDocument.id, pageId)
                if (doubleCheck != null) {
                    logger.debug { "Page $pageId appeared during processing, skipping duplicate save" }
                    skipped++
                } else {
                    savePage(fullPage)
                    created++
                    logger.debug { "Created new page $pageId" }
                }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to connectionDocument for incremental polling next time
        val latestUpdated = fullPages.maxOfOrNull { getPageUpdatedAt(it) }
        if (latestUpdated != null) {
            val maxUpdated =
                if (state != null) {
                    maxOf(state.lastSeenUpdatedAt, latestUpdated)
                } else {
                    latestUpdated
                }

            val updatedPollingStates = connectionDocument.pollingStates.toMutableMap()
            updatedPollingStates[getToolName()] =
                PollingState.Http(
                    lastSeenUpdatedAt = maxUpdated,
                )

            val updatedConnection = connectionDocument.copy(pollingStates = updatedPollingStates)
            connectionService.save(updatedConnection)
        }

        return PollingResult(
            itemsDiscovered = fullPages.size,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    /**
     * Fetch ALL pages with pagination (for initial sync).
     * Calls fetchFullPages multiple times with startAt offset.
     * Deduplicates by page ID to prevent duplicate key errors.
     */
    private suspend fun fetchAllPagesWithPagination(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        spaceKey: String?,
        batchSize: Int,
    ): List<TPage> {
        val seenPageIds = mutableSetOf<String>()
        val allPages = mutableListOf<TPage>()
        var startAt = 0

        while (true) {
            logger.debug { "Fetching batch: startAt=$startAt, batchSize=$batchSize" }

            val batch =
                fetchFullPages(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = clientId,
                    spaceKey = spaceKey,
                    lastSeenUpdatedAt = null,
                    maxResults = batchSize,
                    startAt = startAt,
                )

            if (batch.isEmpty()) {
                logger.debug { "No more pages to fetch (empty batch)" }
                break
            }

            // Deduplicate within pagination (some APIs may return overlapping results)
            val uniqueBatch = batch.filter { page ->
                val pageId = getPageId(page)
                if (seenPageIds.contains(pageId)) {
                    logger.debug { "Skipping duplicate page $pageId in pagination batch" }
                    false
                } else {
                    seenPageIds.add(pageId)
                    true
                }
            }

            allPages.addAll(uniqueBatch)
            logger.info { "Fetched batch of ${batch.size} pages (${uniqueBatch.size} unique, total so far: ${allPages.size})" }

            // Check if we're done (heuristic: if batch < batchSize, we're at the end)
            if (batch.size < batchSize) {
                logger.info { "Last batch was smaller than batchSize, pagination complete" }
                break
            }

            startAt += batch.size

            // Safety: max 100 batches (10,000 pages with batchSize=100)
            if (startAt >= 10000) {
                logger.warn { "Reached safety limit of 10,000 pages, stopping pagination" }
                break
            }
        }

        logger.info { "Pagination complete: fetched ${allPages.size} total unique pages" }
        return allPages
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
     *
     * @param lastSeenUpdatedAt Last seen update timestamp for incremental polling.
     *                          null = first sync (fetch all pages)
     * @param startAt Pagination offset (default 0). Used during initial sync pagination.
     */
    protected abstract suspend fun fetchFullPages(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        spaceKey: String?,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int = 0,
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
    protected abstract suspend fun findExisting(
        connectionId: ObjectId,
        pageId: String,
    ): TPage?

    /**
     * Get updated timestamp from existing page
     */
    protected abstract fun getExistingUpdatedAt(existing: TPage): Instant

    /**
     * Update existing page with new data (reset state to NEW)
     */
    protected abstract fun updateExisting(
        existing: TPage,
        newData: TPage,
    ): TPage

    /**
     * Save page to repository
     */
    protected abstract suspend fun savePage(page: TPage)
}
