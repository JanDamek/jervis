package com.jervis.service.polling.handler.bugtracker

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
 * Base class for bug tracker polling handlers (Jira, YouTrack, Mantis, GitHub Issues, GitLab Issues, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - Incremental polling using lastSeenUpdatedAt state
 * - Connection filtering support (future: per-connection filters)
 * - Generic issue processing and deduplication
 *
 * System-specific implementations (Jira, YouTrack, etc.) only handle:
 * - API client calls
 * - Query building (JQL, YouTrack query, etc.)
 * - Issue data transformation to common format
 * - Repository-specific operations
 */
abstract class BugTrackerPollingHandlerBase<TIssue : Any, TRepository : Any>(
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
                val result = pollClientIssues(connection, client)
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
        connection: Connection.HttpConnection,
        client: ClientDocument,
    ): PollingResult {
        val credentials = requireNotNull(connection.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling
        val state = pollingStateRepository.findByConnectionIdAndTool(connection.id, getToolName())
        val query = buildQuery(client, connection, state?.lastSeenUpdatedAt)

        logger.debug { "Polling ${getSystemName()} for client ${client.name} with query: $query" }

        // Fetch FULL issue data from API (system-specific)
        val fullIssues = fetchFullIssues(
            connection = connection,
            credentials = credentials,
            clientId = client.id,
            query = query,
            maxResults = 100
        )

        logger.info { "Discovered ${fullIssues.size} ${getSystemName()} issues for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullIssue in fullIssues) {
            val issueId = getIssueId(fullIssue)
            val issueUpdatedAt = getIssueUpdatedAt(fullIssue)

            // Check if already exists
            val existing = findExisting(connection.id, issueId)

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
                // New issue - save with NEW state
                saveIssue(fullIssue)
                created++
                logger.debug { "Created new issue $issueId" }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to Mongo for incremental polling next time
        val latestUpdated = fullIssues.maxOfOrNull { getIssueUpdatedAt(it) }
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
     * Get tool name for polling state storage (JIRA, YOUTRACK, etc.)
     */
    protected abstract fun getToolName(): String

    /**
     * Build query for fetching issues (JQL for Jira, YouTrack query, etc.)
     */
    protected abstract fun buildQuery(
        client: ClientDocument,
        connection: Connection.HttpConnection,
        lastSeenUpdatedAt: Instant?,
    ): String

    /**
     * Fetch full issue data from API (system-specific).
     */
    protected abstract suspend fun fetchFullIssues(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials,
        clientId: ObjectId,
        query: String,
        maxResults: Int,
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
    protected abstract suspend fun findExisting(connectionId: ObjectId, issueId: String): TIssue?

    /**
     * Get updated timestamp from existing issue
     */
    protected abstract fun getExistingUpdatedAt(existing: TIssue): Instant

    /**
     * Update existing issue with new data (reset state to NEW)
     */
    protected abstract fun updateExisting(existing: TIssue, newData: TIssue): TIssue

    /**
     * Save issue to repository
     */
    protected abstract suspend fun saveIssue(issue: TIssue)
}
