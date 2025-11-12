package com.jervis.service.jira

import com.jervis.repository.mongo.JiraConnectionMongoRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JiraPollingScheduler(
    private val connectionRepository: JiraConnectionMongoRepository,
    private val orchestrator: JiraIndexingOrchestrator,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Poll next Jira client connection (oldest lastSyncedAt first).
     * Runs every 5 minutes with 1 minute initial delay.
     */
    @Scheduled(
        fixedDelayString = "\${jira.sync.polling-interval-ms:1800000}",
        initialDelayString = "\${jira.sync.initial-delay-ms:60000}",
    )
    suspend fun pollNextClient() {
        runCatching {
            val all = connectionRepository.findAll().toList()
            if (all.isEmpty()) {
                logger.debug { "No Jira connections configured" }
                return
            }

            // Only process connections considered active (expiresAt in future) AND with VALID authentication
            val active = all.filter { it.expiresAt.isAfter(Instant.now()) && it.authStatus == "VALID" }
            if (active.isEmpty()) {
                logger.debug { "No active VALID Jira connections to poll" }
                return
            }

            val next = active.minByOrNull { it.lastSyncedAt ?: Instant.EPOCH } ?: return
            orchestrator.indexClient(next.clientId)
        }.onFailure { e ->
            logger.error(e) { "Error during scheduled Jira poll" }
        }
    }

    /** Manual trigger for a client (useful from admin flows). */
    suspend fun triggerManual(clientId: String) {
        logger.info { "Manually triggering Jira indexing for client=$clientId" }
        orchestrator.indexClient(ObjectId(clientId))
    }
}
