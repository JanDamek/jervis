package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.PollingState
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.polling.PollingResult
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
    private val connectionService: ConnectionService,
) : EmailPollingHandlerBase(repository) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument is ConnectionDocument.Pop3ConnectionDocument

    override fun getProtocolName(): String = "POP3"

    override suspend fun pollClient(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
    ): PollingResult {
        if (connectionDocument !is ConnectionDocument.Pop3ConnectionDocument) {
            logger.warn { "Invalid connectionDocument type for POP3 polling" }
            return PollingResult(errors = 1)
        }

        return pollPop3(connectionDocument, client, projectId)
    }

    private suspend fun pollPop3(
        connectionDocument: ConnectionDocument.Pop3ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
    ): PollingResult =
        withContext(Dispatchers.IO) {
            logger.debug { "Polling POP3 ${connectionDocument.name} for client ${client.name}" }

            // Connect to POP3 server
            val properties =
                Properties().apply {
                    setProperty("mail.store.protocol", "pop3")
                    setProperty("mail.pop3.host", connectionDocument.host)
                    setProperty("mail.pop3.port", connectionDocument.port.toString())
                    if (connectionDocument.useSsl) {
                        setProperty("mail.pop3.ssl.enable", "true")
                        setProperty("mail.pop3.ssl.trust", "*")
                    }
                    setProperty("mail.pop3.connectiontimeout", "30000")
                    setProperty("mail.pop3.timeout", "30000")
                }

            val session = Session.getInstance(properties)
            val store = session.getStore("pop3")

            try {
                store.connect(
                    connectionDocument.host,
                    connectionDocument.port,
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

                    // Load polling state from connectionDocument
                    val lastFetchedNumber = connectionDocument.pollingState?.lastFetchedMessageNumber ?: 0

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

                    // Save polling state to connectionDocument (use max number from ALL fetched messages)
                    if (maxNumberFetched > lastFetchedNumber) {
                        val updatedConnection =
                            connectionDocument.copy(
                                pollingState =
                                    PollingState.Pop3(
                                        lastFetchedMessageNumber = maxNumberFetched,
                                    ),
                            )
                        connectionService.save(updatedConnection)
                        logger.info { "Updated lastFetchedMessageNumber: $lastFetchedNumber -> $maxNumberFetched (processed: created=$created, skipped=$skipped)" }
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
