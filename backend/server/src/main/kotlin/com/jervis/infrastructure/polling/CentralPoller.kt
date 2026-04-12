package com.jervis.infrastructure.polling

import com.jervis.common.types.ClientId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.connection.PollingIntervalSettingsDocument
import com.jervis.connection.ConnectionDocument
import com.jervis.client.ClientRepository
import com.jervis.connection.PollingIntervalSettingsRepository
import com.jervis.project.ProjectRepository
import com.jervis.connection.ConnectionService
import com.jervis.infrastructure.oauth2.OAuth2Service
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.PollingHandler
import com.jervis.task.UserTaskService
import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
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
    private val notificationRpcImpl: com.jervis.rpc.NotificationRpcImpl,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track last poll time per connection
    private val lastPollTimes = mutableMapOf<String, Instant>()

    // Mutex per connection to prevent parallel polling of the same connection
    private val connectionLocks = mutableMapOf<String, Mutex>()

    // Track consecutive auth failures per connection for retry-before-INVALID logic
    private val authFailureCounts = mutableMapOf<String, Int>()
    private val AUTH_FAILURE_THRESHOLD = 3  // Mark INVALID only after 3 consecutive auth failures

    private lateinit var handlersByProvider: Map<ProviderEnum, PollingHandler>

    @PostConstruct
    fun start() {
        logger.debug { "Starting CentralPoller..." }
        handlersByProvider = handlers.associateBy { it.provider }

        // Main polling loop — active/valid connections
        scope.launch {
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

        // Recovery loop — periodically re-test INVALID connections.
        // If the provider is back (e.g. network restored, token auto-refreshed),
        // the connection transitions back to VALID automatically.
        scope.launch {
            delay(60_000)  // Start after 1 minute
            logger.info { "CentralPoller: INVALID connection recovery loop started (5 min interval)" }

            while (isActive) {
                try {
                    recoverInvalidConnections()
                } catch (e: Exception) {
                    logger.warn { "Error in INVALID recovery loop: ${e.message}" }
                }
                delay(300_000)  // Every 5 minutes
            }
        }
    }

    /**
     * Attempt to recover INVALID connections by re-testing them.
     *
     * For OAuth2: try refreshing the token. For BEARER/BASIC: try a lightweight
     * API call. If successful → mark VALID + emit notification. If still failing →
     * keep INVALID, no spam notifications (the original one was already sent).
     */
    private suspend fun recoverInvalidConnections() {
        val invalidConnections = connectionService.findByState(ConnectionStateEnum.INVALID).toList()
        if (invalidConnections.isEmpty()) return

        logger.info { "RECOVERY: testing ${invalidConnections.size} INVALID connection(s)" }

        for (connection in invalidConnections) {
            try {
                var recovered = false

                // OAuth2: try token refresh
                if (connection.authType == AuthTypeEnum.OAUTH2 && connection.refreshToken != null) {
                    recovered = oauth2Service.refreshAccessToken(connection, force = true)
                }

                // If OAuth2 refresh worked, or for BEARER/BASIC — try a lightweight poll
                if (recovered || connection.authType != AuthTypeEnum.OAUTH2) {
                    val handler = handlersByProvider[connection.provider]
                    if (handler != null) {
                        val fresh = connectionService.findById(connection.id) ?: connection
                        val clients = clientRepository.findByConnectionIdsContaining(connection.id).toList()
                        val context = PollingContext(clients = clients, projects = emptyList(), connectionId = connection.id)

                        val result = try {
                            handler.poll(fresh, context)
                        } catch (e: com.jervis.common.http.ProviderAuthException) {
                            PollingResult(errors = 1, authenticationError = true)
                        } catch (e: Exception) {
                            PollingResult(errors = 1)
                        }

                        recovered = !result.authenticationError && result.errors == 0
                    }
                }

                if (recovered) {
                    connectionService.save(connection.copy(state = ConnectionStateEnum.VALID))
                    logger.info { "RECOVERY: '${connection.name}' recovered → VALID" }

                    val clientId = clientRepository.findByConnectionIdsContaining(connection.id)
                        .toList().firstOrNull()?.id?.toString() ?: ""
                    if (clientId.isNotBlank()) {
                        notificationRpcImpl.emitConnectionStateChanged(
                            clientId = clientId,
                            connectionId = connection.id.toString(),
                            connectionName = connection.name,
                            newState = "VALID",
                            message = "Spojení '${connection.name}' se obnovilo.",
                        )
                    }
                } else {
                    logger.debug { "RECOVERY: '${connection.name}' still INVALID" }
                }
            } catch (e: Exception) {
                logger.warn { "RECOVERY: failed for '${connection.name}': ${e.message}" }
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

        connectionService.findAllPollable().collect { connection ->
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

            // Find active projects that reference this connection in their resources
            val projects = projectRepository.findByResourcesConnectionIdAndActiveTrue(connectionDocument.id.value).toList()

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
                } catch (e: com.jervis.common.http.ProviderAuthException) {
                    logger.error { "Auth error in handler $handlerName for connection ${effectiveConnection.name}: ${e.message}" }
                    PollingResult(errors = 1, authenticationError = true)
                } catch (e: ConnectException) {
                    // Network unreachable — NOT an auth failure, will retry next cycle
                    logger.debug { "Network error (ConnectException) for '${effectiveConnection.name}': ${e.message}" }
                    PollingResult(errors = 1, authenticationError = false)
                } catch (e: SocketTimeoutException) {
                    logger.debug { "Network timeout for '${effectiveConnection.name}': ${e.message}" }
                    PollingResult(errors = 1, authenticationError = false)
                } catch (e: ConnectTimeoutException) {
                    logger.debug { "Connect timeout for '${effectiveConnection.name}': ${e.message}" }
                    PollingResult(errors = 1, authenticationError = false)
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

            // Handle authentication errors — retry before marking INVALID
            if (totalResult.authenticationError) {
                if (effectiveConnection.authType == AuthTypeEnum.OAUTH2 && effectiveConnection.refreshToken != null) {
                    val refreshed = oauth2Service.refreshAccessToken(effectiveConnection, force = true)
                    if (refreshed) {
                        logger.info { "OAuth2 token refreshed for '${connectionDocument.name}' after auth error, will retry next cycle" }
                        authFailureCounts.remove(connectionId)
                        lastPollTimes.remove(connectionId) // Force immediate re-poll
                        return
                    }
                }
                // Increment consecutive failure count — only mark INVALID after threshold
                val failCount = (authFailureCounts[connectionId] ?: 0) + 1
                authFailureCounts[connectionId] = failCount
                if (failCount < AUTH_FAILURE_THRESHOLD) {
                    logger.warn {
                        "Auth failure ${failCount}/${AUTH_FAILURE_THRESHOLD} for '${connectionDocument.name}' — " +
                            "will retry next cycle (NOT marking INVALID yet)"
                    }
                    // Force re-poll sooner (halve the interval)
                    lastPollTimes.remove(connectionId)
                    return
                }
                // Threshold reached — mark INVALID + notify
                logger.error {
                    "Auth failure threshold reached (${failCount}/${AUTH_FAILURE_THRESHOLD}) for '${connectionDocument.name}' — marking INVALID"
                }
                handlePollingError(connectionDocument, context, totalResult)
                authFailureCounts.remove(connectionId)
            } else if (totalResult.errors == 0) {
                // Successful poll — reset failure counter
                authFailureCounts.remove(connectionId)
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

        // Emit ConnectionStateChanged event via NotificationRpcImpl so ALL connected
        // clients (desktop, iOS, watch) see the status change immediately. This is the
        // SINGLE remote notification channel — no silent failures.
        val clientId = context.clients.firstOrNull()?.id?.toString() ?: ""
        if (clientId.isNotBlank()) {
            try {
                notificationRpcImpl.emitConnectionStateChanged(
                    clientId = clientId,
                    connectionId = connectionDocument.id.toString(),
                    connectionName = connectionDocument.name,
                    newState = "INVALID",
                    message = "Spojení '${connectionDocument.name}' selhalo po ${AUTH_FAILURE_THRESHOLD} pokusech. Zkontrolujte přihlašovací údaje.",
                )
            } catch (e: Exception) {
                logger.warn { "Failed to emit ConnectionStateChanged event: ${e.message}" }
            }
        }

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
    ): com.jervis.task.TaskDocument =
        com.jervis.task.TaskDocument(
            content = description,
            clientId = clientId,
            correlationId = correlationId,
            type = TaskTypeEnum.SYSTEM, // Fallback type
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
