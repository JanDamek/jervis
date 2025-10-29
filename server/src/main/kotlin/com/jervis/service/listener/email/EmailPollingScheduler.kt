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

    private suspend fun findNextAccountToPoll(): EmailAccountDocument? =
        emailAccountRepository.findFirstByIsActiveTrueOrderByLastPolledAtAsc()

    private suspend fun findAccountById(accountId: String): EmailAccountDocument =
        emailAccountRepository.findById(ObjectId(accountId))
            ?: throw IllegalArgumentException("Email account not found: $accountId")

    private suspend fun processAccountWithTimestampUpdate(account: EmailAccountDocument) {
        logger.info { "Syncing headers for account: ${account.email} (${account.id})" }
        emailIndexingOrchestrator.syncMessageHeaders(account)
        updateAccountTimestamp(account.id)
    }

    private suspend fun updateAccountTimestamp(accountId: ObjectId) {
        val account = emailAccountRepository.findById(accountId) ?: return

        val updated = account.copy(lastPolledAt = Instant.now())

        emailAccountRepository.save(updated)
    }
}
