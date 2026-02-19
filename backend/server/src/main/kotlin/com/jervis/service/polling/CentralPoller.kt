package com.jervis.service.polling

import com.jervis.common.types.ClientId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.PollingIntervalSettingsDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ClientRepository
import com.jervis.repository.PollingIntervalSettingsRepository
import com.jervis.repository.ProjectRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.oauth2.OAuth2Service
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
 * 3. Delegate to the appropriate handler (BugTrackerPollingHandler, WikiPollingHandler, etc.)
 * 4. Handler discovers new/updated items and creates NEW state documents
 * 5. ContinuousIndexer picks up NEW items and indexes them
 */
@Service
class CentralPoller(
    private val connectionService: ConnectionService,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val handlers: List<PollingHandler>,
    private val userTaskService: UserTaskService,
    private val pollingIntervalSettingsRepository: PollingIntervalSettingsRepository,
    private val oauth2Service: OAuth2Service,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track last poll time per connection
    private val lastPollTimes = mutableMapOf<String, Instant>()

    // Mutex per connection to prevent parallel polling of the same connection
    private val connectionLocks = mutableMapOf<String, Mutex>()

    private lateinit var handlersByProvider: Map<ProviderEnum, PollingHandler>

    @PostConstruct
    fun start() {
        logger.debug { "Starting CentralPoller..." }
        handlersByProvider = handlers.associateBy { it.provider }

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

        // Refresh interval settings from DB at the start of each cycle
        refreshIntervalCache()

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

            // Find handler for this connection provider
            val handler = handlersByProvider[connectionDocument.provider]

            if (handler == null) {
                logger.warn {
                    "No handler found for connection '${connectionDocument.name}' (provider: ${connectionDocument.provider})"
                }
                return
            }

            // Find non-archived clients using this connectionDocument
            val clients = clientRepository.findByArchivedFalseAndConnectionIdsContaining(connectionDocument.id).toList()

            // Find projects that reference this connection in their resources
            val projects = projectRepository.findByResourcesConnectionId(connectionDocument.id.value).toList()

            if (clients.isEmpty() && projects.isEmpty()) {
                logger.debug {
                    "Skipping '${connectionDocument.name}' (id=${connectionDocument.id}): " +
                        "no clients or projects using this connectionDocument"
                }
                return
            }

            // Create a polling context with connection ID for capability config lookups
            val context = PollingContext(
                clients = clients,
                projects = projects,
                connectionId = connectionDocument.id,
            )

            // Refresh OAuth2 token if needed (before polling)
            val effectiveConnection =
                if (connectionDocument.authType == AuthTypeEnum.OAUTH2 && connectionDocument.refreshToken != null) {
                    val refreshed = oauth2Service.refreshAccessToken(connectionDocument)
                    if (refreshed) {
                        connectionService.findById(connectionDocument.id) ?: connectionDocument
                    } else {
                        connectionDocument
                    }
                } else {
                    connectionDocument
                }

            val handlerName = handler::class.simpleName ?: "Unknown"
            val clientNames = clients.joinToString(", ") { it.name }
            val projectNames = projects.joinToString(", ") { it.name }
            val scope =
                if (projects.isNotEmpty()) {
                    "Clients: [$clientNames] | Projects: [$projectNames]"
                } else {
                    "Clients: [$clientNames]"
                }
            logger.debug {
                "Polling '${connectionDocument.name}' (${connectionDocument::class.simpleName}) | $scope | Handler: $handlerName"
            }

            val totalResult =
                try {
                    handler.poll(effectiveConnection, context)
                } catch (e: Exception) {
                    logger.error(e) { "Error in handler $handlerName for connection ${effectiveConnection.name}" }
                    PollingResult(errors = 1)
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
        val clientIdVal =
            context.clients.firstOrNull()?.id
                ?: context.projects.firstOrNull()?.clientId
                ?: run {
                    logger.warn { "No clients found for connectionDocument ${connectionDocument.name}, cannot create UserTask" }
                    return
                }

        val correlationId = "connectionDocument-error-${connectionDocument.id}"

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

        // Create task-like context to use failAndEscalateToUserTask
        val dummyTask = connectionDocumentToTask(clientIdVal, correlationId, taskDescription)
        userTaskService.failAndEscalateToUserTask(dummyTask, "Polling failed with ${result.errors} errors")

        logger.debug { "Created UserTask for INVALID connectionDocument: ${connectionDocument.name}" }
    }

    private fun connectionDocumentToTask(
        clientId: ClientId,
        correlationId: String,
        description: String,
    ): com.jervis.entity.TaskDocument =
        com.jervis.entity.TaskDocument(
            content = description,
            clientId = clientId,
            correlationId = correlationId,
            type = TaskTypeEnum.LINK_PROCESSING, // Fallback type
            sourceUrn = SourceUrn.unknownSource(),
        )

    /** Cached intervals from DB, refreshed every poll cycle. */
    private var cachedIntervals: Map<String, Int> = PollingIntervalSettingsDocument.DEFAULT_INTERVALS

    private suspend fun refreshIntervalCache() {
        try {
            val doc = pollingIntervalSettingsRepository.findById(PollingIntervalSettingsDocument.SINGLETON_ID)
            if (doc != null) {
                cachedIntervals = doc.intervals
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load polling interval settings, using cached/defaults" }
        }
    }

    private fun getPollingInterval(connectionDocument: ConnectionDocument): Duration {
        // Determine the primary capability for this connection
        val capability = connectionDocument.availableCapabilities.firstOrNull {
            it != ConnectionCapability.EMAIL_SEND // EMAIL_SEND is not polled
        }

        if (capability == null) {
            return Duration.ofDays(365) // No pollable capability
        }

        val minutes = cachedIntervals[capability.name]
            ?: PollingIntervalSettingsDocument.DEFAULT_INTERVALS[capability.name]
            ?: 30

        return Duration.ofMinutes(minutes.toLong())
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
