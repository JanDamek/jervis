package com.jervis.service.polling.handler.email

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.types.ProjectId
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.Properties

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
    private val pollingStateService: PollingStateService,
) : EmailPollingHandlerBase(repository) {
    override fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.connectionType == ConnectionDocument.ConnectionTypeEnum.IMAP

    override fun getProtocolName(): String = "IMAP"

    override suspend fun pollClient(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
    ): PollingResult {
        if (connectionDocument.connectionType != ConnectionDocument.ConnectionTypeEnum.IMAP) {
            logger.warn { "Invalid connectionDocument type for IMAP polling" }
            return PollingResult(errors = 1)
        }

        return pollImap(connectionDocument, client, projectId)
    }

    private suspend fun pollImap(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
    ): PollingResult =
        withContext(Dispatchers.IO) {
            logger.debug { "Polling IMAP ${connectionDocument.name} for client ${client.name}" }

            // Connect to IMAP server
            val properties =
                Properties().apply {
                    setProperty("mail.store.protocol", "imap")
                    setProperty("mail.imap.host", connectionDocument.host)
                    setProperty("mail.imap.port", connectionDocument.port.toString())
                    setProperty("mail.imap.user", connectionDocument.username)
                    if (connectionDocument.useSsl) {
                        setProperty("mail.imap.ssl.enable", "true")
                        setProperty("mail.imap.ssl.trust", "*")
                    }
                    setProperty("mail.imap.connectiontimeout", "30000")
                    setProperty("mail.imap.timeout", "30000")
                }

            val session = Session.getInstance(properties)
            val store = session.getStore("imap")

            try {
                repeat(2) { attempt ->
                    try {
                        if (store.isConnected) store.close()
                        store.connect(
                            connectionDocument.host,
                            connectionDocument.port,
                            connectionDocument.username,
                            connectionDocument.password,
                        )
                        return@repeat
                    } catch (e: MessagingException) {
                        if (attempt == 1) throw e
                        try {
                            store.close()
                        } catch (_: Exception) {
                        }
                        logger.warn { "SSL handshake failed, retrying (${attempt + 1}/2)" }
                        Thread.sleep(300)
                    }
                }

                val folder = store.getFolder(connectionDocument.folderName)
                folder.open(Folder.READ_ONLY)

                try {
                    val messageCount = folder.messageCount
                    logger.debug { "IMAP folder ${connectionDocument.folderName} has $messageCount messages" }

                    if (messageCount == 0) {
                        logger.debug { "No messages in folder" }
                        return@withContext PollingResult()
                    }

                    // Cast to UIDFolder to use UID operations
                    val uidFolder = folder as? UIDFolder
                    if (uidFolder == null) {
                        logger.warn { "Folder does not support UIDs, falling back to message numbers" }
                        return@withContext PollingResult(errors = 1)
                    }

                    // Load polling state
                    val pollingState = pollingStateService.getState(connectionDocument.id, "IMAP")
                    val lastFetchedUid = pollingState?.lastFetchedUid ?: 0L

                    logger.debug { "IMAP sync state: lastFetchedUid=$lastFetchedUid" }

                    // Fetch only NEW messages since last sync
                    val newMessages =
                        if (lastFetchedUid == 0L) {
                            // First sync - fetch all messages
                            logger.debug { "First sync - fetching all $messageCount messages" }
                            folder.getMessages(1, messageCount)
                        } else {
                            // Fetch messages with UID > lastFetchedUid
                            // Note: Use MAXUID instead of LASTUID per JavaMail API documentation
                            val fromUid = lastFetchedUid + 1
                            val uids = uidFolder.getMessagesByUID(fromUid, UIDFolder.MAXUID)
                            logger.debug { "Fetching ${uids.size} new messages (requested UID range: $fromUid-MAXUID)" }
                            uids
                        }

                    if (newMessages.isEmpty()) {
                        logger.debug { "No new messages to process" }
                        return@withContext PollingResult()
                    }

                    logger.debug { "Processing ${newMessages.size} new messages" }

                    // Collect all UIDs first (before processing)
                    val allUids = newMessages.map { uidFolder.getUID(it) }

                    logger.debug {
                        "IMAP server returned UIDs: [${allUids.joinToString()}] (requested: ${lastFetchedUid + 1}-LASTUID)"
                    }

                    // Filter out messages with UID <= lastFetchedUid (IMAP server bug workaround)
                    val filteredMessages =
                        newMessages.filterIndexed { index, _ ->
                            allUids[index] > lastFetchedUid
                        }

                    if (filteredMessages.isEmpty()) {
                        logger.debug { "All fetched messages already processed (UIDs <= $lastFetchedUid), skipping" }
                        return@withContext PollingResult()
                    }

                    val filteredUids = filteredMessages.map { uidFolder.getUID(it) }
                    val maxUidFetched = filteredUids.maxOrNull() ?: lastFetchedUid

                    logger.debug {
                        "After filtering: ${filteredMessages.size} messages with UIDs: [${filteredUids.joinToString()}], " +
                            "maxUidFetched=$maxUidFetched"
                    }

                    // Process messages using base class logic
                    val (created, skipped) =
                        processMessages(
                            messages = filteredMessages.toTypedArray(),
                            connectionDocument = connectionDocument,
                            client = client,
                            projectId = projectId,
                            getMessageUid = { message, _ ->
                                // Return UID as string for deduplication (stored as messageUid in DB)
                                uidFolder.getUID(message).toString()
                            },
                            folderName = connectionDocument.folderName,
                        )

                    // Save polling state
                    if (filteredMessages.isNotEmpty() && maxUidFetched > lastFetchedUid) {
                        pollingStateService.updateWithUid(connectionDocument.id, "IMAP", maxUidFetched)
                        logger.debug {
                            "Updated lastFetchedUid: $lastFetchedUid -> $maxUidFetched (processed: created=$created, skipped=$skipped)"
                        }
                    } else if (filteredMessages.isNotEmpty()) {
                        logger.debug {
                            "Skipped update: maxUidFetched=$maxUidFetched <= lastFetchedUid=$lastFetchedUid " +
                                "(created=$created, skipped=$skipped)"
                        }
                    }

                    return@withContext PollingResult(
                        itemsDiscovered = filteredMessages.size,
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
