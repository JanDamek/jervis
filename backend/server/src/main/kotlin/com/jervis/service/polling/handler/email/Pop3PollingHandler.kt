package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import jakarta.mail.Folder
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.*

/**
 * POP3 email polling handler.
 *
 * Protocol-specific implementation:
 * - Connects to POP3 server (with SSL support)
 * - Always uses INBOX folder (POP3 limitation)
 * - Fetches recent messages (last 50)
 * - Uses Message-ID header as unique identifier (POP3 has no native UID support)
 *
 * Shared logic (parsing, deduplication, MongoDB) is in EmailPollingHandlerBase.
 */
@Component
class Pop3PollingHandler(
    repository: EmailMessageIndexMongoRepository,
) : EmailPollingHandlerBase(repository) {

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.Pop3Connection
    }

    override fun getProtocolName(): String = "POP3"

    override suspend fun pollClient(
        connection: Connection,
        client: ClientDocument,
    ): PollingResult {
        if (connection !is Connection.Pop3Connection) {
            logger.warn { "Invalid connection type for POP3 polling" }
            return PollingResult(errors = 1)
        }

        return pollPop3(connection, client)
    }

    private suspend fun pollPop3(
        connection: Connection.Pop3Connection,
        client: ClientDocument,
    ): PollingResult = withContext(Dispatchers.IO) {
        logger.debug { "Polling POP3 ${connection.name} for client ${client.name}" }

        // Connect to POP3 server
        val properties = Properties().apply {
            setProperty("mail.store.protocol", "pop3")
            setProperty("mail.pop3.host", connection.host)
            setProperty("mail.pop3.port", connection.port.toString())
            if (connection.useSsl) {
                setProperty("mail.pop3.ssl.enable", "true")
                setProperty("mail.pop3.ssl.trust", "*")
            }
            setProperty("mail.pop3.connectiontimeout", "30000")
            setProperty("mail.pop3.timeout", "30000")
        }

        val session = Session.getInstance(properties)
        val store = session.getStore("pop3")

        try {
            store.connect(connection.host, connection.port, connection.username, connection.password)

            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            try {
                val messageCount = folder.messageCount
                logger.info { "POP3 INBOX has $messageCount messages" }

                // Fetch recent messages (last 50)
                val start = (messageCount - 49).coerceAtLeast(1)
                val end = messageCount

                if (start > end) {
                    logger.info { "No messages to poll" }
                    return@withContext PollingResult()
                }

                val messages = folder.getMessages(start, end)
                logger.info { "Fetched ${messages.size} messages from POP3" }

                // Process messages using base class logic
                val (created, skipped) = processMessages(
                    messages = messages,
                    connection = connection,
                    client = client,
                    getMessageUid = { message, index ->
                        // POP3 doesn't have UIDs, use Message-ID header
                        val messageId = message.getHeader("Message-ID")?.firstOrNull()
                        messageId ?: "pop3-${connection.id}-$index"
                    },
                    folderName = "INBOX"
                )

                return@withContext PollingResult(
                    itemsDiscovered = messages.size,
                    itemsCreated = created,
                    itemsSkipped = skipped
                )
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }
}
