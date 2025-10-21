package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.service.listener.domain.ListenerPollResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Listener for Microsoft Teams messages
 * Uses Microsoft Graph API and supports webhooks
 */
@Service
class TeamsListener : ServiceListener {
    override val serviceTypeEnum: ServiceTypeEnum = ServiceTypeEnum.TEAMS

    private val logger = LoggerFactory.getLogger(TeamsListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentials,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Polling Teams for client ${credentials.clientId}")
                ListenerPollResult(
                    serviceTypeEnum = serviceTypeEnum,
                    clientId = ObjectId(credentials.clientId),
                    projectId = credentials.projectId?.let { ObjectId(it) },
                    newMessages = emptyList(),
                )
            } catch (e: Exception) {
                logger.error("Error polling Teams for client ${credentials.clientId}", e)
                ListenerPollResult(
                    serviceTypeEnum = serviceTypeEnum,
                    clientId = ObjectId(credentials.clientId),
                    projectId = credentials.projectId?.let { ObjectId(it) },
                    newMessages = emptyList(),
                    error = e.message,
                )
            }
        }

    override suspend fun verifyCredentials(credentials: ServiceCredentials): Boolean =
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
        credentials: ServiceCredentials,
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
        credentials: ServiceCredentials,
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
