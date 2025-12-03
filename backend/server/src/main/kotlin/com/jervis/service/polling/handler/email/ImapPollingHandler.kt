package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.*

/**
 * IMAP email polling handler.
 *
 * Protocol-specific implementation:
 * - Connects to IMAP server (with SSL support)
 * - Opens specified folder (configurable per connection)
 * - Fetches recent messages (last 50)
 * - Uses Message-ID header as unique identifier
 *
 * Shared logic (parsing, deduplication, MongoDB) is in EmailPollingHandlerBase.
 */
@Component
class ImapPollingHandler(
    repository: EmailMessageIndexMongoRepository,
) : EmailPollingHandlerBase(repository) {

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.ImapConnection
    }

    override fun getProtocolName(): String = "IMAP"

    override suspend fun pollClient(
        connection: Connection,
        client: ClientDocument,
    ): PollingResult {
        if (connection !is Connection.ImapConnection) {
            logger.warn { "Invalid connection type for IMAP polling" }
            return PollingResult(errors = 1)
        }

        return pollImap(connection, client)
    }

    private suspend fun pollImap(
        connection: Connection.ImapConnection,
        client: ClientDocument,
    ): PollingResult = withContext(Dispatchers.IO) {
        logger.debug { "Polling IMAP ${connection.name} for client ${client.name}" }

        // Connect to IMAP server
        val properties = Properties().apply {
            setProperty("mail.store.protocol", "imap")
            setProperty("mail.imap.host", connection.host)
            setProperty("mail.imap.port", connection.port.toString())
            if (connection.useSsl) {
                setProperty("mail.imap.ssl.enable", "true")
                setProperty("mail.imap.ssl.trust", "*")
            }
            setProperty("mail.imap.connectiontimeout", "30000")
            setProperty("mail.imap.timeout", "30000")
        }

        val session = Session.getInstance(properties)
        val store = session.getStore("imap")

        try {
            store.connect(connection.host, connection.port, connection.username, connection.password)

            val folder = store.getFolder(connection.folderName)
            folder.open(Folder.READ_ONLY)

            try {
                val messageCount = folder.messageCount
                logger.info { "IMAP folder ${connection.folderName} has $messageCount messages" }

                // Fetch recent messages (last 50)
                val start = (messageCount - 49).coerceAtLeast(1)
                val end = messageCount

                if (start > end) {
                    logger.info { "No messages to poll" }
                    return@withContext PollingResult()
                }

                val messages = folder.getMessages(start, end)
                logger.info { "Fetched ${messages.size} messages from IMAP" }

                // Process messages using base class logic
                val (created, skipped) = processMessages(
                    messages = messages,
                    connection = connection,
                    client = client,
                    getMessageUid = { message, _ ->
                        val messageId = message.getHeader("Message-ID")?.firstOrNull()
                        messageId ?: "imap-${connection.id}-${message.messageNumber}"
                    },
                    folderName = connection.folderName
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
