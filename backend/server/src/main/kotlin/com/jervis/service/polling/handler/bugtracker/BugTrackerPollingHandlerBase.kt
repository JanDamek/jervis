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
import com.jervis.types.ConnectionId
import mu.KotlinLogging
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
abstract class BugTrackerPollingHandlerBase<TIssue : Any>(
    protected val connectionService: ConnectionService,
) : PollingHandler {
    protected val logger = KotlinLogging.logger {}

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        if (connectionDocument !is ConnectionDocument.HttpConnectionDocument || connectionDocument.credentials == null) {
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

        val fullIssues =
            fetchFullIssues(
                connectionDocument = connectionDocument,
                credentials = credentials,
                clientId = client.id,
                query = query,
                lastSeenUpdatedAt = state?.lastSeenUpdatedAt,
                maxResults = 1000,
            )

        logger.info { "Discovered ${fullIssues.size} ${getSystemName()} issues for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullIssue in fullIssues) {
            val existing = findExisting(connectionDocument.id, fullIssue)
            if (existing != null) {
                skipped++
                continue
            }

            saveIssue(fullIssue)
            created++
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        fullIssues.maxOfOrNull { getIssueUpdatedAt(it) }?.let { latestUpdated ->
            val maxUpdated = state?.lastSeenUpdatedAt?.let { maxOf(it, latestUpdated) } ?: latestUpdated
            val updatedPollingStates =
                connectionDocument.pollingStates + (getToolName() to PollingState.Http(maxUpdated))
            connectionService.save(connectionDocument.copy(pollingStates = updatedPollingStates))
        }

        return PollingResult(
            itemsDiscovered = fullIssues.size,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
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
     * Get issue updated timestamp
     */
    protected abstract fun getIssueUpdatedAt(issue: TIssue): Instant

    /**
     * Find existing issue in repository by full unique key.
     * For Jira: must use (connectionId, issueKey, latestChangelogId)
     * because each changelog entry is a separate record.
     */
    protected abstract suspend fun findExisting(
        connectionId: ConnectionId,
        issue: TIssue,
    ): TIssue?

    /**
     * Save issue to repository.
     * Each changelog entry is saved as a separate record.
     * If same changelogId already exists, MongoDB unique index will prevent duplicate.
     */
    protected abstract suspend fun saveIssue(issue: TIssue)
}
