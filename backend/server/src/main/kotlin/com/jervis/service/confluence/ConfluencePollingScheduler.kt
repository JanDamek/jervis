package com.jervis.service.confluence

import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.repository.mongo.ConfluenceAccountMongoRepository
import com.jervis.service.confluence.state.ConfluencePageStateManager
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling scheduler for Confluence documentation - similar to EmailPollingScheduler and GitPollingScheduler.
 *
 * Architecture:
 * - Polls oldest account every 5 minutes (fixedDelay = 300_000)
 * - Discovers new/changed pages by comparing versions
 * - Saves page metadata to MongoDB as NEW
 * - ConfluenceContinuousIndexer picks up NEW pages and processes them
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
) {
    /**
     * Poll next Confluence account (oldest lastPolledAt first).
     * Runs every 5 minutes with 1 minute initial delay.
     */
    @Scheduled(
        fixedDelayString = "\${confluence.sync.polling-interval-ms:1800000}",
        initialDelayString = "\${confluence.sync.initial-delay-ms:60000}",
    ) // configurable polling
    suspend fun pollNextAccount() {
        runCatching {
            val account = findNextAccountToPoll()

            if (account != null) {
                processAccountWithTimestampUpdate(account)
            } else {
                logger.debug { "No active Confluence accounts to poll" }
            }
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled Confluence poll" }
            // Scheduler continues - error doesn't stop future polls
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
        accountRepository.findFirstByIsActiveTrueOrderByLastPolledAtAsc()

    private suspend fun findAccountById(accountId: String): ConfluenceAccountDocument =
        accountRepository.findById(ObjectId(accountId))
            ?: throw IllegalArgumentException("Confluence account not found: $accountId")

    private suspend fun processAccountWithTimestampUpdate(account: ConfluenceAccountDocument) {
        logger.info {
            "Syncing Confluence pages for account: ${account.siteName} (${account.id})"
        }

        try {
            syncPagesForAccount(account)
            updateAccountTimestamp(account.id, success = true)
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to sync Confluence account ${account.id}, will retry on next poll cycle"
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
                lastErrorMessage = if (success) null else errorMessage?.take(500),
                updatedAt = Instant.now(),
            )

        accountRepository.save(updated)
    }
}
