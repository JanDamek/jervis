package com.jervis.service.listener.email

import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.EmailAccountMongoRepository
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

private val logger = KotlinLogging.logger {}

/**
 * Periodic poller for email accounts.
 * Connects to IMAP servers and discovers new messages.
 * Saves discovered messages to MongoDB with state NEW.
 *
 * EmailContinuousIndexer then picks up NEW messages and indexes them to RAG.
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class EmailContinuousPoller(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailIndexingOrchestrator: EmailIndexingOrchestrator,
) : AbstractPeriodicPoller<EmailAccountDocument>() {

    override val pollerName = "EmailContinuousPoller"
    override val pollingIntervalMs: Long = 300_000L // Poll every 5 minutes
    override val initialDelayMs: Long = 30_000L // Start after 30 seconds
    override val cycleDelayMs: Long = 60_000L // Check accounts every 1 minute

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $pollerName..." }
        scope.launch {
            startPeriodicPolling()
        }
    }

    override fun accountsFlow(): Flow<EmailAccountDocument> =
        emailAccountRepository.findAll()

    override suspend fun getLastPollTime(account: EmailAccountDocument): Long? =
        account.lastPolledAt?.toEpochMilli()

    override suspend fun executePoll(account: EmailAccountDocument): Boolean {
        // Skip inactive accounts
        if (!account.isActive) {
            logger.debug { "[$pollerName] Skipping inactive account ${account.id} (${account.email})" }
            return false
        }

        return runCatching {
            emailIndexingOrchestrator.syncMessageHeaders(account)
            true
        }.onFailure { e ->
            logger.error(e) { "[$pollerName] Failed to poll account ${account.id} (${account.email}): ${e.message}" }

            // Mark account as inactive on auth errors (IMAP login failure)
            if (isAuthError(e)) {
                logger.warn { "[$pollerName] Auth error for account ${account.id} (${account.email}), marking as inactive" }
                val updated = account.copy(
                    isActive = false,
                    lastAuthCheckedAt = java.time.Instant.now(),
                    lastErrorMessage = e.message?.take(500)
                )
                emailAccountRepository.save(updated)
                // TODO: Create user task to notify about auth failure
            }
        }.getOrDefault(false)
    }

    private fun isAuthError(e: Throwable): Boolean =
        e.message?.contains("authentication", ignoreCase = true) == true ||
            e.message?.contains("login", ignoreCase = true) == true ||
            e.message?.contains("password", ignoreCase = true) == true ||
            e.message?.contains("credentials", ignoreCase = true) == true

    override suspend fun updateLastPollTime(account: EmailAccountDocument, timestamp: Long) {
        val updated = account.copy(lastPolledAt = java.time.Instant.ofEpochMilli(timestamp))
        emailAccountRepository.save(updated)
    }

    override fun accountLogLabel(account: EmailAccountDocument): String =
        "Email:${account.email}"
}
