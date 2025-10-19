package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.entity.mongo.ServiceCredentialsDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates polling of all configured external service listeners
 */
@Service
class ServiceListenerOrchestrator(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val syncService: ServiceMessageSyncService,
    private val listeners: List<ServiceListener>,
) {
    private val logger = LoggerFactory.getLogger(ServiceListenerOrchestrator::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lastPollTimes = ConcurrentHashMap<String, Instant>()
    private val listenerMap: Map<ServiceTypeEnum, ServiceListener> = listeners.associateBy { it.serviceTypeEnum }

    /**
     * Poll all active service credentials every 5 minutes
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 30 * 1000)
    fun scheduledPoll() {
        scope.launch {
            try {
                logger.info("Starting scheduled poll of external services")
                pollAllServices()
                logger.info("Completed scheduled poll of external services")
            } catch (e: Exception) {
                logger.error("Error during scheduled poll", e)
            }
        }
    }

    /**
     * Poll all configured services
     */
    suspend fun pollAllServices() {
        val credentials =
            mongoTemplate
                .find(
                    Query.query(Criteria.where("isActive").`is`(true)),
                    ServiceCredentialsDocument::class.java,
                ).asFlow()
                .toList()

        logger.info("Found ${credentials.size} active service credentials to poll")

        credentials.forEach { credential ->
            scope.launch {
                try {
                    pollService(credential)
                } catch (e: Exception) {
                    logger.error(
                        "Error polling service ${credential.serviceTypeEnum} for client ${credential.clientId}",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Poll a specific service
     */
    private suspend fun pollService(credential: ServiceCredentialsDocument) {
        val listener = listenerMap[credential.serviceTypeEnum]
        if (listener == null) {
            logger.warn("No listener found for service type ${credential.serviceTypeEnum}")
            return
        }

        val lastPollKey = "${credential.clientId}-${credential.projectId}-${credential.serviceTypeEnum}"
        val lastPollTime = lastPollTimes[lastPollKey]

        try {
            logger.info("Polling ${credential.serviceTypeEnum} for client ${credential.clientId}")

            val result = listener.poll(credential, lastPollTime)

            if (result.error != null) {
                logger.error("Error from ${credential.serviceTypeEnum} listener: ${result.error}")
                return
            }

            syncService.processNewMessages(result)
            syncService.processDeletedMessages(result)

            lastPollTimes[lastPollKey] = Instant.now()

            updateLastUsed(credential)

            logger.info(
                "Successfully polled ${credential.serviceTypeEnum} for client ${credential.clientId}: " +
                    "${result.newMessages.size} new, ${result.deletedMessageIds.size} deleted",
            )
        } catch (e: Exception) {
            logger.error("Error polling ${credential.serviceTypeEnum} for client ${credential.clientId}", e)
        }
    }

    /**
     * Manually trigger a poll for a specific client/project/service
     */
    suspend fun triggerPoll(
        clientId: String,
        projectId: String?,
        serviceTypeEnum: ServiceTypeEnum,
    ) {
        val query =
            Query.query(
                Criteria
                    .where("clientId")
                    .`is`(clientId)
                    .and("serviceType")
                    .`is`(serviceTypeEnum)
                    .and("isActive")
                    .`is`(true),
            )

        if (projectId != null) {
            query.addCriteria(Criteria.where("projectId").`is`(projectId))
        }

        val credentials =
            mongoTemplate
                .find(query, ServiceCredentialsDocument::class.java)
                .asFlow()
                .toList()

        credentials.forEach { credential ->
            pollService(credential)
        }
    }

    /**
     * Update last used timestamp for credentials
     */
    private suspend fun updateLastUsed(credential: ServiceCredentialsDocument) {
        mongoTemplate
            .updateFirst(
                Query.query(Criteria.where("_id").`is`(credential.id)),
                Update().set("lastUsedAt", Instant.now()),
                ServiceCredentialsDocument::class.java,
            ).asFlow()
            .toList()
    }
}
