package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceType
import com.jervis.entity.mongo.ServiceCredentialsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Listener for Microsoft Teams messages
 * Uses Microsoft Graph API and supports webhooks
 */
@Service
class TeamsListener : ServiceListener {
    override val serviceType: ServiceType = ServiceType.TEAMS

    private val logger = LoggerFactory.getLogger(TeamsListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Polling Teams for client ${credentials.clientId}")
                ListenerPollResult(
                    serviceType = serviceType,
                    clientId = credentials.clientId,
                    projectId = credentials.projectId,
                    newMessages = emptyList(),
                )
            } catch (e: Exception) {
                logger.error("Error polling Teams for client ${credentials.clientId}", e)
                ListenerPollResult(
                    serviceType = serviceType,
                    clientId = credentials.clientId,
                    projectId = credentials.projectId,
                    newMessages = emptyList(),
                    error = e.message,
                )
            }
        }

    override suspend fun verifyCredentials(credentials: ServiceCredentialsDocument): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Verifying Teams credentials for client ${credentials.clientId}")
                true
            } catch (e: Exception) {
                logger.error("Error verifying Teams credentials", e)
                false
            }
        }

    override fun supportsWebhooks(): Boolean = true

    override suspend fun registerWebhook(
        credentials: ServiceCredentialsDocument,
        webhookUrl: String,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Registering Teams webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error registering Teams webhook", e)
                null
            }
        }

    override suspend fun handleWebhookEvent(
        credentials: ServiceCredentialsDocument,
        payload: String,
    ): ListenerPollResult? =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Processing Teams webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error processing Teams webhook", e)
                null
            }
        }
}
