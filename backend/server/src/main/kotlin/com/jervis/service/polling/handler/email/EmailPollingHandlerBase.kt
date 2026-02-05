package com.jervis.service.polling.handler.email

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.email.EmailAttachment
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.service.polling.handler.ResourceFilter
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import mu.KotlinLogging
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
 * - ConnectionDocument setup
 * - Folder/mailbox access
 * - Message fetching
 */
abstract class EmailPollingHandlerBase(
    protected val repository: EmailMessageIndexRepository,
) {
    protected val logger = KotlinLogging.logger {}

    suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        logger.debug { "  → ${getProtocolName()} handler polling ${context.clients.size} client(s)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        // Email connections are always at client level (not project-specific)
        // Process each client
        for (client in context.clients) {
            // Check capability configuration - get resource filter
            val resourceFilter = context.getResourceFilter(client.id, ConnectionCapability.EMAIL_READ)

            // Skip if capability is disabled (null filter)
            if (resourceFilter == null) {
                logger.debug { "    Skipping ${getProtocolName()} for client ${client.name}: EMAIL capability disabled" }
                continue
            }

            try {
                logger.debug { "    Polling ${getProtocolName()} for client: ${client.name}" }
                val result = pollClient(connectionDocument, client, projectId = null, resourceFilter = resourceFilter)
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

        logger.debug {
            "  ← ${getProtocolName()} handler completed | " +
                "Total: discovered=$totalDiscovered, created=$totalCreated, skipped=$totalSkipped, errors=$totalErrors"
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors,
        )
    }

    /**
     * Poll emails for a single client. Protocol-specific implementation.
     *
     * @param resourceFilter Filter to determine which folders to index
     */
    protected abstract suspend fun pollClient(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        resourceFilter: ResourceFilter,
    ): PollingResult

    /**
     * Get protocol name for logging (IMAP, POP3, etc.)
     */
    protected abstract fun getProtocolName(): String

    /**
     * Process and save discovered messages to MongoDB.
     * Returns (created, skipped) counts.
     *
     * Note: ConnectionDocument can be assigned to client OR project.
     * - If in client.connectionIds → projectId = null
     * - If in project.connectionIds → projectId = project.id, clientId = project.clientId
     */
    protected suspend fun processMessages(
        messages: Array<Message>,
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        getMessageUid: (Message, Int) -> String,
        folderName: String,
    ): Pair<Int, Int> {
        var created = 0
        var skipped = 0

        for ((index, message) in messages.withIndex()) {
            try {
                // Get unique identifier (protocol-specific)
                val uid = getMessageUid(message, index)
                val messageId = message.getHeader("Message-ID")?.firstOrNull() ?: "noneMsgID"

                // Check if already exists
                val existing = repository.existsByConnectionIdAndMessageUid(connectionDocument.id, uid)
                if (existing) {
                    skipped++
                    continue
                }

                // Parse and save message
                val document =
                    parseMessage(message, uid, messageId, client.id, projectId, connectionDocument.id, folderName)
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
     * Parse jakarta.mail.Message into EmailMessageIndexDocument.New.
     */
    protected fun parseMessage(
        message: Message,
        uid: String,
        messageId: String,
        clientId: ClientId,
        projectId: ProjectId?,
        connectionId: ConnectionId,
        folderName: String,
    ): EmailMessageIndexDocument {
        val subject = message.subject ?: "(No Subject)"
        val from = message.from?.firstOrNull()?.toString() ?: "unknown"
        val to = message.allRecipients?.map { it.toString() } ?: emptyList()
        val sentDate = message.sentDate?.toInstant() ?: Instant.now()
        val receivedDate = message.receivedDate?.toInstant() ?: Instant.now()

        // Parse body and attachments (may return null if content cannot be loaded)
        val contentResult = parseContent(message)
        val (textBody, htmlBody, attachments) =
            if (contentResult != null) {
                contentResult
            } else {
                // Content could not be loaded - create document with error indicator
                Triple(
                    "[ERROR: Message content could not be loaded - BODYSTRUCTURE unavailable]",
                    null,
                    emptyList(),
                )
            }

        return EmailMessageIndexDocument(
            clientId = clientId,
            projectId = projectId,
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
        )
    }

    /**
     * Parse email content (text/html body + attachments).
     * Returns null if content cannot be loaded (corrupted message, IMAP error, etc.)
     */
    protected fun parseContent(message: Message): Triple<String?, String?, List<EmailAttachment>>? {
        val content =
            try {
                message.content
            } catch (e: jakarta.mail.MessagingException) {
                logger.warn { "Failed to load message content (BODYSTRUCTURE error): ${e.message}" }
                return null
            } catch (e: Exception) {
                logger.warn { "Failed to parse message content: ${e.message}" }
                return null
            }

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
                                ),
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
