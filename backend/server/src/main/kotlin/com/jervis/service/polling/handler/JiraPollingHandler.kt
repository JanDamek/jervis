package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.polling.PollingResult
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Polling handler for Jira issues.
 *
 * Fetches COMPLETE issue data from Jira API and stores in MongoDB as NEW.
 * ContinuousIndexer then reads from MongoDB (no API calls) and indexes to RAG.
 */
@Component
class JiraPollingHandler(
    private val apiClient: AtlassianApiClient,
    private val repository: JiraIssueIndexMongoRepository,
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
            logger.warn { "Invalid connection or credentials for Jira polling" }
            return PollingResult(errors = 1)
        }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                val result = pollClientIssues(connection, client)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling Jira for client ${client.name}" }
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

    private suspend fun pollClientIssues(
        connection: Connection.HttpConnection,
        client: ClientDocument,
    ): PollingResult {
        val credentials = requireNotNull(connection.credentials) { "HTTP credentials required for Jira polling" }
        // Build JQL query based on last successful poll state (per connection)
        val state = pollingStateRepository.findByConnectionIdAndTool(connection.id, TOOL_JIRA)
        val jql = buildJqlQuery(client, connection, state?.lastSeenUpdatedAt)

        logger.debug { "Polling Jira for client ${client.name} with JQL: $jql" }

        // Fetch FULL issue data from API (includes comments, attachments, everything)
        val fullIssues = apiClient.searchAndFetchFullIssues(
            connection = connection,
            credentials = credentials,
            clientId = client.id,
            jql = jql,
            maxResults = 100
        )

        logger.info { "Discovered ${fullIssues.size} Jira issues for client ${client.name}" }

        var created = 0
        var skipped = 0

        for (fullIssue in fullIssues) {
            // Check if already exists
            val existing = repository.findByConnectionIdAndIssueKey(connection.id, fullIssue.issueKey)

            if (existing != null && existing.jiraUpdatedAt >= fullIssue.jiraUpdatedAt) {
                // No changes since last poll
                skipped++
                continue
            }

            if (existing != null) {
                // Update existing document with new data, reset to NEW state
                val updated = fullIssue.copy(
                    id = existing.id,
                    state = "NEW", // Re-index because data changed
                )
                repository.save(updated)
                created++
                logger.debug { "Updated issue ${fullIssue.issueKey} (changed since last poll)" }
            } else {
                // New issue - save with NEW state
                repository.save(fullIssue)
                created++
                logger.debug { "Created new issue ${fullIssue.issueKey}" }
            }
        }

        logger.info { "Jira polling for ${client.name}: created/updated=$created, skipped=$skipped" }

        // Persist latest seen updatedAt to Mongo for incremental polling next time
        val latestUpdated = fullIssues.maxOfOrNull { it.jiraUpdatedAt }
        if (latestUpdated != null) {
            val updatedState =
                if (state == null) {
                    com.jervis.entity.polling.PollingStateDocument(
                        connectionId = connection.id,
                        tool = TOOL_JIRA,
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
            itemsSkipped = skipped
        )
    }

    private fun buildJqlQuery(
        client: ClientDocument,
        connection: Connection.HttpConnection,
        lastSeenUpdatedAt: Instant?,
    ): String {
        // NOTE:
        // We do not have per-connection filters yet. We rely on lastSeenUpdatedAt
        // to build an incremental query. If not available, default to last 7 days.

        val timeFilter =
            lastSeenUpdatedAt?.let { ts ->
                val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
                "updated >= \"${fmt.format(ts)}\""
            } ?: "updated >= -7d"

        // No per-connection filters; default to time-based incremental polling only
        return timeFilter
    }

    companion object {
        private const val TOOL_JIRA = "JIRA"
    }
}
