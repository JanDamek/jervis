package com.jervis.service.polling.handler.bugtracker

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
 * Base class for bug tracker polling handlers (Jira, YouTrack, Mantis, GitHub Issues, GitLab Issues, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - **Pagination for initial sync** (fetch ALL issues)
 * - Incremental polling using lastSeenUpdatedAt state
 * - Generic issue processing and deduplication
 *
 * System-specific implementations (Jira, YouTrack, etc.) only handle:
 * - API client calls
 * - Query building (JQL, YouTrack query, etc.)
 * - Issue data transformation to common format
 * - Repository-specific operations
 */
abstract class BugTrackerPollingHandlerBase<TIssue : Any, TRepository : Any>(
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
                val result = pollClientIssues(connectionDocument, client)
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
     * Poll issues for a single client.
     */
    private suspend fun pollClientIssues(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        client: ClientDocument,
    ): PollingResult {
        val credentials =
            requireNotNull(connectionDocument.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling from connectionDocument
        val state = connectionDocument.pollingStates[getToolName()]
        val query = buildQuery(client, connectionDocument, state?.lastSeenUpdatedAt)

        logger.debug { "Polling ${getSystemName()} for client ${client.name} with query: $query" }

        // Fetch FULL issue data from API (system-specific)
        // INITIAL SYNC (lastSeenUpdatedAt == null): Pagination to fetch ALL issues
        // INCREMENTAL SYNC: Single call for changes only (no pagination needed)
        val isInitialSync = state?.lastSeenUpdatedAt == null
        val fullIssues =
            if (isInitialSync) {
                // INITIAL SYNC: Fetch ALL issues with pagination
                logger.info { "Initial sync for ${getSystemName()} client ${client.name} - fetching all issues with pagination" }
                fetchAllIssuesWithPagination(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = client.id,
                    query = query,
                    batchSize = 100,
                )
            } else {
                // INCREMENTAL SYNC: Fetch only changes (no pagination, max 1000 changes per poll)
                fetchFullIssues(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = client.id,
                    query = query,
                    lastSeenUpdatedAt = state.lastSeenUpdatedAt,
                    maxResults = 1000,
                )
            }

        logger.info { "Discovered ${fullIssues.size} ${getSystemName()} issues for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullIssue in fullIssues) {
            val issueId = getIssueId(fullIssue)
            val issueUpdatedAt = getIssueUpdatedAt(fullIssue)

            // Check if already exists
            val existing = findExisting(connectionDocument.id, issueId)

            if (existing != null && getExistingUpdatedAt(existing) >= issueUpdatedAt) {
                // No changes since last poll
                skipped++
                continue
            }

            if (existing != null) {
                // Update existing document with new data, reset to NEW state
                val updated = updateExisting(existing, fullIssue)
                saveIssue(updated)
                created++
                logger.debug { "Updated issue $issueId (changed since last poll)" }
            } else {
                // New issue - double-check before save (race condition protection)
                val doubleCheck = findExisting(connectionDocument.id, issueId)
                if (doubleCheck != null) {
                    logger.debug { "Issue $issueId appeared during processing, skipping duplicate save" }
                    skipped++
                } else {
                    saveIssue(fullIssue)
                    created++
                    logger.debug { "Created new issue $issueId" }
                }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to connectionDocument for incremental polling next time
        val latestUpdated = fullIssues.maxOfOrNull { getIssueUpdatedAt(it) }
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
            itemsDiscovered = fullIssues.size,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    /**
     * Fetch ALL issues with pagination (for initial sync).
     * Calls fetchFullIssues multiple times with startAt offset.
     * Deduplicates by issue ID to prevent duplicate key errors.
     */
    private suspend fun fetchAllIssuesWithPagination(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        query: String,
        batchSize: Int,
    ): List<TIssue> {
        val seenIssueIds = mutableSetOf<String>()
        val allIssues = mutableListOf<TIssue>()
        var startAt = 0

        while (true) {
            logger.debug { "Fetching batch: startAt=$startAt, batchSize=$batchSize" }

            val batch =
                fetchFullIssues(
                    connectionDocument = connectionDocument,
                    credentials = credentials,
                    clientId = clientId,
                    query = query,
                    lastSeenUpdatedAt = null,
                    maxResults = batchSize,
                    startAt = startAt,
                )

            if (batch.isEmpty()) {
                logger.debug { "No more issues to fetch (empty batch)" }
                break
            }

            // Deduplicate within pagination (some APIs may return overlapping results)
            val uniqueBatch = batch.filter { issue ->
                val issueId = getIssueId(issue)
                if (seenIssueIds.contains(issueId)) {
                    logger.debug { "Skipping duplicate issue $issueId in pagination batch" }
                    false
                } else {
                    seenIssueIds.add(issueId)
                    true
                }
            }

            allIssues.addAll(uniqueBatch)
            logger.info { "Fetched batch of ${batch.size} issues (${uniqueBatch.size} unique, total so far: ${allIssues.size})" }

            // Check if we're done (heuristic: if batch < batchSize, we're at the end)
            if (batch.size < batchSize) {
                logger.info { "Last batch was smaller than batchSize, pagination complete" }
                break
            }

            startAt += batch.size

            // Safety: max 100 batches (10,000 issues with batchSize=100)
            if (startAt >= 10000) {
                logger.warn { "Reached safety limit of 10,000 issues, stopping pagination" }
                break
            }
        }

        logger.info { "Pagination complete: fetched ${allIssues.size} total unique issues" }
        return allIssues
    }

    /**
     * Get bug tracker system name for logging (Jira, YouTrack, etc.)
     */
    protected abstract fun getSystemName(): String

    /**
     * Get tool name for polling state storage (JIRA, CONFLUENCE, etc.)
     */
    protected abstract fun getToolName(): String

    /**
     * Build query for fetching issues (JQL for Jira, YouTrack query, etc.)
     */
    protected abstract fun buildQuery(
        client: ClientDocument,
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        lastSeenUpdatedAt: Instant?,
    ): String

    /**
     * Fetch full issue data from API (system-specific).
     *
     * @param lastSeenUpdatedAt Last seen update timestamp for incremental polling.
     *                          null = first sync (fetch all open issues)
     * @param startAt Pagination offset (default 0). Used during initial sync pagination.
     */
    protected abstract suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument.HttpConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        query: String,
        lastSeenUpdatedAt: Instant?,
        maxResults: Int,
        startAt: Int = 0,
    ): List<TIssue>

    /**
     * Get unique issue identifier (issue key, ID, etc.)
     */
    protected abstract fun getIssueId(issue: TIssue): String

    /**
     * Get issue updated timestamp
     */
    protected abstract fun getIssueUpdatedAt(issue: TIssue): Instant

    /**
     * Find existing issue in repository
     */
    protected abstract suspend fun findExisting(
        connectionId: ObjectId,
        issueId: String,
    ): TIssue?

    /**
     * Get updated timestamp from existing issue
     */
    protected abstract fun getExistingUpdatedAt(existing: TIssue): Instant

    /**
     * Update existing issue with new data (reset state to NEW)
     */
    protected abstract fun updateExisting(
        existing: TIssue,
        newData: TIssue,
    ): TIssue

    /**
     * Save issue to repository
     */
    protected abstract suspend fun saveIssue(issue: TIssue)
}
