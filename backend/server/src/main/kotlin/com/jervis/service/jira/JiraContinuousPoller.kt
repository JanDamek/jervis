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
    private val connectionResolver: com.jervis.service.atlassian.AtlassianConnectionResolver,
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val api: AtlassianApiClient,
    private val auth: AtlassianAuthService,
    private val stateManager: JiraStateManager,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
) : AbstractPeriodicPoller<com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding>() {
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

    override fun accountsFlow(): Flow<com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding> =
        connectionResolver.getAllConnectionBindings()

    override suspend fun getLastPollTime(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): Long? =
        account.connection.lastSyncedAt?.toEpochMilli()

    override suspend fun executePoll(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): Boolean {
        if (account.connection.authStatus != "VALID") {
            logger.debug { "[$pollerName] Skipping binding client=${account.clientId} project=${account.projectId} - authStatus=${account.connection.authStatus}" }
            return false
        }

        return runCatching {
            discoverAndSaveNewIssues(account)
            true
        }.onFailure { e ->
            logger.error(e) { "[$pollerName] Failed to poll binding client=${account.clientId} project=${account.projectId}: ${e.message}" }

            // Mark connection as INVALID on auth errors
            if (e is com.jervis.service.atlassian.JiraAuthException || e.cause is com.jervis.service.atlassian.JiraAuthException) {
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
            lastSyncedAt = Instant.ofEpochMilli(timestamp),
            updatedAt = Instant.now()
        )
        connectionRepository.save(updated)
    }

    override fun accountLogLabel(account: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding): String =
        "Client ${account.clientId} project ${account.projectId ?: "N/A"} (tenant=${account.connection.tenant})"

    /**
     * Discover all Jira issues from API and save to MongoDB with state NEW.
     * Uses hash-based change detection to skip unchanged issues.
     */
    private suspend fun discoverAndSaveNewIssues(binding: com.jervis.service.atlassian.AtlassianConnectionResolver.ConnectionBinding) {
        val connection = binding.connection
        val conn = auth.ensureValidToken(connection.toDomain())
        val accountId = connection.id
        val clientId = binding.clientId

        // Fetch all projects (or use filtered projects from binding)
        val projects = if (binding.jiraProjectKeys.isNotEmpty()) {
            binding.jiraProjectKeys.map { com.jervis.domain.jira.JiraProjectKey(it) }
        } else {
            try {
                api.listProjects(conn).map { (key, _) -> key }
            } catch (e: Exception) {
                logger.warn(e) { "[$pollerName] Failed to list projects for client=$clientId project=${binding.projectId}" }
                return
            }
        }

        if (projects.isEmpty()) {
            logger.info { "[$pollerName] No projects found for client=$clientId project=${binding.projectId}" }
            return
        }

        logger.info { "[$pollerName] Polling ${projects.size} Jira projects for client=$clientId project=${binding.projectId}" }

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
                        accountId = accountId,
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

                logger.info { "[$pollerName] Discovered $discoveredCount issues in Jira project ${projectKey.value}" }
            } catch (e: Exception) {
                logger.error(e) { "[$pollerName] Failed to poll Jira project ${projectKey.value}: ${e.message}" }
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
