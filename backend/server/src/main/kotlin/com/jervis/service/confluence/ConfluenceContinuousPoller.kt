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
    private val connectionResolver: com.jervis.service.atlassian.AtlassianConnectionResolver,
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
    private val connectionRepository: AtlassianConnectionMongoRepository,
) : AbstractPeriodicPoller<com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding>() {
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

    override fun accountsFlow(): Flow<com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding> =
        connectionResolver.getAllConnectionBindings()

    override suspend fun getLastPollTime(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): Long? =
        account.connection.lastConfluencePolledAt?.toEpochMilli()

    override suspend fun executePoll(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): Boolean {
        if (account.connection.authStatus != "VALID") {
            logger.debug { "[$pollerName] Skipping binding client=${account.clientId} project=${account.projectId} - authStatus=${account.connection.authStatus}" }
            return false
        }

        return runCatching {
            discoverAndSaveNewPages(account)
            true
        }.onFailure { e ->
            logger.error(e) { "[$pollerName] Failed to poll binding client=${account.clientId} project=${account.projectId}: ${e.message}" }

            // Mark connection as INVALID on auth errors
            if (e is ConfluenceAuthException || e.cause is ConfluenceAuthException) {
                logger.warn { "[$pollerName] Auth error for connection ${account.connectionId}, marking as INVALID" }
                val updated = account.connection.copy(
                    authStatus = "INVALID",
                    updatedAt = java.time.Instant.now()
                )
                connectionRepository.save(updated)
            }
        }.getOrDefault(false)
    }

    override suspend fun updateLastPollTime(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding, timestamp: Long) {
        val updated = account.connection.copy(
            lastConfluencePolledAt = Instant.ofEpochMilli(timestamp),
            updatedAt = Instant.now()
        )
        connectionRepository.save(updated)
    }

    override fun accountLogLabel(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): String =
        "Client ${account.clientId} project ${account.projectId ?: "N/A"} (tenant=${account.connection.tenant})"

    /**
     * Discover all Confluence pages from API and save to MongoDB with state NEW.
     * Uses version-based change detection to skip unchanged pages.
     */
    private suspend fun discoverAndSaveNewPages(binding: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding) {
        val connection = binding.connection
        val clientId = binding.clientId
        val projectId = binding.projectId
        val siteUrl = "https://${connection.tenant}"

        // Fetch all spaces (or use filtered spaces from binding)
        val spaces = if (binding.confluenceSpaceKeys.isNotEmpty()) {
            binding.confluenceSpaceKeys
        } else {
            val allSpaces = mutableListOf<String>()
            confluenceApiClient.listSpaces(connection).collect { space ->
                allSpaces.add(space.key)
            }
            allSpaces
        }

        if (spaces.isEmpty()) {
            logger.info { "[$pollerName] No spaces found for client=$clientId project=$projectId" }
            return
        }

        logger.info { "[$pollerName] Polling ${spaces.size} spaces for client=$clientId project=$projectId" }

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
                        projectId = projectId,
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
