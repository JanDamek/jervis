package com.jervis.service.polling.handler.email

import com.jervis.common.types.ProjectId
import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.service.polling.handler.ResourceFilter
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Store
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
    repository: EmailMessageIndexRepository,
    private val pollingStateService: PollingStateService,
) : EmailPollingHandlerBase(repository) {
    fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.protocol == com.jervis.dto.connection.ProtocolEnum.IMAP

    override fun getProtocolName(): String = "IMAP"

    override suspend fun pollClient(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        resourceFilter: ResourceFilter,
    ): PollingResult {
        if (connectionDocument.protocol != com.jervis.dto.connection.ProtocolEnum.IMAP) {
            logger.warn { "Invalid connectionDocument type for IMAP polling" }
            return PollingResult(errors = 1)
        }

        return pollImap(connectionDocument, client, projectId, resourceFilter)
    }

    private suspend fun pollImap(
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        resourceFilter: ResourceFilter,
    ): PollingResult =
        withContext(Dispatchers.IO) {
            logger.debug { "Polling IMAP ${connectionDocument.name} for client ${client.name}" }

            // Determine which folders to poll based on resource filter
            val foldersToIndex = getFoldersToPoll(connectionDocument, resourceFilter)
            logger.debug { "IMAP folders to poll: ${foldersToIndex.joinToString(", ")}" }

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

                // Poll each folder and aggregate results
                var totalDiscovered = 0
                var totalCreated = 0
                var totalSkipped = 0

                for (folderName in foldersToIndex) {
                    val folderResult = pollFolder(store, connectionDocument, client, projectId, folderName)
                    totalDiscovered += folderResult.itemsDiscovered
                    totalCreated += folderResult.itemsCreated
                    totalSkipped += folderResult.itemsSkipped
                }

                return@withContext PollingResult(
                    itemsDiscovered = totalDiscovered,
                    itemsCreated = totalCreated,
                    itemsSkipped = totalSkipped,
                )
            } finally {
                store.close()
            }
        }

    /**
     * Determine which folders to poll based on resource filter.
     */
    private fun getFoldersToPoll(
        connectionDocument: ConnectionDocument,
        resourceFilter: ResourceFilter,
    ): List<String> =
        when (resourceFilter) {
            is ResourceFilter.IndexAll -> {
                // Use legacy folder name from connection document
                listOf(connectionDocument.folderName)
            }
            is ResourceFilter.IndexSelected -> {
                if (resourceFilter.resources.isNotEmpty()) {
                    resourceFilter.resources
                } else {
                    // No specific folders selected - fallback to legacy folder
                    listOf(connectionDocument.folderName)
                }
            }
        }

    /**
     * Poll a single IMAP folder.
     */
    private suspend fun pollFolder(
        store: Store,
        connectionDocument: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        folderName: String,
    ): PollingResult {
        val folder = store.getFolder(folderName)
        folder.open(Folder.READ_ONLY)

        try {
            val messageCount = folder.messageCount
            logger.debug { "IMAP folder $folderName has $messageCount messages" }

            if (messageCount == 0) {
                logger.debug { "No messages in folder $folderName" }
                return PollingResult()
            }

            // Cast to UIDFolder to use UID operations
            val uidFolder = folder as? UIDFolder
            if (uidFolder == null) {
                logger.warn { "Folder $folderName does not support UIDs, falling back to message numbers" }
                return PollingResult(errors = 1)
            }

            // Load polling state (per folder)
            val stateKey = "${connectionDocument.id}_$folderName"
            val pollingState = pollingStateService.getState(connectionDocument.id, connectionDocument.provider, "IMAP")
            val lastFetchedUid = pollingState?.lastFetchedUid ?: 0L

            logger.debug { "IMAP sync state for $folderName: lastFetchedUid=$lastFetchedUid" }

            // Fetch only NEW messages since last sync
            val newMessages =
                if (lastFetchedUid == 0L) {
                    // First sync - fetch all messages
                    logger.debug { "First sync for $folderName - fetching all $messageCount messages" }
                    folder.getMessages(1, messageCount)
                } else {
                    // Fetch messages with UID > lastFetchedUid
                    val fromUid = lastFetchedUid + 1
                    val uids = uidFolder.getMessagesByUID(fromUid, UIDFolder.MAXUID)
                    logger.debug { "Fetching ${uids.size} new messages from $folderName (UID range: $fromUid-MAXUID)" }
                    uids
                }

            if (newMessages.isEmpty()) {
                logger.debug { "No new messages to process in $folderName" }
                return PollingResult()
            }

            logger.debug { "Processing ${newMessages.size} new messages from $folderName" }

            // Collect all UIDs first (before processing)
            val allUids = newMessages.map { uidFolder.getUID(it) }

            logger.debug {
                "IMAP server returned UIDs for $folderName: [${allUids.joinToString()}] (requested: ${lastFetchedUid + 1}-LASTUID)"
            }

            // Filter out messages with UID <= lastFetchedUid (IMAP server bug workaround)
            val filteredMessages =
                newMessages.filterIndexed { index, _ ->
                    allUids[index] > lastFetchedUid
                }

            if (filteredMessages.isEmpty()) {
                logger.debug { "All fetched messages in $folderName already processed (UIDs <= $lastFetchedUid), skipping" }
                return PollingResult()
            }

            val filteredUids = filteredMessages.map { uidFolder.getUID(it) }
            val maxUidFetched = filteredUids.maxOrNull() ?: lastFetchedUid

            logger.debug {
                "After filtering $folderName: ${filteredMessages.size} messages with UIDs: [${filteredUids.joinToString()}], " +
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
                    folderName = folderName,
                )

            // Save polling state
            if (filteredMessages.isNotEmpty() && maxUidFetched > lastFetchedUid) {
                pollingStateService.updateWithUid(connectionDocument.id, connectionDocument.provider, maxUidFetched, "IMAP")
                logger.debug {
                    "Updated lastFetchedUid for $folderName: $lastFetchedUid -> $maxUidFetched (created=$created, skipped=$skipped)"
                }
            }

            return PollingResult(
                itemsDiscovered = filteredMessages.size,
                itemsCreated = created,
                itemsSkipped = skipped,
            )
        } finally {
            folder.close(false)
        }
    }
}
