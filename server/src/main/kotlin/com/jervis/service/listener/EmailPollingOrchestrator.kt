package com.jervis.service.listener

import com.jervis.entity.mongo.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import com.jervis.service.indexing.EmailIndexingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class EmailPollingOrchestrator(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailListener: EmailListener,
    private val emailIndexingService: EmailIndexingService,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    fun scheduledPoll() {
        scope.launch {
            try {
                logger.info { "Starting scheduled email polling" }
                pollAllAccounts()
                logger.info { "Completed scheduled email polling" }
            } catch (e: Exception) {
                logger.error(e) { "Error during scheduled email poll" }
            }
        }
    }

    suspend fun pollAllAccounts() {
        val activeAccounts =
            mongoTemplate
                .find(
                    Query.query(Criteria.where("isActive").`is`(true)),
                    EmailAccountDocument::class.java,
                ).asFlow()
                .toList()

        logger.info { "Found ${activeAccounts.size} active email accounts to poll" }

        activeAccounts.forEach { account ->
            scope.launch {
                try {
                    pollAccount(account)
                } catch (e: Exception) {
                    logger.error(e) { "Error polling email account ${account.id}" }
                }
            }
        }
    }

    private suspend fun pollAccount(account: EmailAccountDocument) {
        logger.info { "Polling email account ${account.id} (${account.email})" }

        try {
            val messages = emailListener.pollEmailAccount(account, account.lastPolledAt)

            if (messages.isNotEmpty()) {
                logger.info { "Retrieved ${messages.size} new emails from account ${account.id}" }

                emailIndexingService.indexEmails(
                    messages = messages,
                    clientId = account.clientId,
                    projectId = account.projectId,
                    accountId = account.id.toHexString(),
                )
            }

            updateLastPolled(account)

            logger.info { "Successfully polled account ${account.id}: ${messages.size} new messages" }
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
        logger.info { "Manually triggering poll for account $accountId" }

        val account =
            emailAccountRepository.findById(org.bson.types.ObjectId(accountId)).awaitFirstOrNull()
                ?: throw IllegalArgumentException("Email account not found: $accountId")

        pollAccount(account)
    }
}
