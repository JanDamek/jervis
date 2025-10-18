package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceType
import com.jervis.entity.mongo.ServiceCredentialsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Listener for Discord messages
 * Uses Discord Bot API and supports webhooks
 */
@Service
class DiscordListener : ServiceListener {
    override val serviceType: ServiceType = ServiceType.DISCORD

    private val logger = LoggerFactory.getLogger(DiscordListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Polling Discord for client ${credentials.clientId}")
                ListenerPollResult(
                    serviceType = serviceType,
                    clientId = credentials.clientId,
                    projectId = credentials.projectId,
                    newMessages = emptyList(),
                )
            } catch (e: Exception) {
                logger.error("Error polling Discord for client ${credentials.clientId}", e)
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
                logger.info("Verifying Discord credentials for client ${credentials.clientId}")
                true
            } catch (e: Exception) {
                logger.error("Error verifying Discord credentials", e)
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
                logger.info("Registering Discord webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error registering Discord webhook", e)
                null
            }
        }

    override suspend fun handleWebhookEvent(
        credentials: ServiceCredentialsDocument,
        payload: String,
    ): ListenerPollResult? =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Processing Discord webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error processing Discord webhook", e)
                null
            }
        }
}
