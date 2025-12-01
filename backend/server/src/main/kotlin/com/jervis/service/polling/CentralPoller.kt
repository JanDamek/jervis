package com.jervis.service.polling

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.repository.ClientMongoRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.polling.handler.PollingHandler
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
 * 3. Delegate to the appropriate handler (JiraPollingHandler, ConfluencePollingHandler, etc.)
 * 4. Handler discovers new/updated items and creates NEW state documents
 * 5. ContinuousIndexer picks up NEW items and indexes them
 */
@Service
class CentralPoller(
    private val connectionService: ConnectionService,
    private val clientRepository: ClientMongoRepository,
    private val handlers: List<PollingHandler>,
    private val pendingTaskService: com.jervis.service.background.PendingTaskService,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track last poll time per connection
    private val lastPollTimes = mutableMapOf<String, Instant>()

    @PostConstruct
    fun start() {
        logger.info { "Starting CentralPoller..." }

        scope.launch {
            // Initial delay to let application fully start
            delay(10_000)
            logger.info { "CentralPoller initial delay complete, beginning polling loop" }

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
        val jobs = mutableListOf<Job>()
        var count = 0

        connectionService.findAllValid().collect { connection ->
            count++
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

        if (count == 0) {
            logger.debug { "No enabled connections found, skipping poll cycle" }
            return
        }

        logger.debug { "Polling $count enabled connections" }

        // Wait for all polling jobs to complete
        jobs.joinAll()
    }

    private suspend fun pollConnection(connection: Connection) {
        val now = Instant.now()
        val lastPoll = lastPollTimes[connection.id.toString()]
        val interval = getPollingInterval(connection)

        if (lastPoll != null && Duration.between(lastPoll, now) < interval) {
            logger.trace { "Skipping ${connection.name}: polling interval not reached" }
            return
        }

        // Find ALL handlers for this connection type (e.g., Atlassian → Jira + Confluence)
        val matchingHandlers = handlers.filter { it.canHandle(connection) }

        if (matchingHandlers.isEmpty()) {
            logger.warn { "No handler found for connection type: ${connection::class.simpleName}" }
            return
        }

        // Find all clients/projects using this connection - use Flow.toList() for processing
        val clients = clientRepository.findByConnectionIdsContaining(connection.id).toList()

        if (clients.isEmpty()) {
            logger.debug { "Connection ${connection.name} not used by any clients, skipping" }
            return
        }

        logger.info {
            "Polling ${connection.name} (${connection::class.simpleName}) for ${clients.size} clients with ${matchingHandlers.size} handler(s)"
        }

        // Execute all matching handlers in parallel (e.g., Jira + Confluence for Atlassian)
        val results =
            coroutineScope {
                matchingHandlers
                    .map { handler ->
                        async {
                            try {
                                handler.poll(connection, clients)
                            } catch (e: Exception) {
                                logger.error(e) { "Error in handler ${handler::class.simpleName} for connection ${connection.name}" }
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

        logger.info {
            "Polled ${connection.name}: discovered=${totalResult.itemsDiscovered}, " +
                "created=${totalResult.itemsCreated}, skipped=${totalResult.itemsSkipped}, errors=${totalResult.errors}"
        }

        // Handle authentication errors - set connection to INVALID and create UserTask
        if (totalResult.authenticationError) {
            handlePollingError(connection, clients, totalResult)
        }

        // Update last poll time
        lastPollTimes[connection.id.toString()] = now
    }

    /**
     * Handle authentication errors by setting connection to INVALID state and creating UserTask.
     * Only called when authenticationError flag is true (not for temporary network errors).
     */
    private suspend fun handlePollingError(
        connection: Connection,
        clients: List<ClientDocument>,
        result: PollingResult,
    ) {
        logger.warn { "Connection ${connection.name} encountered authentication error during polling" }

        // Set connection to INVALID state
        val updatedConnection = when (connection) {
            is Connection.HttpConnection -> connection.copy(state = com.jervis.entity.connection.ConnectionStateEnum.INVALID)
            is Connection.ImapConnection -> connection.copy(state = com.jervis.entity.connection.ConnectionStateEnum.INVALID)
            is Connection.Pop3Connection -> connection.copy(state = com.jervis.entity.connection.ConnectionStateEnum.INVALID)
            is Connection.SmtpConnection -> connection.copy(state = com.jervis.entity.connection.ConnectionStateEnum.INVALID)
            is Connection.OAuth2Connection -> connection.copy(state = com.jervis.entity.connection.ConnectionStateEnum.INVALID)
        }
        
        connectionService.update(updatedConnection)
        logger.info { "Connection ${connection.name} (${connection.id}) set to INVALID state" }

        // Check if UserTask already exists for this connection
        val connectionSource = "connection://${connection.id.toHexString()}"
        val clientId = clients.firstOrNull()?.id ?: run {
            logger.warn { "No clients found for connection ${connection.name}, cannot create UserTask" }
            return
        }

        // Find existing CONNECTION_ERROR tasks for this client and connection
        val existingTasks = pendingTaskService.findAllTasks(
            taskType = com.jervis.dto.PendingTaskTypeEnum.CONNECTION_ERROR,
            state = com.jervis.dto.PendingTaskStateEnum.READY_FOR_QUALIFICATION
        ).toList()

        val taskExists = existingTasks.any { task ->
            task.clientId == clientId && task.content.contains(connectionSource)
        }

        if (!taskExists) {
            // Create UserTask for manual fix
            val taskContent = buildString {
                appendLine("Connection Polling Error")
                appendLine()
                appendLine("Connection: ${connection.name}")
                appendLine("Type: ${connection::class.simpleName}")
                appendLine("Source: $connectionSource")
                appendLine()
                appendLine("Status: Connection failed during polling with ${result.errors} error(s)")
                appendLine()
                appendLine("Action Required:")
                appendLine("1. Check connection credentials and configuration")
                appendLine("2. Verify network connectivity")
                appendLine("3. Test connection manually")
                appendLine("4. Update connection settings if needed")
            }

            pendingTaskService.createTask(
                taskType = com.jervis.dto.PendingTaskTypeEnum.CONNECTION_ERROR,
                content = taskContent,
                projectId = null,
                clientId = clients.firstOrNull()?.id ?: return
            )

            logger.info { "Created UserTask for INVALID connection: ${connection.name}" }
        } else {
            logger.debug { "UserTask already exists for connection ${connection.name}, skipping creation" }
        }
    }

    private fun getPollingInterval(connection: Connection): Duration =
        when (connection) {
            is Connection.HttpConnection -> Duration.ofMinutes(5)

            is Connection.ImapConnection -> Duration.ofMinutes(1)

            is Connection.Pop3Connection -> Duration.ofMinutes(2)

            is Connection.SmtpConnection -> Duration.ofHours(1)

            // SMTP usually for sending, not polling
            is Connection.OAuth2Connection -> Duration.ofMinutes(5)
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
