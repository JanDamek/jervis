package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.types.ProjectId
import jakarta.mail.Folder
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.Properties

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
    private val pollingStateService: PollingStateService,
) : EmailPollingHandlerBase(repository) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.connectionType == ConnectionDocument.ConnectionTypeEnum.POP3

    override fun getProtocolName(): String = "POP3"

    override suspend fun pollClient(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
    ): PollingResult =
        withContext(Dispatchers.IO) {
            logger.debug { "Polling POP3 ${connectionDocument.name} for client ${client.name}" }

            // Connect to POP3 server
            // Determine protocol: pop3s (SSL) vs pop3 (plain/STARTTLS)
            val protocol = if (connectionDocument.useSsl) "pop3s" else "pop3"
            val protocolPrefix = if (connectionDocument.useSsl) "mail.pop3s" else "mail.pop3"

            val properties =
                Properties().apply {
                    setProperty("mail.store.protocol", protocol)
                    setProperty("$protocolPrefix.host", connectionDocument.host ?: "")
                    setProperty("$protocolPrefix.port", connectionDocument.port.toString())

                    if (connectionDocument.useSsl) {
                        // pop3s protocol (direct SSL)
                        setProperty("$protocolPrefix.ssl.enable", "true")
                        setProperty("$protocolPrefix.ssl.trust", "*")
                        setProperty("$protocolPrefix.ssl.protocols", "TLSv1.2 TLSv1.3")
                        setProperty("$protocolPrefix.ssl.checkserveridentity", "false")
                    } else if (connectionDocument.useTls == true) {
                        // pop3 protocol with STARTTLS
                        setProperty("$protocolPrefix.starttls.enable", "true")
                        setProperty("$protocolPrefix.starttls.required", "true")
                        setProperty("$protocolPrefix.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }

                    setProperty("$protocolPrefix.connectiontimeout", connectionDocument.timeoutMs.toString())
                    setProperty("$protocolPrefix.timeout", connectionDocument.timeoutMs.toString())

                    // Debug SSL issues
                    setProperty("mail.debug", "true")
                    setProperty("mail.debug.auth", "true")
                }

            val session = Session.getInstance(properties)
            val store = session.getStore(protocol)

            try {
                store.connect(
                    connectionDocument.host,
                    connectionDocument.username,
                    connectionDocument.password,
                )

                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)

                try {
                    val messageCount = folder.messageCount
                    logger.debug { "POP3 INBOX has $messageCount messages" }

                    if (messageCount == 0) {
                        logger.debug { "No messages in folder" }
                        return@withContext PollingResult()
                    }

                    // Load polling state
                    val pollingState = pollingStateService.getState(connectionDocument.id, "POP3")
                    val lastFetchedNumber = pollingState?.lastFetchedMessageNumber ?: 0

                    logger.debug { "POP3 sync state: lastFetchedMessageNumber=$lastFetchedNumber, currentCount=$messageCount" }

                    // Fetch only NEW messages since last sync (message numbers are sequential)
                    val start = (lastFetchedNumber + 1).coerceAtLeast(1)
                    val end = messageCount

                    if (start > end) {
                        logger.debug { "No new messages to process" }
                        return@withContext PollingResult()
                    }

                    val newMessages = folder.getMessages(start, end)
                    logger.debug { "Fetching ${newMessages.size} new messages (numbers $start-$end)" }

                    // Collect all message numbers first (before processing)
                    val allNumbers = newMessages.map { it.messageNumber }
                    val maxNumberFetched = allNumbers.maxOrNull() ?: lastFetchedNumber

                    // Process messages using base class logic
                    val (created, skipped) =
                        processMessages(
                            messages = newMessages,
                            connectionDocument = connectionDocument,
                            client = client,
                            projectId = projectId,
                            getMessageUid = { message, _ ->
                                // Return message number as string for deduplication (stored as messageUid in DB)
                                message.messageNumber.toString()
                            },
                            folderName = "INBOX",
                        )

                    // Save polling state
                    if (maxNumberFetched > lastFetchedNumber) {
                        pollingStateService.updateWithMessageNumber(connectionDocument.id, "POP3", maxNumberFetched)
                        logger.info {
                            "Updated lastFetchedMessageNumber: $lastFetchedNumber -> $maxNumberFetched (processed: created=$created, skipped=$skipped)"
                        }
                    }

                    return@withContext PollingResult(
                        itemsDiscovered = newMessages.size,
                        itemsCreated = created,
                        itemsSkipped = skipped,
                    )
                } finally {
                    folder.close(false)
                }
            } finally {
                store.close()
            }
        }
}
