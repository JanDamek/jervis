package com.jervis.service.listener.confluence

import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.service.confluence.ConfluenceContinuousIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Starts continuous indexers for all active Atlassian connections on application startup.
 * Launches Confluence indexers for connections with VALID auth status.
 *
 * Architecture:
 * - Launches one continuous indexer per VALID connection
 * - Each indexer polls for NEW Confluence pages and indexes them
 * - ConfluencePollingScheduler discovers pages and marks them NEW
 * - This listener ensures indexers are running to process those NEW pages
 */
@Component
class ConfluenceIndexerStartupListener(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val confluenceContinuousIndexer: ConfluenceContinuousIndexer,
) {
    private val indexerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @EventListener(ApplicationReadyEvent::class)
    fun startContinuousIndexers() =
        runBlocking {
            logger.info { "Starting continuous Confluence indexers for all VALID Atlassian connections..." }

            val validConnections =
                connectionRepository
                    .findAll()
                    .toList()
                    .filter { it.authStatus == "VALID" }

            logger.info { "Found ${validConnections.size} VALID Atlassian connections" }

            validConnections.forEach { connection ->
                logger.info {
                    "Launching continuous Confluence indexer for client ${connection.clientId.toHexString()} (tenant: ${connection.tenant})"
                }
                confluenceContinuousIndexer.launchContinuousIndexing(connection, indexerScope)
            }

            logger.info { "All Confluence continuous indexers launched successfully" }
        }
}
