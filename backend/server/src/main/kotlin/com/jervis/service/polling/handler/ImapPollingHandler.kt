package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.email.EmailAttachment
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.*

/**
 * Polling handler for IMAP email connections.
 *
 * Fetches COMPLETE email data from IMAP server and stores in MongoDB as NEW.
 * ContinuousIndexer then reads from MongoDB (no IMAP calls) and indexes to RAG.
 */
@Component
class ImapPollingHandler(
    private val repository: EmailMessageIndexMongoRepository,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override fun canHandle(connection: Connection): Boolean {
        return connection is Connection.ImapConnection
    }

    override suspend fun poll(
        connection: Connection,
        credentials: HttpCredentials?,
        clients: List<ClientDocument>,
    ): PollingResult {
        if (connection !is Connection.ImapConnection) {
            logger.warn { "Invalid connection type for IMAP polling" }
            return PollingResult(errors = 1)
        }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                val result = pollClientEmails(connection, client)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling IMAP for client ${client.name}" }
                totalErrors++
            }
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors
        )
    }

    private suspend fun pollClientEmails(
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

                var created = 0
                var skipped = 0

                for (message in messages) {
                    try {
                        // Use Message-ID header as unique identifier
                        val messageId = message.getHeader("Message-ID")?.firstOrNull()
                        val uid = messageId ?: "imap-${connection.id}-${message.messageNumber}"

                        // Check if already exists
                        val existing = repository.findByConnectionIdAndMessageUid(connection.id, uid)
                        if (existing != null) {
                            skipped++
                            continue
                        }

                        // Parse and save message
                        val document = parseMessage(message, uid, messageId, client.id, connection)
                        repository.save(document)
                        created++
                        logger.debug { "Created email message: ${document.subject}" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing message" }
                    }
                }

                logger.info { "IMAP polling for ${client.name}: created=$created, skipped=$skipped" }

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

    private fun parseMessage(
        message: Message,
        uid: String,
        messageId: String?,
        clientId: org.bson.types.ObjectId,
        connection: Connection.ImapConnection,
    ): EmailMessageIndexDocument {
        val subject = message.subject ?: "(No Subject)"
        val from = message.from?.firstOrNull()?.toString() ?: "unknown"
        val to = message.allRecipients?.map { it.toString() } ?: emptyList()
        val sentDate = message.sentDate?.toInstant() ?: Instant.now()
        val receivedDate = message.receivedDate?.toInstant() ?: Instant.now()

        // Parse body and attachments
        val (textBody, htmlBody, attachments) = parseContent(message)

        return EmailMessageIndexDocument(
            clientId = clientId,
            connectionId = connection.id,
            messageUid = uid,
            messageId = messageId,
            subject = subject,
            from = from,
            to = to,
            sentDate = sentDate,
            receivedDate = receivedDate,
            textBody = textBody,
            htmlBody = htmlBody,
            attachments = attachments,
            folder = connection.folderName,
            state = "NEW",
        )
    }

    private fun parseContent(message: Message): Triple<String?, String?, List<EmailAttachment>> {
        val content = message.content
        var textBody: String? = null
        var htmlBody: String? = null
        val attachments = mutableListOf<EmailAttachment>()

        when (content) {
            is String -> {
                textBody = content
            }
            is Multipart -> {
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    when {
                        Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) -> {
                            // Attachment
                            attachments.add(
                                EmailAttachment(
                                    filename = part.fileName ?: "attachment_$i",
                                    contentType = part.contentType,
                                    size = part.size.toLong(),
                                )
                            )
                        }
                        part.isMimeType("text/plain") -> {
                            textBody = part.content.toString()
                        }
                        part.isMimeType("text/html") -> {
                            htmlBody = part.content.toString()
                        }
                    }
                }
            }
        }

        return Triple(textBody, htmlBody, attachments)
    }
}
