package com.jervis.service.listener.email

import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
            findNextAccountToPoll()
                ?.let { account -> processAccountWithTimestampUpdate(account) }
                ?: logger.debug { "No active email accounts to poll" }
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled email poll" }
        }
    }

    suspend fun triggerManualPoll(accountId: String) {
        logger.info { "Manually triggering poll for account $accountId" }

        val account = findAccountById(accountId)
        processAccountWithTimestampUpdate(account)
    }

    private suspend fun findNextAccountToPoll() =
        emailAccountRepository
            .findFirstByIsActiveTrueOrderByLastIndexedAtAscCreatedAtAsc()
            .awaitSingleOrNull()

    private suspend fun findAccountById(accountId: String) =
        emailAccountRepository
            .findById(ObjectId(accountId))
            .awaitSingleOrNull()
            ?: throw IllegalArgumentException("Email account not found: $accountId")

    private suspend fun processAccountWithTimestampUpdate(account: EmailAccountDocument) {
        logger.info { "Polling account: ${account.email} (${account.id})" }
        emailIndexingOrchestrator.processAccount(account)
        updateAccountTimestamp(account.id)
    }

    private suspend fun updateAccountTimestamp(accountId: ObjectId) {
        val account = emailAccountRepository.findById(accountId).awaitSingleOrNull() ?: return

        val updated =
            account.copy(
                lastIndexedAt = Instant.now(),
                lastPolledAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        emailAccountRepository.save(updated).awaitSingleOrNull()
    }
}
