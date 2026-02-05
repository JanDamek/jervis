package com.jervis.service.polling.handler.email

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Email polling handler - handles GENERIC_EMAIL provider with IMAP/POP3 protocols.
 * Google Workspace and Microsoft 365 have their own handlers (OAuth2-based).
 */
@Component
class EmailPollingHandler(
    private val imapPollingHandler: ImapPollingHandler,
    private val pop3PollingHandler: Pop3PollingHandler,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override val provider: ProviderEnum = ProviderEnum.GENERIC_EMAIL

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.EMAIL_READ || it == ConnectionCapability.EMAIL_SEND
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        return when (connectionDocument.protocol) {
            ProtocolEnum.IMAP -> {
                imapPollingHandler.poll(connectionDocument, context)
            }
            ProtocolEnum.POP3 -> {
                pop3PollingHandler.poll(connectionDocument, context)
            }
            else -> {
                logger.warn { "EmailPollingHandler received unsupported protocol: ${connectionDocument.protocol}" }
                PollingResult(errors = 1)
            }
        }
    }
}
