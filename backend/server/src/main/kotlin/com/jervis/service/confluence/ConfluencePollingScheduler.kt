package com.jervis.service.confluence

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.mongo.AtlassianConnectionMongoRepository
import com.jervis.service.confluence.state.ConfluencePageStateManager
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling scheduler for Confluence documentation with intelligent adaptive intervals.
 *
 * Architecture:
 * - Polls oldest account with adaptive delay based on last run duration
 * - Discovers new/changed pages by comparing versions
 * - Saves page metadata to MongoDB as NEW
 * - ConfluenceContinuousIndexer picks up NEW pages and processes them
 *
 * Adaptive Polling Strategy:
 * - Run duration < 5 min → next poll in 10 min
 * - Run duration 5-30 min → next poll in 30 min
 * - Run duration > 30 min → next poll in 60 min
 * - Minimum interval: 10 minutes (prevents thrashing)
 * - Maximum interval: 60 minutes (ensures freshness)
 * - Startup: runs after 60 seconds
 *
 * Change Detection:
 * - Confluence API provides 'version.number' that increments on edit
 * - Compare with lastKnownVersion in MongoDB
 * - If version increased → mark as NEW for reindexing
 * - If new page → mark as NEW
 *
 * Error Handling:
 * - All errors logged but scheduler continues
 * - Failed sync doesn't update lastPolledAt → account retried on next cycle
 * - Individual page errors don't stop batch processing
 */
