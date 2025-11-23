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
 * DISABLED: Confluence continuous indexer now runs as single instance for all connections.
 * The indexer is started automatically via @PostConstruct in ConfluenceContinuousIndexer.
 *
 * Old behavior:
 * - Launched one continuous indexer per VALID connection
 * - Each indexer polled for NEW Confluence pages and indexed them
 *
 * New behavior (refactored):
 * - Single indexer instance processes all connections
 * - Indexer queries NEW pages across all accounts
 * - Started automatically, no manual launch needed
 */
// @Component - DISABLED, indexer now starts automatically
class ConfluenceIndexerStartupListener(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val confluenceContinuousIndexer: ConfluenceContinuousIndexer,
) {
    private val indexerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // @EventListener(ApplicationReadyEvent::class) - DISABLED
    fun startContinuousIndexers() =
        runBlocking {
            logger.info { "ConfluenceIndexerStartupListener is DISABLED - indexer now starts automatically via @PostConstruct" }
        }
}
