package com.jervis.service.indexing

import com.jervis.repository.mongo.AtlassianConnectionMongoRepository
import com.jervis.repository.mongo.EmailAccountMongoRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Universal continuous indexing engine that starts all AbstractContinuousIndexer implementations.
 *
 * Pattern (similar to BackgroundEngine):
 * - Each account/connection gets its own coroutine running continuous indexing
 * - Loop: poll DB for NEW items → process → mark INDEXED → repeat
 * - If no items: wait 30s, then retry
 * - If error: log, continue to next item
 *
 * This replaces old @Scheduled fixed-delay polling with true continuous processing.
 */
@Service
class ContinuousIndexingEngine(
    private val emailAccounts: EmailAccountMongoRepository,
    private val atlassianConnections: AtlassianConnectionMongoRepository,
    private val emailIndexer: com.jervis.service.listener.email.EmailContinuousIndexer,
    private val confluenceIndexer: com.jervis.service.confluence.ConfluenceContinuousIndexer,
    private val jiraIndexer: com.jervis.service.jira.JiraContinuousIndexer,
    // TODO: Add GitContinuousIndexer when implemented
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private val indexerJobs = mutableListOf<Job>()

    @PostConstruct
    fun start() {
        logger.info { "ContinuousIndexingEngine starting..." }

        // Start email indexing for all active accounts
        val emailJob = scope.launch {
            try {
                logger.info { "Email continuous indexing loop STARTED" }
                runEmailIndexingLoop()
            } catch (e: CancellationException) {
                logger.info { "Email indexing loop CANCELLED" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Email indexing loop FAILED!" }
            }
        }
        indexerJobs.add(emailJob)

        // Start Confluence indexing for all VALID connections
        val confluenceJob = scope.launch {
            try {
                logger.info { "Confluence continuous indexing loop STARTED" }
                runConfluenceIndexingLoop()
            } catch (e: CancellationException) {
                logger.info { "Confluence indexing loop CANCELLED" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Confluence indexing loop FAILED!" }
            }
        }
        indexerJobs.add(confluenceJob)

        // Start Jira indexing for all VALID connections
        val jiraJob = scope.launch {
            try {
                logger.info { "Jira continuous indexing loop STARTED" }
                runJiraIndexingLoop()
            } catch (e: CancellationException) {
                logger.info { "Jira indexing loop CANCELLED" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Jira indexing loop FAILED!" }
            }
        }
        indexerJobs.add(jiraJob)

        logger.info { "ContinuousIndexingEngine initialization complete - all loops launched" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "ContinuousIndexingEngine stopping..." }
        indexerJobs.forEach { it.cancel() }
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5000) {
                    indexerJobs.forEach { it.join() }
                }
            }
        } catch (_: Exception) {
            logger.debug { "ContinuousIndexingEngine shutdown timeout" }
        }
    }

    /**
     * Email indexing loop - processes all active email accounts continuously.
     * Each account runs AbstractContinuousIndexer which polls DB for NEW messages.
     */
    private suspend fun runEmailIndexingLoop() {
        logger.info { "Email indexing loop entering main loop..." }

        while (scope.isActive) {
            try {
                // Find all active email accounts
                val accounts = emailAccounts.findAllByIsActiveTrue().toList()

                if (accounts.isEmpty()) {
                    logger.debug { "No active email accounts, sleeping 60s..." }
                    delay(60_000)
                    continue
                }

                logger.info { "Starting continuous indexing for ${accounts.size} email account(s)" }

                // Start continuous indexer for each account (they run in parallel)
                val jobs = accounts.map { account ->
                    scope.launch {
                        try {
                            logger.info { "Email indexer STARTED for account ${account.id} (${account.email})" }
                            emailIndexer.startContinuousIndexing(account)
                        } catch (e: CancellationException) {
                            logger.info { "Email indexer CANCELLED for account ${account.id}" }
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Email indexer FAILED for account ${account.id}" }
                        }
                    }
                }

                // Wait for all indexers to finish (they shouldn't finish unless cancelled or error)
                jobs.forEach { it.join() }

                // If we get here, all indexers finished - wait before retrying
                logger.warn { "All email indexers finished unexpectedly, retrying in 60s..." }
                delay(60_000)
            } catch (e: CancellationException) {
                logger.info { "Email indexing loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Email indexing loop error - will retry in 60s" }
                delay(60_000)
            }
        }

        logger.warn { "Email indexing loop exited - scope is no longer active" }
    }

    /**
     * Confluence indexing loop - processes all VALID Atlassian connections continuously.
     */
    private suspend fun runConfluenceIndexingLoop() {
        logger.info { "Confluence indexing loop entering main loop..." }

        while (scope.isActive) {
            try {
                // Find all VALID Atlassian connections (Confluence uses same connection as Jira)
                val connections = atlassianConnections.findAll().toList()
                    .filter { it.authStatus == "VALID" }

                if (connections.isEmpty()) {
                    logger.debug { "No VALID Atlassian connections for Confluence, sleeping 60s..." }
                    delay(60_000)
                    continue
                }

                logger.info { "Starting continuous indexing for ${connections.size} Confluence connection(s)" }

                // Start continuous indexer for each connection
                val jobs = connections.map { connection ->
                    scope.launch {
                        try {
                            logger.info { "Confluence indexer STARTED for connection ${connection.id}" }
                            confluenceIndexer.startContinuousIndexing(connection)
                        } catch (e: CancellationException) {
                            logger.info { "Confluence indexer CANCELLED for connection ${connection.id}" }
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Confluence indexer FAILED for connection ${connection.id}" }
                        }
                    }
                }

                // Wait for all indexers to finish
                jobs.forEach { it.join() }

                // If we get here, all indexers finished - wait before retrying
                logger.warn { "All Confluence indexers finished unexpectedly, retrying in 60s..." }
                delay(60_000)
            } catch (e: CancellationException) {
                logger.info { "Confluence indexing loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Confluence indexing loop error - will retry in 60s" }
                delay(60_000)
            }
        }

        logger.warn { "Confluence indexing loop exited - scope is no longer active" }
    }

    /**
     * Jira indexing loop - processes all VALID Atlassian connections continuously.
     */
    private suspend fun runJiraIndexingLoop() {
        logger.info { "Jira indexing loop entering main loop..." }

        while (scope.isActive) {
            try {
                // Find all VALID Atlassian connections
                val connections = atlassianConnections.findAll().toList()
                    .filter { it.authStatus == "VALID" }

                if (connections.isEmpty()) {
                    logger.debug { "No VALID Atlassian connections for Jira, sleeping 60s..." }
                    delay(60_000)
                    continue
                }

                logger.info { "Starting continuous indexing for ${connections.size} Jira connection(s)" }

                // Start continuous indexer for each connection
                val jobs = connections.map { connection ->
                    scope.launch {
                        try {
                            logger.info { "Jira indexer STARTED for connection ${connection.id}" }
                            jiraIndexer.startContinuousIndexing(connection)
                        } catch (e: CancellationException) {
                            logger.info { "Jira indexer CANCELLED for connection ${connection.id}" }
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Jira indexer FAILED for connection ${connection.id}" }
                        }
                    }
                }

                // Wait for all indexers to finish
                jobs.forEach { it.join() }

                // If we get here, all indexers finished - wait before retrying
                logger.warn { "All Jira indexers finished unexpectedly, retrying in 60s..." }
                delay(60_000)
            } catch (e: CancellationException) {
                logger.info { "Jira indexing loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Jira indexing loop error - will retry in 60s" }
                delay(60_000)
            }
        }

        logger.warn { "Jira indexing loop exited - scope is no longer active" }
    }
}
