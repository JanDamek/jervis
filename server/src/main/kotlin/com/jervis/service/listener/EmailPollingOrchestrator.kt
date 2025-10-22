package com.jervis.service.listener

import com.jervis.entity.mongo.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import com.jervis.service.indexing.EmailIndexingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates email polling using round-robin strategy.
 * Continuously polls one account at a time with 1-minute pause between accounts.
 * If there are 60 accounts, it will take 1 hour to complete one full cycle.
 *
 * Polling is protected by mutex to prevent concurrent execution.
 */
@Service
class EmailPollingOrchestrator(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailListener: EmailListener,
    private val emailIndexingService: EmailIndexingService,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val currentAccountIndex = AtomicInteger(0)
    private val pauseBetweenAccountsMs = 60 * 1000L
    private val pollingMutex = Mutex()

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 30 * 1000)
    fun scheduledPoll() {
        scope.launch {
            val acquired = pollingMutex.tryLock()
            if (!acquired) {
                logger.info { "Skipping scheduled poll - polling already in progress" }
                return@launch
            }

            try {
                pollNextAccount()
            } catch (e: Exception) {
                logger.error(e) { "Error during scheduled email poll" }
            } finally {
                pollingMutex.unlock()
            }
        }
    }

    private suspend fun pollNextAccount() {
        val activeAccounts =
            mongoTemplate
                .find(
                    Query.query(Criteria.where("isActive").`is`(true)),
                    EmailAccountDocument::class.java,
                ).asFlow()
                .toList()

        if (activeAccounts.isEmpty()) {
            logger.debug { "No active email accounts to poll" }
            return
        }

        val index = currentAccountIndex.getAndUpdate { (it + 1) % activeAccounts.size }
        val account = activeAccounts[index]

        logger.info { "Polling account ${index + 1}/${activeAccounts.size}: ${account.email} (${account.id})" }

        try {
            pollAccount(account)
        } catch (e: Exception) {
            logger.error(e) { "Error polling email account ${account.id}" }
        }

        if (index == activeAccounts.size - 1) {
            logger.info { "Completed one full cycle of ${activeAccounts.size} email accounts" }
        }
    }

    suspend fun pollAllAccounts() {
        pollingMutex.withLock {
            val activeAccounts =
                mongoTemplate
                    .find(
                        Query.query(Criteria.where("isActive").`is`(true)),
                        EmailAccountDocument::class.java,
                    ).asFlow()
                    .toList()

            logger.info { "Manually polling all ${activeAccounts.size} active email accounts" }

            activeAccounts.forEachIndexed { index, account ->
                try {
                    logger.info { "Polling account ${index + 1}/${activeAccounts.size}: ${account.email}" }
                    pollAccount(account)

                    if (index < activeAccounts.size - 1) {
                        delay(pauseBetweenAccountsMs)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error polling email account ${account.id}" }
                }
            }

            logger.info { "Completed manual polling of all accounts" }
        }
    }

    private suspend fun pollAccount(account: EmailAccountDocument) {
        logger.info { "Polling email account ${account.id} (${account.email})" }

        try {
            val accountId = account.id.toHexString()

            // Fetch all messages from provider (including trash)
            val fetchedMessages = emailListener.pollEmailAccount(account, null)
            logger.info { "Fetched ${fetchedMessages.size} total messages from provider for account ${account.id}" }

            // Get set of message IDs already indexed in vector store
            val indexedMessageIds = emailIndexingService.getIndexedMessageIds(accountId)
            logger.info { "Found ${indexedMessageIds.size} messages already indexed for account ${account.id}" }

            // Determine new messages to index
            val fetchedMessageIds = fetchedMessages.map { it.id }.toSet()
            val newMessages = fetchedMessages.filter { it.id !in indexedMessageIds }

            // Determine deleted messages to remove from vector store
            val deletedMessageIds = (indexedMessageIds - fetchedMessageIds).toList()

            // Index new messages
            if (newMessages.isNotEmpty()) {
                logger.info { "Indexing ${newMessages.size} new messages for account ${account.id}" }
                emailIndexingService.indexEmails(
                    messages = newMessages,
                    clientId = account.clientId,
                    projectId = account.projectId,
                    accountId = accountId,
                )
            }

            // Delete removed messages
            if (deletedMessageIds.isNotEmpty()) {
                logger.info { "Deleting ${deletedMessageIds.size} removed messages from vector store for account ${account.id}" }
                emailIndexingService.deleteEmailsFromVectorStore(
                    messageIds = deletedMessageIds,
                    accountId = accountId,
                )
            }

            updateLastPolled(account)

            logger.info {
                "Successfully polled account ${account.id}: ${newMessages.size} new, ${deletedMessageIds.size} deleted, ${fetchedMessages.size} total"
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to poll account ${account.id}" }
        }
    }

    private suspend fun updateLastPolled(account: EmailAccountDocument) {
        mongoTemplate
            .updateFirst(
                Query.query(Criteria.where("_id").`is`(account.id)),
                Update().set("lastPolledAt", Instant.now()),
                EmailAccountDocument::class.java,
            ).awaitFirstOrNull()
    }

    suspend fun triggerPoll(accountId: String) {
        pollingMutex.withLock {
            logger.info { "Manually triggering poll for account $accountId" }

            val account =
                emailAccountRepository.findById(org.bson.types.ObjectId(accountId)).awaitFirstOrNull()
                    ?: throw IllegalArgumentException("Email account not found: $accountId")

            pollAccount(account)
        }
    }
}
