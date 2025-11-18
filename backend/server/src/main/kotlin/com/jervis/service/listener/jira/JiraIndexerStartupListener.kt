package com.jervis.service.listener.jira

import com.jervis.repository.mongo.AtlassianConnectionMongoRepository
import com.jervis.service.jira.JiraIndexingOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Starts continuous URL queue processors for all active Atlassian connections on application startup.
 * Launches Jira queue processors for connections with VALID auth status.
 *
 * Architecture:
 * - Launches one queue processor per VALID connection
 * - Each processor polls LinkIndexingQueue for Jira URLs discovered by other indexers
 * - JiraPollingScheduler handles regular issue indexing
 * - This listener ensures queue processors are running to handle cross-indexer URLs
 */
@Component
class JiraIndexerStartupListener(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val orchestrator: JiraIndexingOrchestrator,
) {
    private val indexerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @EventListener(ApplicationReadyEvent::class)
    fun startQueueProcessors() =
        runBlocking {
            logger.info { "Starting Jira queue processors for all VALID Atlassian connections..." }

            val validConnections =
                connectionRepository
                    .findAll()
                    .toList()
                    .filter { it.authStatus == "VALID" }

            logger.info { "Found ${validConnections.size} VALID Atlassian connections" }

            validConnections.forEach { connection ->
                logger.info { "Launching Jira queue processor for client ${connection.clientId.toHexString()} (tenant: ${connection.tenant})" }
                indexerScope.launch {
                    runCatching { orchestrator.processQueuedUrls(connection.clientId) }
                        .onFailure { e -> logger.error(e) { "Jira queue processor crashed for client ${connection.clientId.toHexString()}" } }
                }
            }

            logger.info { "All Jira queue processors launched successfully" }
        }
}