@Service
class ConfluencePollingScheduler(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
    private val configCache: com.jervis.service.cache.ClientProjectConfigCache,
    private val connectionService: com.jervis.service.atlassian.AtlassianConnectionService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private var pollingJob: Job? = null

    @PostConstruct
    fun start() {
        logger.info { "ConfluencePollingScheduler starting with adaptive intervals..." }

        pollingJob =
            scope.launch {
                try {
                    logger.info { "Confluence polling loop STARTED (adaptive 10-60 min intervals)" }
                    runPollingLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Confluence polling loop FAILED to start!" }
                }
            }

        logger.info { "ConfluencePollingScheduler initialization complete" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "ConfluencePollingScheduler stopping..." }
        pollingJob?.cancel(CancellationException("Application shutdown"))
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(3000) {
                    pollingJob?.join()
                }
            }
        } catch (_: Exception) {
            logger.debug { "ConfluencePollingScheduler shutdown timeout" }
        }
    }

    /**
     * Continuous polling loop with adaptive delay based on run duration.
     */
    private suspend fun runPollingLoop() {
        // Initial delay: 60 seconds after startup
        delay(60_000)

        while (scope.isActive) {
            val startTime = System.currentTimeMillis()

            try {
                val connection = findNextConnectionToPoll()

                if (connection != null) {
                    processConnectionWithTimestampUpdate(connection)
                } else {
                    logger.debug { "No active VALID Atlassian connections to poll for Confluence" }
                }
            } catch (e: CancellationException) {
                logger.info { "Confluence polling loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error during Confluence poll cycle" }
            }

            // Calculate adaptive delay based on run duration
            val runDurationMs = System.currentTimeMillis() - startTime
            val nextDelayMs = calculateAdaptiveDelay(runDurationMs)

            logger.info {
                "Confluence sync completed in ${runDurationMs / 1000}s, next poll in ${nextDelayMs / 1000}s " +
                    "(${nextDelayMs / 60_000} minutes)"
            }

            delay(nextDelayMs)
        }

        logger.warn { "Confluence polling loop exited - scope is no longer active" }
    }

    /**
     * Calculate adaptive delay based on last run duration:
     * - < 5 min run → 10 min delay
     * - 5-30 min run → 30 min delay
     * - > 30 min run → 60 min delay
     * - Min: 10 minutes, Max: 60 minutes
     */
    private fun calculateAdaptiveDelay(runDurationMs: Long): Long {
        val runMinutes = runDurationMs / 60_000

        return when {
            runMinutes < 5 -> 10 * 60_000L // 10 minutes
            runMinutes < 30 -> 30 * 60_000L // 30 minutes
            else -> 60 * 60_000L // 60 minutes
        }.also { delayMs ->
            logger.debug {
                "Adaptive delay calculated: runTime=${runMinutes}min → nextDelay=${delayMs / 60_000}min"
            }
        }
    }

    /**
     * Manual trigger for testing/admin UI.
     */
    suspend fun triggerManualPoll(clientId: String) {
        logger.info { "Manually triggering Confluence poll for client $clientId" }

        val connection = findConnectionById(clientId)
        processConnectionWithTimestampUpdate(connection)
    }

    /**
     * Manual trigger that auto-selects the next eligible connection (oldest lastConfluencePolledAt, VALID auth).
     */
    suspend fun triggerNext() {
        logger.info { "Manually triggering Confluence sync (auto-select next connection)" }
        val connection = findNextConnectionToPoll()
        if (connection != null) {
            processConnectionWithTimestampUpdate(connection)
        } else {
            logger.debug { "No active VALID Atlassian connections to poll for Confluence" }
        }
    }

    private suspend fun findNextConnectionToPoll(): AtlassianConnectionDocument? {
        // Find connections with VALID auth, sorted by lastConfluencePolledAt (oldest first)
        val all = connectionRepository.findAll().toList()
        return all
            .filter { it.authStatus == "VALID" }
            .minByOrNull { it.lastConfluencePolledAt ?: Instant.EPOCH }
    }

    private suspend fun findConnectionById(clientId: String): AtlassianConnectionDocument =
        connectionRepository.findByClientId(ObjectId(clientId))
            ?: throw IllegalArgumentException("Atlassian connection not found for client: $clientId")

    private suspend fun processConnectionWithTimestampUpdate(connection: AtlassianConnectionDocument) {
        if (connection.authStatus != "VALID") {
            logger.warn { "Skipping Confluence sync for client ${connection.clientId} (authStatus=${connection.authStatus}). Use Test Connection to enable." }
            return
        }

        logger.info { "Syncing Confluence pages for client ${connection.clientId.toHexString()} (tenant: ${connection.tenant})" }

        try {
            syncPagesForConnection(connection)
            updateConnectionTimestamp(connection.id, success = true)
        } catch (e: Exception) {
            if (e is ConfluenceAuthException) {
                logger.warn(e) { "Auth error while syncing Confluence for client ${connection.clientId}, marking as INVALID" }
                runCatching { connectionService.markAuthInvalid(connection, e.message) }
            } else {
                logger.error(e) { "Failed to sync Confluence for client ${connection.clientId}, will retry on next poll cycle" }
            }
            // Don't update timestamp on failure - connection stays as "needs sync"
            updateConnectionTimestamp(connection.id, success = false, errorMessage = e.message)
        }
    }

    /**
     * Sync all pages from all configured spaces.
     */
    private suspend fun syncPagesForConnection(connection: AtlassianConnectionDocument) {
        val startTime = System.currentTimeMillis()
        var totalPagesDiscovered = 0
        var totalPagesChanged = 0
        var totalSpaces = 0

        // Determine which spaces to sync
        val spacesToSync =
            if (connection.confluenceSpaceKeys.isEmpty()) {
                // No specific spaces configured → sync all accessible spaces
                logger.info { "No spaceKeys configured, discovering all spaces..." }
                val spaces = confluenceApiClient.listSpaces(connection).toList()
                logger.info { "Discovered ${spaces.size} accessible spaces" }
                totalSpaces = spaces.size
                spaces.map { it.key }
            } else {
                // Use configured spaces
                logger.info { "Syncing configured spaces: ${connection.confluenceSpaceKeys.joinToString(", ")}" }
                totalSpaces = connection.confluenceSpaceKeys.size
                connection.confluenceSpaceKeys
            }

        // Sync each space
        for (spaceKey in spacesToSync) {
            try {
                val (discovered, changed) = syncSpacePages(connection, spaceKey)
                totalPagesDiscovered += discovered
                totalPagesChanged += changed

                logger.info {
                    "Synced space $spaceKey: $discovered pages discovered, $changed changed/new"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to sync space $spaceKey for client ${connection.clientId}" }
                // Continue with other spaces even if one fails
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Confluence sync completed for tenant ${connection.tenant}: " +
                "$totalSpaces spaces, $totalPagesDiscovered pages, $totalPagesChanged new/changed in ${elapsed}ms"
        }
    }

    /**
     * Sync all pages in a space.
     * Returns (discovered count, changed/new count).
     */
    private suspend fun syncSpacePages(
        connection: AtlassianConnectionDocument,
        spaceKey: String,
    ): Pair<Int, Int> {
        var discoveredCount = 0
        var changedCount = 0

        // Fetch pages modified since last successful sync (or all if first sync)
        val modifiedSince = connection.lastConfluenceSyncedAt

        confluenceApiClient
            .listPagesInSpace(connection, spaceKey, modifiedSince)
            .collect { page ->
                discoveredCount++

                try {
                    // Construct page URL
                    val pageUrl = "https://${connection.tenant}/wiki/spaces/$spaceKey/pages/${page.id}"

                    // Determine projectId: check space mapping first, fallback to null (client-level)
                    val projectId =
                        try {
                            configCache.getProjectForConfluenceSpace(connection.clientId, spaceKey)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to load Confluence space mapping from cache" }
                            null
                        }

                    // Save or update page, returns true if NEW/changed
                    val isNewOrChanged =
                        stateManager.saveOrUpdatePage(
                            accountId = connection.id, // Using connection ID as account ID
                            clientId = connection.clientId,
                            projectId = projectId,
                            spaceKey = spaceKey,
                            page = page,
                            url = pageUrl,
                        )

                    if (isNewOrChanged) {
                        changedCount++
                    }

                    // Log progress every 50 pages
                    if (discoveredCount % 50 == 0) {
                        logger.debug { "Space $spaceKey progress: $discoveredCount pages, $changedCount new/changed..." }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process page ${page.id} in space $spaceKey" }
                    // Continue with other pages
                }
            }

        return discoveredCount to changedCount
    }

    private suspend fun updateConnectionTimestamp(
        connectionId: ObjectId,
        success: Boolean,
        errorMessage: String? = null,
    ) {
        val connection = connectionRepository.findById(connectionId) ?: return

        val updated =
            connection.copy(
                lastConfluencePolledAt = Instant.now(),
                lastConfluenceSyncedAt = if (success) Instant.now() else connection.lastConfluenceSyncedAt,
                lastErrorMessage = if (success) null else errorMessage, // Shared error field for both Jira and Confluence
                updatedAt = Instant.now(),
            )

        connectionRepository.save(updated)
    }
}
