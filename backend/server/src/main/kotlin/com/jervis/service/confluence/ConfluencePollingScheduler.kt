package com.jervis.service.confluence

import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.repository.mongo.ConfluenceAccountMongoRepository
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
    private val accountRepository: ConfluenceAccountMongoRepository,
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
    private val configCache: com.jervis.service.cache.ClientProjectConfigCache,
    private val accountService: ConfluenceAccountService,
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
                val account = findNextAccountToPoll()

                if (account != null) {
                    processAccountWithTimestampUpdate(account)
                } else {
                    logger.debug { "No active Confluence accounts to poll" }
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
    suspend fun triggerManualPoll(accountId: String) {
        logger.info { "Manually triggering poll for Confluence account $accountId" }

        val account = findAccountById(accountId)
        processAccountWithTimestampUpdate(account)
    }

    private suspend fun findNextAccountToPoll(): ConfluenceAccountDocument? =
        accountRepository.findFirstByIsActiveTrueAndAuthStatusOrderByLastPolledAtAsc("VALID")

    private suspend fun findAccountById(accountId: String): ConfluenceAccountDocument =
        accountRepository.findById(ObjectId(accountId))
            ?: throw IllegalArgumentException("Confluence account not found: $accountId")

    private suspend fun processAccountWithTimestampUpdate(account: ConfluenceAccountDocument) {
        if (account.authStatus != "VALID") {
            logger.warn { "Skipping Confluence account ${account.id} (authStatus=${account.authStatus}). Use Test Connection to enable." }
            return
        }

        logger.info { "Syncing Confluence pages for account: ${account.siteName} (${account.id})" }

        try {
            syncPagesForAccount(account)
            updateAccountTimestamp(account.id, success = true)
        } catch (e: Exception) {
            if (e is ConfluenceAuthException) {
                logger.warn(e) { "Auth error while syncing Confluence account ${account.id}, marking as INVALID" }
                runCatching { accountService.markAuthInvalid(account, e.message) }
            } else {
                logger.error(e) { "Failed to sync Confluence account ${account.id}, will retry on next poll cycle" }
            }
            // Don't update timestamp on failure - account stays as "needs sync"
            updateAccountTimestamp(account.id, success = false, errorMessage = e.message)
        }
    }

    /**
     * Sync all pages from all configured spaces.
     */
    private suspend fun syncPagesForAccount(account: ConfluenceAccountDocument) {
        val startTime = System.currentTimeMillis()
        var totalPagesDiscovered = 0
        var totalPagesChanged = 0
        var totalSpaces = 0

        // Determine which spaces to sync
        val spacesToSync =
            if (account.spaceKeys.isEmpty()) {
                // No specific spaces configured → sync all accessible spaces
                logger.info { "No spaceKeys configured, discovering all spaces..." }
                val spaces = confluenceApiClient.listSpaces(account).toList()
                logger.info { "Discovered ${spaces.size} accessible spaces" }
                totalSpaces = spaces.size
                spaces.map { it.key }
            } else {
                // Use configured spaces
                logger.info { "Syncing configured spaces: ${account.spaceKeys.joinToString(", ")}" }
                totalSpaces = account.spaceKeys.size
                account.spaceKeys
            }

        // Sync each space
        for (spaceKey in spacesToSync) {
            try {
                val (discovered, changed) = syncSpacePages(account, spaceKey)
                totalPagesDiscovered += discovered
                totalPagesChanged += changed

                logger.info {
                    "Synced space $spaceKey: $discovered pages discovered, $changed changed/new"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to sync space $spaceKey for account ${account.id}" }
                // Continue with other spaces even if one fails
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Confluence sync completed for ${account.siteName}: " +
                "$totalSpaces spaces, $totalPagesDiscovered pages, $totalPagesChanged new/changed in ${elapsed}ms"
        }
    }

    /**
     * Sync all pages in a space.
     * Returns (discovered count, changed/new count).
     */
    private suspend fun syncSpacePages(
        account: ConfluenceAccountDocument,
        spaceKey: String,
    ): Pair<Int, Int> {
        var discoveredCount = 0
        var changedCount = 0

        // Fetch pages modified since last successful sync (or all if first sync)
        val modifiedSince = account.lastSuccessfulSyncAt

        confluenceApiClient
            .listPagesInSpace(account, spaceKey, modifiedSince)
            .collect { page ->
                discoveredCount++

                try {
                    // Construct page URL
                    val pageUrl = "${account.siteUrl}/wiki/spaces/$spaceKey/pages/${page.id}"

                    // Determine projectId: check space mapping first, fallback to account.projectId
                    val projectId =
                        try {
                            configCache.getProjectForConfluenceSpace(account.clientId, spaceKey) ?: account.projectId
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to load Confluence space mapping from cache, using account projectId" }
                            account.projectId
                        }

                    // Save or update page, returns true if NEW/changed
                    val isNewOrChanged =
                        stateManager.saveOrUpdatePage(
                            accountId = account.id,
                            clientId = account.clientId,
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

    private suspend fun updateAccountTimestamp(
        accountId: ObjectId,
        success: Boolean,
        errorMessage: String? = null,
    ) {
        val account = accountRepository.findById(accountId) ?: return

        val updated =
            account.copy(
                lastPolledAt = Instant.now(),
                lastSuccessfulSyncAt = if (success) Instant.now() else account.lastSuccessfulSyncAt,
                lastErrorMessage = if (success) null else errorMessage,
                updatedAt = Instant.now(),
            )

        accountRepository.save(updated)
    }
}
