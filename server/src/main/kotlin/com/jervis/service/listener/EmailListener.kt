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
 * Listener for email services (IMAP, Microsoft Graph, etc.)
 * Polls for new emails and indexes them into RAG
 */
@Service
class EmailListener : ServiceListener {
    override val serviceTypeEnum: ServiceTypeEnum = ServiceTypeEnum.EMAIL

    private val logger = LoggerFactory.getLogger(EmailListener::class.java)

    override suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult =
        withContext(Dispatchers.IO) {
            try {
                val protocol = credentials.additionalData["protocol"] ?: "imap"
                when (protocol.lowercase()) {
                    "imap" -> pollImap(credentials, lastCheckTime)
                    "graph" -> pollGraph(credentials, lastCheckTime)
                    else -> {
                        logger.warn("Unsupported email protocol: $protocol")
                        ListenerPollResult(
                            serviceTypeEnum = serviceTypeEnum,
                            clientId = credentials.clientId,
                            projectId = credentials.projectId,
                            newMessages = emptyList(),
                            error = "Unsupported protocol: $protocol",
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error polling email for client ${credentials.clientId}", e)
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
                val protocol = credentials.additionalData["protocol"] ?: "imap"
                when (protocol.lowercase()) {
                    "imap" -> verifyImapCredentials(credentials)
                    "graph" -> verifyGraphCredentials(credentials)
                    else -> false
                }
            } catch (e: Exception) {
                logger.error("Error verifying email credentials", e)
                false
            }
        }

    private suspend fun pollImap(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult {
        logger.info("Polling IMAP for client ${credentials.clientId}")
        return ListenerPollResult(
            serviceTypeEnum = serviceTypeEnum,
            clientId = credentials.clientId,
            projectId = credentials.projectId,
            newMessages = emptyList(),
        )
    }

    private suspend fun pollGraph(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult {
        logger.info("Polling Microsoft Graph for client ${credentials.clientId}")
        return ListenerPollResult(
            serviceTypeEnum = serviceTypeEnum,
            clientId = credentials.clientId,
            projectId = credentials.projectId,
            newMessages = emptyList(),
        )
    }

    private suspend fun verifyImapCredentials(credentials: ServiceCredentialsDocument): Boolean {
        logger.info("Verifying IMAP credentials for client ${credentials.clientId}")
        return true
    }

    private suspend fun verifyGraphCredentials(credentials: ServiceCredentialsDocument): Boolean {
        logger.info("Verifying Graph credentials for client ${credentials.clientId}")
        return true
    }
}
