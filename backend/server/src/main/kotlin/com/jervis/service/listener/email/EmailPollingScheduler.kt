package com.jervis.service.listener.email

import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class EmailPollingScheduler(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailIndexingOrchestrator: EmailIndexingOrchestrator,
) {
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    suspend fun pollNextAccount() {
        runCatching {
            val account = findNextAccountToPoll()
            if (account != null) {
                processAccountWithTimestampUpdate(account)
            } else {
                logger.debug { "No active email accounts to poll" }
            }
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled email poll" }
        }
    }

    suspend fun triggerManualPoll(accountId: String) {
        logger.info { "Manually triggering poll for account $accountId" }

        val account = findAccountById(accountId)
        processAccountWithTimestampUpdate(account)
    }

    /** Manual trigger to pick the next eligible email account automatically. */
    suspend fun triggerNext() {
        logger.info { "Manually triggering Email indexing (auto-select next account)" }
        pollNextAccount()
    }

    private suspend fun findNextAccountToPoll(): EmailAccountDocument? =
        emailAccountRepository.findFirstByIsActiveTrueOrderByLastPolledAtAsc()

    private suspend fun findAccountById(accountId: String): EmailAccountDocument =
        emailAccountRepository.findById(ObjectId(accountId))
            ?: throw IllegalArgumentException("Email account not found: $accountId")

    private suspend fun processAccountWithTimestampUpdate(account: EmailAccountDocument) {
        logger.info { "Syncing headers for account: ${account.email} (${account.id})" }

        try {
            emailIndexingOrchestrator.syncMessageHeaders(account)
            updateAccountTimestamp(account.id)
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync account ${account.id}, will retry on next poll cycle" }
            // Don't update timestamp on failure - account stays as "needs sync"
            // Scheduler will retry on next cycle (60s later)
        }
    }

    private suspend fun updateAccountTimestamp(accountId: ObjectId) {
        val account = emailAccountRepository.findById(accountId) ?: return

        val updated = account.copy(lastPolledAt = Instant.now())

        emailAccountRepository.save(updated)
    }

    /**
     * Returns human-readable reason why email indexing is IDLE despite having NEW messages.
     * Used by UI to explain why indexer is not processing.
     */
    suspend fun getIdleReason(newCount: Long): String? {
        if (newCount == 0L) return null // No NEW messages, no reason needed

        val activeAccountsCount = emailAccountRepository.countByIsActiveTrue()
        if (activeAccountsCount == 0L) {
            return "No active email accounts configured"
        }

        val nextAccount = findNextAccountToPoll()
        if (nextAccount == null) {
            return "No email accounts ready to poll (all recently polled)"
        }

        // Has active accounts and they're ready, so scheduler will pick it up soon
        return "Waiting for next scheduled poll (runs every 60s)"
    }
}
