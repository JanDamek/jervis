package com.jervis.service.jira

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.atlassian.AtlassianAuthService
import com.jervis.service.indexing.AbstractPeriodicPoller
import com.jervis.service.jira.state.JiraStateManager
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Continuous poller for Jira issues.
 * Polls Jira API for all issues, saves to MongoDB with state NEW.
 *
 * Pattern:
 * 1. JiraContinuousPoller (this) discovers issues via API search → saves to DB as NEW
 * 2. JiraContinuousIndexer picks up NEW issues → fetches details → indexes to RAG → marks INDEXED
 *
 * This separates discovery (fast, bulk) from indexing (slow, detailed).
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousPoller(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val api: AtlassianApiClient,
    private val auth: AtlassianAuthService,
    private val stateManager: JiraStateManager,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
) : AbstractPeriodicPoller<AtlassianConnectionDocument>() {
    override val pollerName: String = "JiraContinuousPoller"
    override val pollingIntervalMs: Long = 300_000L // 5 minutes (Jira changes less frequently)

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
            discoverAndSaveNewIssues(account)
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
     * Discover all Jira issues from API and save to MongoDB with state NEW.
     * Uses hash-based change detection to skip unchanged issues.
     */
    private suspend fun discoverAndSaveNewIssues(connection: AtlassianConnectionDocument) {
        val conn = auth.ensureValidToken(connection.toDomain())
        val clientId = connection.clientId

        // Fetch all projects for this connection
        val projects = try {
            api.listProjects(conn).map { (key, _) -> key }
        } catch (e: Exception) {
            logger.warn(e) { "[$pollerName] Failed to list projects for connection ${connection.id}" }
            return
        }

        if (projects.isEmpty()) {
            logger.info { "[$pollerName] No projects found for connection ${connection.id}" }
            return
        }

        logger.info { "[$pollerName] Polling ${projects.size} projects for connection ${connection.id}" }

        // Poll each project
        projects.forEach { projectKey ->
            try {
                val jql = "project = ${projectKey.value} ORDER BY created DESC"

                var discoveredCount = 0
                api.searchIssues(conn, jql, updatedSinceEpochMs = null).collect { issue ->
                    // Calculate hashes for change detection
                    val contentHash = computeHash("${issue.summary}|${issue.description ?: ""}")
                    val statusHash = computeHash(issue.status)

                    // Save to MongoDB - will mark as NEW if changed
                    stateManager.upsertIssueFromApi(
                        clientId = clientId,
                        issueKey = issue.key,
                        projectKey = projectKey.value,
                        summary = issue.summary,
                        status = issue.status,
                        assignee = issue.assignee?.value,
                        updated = issue.updated,
                        contentHash = contentHash,
                        statusHash = statusHash,
                    )
                    discoveredCount++
                }

                logger.info { "[$pollerName] Discovered $discoveredCount issues in project ${projectKey.value}" }
            } catch (e: Exception) {
                logger.error(e) { "[$pollerName] Failed to poll project ${projectKey.value}: ${e.message}" }
            }
        }
    }

    /**
     * Compute SHA-256 hash of input string for change detection.
     */
    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
