package com.jervis.service.polling.handler.email

import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class EmailPollingHandler(
    private val imapPollingHandler: ImapPollingHandler,
    private val pop3PollingHandler: Pop3PollingHandler,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override val provider: ProviderEnum = ProviderEnum.IMAP

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.provider == ProviderEnum.IMAP
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        return when (connectionDocument.connectionType) {
            ConnectionDocument.ConnectionTypeEnum.IMAP -> {
                imapPollingHandler.poll(connectionDocument, context)
            }
            ConnectionDocument.ConnectionTypeEnum.POP3 -> {
                pop3PollingHandler.poll(connectionDocument, context)
            }
            else -> {
                logger.warn { "EmailPollingHandler received unsupported connection type: ${connectionDocument.connectionType}" }
                PollingResult(errors = 1)
            }
        }
    }
}
