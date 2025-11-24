package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.polling.PollingResult
import mu.KotlinLogging
import org.springframework.stereotype.Component

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
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.HttpConnection &&
            connection.baseUrl.contains("atlassian.net")
    }

    override suspend fun poll(
        connection: Connection,
        credentials: HttpCredentials?,
        clients: List<ClientDocument>,
    ): PollingResult {
        if (connection !is Connection.HttpConnection || credentials == null) {
            logger.warn { "Invalid connection or credentials for Jira polling" }
            return PollingResult(errors = 1)
        }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                val result = pollClientIssues(connection, credentials, client)
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
        credentials: HttpCredentials,
        client: ClientDocument,
    ): PollingResult {
        // Build JQL query based on client configuration
        val jql = buildJqlQuery(client)

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

        return PollingResult(
            itemsDiscovered = fullIssues.size,
            itemsCreated = created,
            itemsSkipped = skipped
        )
    }

    private fun buildJqlQuery(client: ClientDocument): String {
        // Build JQL based on client configuration
        val projectFilter = if (client.atlassianJiraProjects.isNotEmpty()) {
            "project IN (${client.atlassianJiraProjects.joinToString(",") { "'$it'" }})"
        } else {
            null
        }

        val dateFilter = "updated >= -7d" // Last 7 days

        return listOfNotNull(projectFilter, dateFilter)
            .joinToString(" AND ")
            .ifEmpty { "updated >= -7d" }
    }
}
