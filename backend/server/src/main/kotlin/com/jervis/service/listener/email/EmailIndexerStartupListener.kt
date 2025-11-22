package com.jervis.service.listener.email

import com.jervis.repository.EmailAccountMongoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Starts continuous indexers for all active email accounts on application startup.
 */
@Component
class EmailIndexerStartupListener(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailContinuousIndexer: EmailContinuousIndexer,
) {
    private val indexerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @EventListener(ApplicationReadyEvent::class)
    fun startContinuousIndexers() =
        runBlocking {
            logger.info { "Starting continuous email indexers for all active accounts..." }

            val activeAccounts =
                emailAccountRepository
                    .findAllByIsActiveTrue()
                    .toList()

            logger.info { "Found ${activeAccounts.size} active email accounts" }

            activeAccounts.forEach { account ->
                logger.info { "Launching continuous indexer for account ${account.id} (${account.email})" }
                emailContinuousIndexer.launchContinuousIndexing(account, indexerScope)
            }

            logger.info { "All continuous indexers launched successfully" }
        }
}
