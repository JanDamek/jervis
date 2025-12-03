package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.entity.email.EmailAttachment
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingHandler
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Base class for email polling handlers (IMAP, POP3, etc.).
 *
 * Provides shared logic:
 * - Poll orchestration across multiple clients
 * - Message parsing (body, attachments)
 * - MongoDB document creation
 * - Deduplication checking
 *
 * Protocol-specific implementations (IMAP, POP3) only handle:
 * - Connection setup
 * - Folder/mailbox access
 * - Message fetching
 */
abstract class EmailPollingHandlerBase(
    protected val repository: EmailMessageIndexMongoRepository,
) : PollingHandler {
    protected val logger = KotlinLogging.logger {}

    override suspend fun poll(
        connection: Connection,
        clients: List<ClientDocument>,
    ): PollingResult {
        logger.info { "  → ${getProtocolName()} handler polling ${clients.size} client(s)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        for (client in clients) {
            try {
                logger.debug { "    Polling ${getProtocolName()} for client: ${client.name}" }
                val result = pollClient(connection, client)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors

                if (result.itemsCreated > 0 || result.itemsDiscovered > 0) {
                    logger.info {
                        "    ${client.name}: discovered=${result.itemsDiscovered}, " +
                        "created=${result.itemsCreated}, skipped=${result.itemsSkipped}"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "    Error polling ${getProtocolName()} for client ${client.name}" }
                totalErrors++
            }
        }

        logger.info {
            "  ← ${getProtocolName()} handler completed | " +
            "Total: discovered=$totalDiscovered, created=$totalCreated, skipped=$totalSkipped, errors=$totalErrors"
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors
        )
    }

    /**
     * Poll emails for a single client. Protocol-specific implementation.
     */
    protected abstract suspend fun pollClient(connection: Connection, client: ClientDocument): PollingResult

    /**
     * Get protocol name for logging (IMAP, POP3, etc.)
     */
    protected abstract fun getProtocolName(): String

    /**
     * Process and save discovered messages to MongoDB.
     * Returns (created, skipped) counts.
     */
    protected suspend fun processMessages(
        messages: Array<Message>,
        connection: Connection,
        client: ClientDocument,
        getMessageUid: (Message, Int) -> String,
        folderName: String,
    ): Pair<Int, Int> {
        var created = 0
        var skipped = 0

        for ((index, message) in messages.withIndex()) {
            try {
                // Get unique identifier (protocol-specific)
                val uid = getMessageUid(message, index)
                val messageId = message.getHeader("Message-ID")?.firstOrNull()

                // Check if already exists
                val existing = repository.findByConnectionIdAndMessageUid(connection.id, uid)
                if (existing != null) {
                    skipped++
                    continue
                }

                // Parse and save message
                val document = parseMessage(message, uid, messageId, client.id, connection.id, folderName)
                repository.save(document)
                created++
                logger.debug { "Created email message: ${document.subject}" }
            } catch (e: Exception) {
                logger.error(e) { "Error processing message $index" }
            }
        }

        logger.info { "${getProtocolName()} polling for ${client.name}: created=$created, skipped=$skipped" }
        return Pair(created, skipped)
    }

    /**
     * Parse jakarta.mail.Message into EmailMessageIndexDocument.
     */
    protected fun parseMessage(
        message: Message,
        uid: String,
        messageId: String?,
        clientId: ObjectId,
        connectionId: ObjectId,
        folderName: String,
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
            connectionId = connectionId,
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
            folder = folderName,
            state = "NEW",
        )
    }

    /**
     * Parse email content (text/html body + attachments).
     */
    protected fun parseContent(message: Message): Triple<String?, String?, List<EmailAttachment>> {
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
