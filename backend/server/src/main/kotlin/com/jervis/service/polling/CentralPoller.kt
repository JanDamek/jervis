package com.jervis.service.polling

import com.jervis.entity.connection.Connection
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ConnectionMongoRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.polling.handler.PollingHandler
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
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
 * 3. Delegate to appropriate handler (JiraPollingHandler, ConfluencePollingHandler, etc.)
 * 4. Handler discovers new/updated items and creates NEW state documents
 * 5. ContinuousIndexer picks up NEW items and indexes them
 */
@Service
class CentralPoller(
    private val connectionRepository: ConnectionMongoRepository,
    private val connectionService: ConnectionService,
    private val clientRepository: ClientMongoRepository,
    private val handlers: List<PollingHandler>,
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

                // Sleep before next iteration (minimum interval between full cycles)
                delay(5_000)
            }
        }
    }

    private suspend fun pollAllConnections() {
        // Find all enabled connections - use Flow + async pattern per guidelines
        val jobs = mutableListOf<Job>()
        var count = 0

        connectionService.findAllEnabled().collect { connection ->
            count++
            val job = scope.async {
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
        // Check if enough time has passed since last poll
        val now = Instant.now()
        val lastPoll = lastPollTimes[connection.id.toString()]
        val interval = getPollingInterval(connection)

        if (lastPoll != null && Duration.between(lastPoll, now) < interval) {
            logger.trace { "Skipping ${connection.name}: polling interval not reached" }
            return
        }

        // Find handler for this connection type
        val handler = handlers.firstOrNull { it.canHandle(connection) }

        if (handler == null) {
            logger.warn { "No handler found for connection type: ${connection::class.simpleName}" }
            return
        }

        // Find all clients/projects using this connection - use Flow.toList() for processing
        val clients = clientRepository.findByConnectionIdsContaining(connection.id).toList()

        if (clients.isEmpty()) {
            logger.debug { "Connection ${connection.name} not used by any clients, skipping" }
            return
        }

        logger.info { "Polling ${connection.name} (${connection::class.simpleName}) for ${clients.size} clients" }

        // Parse credentials if needed (plain text, no decryption)
        val credentials = if (connection is Connection.HttpConnection) {
            connectionService.parseCredentials(connection)
        } else {
            null
        }

        // Execute polling
        val result = handler.poll(connection, credentials, clients)

        logger.info {
            "Polled ${connection.name}: discovered=${result.itemsDiscovered}, " +
            "created=${result.itemsCreated}, skipped=${result.itemsSkipped}"
        }

        // Update last poll time
        lastPollTimes[connection.id.toString()] = now
    }

    private fun getPollingInterval(connection: Connection): Duration {
        return when (connection) {
            is Connection.HttpConnection -> Duration.ofMinutes(5)
            is Connection.ImapConnection -> Duration.ofMinutes(1)
            is Connection.Pop3Connection -> Duration.ofMinutes(2)
            is Connection.SmtpConnection -> Duration.ofHours(1) // SMTP usually for sending, not polling
            is Connection.OAuth2Connection -> Duration.ofMinutes(5)
        }
    }
}

/**
 * Result of polling operation.
 */
data class PollingResult(
    val itemsDiscovered: Int = 0,
    val itemsCreated: Int = 0,
    val itemsSkipped: Int = 0,
    val errors: Int = 0,
)
