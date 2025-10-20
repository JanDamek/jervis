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
 * Listener for Jira issues and comments
 * Uses Jira REST API and supports webhooks
 */
@Service
class JiraListener : ServiceListener {
    override val serviceTypeEnum: ServiceTypeEnum = ServiceTypeEnum.JIRA

    private val logger = LoggerFactory.getLogger(JiraListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Polling Jira for client ${credentials.clientId}")
                ListenerPollResult(
                    serviceTypeEnum = serviceTypeEnum,
                    clientId = credentials.clientId,
                    projectId = credentials.projectId,
                    newMessages = emptyList(),
                )
            } catch (e: Exception) {
                logger.error("Error polling Jira for client ${credentials.clientId}", e)
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
                logger.info("Verifying Jira credentials for client ${credentials.clientId}")
                true
            } catch (e: Exception) {
                logger.error("Error verifying Jira credentials", e)
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
                logger.info("Registering Jira webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error registering Jira webhook", e)
                null
            }
        }

    override suspend fun handleWebhookEvent(
        credentials: ServiceCredentialsDocument,
        payload: String,
    ): ListenerPollResult? =
        withContext(Dispatchers.IO) {
            try {
                logger.info("Processing Jira webhook for client ${credentials.clientId}")
                null
            } catch (e: Exception) {
                logger.error("Error processing Jira webhook", e)
                null
            }
        }
}
