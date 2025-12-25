package com.jervis.service.polling

import com.jervis.configuration.properties.PollingProperties
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ClientRepository
import com.jervis.repository.ProjectRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.service.task.UserTaskService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import mu.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Central polling service for all connection types.
 *
 * Architecture:
 * - Single poller that runs continuously
 * - Discovers all enabled connections from DB
 * - Delegates to type-specific PollingHandler implementations
 * - Rate limiting happens per domain in HttpClient
 * - Each connection type has its own polling interval
 *
 * Flow:
 * 1. Load all enabled connections
 * 2. For each connection, find clients/projects using it
 * 3. Delegate to the appropriate handler (JiraPollingHandler, ConfluencePollingHandler, etc.)
 * 4. Handler discovers new/updated items and creates NEW state documents
 * 5. ContinuousIndexer picks up NEW items and indexes them
 */
@Service
@Profile("!cli")
class CentralPoller(
    private val connectionService: ConnectionService,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val handlers: List<PollingHandler>,
    private val userTaskService: UserTaskService,
    private val pollingProperties: PollingProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track last poll time per connection
    private val lastPollTimes = mutableMapOf<String, Instant>()

    // Mutex per connection to prevent parallel polling of the same connection
    private val connectionLocks = mutableMapOf<String, Mutex>()

    @PostConstruct
    fun start() {
        logger.debug { "Starting CentralPoller..." }

        scope.launch {
            // Initial delay to let application fully start
            delay(10_000)
            logger.debug { "CentralPoller initial delay complete, beginning polling loop" }

            while (isActive) {
                try {
                    pollAllConnections()
                } catch (e: Exception) {
                    logger.error(e) { "Error in central polling loop" }
                }

                delay(30_000)
            }
        }
    }

    private suspend fun pollAllConnections() {
        val startTime = Instant.now()
        val jobs = mutableListOf<Job>()
        val connectionDocuments = mutableListOf<ConnectionDocument>()

        logger.debug { "=== Starting polling cycle ===" }

        connectionService.findAllValid().collect { connection ->
            connectionDocuments.add(connection)
            val job =
                scope.async {
                    try {
                        pollConnection(connection)
                    } catch (e: Exception) {
                        logger.error(e) { "Error polling connection: ${connection.name} (${connection.id})" }
                    }
                }
            jobs.add(job)
        }

        if (connectionDocuments.isEmpty()) {
            logger.debug { "No enabled connections found, skipping poll cycle" }
            return
        }

        logger.debug {
            "Found ${connectionDocuments.size} enabled connection(s): ${
                connectionDocuments.joinToString(
                    ", ",
                ) { "'${it.name}'" }
            }"
        }

        // Wait for all polling jobs to complete
        jobs.joinAll()

        val duration = Duration.between(startTime, Instant.now())
        logger.debug { "=== Polling cycle completed in ${duration.toMillis()}ms ===" }
    }

    private suspend fun pollConnection(connectionDocument: ConnectionDocument) {
        val connectionId = connectionDocument.id.toString()

        // Get or create mutex for this connection
        val mutex =
            synchronized(connectionLocks) {
                connectionLocks.getOrPut(connectionId) { Mutex() }
            }

        // Try to acquire lock, skip if already polling
        if (!mutex.tryLock()) {
            logger.debug {
                "Skipping '${connectionDocument.name}' (${connectionDocument.id}): already polling in another coroutine"
            }
            return
        }

        try {
            val startTime = Instant.now()
            val now = Instant.now()
            val lastPoll = lastPollTimes[connectionId]
            val interval = getPollingInterval(connectionDocument)

            logger.debug {
                "  Checking connectionDocument '${connectionDocument.name}' (${connectionDocument::class.simpleName}, id=${connectionDocument.id})"
            }

            if (lastPoll != null && Duration.between(lastPoll, now) < interval) {
                val timeSinceLastPoll = Duration.between(lastPoll, now)
                logger.debug {
                    "Skipping '${connectionDocument.name}': interval not reached | " +
                        "Last poll: ${timeSinceLastPoll.toMinutes()}min ago, Interval: ${interval.toMinutes()}min"
                }
                return
            }

            // Find ALL handlers for this connectionDocument type (e.g., Atlassian → Jira + Confluence)
            val matchingHandlers = handlers.filter { it.canHandle(connectionDocument) }

            if (matchingHandlers.isEmpty()) {
                logger.warn {
                    "No handler found for connectionDocument '${connectionDocument.name}' (type: ${connectionDocument::class.simpleName})"
                }
                return
            }

            // Find all clients/projects using this connectionDocument
            val clients = clientRepository.findByConnectionIdsContaining(connectionDocument.id).toList()
            val projects = projectRepository.findByConnectionIdsContaining(connectionDocument.id).toList()

            if (clients.isEmpty() && projects.isEmpty()) {
                logger.debug {
                    "Skipping '${connectionDocument.name}' (id=${connectionDocument.id}): " +
                        "no clients or projects using this connectionDocument"
                }
                return
            }

            // Create polling context
            val context = PollingContext(clients = clients, projects = projects)

            val handlerNames = matchingHandlers.joinToString(", ") { it::class.simpleName ?: "Unknown" }
            val clientNames = clients.joinToString(", ") { it.name }
            val projectNames = projects.joinToString(", ") { it.name }
            val scope =
                if (projects.isNotEmpty()) {
                    "Clients: [$clientNames] | Projects: [$projectNames]"
                } else {
                    "Clients: [$clientNames]"
                }
            logger.debug {
                "Polling '${connectionDocument.name}' (${connectionDocument::class.simpleName}) | $scope | Handlers: [$handlerNames]"
            }

            // Execute all matching handlers in parallel (e.g., Jira + Confluence for Atlassian)
            val results =
                coroutineScope {
                    matchingHandlers
                        .map { handler ->
                            async {
                                try {
                                    handler.poll(connectionDocument, context)
                                } catch (e: Exception) {
                                    logger.error(
                                        e,
                                    ) { "Error in handler ${handler::class.simpleName} for connectionDocument ${connectionDocument.name}" }
                                    PollingResult(errors = 1)
                                }
                            }
                        }.awaitAll()
                }

            // Aggregate results from all handlers
            val totalResult =
                results.fold(PollingResult()) { acc, result ->
                    PollingResult(
                        itemsDiscovered = acc.itemsDiscovered + result.itemsDiscovered,
                        itemsCreated = acc.itemsCreated + result.itemsCreated,
                        itemsSkipped = acc.itemsSkipped + result.itemsSkipped,
                        errors = acc.errors + result.errors,
                    )
                }

            val duration = Duration.between(startTime, Instant.now())
            val statusIcon = if (totalResult.errors > 0) "✗" else "✓"
            logger.debug {
                "$statusIcon Completed '${connectionDocument.name}' in ${duration.toMillis()}ms | " +
                    "Discovered: ${totalResult.itemsDiscovered}, Created: ${totalResult.itemsCreated}, " +
                    "Skipped: ${totalResult.itemsSkipped}, Errors: ${totalResult.errors}"
            }

            // Handle authentication errors - set connectionDocument to INVALID and create UserTask for manual fix
            if (totalResult.authenticationError) {
                handlePollingError(connectionDocument, context, totalResult)
            }

            // Update last poll time
            lastPollTimes[connectionId] = now
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Handle authentication errors by setting connectionDocument to the INVALID state and creating PendingTask.
     * Only called when the authenticationError flag is true (not for temporary network errors).
     */
    private suspend fun handlePollingError(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
        result: PollingResult,
    ) {
        logger.warn { "ConnectionDocument ${connectionDocument.name} encountered authentication error during polling" }

        // Set connectionDocument to INVALID state using copy
        val invalidConnection = connectionDocument.copy(state = ConnectionStateEnum.INVALID)

        connectionService.save(invalidConnection)
        logger.debug { "ConnectionDocument ${connectionDocument.name} (${connectionDocument.id}) set to INVALID state" }

        // Check if UserTask already exists for this connectionDocument
        val clientId =
            context.clients.firstOrNull()?.id
                ?: context.projects.firstOrNull()?.clientId
                ?: run {
                    logger.warn { "No clients found for connectionDocument ${connectionDocument.name}, cannot create UserTask" }
                    return
                }

        val correlationId = "connectionDocument-error-${connectionDocument.id}"

        // Create UserTask for manual fix - UserTaskService handles duplicates
        val taskDescription =
            buildString {
                appendLine("ConnectionDocument: ${connectionDocument.name}")
                appendLine("Type: ${connectionDocument::class.simpleName}")
                appendLine()
                appendLine("Status: ConnectionDocument failed during polling with ${result.errors} error(s)")
                appendLine()
                appendLine("Action Required:")
                appendLine("1. Check connectionDocument credentials and configuration")
                appendLine("2. Verify network connectivity")
                appendLine("3. Test connectionDocument manually")
                appendLine("4. Update connectionDocument settings if needed")
            }

        userTaskService.createTask(
            title = "Fix ConnectionDocument: ${connectionDocument.name}",
            description = taskDescription,
            projectId = null,
            clientId = clientId,
            correlationId = correlationId,
        )

        logger.debug { "Created UserTask for INVALID connectionDocument: ${connectionDocument.name}" }
    }

    private fun getPollingInterval(connectionDocument: ConnectionDocument): Duration =
        when (connectionDocument.connectionType) {
            ConnectionDocument.ConnectionTypeEnum.HTTP -> pollingProperties.http

            ConnectionDocument.ConnectionTypeEnum.IMAP -> pollingProperties.imap

            ConnectionDocument.ConnectionTypeEnum.POP3 -> pollingProperties.pop3

            ConnectionDocument.ConnectionTypeEnum.SMTP -> Duration.ofDays(365)

            // SMTP is for sending only
            ConnectionDocument.ConnectionTypeEnum.OAUTH2 -> pollingProperties.oauth2
        }
}

/**
 * Result of a polling operation.
 */
data class PollingResult(
    val itemsDiscovered: Int = 0,
    val itemsCreated: Int = 0,
    val itemsSkipped: Int = 0,
    val errors: Int = 0,
    val authenticationError: Boolean = false,
)
