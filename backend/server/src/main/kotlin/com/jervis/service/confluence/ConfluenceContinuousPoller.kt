package com.jervis.service.confluence

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.service.confluence.state.ConfluencePageStateManager
import com.jervis.service.indexing.AbstractPeriodicPoller
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Continuous poller for Confluence pages.
 * Polls Confluence API for all pages, saves to MongoDB with state NEW.
 *
 * Pattern:
 * 1. ConfluenceContinuousPoller (this) discovers pages via API → saves to DB as NEW
 * 2. ConfluenceContinuousIndexer picks up NEW pages → fetches full content → indexes to RAG → marks INDEXED
 *
 * This separates discovery (fast, bulk) from indexing (slow, detailed).
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class ConfluenceContinuousPoller(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
) : AbstractPeriodicPoller<AtlassianConnectionDocument>() {
    override val pollerName: String = "ConfluenceContinuousPoller"
    override val pollingIntervalMs: Long = 600_000L // 10 minutes (Confluence changes less frequently)

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $pollerName for all Atlassian connections..." }
        scope.launch {
            startPeriodicPolling()
        }
    }

    override fun accountsFlow(): Flow<AtlassianConnectionDocument> =
        connectionRepository.findAll()

    override suspend fun getLastPollTime(account: AtlassianConnectionDocument): Long? =
        account.lastSyncedAt?.toEpochMilli()

    override suspend fun executePoll(account: AtlassianConnectionDocument): Boolean {
        if (account.authStatus != "VALID") {
            logger.debug { "[$pollerName] Skipping connection ${account.id} - authStatus=${account.authStatus}" }
            return false
        }

        return runCatching {
            discoverAndSaveNewPages(account)
            true
        }.onFailure { e ->
            logger.error(e) { "[$pollerName] Failed to poll connection ${account.id}: ${e.message}" }
        }.getOrDefault(false)
    }

    override suspend fun updateLastPollTime(account: AtlassianConnectionDocument, timestamp: Long) {
        val updated = account.copy(
            lastSyncedAt = Instant.ofEpochMilli(timestamp),
            updatedAt = Instant.now()
        )
        connectionRepository.save(updated)
    }

    override fun accountLogLabel(account: AtlassianConnectionDocument): String =
        "Atlassian connection ${account.id} (tenant=${account.tenant})"

    /**
     * Discover all Confluence pages from API and save to MongoDB with state NEW.
     * Uses version-based change detection to skip unchanged pages.
     */
    private suspend fun discoverAndSaveNewPages(connection: AtlassianConnectionDocument) {
        val clientId = connection.clientId
        val siteUrl = "https://${connection.tenant}"

        // Fetch all spaces
        val spaces = mutableListOf<String>()
        confluenceApiClient.listSpaces(connection).collect { space ->
            spaces.add(space.key)
        }

        if (spaces.isEmpty()) {
            logger.info { "[$pollerName] No spaces found for connection ${connection.id}" }
            return
        }

        logger.info { "[$pollerName] Polling ${spaces.size} spaces for connection ${connection.id}" }

        // Poll each space
        spaces.forEach { spaceKey ->
            try {
                var discoveredCount = 0

                confluenceApiClient.listPagesInSpace(connection, spaceKey).collect { page ->
                    val url = "$siteUrl/wiki/spaces/$spaceKey/pages/${page.id}"

                    // Save to MongoDB - will mark as NEW if changed
                    val needsIndexing = stateManager.saveOrUpdatePage(
                        accountId = connection.id,
                        clientId = clientId,
                        projectId = null, // TODO: resolve projectId from mapping
                        spaceKey = spaceKey,
                        page = page,
                        url = url,
                    )

                    if (needsIndexing) {
                        discoveredCount++
                    }
                }

                logger.info { "[$pollerName] Discovered $discoveredCount new/changed pages in space $spaceKey" }
            } catch (e: Exception) {
                logger.error(e) { "[$pollerName] Failed to poll space $spaceKey: ${e.message}" }
            }
        }
    }
}
