package com.jervis.service.listener.jira

import com.jervis.repository.AtlassianConnectionMongoRepository
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
 * DISABLED: Queue processing for Jira URLs needs refactoring for new connection architecture.
 * TODO: Refactor to use ConnectionBinding pattern from AtlassianConnectionResolver
 *
 * Old behavior:
 * - Launches one queue processor per VALID connection
 * - Each processor polls LinkIndexingQueue for Jira URLs discovered by other indexers
 * - JiraPollingScheduler handles regular issue indexing
 */
// @Component - DISABLED until refactored
class JiraIndexerStartupListener(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val orchestrator: JiraIndexingOrchestrator,
) {
    private val indexerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // @EventListener(ApplicationReadyEvent::class) - DISABLED
    fun startQueueProcessors() =
        runBlocking {
            logger.warn { "JiraIndexerStartupListener is DISABLED - queue processing needs refactoring for new connection architecture" }
            // TODO: Refactor to iterate ConnectionBindings instead of connections
            // TODO: processQueuedUrls needs to accept (connectionId, clientId) instead of just clientId
        }
}
