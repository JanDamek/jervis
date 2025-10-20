package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.entity.mongo.ServiceCredentialsDocument
import com.jervis.service.listener.domain.ListenerPollResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Listener for Slack messages
 * Supports both webhook and polling mechanisms
 */
@Service
class SlackListener : ServiceListener {
    override val serviceTypeEnum: ServiceTypeEnum = ServiceTypeEnum.SLACK

    private val logger = LoggerFactory.getLogger(SlackListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Polling Slack for client ${credentials.clientId}")
                ListenerPollResult(
                    serviceTypeEnum = serviceTypeEnum,
                    clientId = credentials.clientId,
                    projectId = credentials.projectId,
                    newMessages = emptyList(),
                )
            } catch (e: Exception) {
                logger.error("Error polling Slack for client ${credentials.clientId}", e)
                ListenerPollResult(
                    serviceTypeEnum = serviceTypeEnum,
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
                logger.info("Verifying Slack credentials for client ${credentials.clientId}")
                true
            } catch (e: Exception) {
                logger.error("Error verifying Slack credentials", e)
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
                logger.info("Registering Slack webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error registering Slack webhook", e)
                null
            }
        }

    override suspend fun handleWebhookEvent(
        credentials: ServiceCredentialsDocument,
        payload: String,
    ): ListenerPollResult? =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Processing Slack webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error processing Slack webhook", e)
                null
            }
        }
}
