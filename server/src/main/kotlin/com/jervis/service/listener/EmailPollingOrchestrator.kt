package com.jervis.service.listener

import com.jervis.entity.mongo.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import com.jervis.service.indexing.EmailIndexingService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates email polling with round-robin strategy.
 * Uses fixedDelay to ensure sequential processing - next run only after previous completes.
 * Processes oldest unindexed account first based on lastIndexedAt.
 */
@Service
class EmailPollingOrchestrator(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailListener: EmailListener,
    private val emailIndexingService: EmailIndexingService,
) {
    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 30 * 1000)
    suspend fun pollNextAccount() {
        try {
            val account =
                emailAccountRepository.findFirstByIsActiveTrueOrderByLastIndexedAtAscCreatedAtAsc().awaitSingleOrNull()

            if (account == null) {
                logger.debug { "No active email accounts to poll" }
                return
            }

            logger.info { "Polling account: ${account.email} (${account.id})" }

            pollAccount(account)
        } catch (e: Exception) {
            logger.error(e) { "Error during scheduled email poll" }
        }
    }

    private suspend fun pollAccount(account: EmailAccountDocument) {
        logger.info { "Polling email account ${account.id} (${account.email})" }

        try {
            val accountId = account.id.toHexString()

            val fetchedMessages = emailListener.pollEmailAccount(account, null)
            logger.info { "Fetched ${fetchedMessages.size} total messages from provider for account ${account.id}" }

            val indexedMessageIds = emailIndexingService.getIndexedMessageIds(accountId)
            logger.info { "Found ${indexedMessageIds.size} messages already indexed for account ${account.id}" }

            val fetchedMessageIds = fetchedMessages.map { it.id }.toSet()
            val newMessages = fetchedMessages.filter { it.id !in indexedMessageIds }

            val deletedMessageIds = (indexedMessageIds - fetchedMessageIds).toList()

            if (newMessages.isNotEmpty()) {
                logger.info { "Indexing ${newMessages.size} new messages for account ${account.id}" }
                emailIndexingService.indexEmails(
                    messages = newMessages,
                    clientId = account.clientId,
                    projectId = account.projectId,
                    accountId = accountId,
                )
            }

            if (deletedMessageIds.isNotEmpty()) {
                logger.info { "Deleting ${deletedMessageIds.size} removed messages from vector store for account ${account.id}" }
                emailIndexingService.deleteEmailsFromVectorStore(
                    messageIds = deletedMessageIds,
                    accountId = accountId,
                )
            }

            updateLastIndexed(account)

            logger.info {
                "Successfully polled account ${account.id}: ${newMessages.size} new, ${deletedMessageIds.size} deleted, ${fetchedMessages.size} total"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to poll account ${account.id}" }
        }
    }

    private suspend fun updateLastIndexed(account: EmailAccountDocument) {
        val updated =
            account.copy(
                lastIndexedAt = Instant.now(),
                lastPolledAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        emailAccountRepository.save(updated).awaitSingleOrNull()
    }
}
