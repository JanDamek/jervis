package com.jervis.service.jira

import com.jervis.repository.mongo.AtlassianConnectionMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.atlassian.AtlassianAuthService
import com.jervis.service.jira.state.JiraStateManager
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

@Service
class JiraPollingScheduler(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val api: AtlassianApiClient,
    private val auth: AtlassianAuthService,
    private val stateManager: JiraStateManager,
    private val indexingRegistry: com.jervis.service.indexing.status.IndexingStatusRegistry,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Poll next Jira client connection (oldest lastSyncedAt first).
     * ONLY discovers issues and saves them to DB as NEW.
     * Actual indexing is done by JiraContinuousIndexer.
     * Runs every 30 minutes with 1 minute initial delay.
     */
    @Scheduled(
        fixedDelayString = "\${jira.sync.polling-interval-ms:1800000}",
        initialDelayString = "\${jira.sync.initial-delay-ms:60000}",
    )
    suspend fun pollNextClient() {
        runCatching {
            val all = connectionRepository.findAll().toList()
            if (all.isEmpty()) {
                logger.debug { "No Jira connections configured" }
                return
            }

            // Only process connections with VALID authentication
            val active = all.filter { it.authStatus == "VALID" }
            if (active.isEmpty()) {
                logger.debug { "No active VALID Jira connections to poll" }
                return
            }

            val next = active.minByOrNull { it.lastSyncedAt ?: Instant.EPOCH } ?: return
            discoverIssues(next.clientId)
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled Jira poll" }
        }
    }

    /**
     * Discover issues for a client (fast JQL search).
     * Only saves issue metadata to DB as NEW - no deep indexing.
     */
    private suspend fun discoverIssues(clientId: ObjectId) {
        val toolKey = "jira"
        indexingRegistry.ensureTool(toolKey, displayName = "Atlassian (Jira)")
        indexingRegistry.start(toolKey, displayName = "Atlassian (Jira)", message = "Discovering projects and issues for client ${clientId.toHexString()}")
        logger.info { "JIRA_DISCOVERY: Starting for client=${clientId.toHexString()}" }

        val connectionDoc = connectionRepository.findByClientId(clientId) ?: run {
            logger.warn { "JIRA_DISCOVERY: No connection found for client=${clientId.toHexString()}" }
            indexingRegistry.info(toolKey, "No connection found for client ${clientId.toHexString()}")
            indexingRegistry.finish(toolKey, message = "Jira discovery finished: no connection")
            return
        }

        if (connectionDoc.authStatus != "VALID") {
            logger.warn { "JIRA_DISCOVERY: Skipping client=${clientId.toHexString()}, authStatus=${connectionDoc.authStatus}" }
            indexingRegistry.info(toolKey, "Skipping client ${clientId.toHexString()}, authStatus=${connectionDoc.authStatus}")
            indexingRegistry.finish(toolKey, message = "Jira discovery finished: auth not valid")
            return
        }

        val conn = connectionDoc.toDomain()
        val validConn = auth.ensureValidToken(conn)

        // Get all projects
        val projects = api.listProjects(validConn).map { (key, _) -> key }
        logger.info { "JIRA_DISCOVERY: Found ${projects.size} projects for client=${clientId.toHexString()}" }
        indexingRegistry.info(toolKey, "Found ${projects.size} projects for client ${clientId.toHexString()}")

        var totalDiscovered = 0
        projects.forEachIndexed { idx, projectKey ->
            val jql = "project = ${projectKey.value} ORDER BY created DESC"
            val projectProgressMsg = "Project ${idx + 1}/${projects.size}: ${projectKey.value}"
            indexingRegistry.info(toolKey, "$projectProgressMsg – starting search")
            logger.debug { "JIRA_DISCOVERY: Searching with JQL: $jql" }

            api.searchIssues(validConn, jql).collect { issue ->
                val contentHash = computeHash("${issue.summary}|${issue.description ?: ""}")
                val statusHash = computeHash(issue.status)

                stateManager.upsertIssueFromApi(
                    clientId = clientId,
                    issueKey = issue.key,
                    projectKey = issue.project.value,
                    summary = issue.summary,
                    status = issue.status,
                    assignee = issue.assignee?.value,
                    updated = issue.updated,
                    contentHash = contentHash,
                    statusHash = statusHash,
                )
                totalDiscovered++
                // Report progress every item (registry throttles notifications internally)
                indexingRegistry.progress(toolKey, processedInc = 1, message = "$projectProgressMsg – discovered ${issue.key}")
            }

            indexingRegistry.info(toolKey, "$projectProgressMsg – completed")
        }

        // Update lastSyncedAt
        val updatedDoc = connectionDoc.copy(
            lastSyncedAt = Instant.now(),
            updatedAt = Instant.now()
        )
        connectionRepository.save(updatedDoc)

        logger.info { "JIRA_DISCOVERY: Completed for client=${clientId.toHexString()}, discovered $totalDiscovered issues" }
        indexingRegistry.finish(toolKey, message = "Discovery completed – discovered $totalDiscovered issues")
    }

    private fun computeHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Manual trigger to pick the next eligible Jira connection automatically. */
    suspend fun triggerNext() {
        logger.info { "Manually triggering Jira discovery (auto-select next connection)" }
        pollNextClient()
    }

    /**
     * Returns human-readable reason why Jira indexing is IDLE despite having issues in DB.
     * Used by UI to explain why indexer is not processing.
     */
    suspend fun getIdleReason(newCount: Long): String? {
        if (newCount == 0L) return null // No issues in DB yet, no reason needed

        val all = connectionRepository.findAll().toList()
        if (all.isEmpty()) {
            return "No Jira connections configured"
        }

        val valid = all.filter { it.authStatus == "VALID" }
        if (valid.isEmpty()) {
            return "No VALID Jira connections (check authentication)"
        }

        // Has valid connections, scheduler will pick them up
        return "Waiting for next scheduled sync (runs every 30 minutes)"
    }
}
