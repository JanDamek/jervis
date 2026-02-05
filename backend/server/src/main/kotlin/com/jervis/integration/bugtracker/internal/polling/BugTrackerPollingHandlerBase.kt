package com.jervis.integration.bugtracker.internal.polling

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.service.client.ClientService
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.service.polling.handler.ResourceFilter
import mu.KotlinLogging
import java.time.Instant

/**
 * Base class for bug tracker polling handlers (Atlassian Jira, YouTrack, Mantis, GitHub Issues, GitLab Issues, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - **Pagination for initial sync** (fetch ALL issues)
 * - Incremental polling using lastSeenUpdatedAt state
 * - Generic issue processing and deduplication
 *
 * System-specific implementations (Atlassian Jira, YouTrack, etc.) only handle:
 * - API client calls
 * - Query building (JQL, YouTrack query, etc.)
 * - Issue data transformation to common format
 * - Repository-specific operations
 */
abstract class BugTrackerPollingHandlerBase<TIssue : Any>(
    protected val pollingStateService: PollingStateService,
    protected val clientService: ClientService,
) {
    protected val logger = KotlinLogging.logger {}

    suspend fun poll(
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

        suspend fun pollClientIssuesAsync(
            client: ClientDocument,
            project: ProjectDocument?,
        ) {
            // Check capability configuration - get resource filter
            val resourceFilter =
                if (project != null) {
                    context.getProjectResourceFilter(project.id, client.id, ConnectionCapability.BUGTRACKER)
                } else {
                    context.getResourceFilter(client.id, ConnectionCapability.BUGTRACKER)
                }

            // Skip if capability is disabled (null filter)
            if (resourceFilter == null) {
                logger.debug {
                    "    Skipping ${getSystemName()} for ${if (project != null) "project ${project.name}" else "client ${client.name}"}: " +
                        "BUGTRACKER capability disabled"
                }
                return
            }

            try {
                logger.debug { "    Polling ${getSystemName()} for client: ${client.name}" }
                val result = pollClientIssues(connectionDocument, client, project, resourceFilter)
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

        for (client in context.clients) {
            pollClientIssuesAsync(client, null)
        }
        for (project in context.projects) {
            pollClientIssuesAsync(clientService.getClientById(project.clientId), project)
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
     *
     * @param resourceFilter Filter to determine which projects/issues to index
     */
    private suspend fun pollClientIssues(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        project: ProjectDocument?,
        resourceFilter: ResourceFilter,
    ): PollingResult {
        val credentials =
            requireNotNull(connectionDocument.credentials) { "HTTP credentials required for ${getSystemName()} polling" }

        // Get last polling state for incremental polling
        val state = pollingStateService.getState(connectionDocument.id, connectionDocument.provider)
        val query = buildQuery(client, connectionDocument, state?.lastSeenUpdatedAt, resourceFilter)

        logger.debug { "Polling ${getSystemName()} for client ${client.name} with query: $query" }

        val fullIssues =
            fetchFullIssues(
                connectionDocument = connectionDocument,
                credentials = credentials,
                clientId = client.id,
                projectId = project?.id,
                query = query,
                lastSeenUpdatedAt = state?.lastSeenUpdatedAt,
            )

        logger.info { "Discovered ${fullIssues.size} ${getSystemName()} issues for client ${client.name}" }

        var created = 0
        var skipped = 0
        var latestUpdatedAt: Instant? = null

        for ((index, fullIssue) in fullIssues.withIndex()) {
            if (findExisting(connectionDocument.id, fullIssue)) {
                skipped++
                continue
            }

            saveIssue(fullIssue)
            created++

            // Track latest updated timestamp
            val issueUpdated = getIssueUpdatedAt(fullIssue)
            latestUpdatedAt = latestUpdatedAt?.let { maxOf(it, issueUpdated) } ?: issueUpdated

            // Save progress every 100 items to prevent re-downloading on interruption
            if ((index + 1) % 100 == 0) {
                val maxUpdated = state?.lastSeenUpdatedAt?.let { maxOf(it, latestUpdatedAt) } ?: latestUpdatedAt
                pollingStateService.updateWithTimestamp(connectionDocument.id, connectionDocument.provider, maxUpdated)
                logger.debug { "${getSystemName()} progress saved: processed ${index + 1}/${fullIssues.size}" }
            }
        }

        logger.info { "${getSystemName()} polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Always update lastSeenUpdatedAt - even if no issues found
        // Use latest issue timestamp if available, otherwise use current time to mark polling completion
        val finalUpdatedAt = latestUpdatedAt ?: state?.lastSeenUpdatedAt ?: Instant.now()
        val maxUpdated = state?.lastSeenUpdatedAt?.let { maxOf(it, finalUpdatedAt) } ?: finalUpdatedAt
        pollingStateService.updateWithTimestamp(connectionDocument.id, connectionDocument.provider, maxUpdated)
        logger.debug { "${getSystemName()} polling state saved: lastSeenUpdatedAt=$maxUpdated" }

        return PollingResult(
            itemsDiscovered = fullIssues.size,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    /**
     * Get bug tracker system name for logging (Atlassian Jira, YouTrack, etc.)
     */
    protected abstract fun getSystemName(): String

    /**
     * Get tool name for polling state storage (BUGTRACKER, WIKI, etc.)
     */
    protected abstract fun getToolName(): String

    /**
     * Build query for fetching issues (JQL for Atlassian Jira, YouTrack query, etc.)
     *
     * @param resourceFilter Filter to restrict which projects to query (if IndexSelected, filter by project keys)
     */
    protected abstract fun buildQuery(
        client: ClientDocument?,
        connectionDocument: ConnectionDocument,
        lastSeenUpdatedAt: Instant?,
        resourceFilter: ResourceFilter,
    ): String

    /**
     * Fetch full issue data from API (system-specific).
     *
     * @param lastSeenUpdatedAt Last seen update timestamp for incremental polling.
     *                          null = first sync (fetch all open issues)
     */
    protected abstract suspend fun fetchFullIssues(
        connectionDocument: ConnectionDocument,
        credentials: HttpCredentials,
        clientId: ClientId,
        projectId: ProjectId?,
        query: String,
        lastSeenUpdatedAt: Instant?,
    ): List<TIssue>

    /**
     * Get issue updated timestamp
     */
    protected abstract fun getIssueUpdatedAt(issue: TIssue): Instant

    /**
     * Find existing issue in repository by full unique key.
     * For Atlassian Jira: must use (connectionId, issueKey, latestChangelogId)
     * because each changelog entry is a separate record.
     */
    protected abstract suspend fun findExisting(
        connectionId: ConnectionId,
        issue: TIssue,
    ): Boolean

    /**
     * Save issue to repository.
     * Each changelog entry is saved as a separate record.
     * If same changelogId already exists, MongoDB unique index will prevent duplicate.
     */
    protected abstract suspend fun saveIssue(issue: TIssue)
}
