package com.jervis.entity.email

import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Email message index - tracking which emails have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * STATES:
 * - NEW: Full email data from IMAP/POP3, ready for indexing
 * - INDEXED: Minimal tracking record (data in RAG/Graph via sourceUrn)
 * - FAILED: NEW state with error - full data kept for retry
 *
 * FLOW:
 * 1. CentralPoller fetches → saves as NEW with full data
 * 2. EmailContinuousIndexer creates PendingTask → converts to INDEXED (minimal)
 * 3. KoogQualifierAgent stores to RAG/Graph with sourceUrn
 * 4. Future lookups use sourceUrn to find original in IMAP/POP3
 *
 * MONGODB STORAGE (single instance, no INDEXING state needed):
 * - NEW/FAILED: Full document with textBody, htmlBody, attachments
 * - INDEXED: Minimal document - only id, connectionId, messageUid, state, receivedDate
 *
 * BREAKING CHANGE - MIGRATION REQUIRED:
 * Sealed class structure requires _class discriminator field in MongoDB.
 * Old documents without _class will FAIL deserialization (fail-fast design).
 *
 * MIGRATION: Drop collection before starting server:
 *   db.email_message_index.drop()
 *
 * All emails will be re-indexed from IMAP/POP3 on next polling cycle.
 */
@Document(collection = "email_message_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_uid_idx", def = "{'connectionId': 1, 'messageUid': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
)
sealed class EmailMessageIndexDocument {
    abstract val id: ObjectId
    abstract val clientId: ClientId
    abstract val connectionId: ConnectionId
    abstract val messageUid: String
    abstract val messageId: String?
    abstract val state: String
    abstract val receivedDate: Instant

    /**
     * NEW state - full email data from IMAP/POP3, ready for indexing.
     */
    @TypeAlias("EmailNew")
    data class New(
        @Id override val id: ObjectId = ObjectId.get(),
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionId: ConnectionId,
        override val messageUid: String,
        override val messageId: String?,
        val subject: String,
        val from: String,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val sentDate: Instant?,
        override val receivedDate: Instant,
        val textBody: String?,
        val htmlBody: String?,
        val attachments: List<EmailAttachment> = emptyList(),
        val folder: String = "INBOX",
    ) : EmailMessageIndexDocument() {
        override val state: String = "NEW"
    }

    /**
     * INDEXED state - minimal tracking record, actual data in RAG/Graph.
     * Only keeps essentials for deduplication and sourceUrn lookup.
     */
    @TypeAlias("EmailIndexed")
    data class Indexed(
        @Id override val id: ObjectId,
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionId: ConnectionId,
        override val messageUid: String,
        override val messageId: String?,
        override val receivedDate: Instant,
    ) : EmailMessageIndexDocument() {
        override val state: String = "INDEXED"
    }

    /**
     * FAILED state - same as NEW but with error, full data kept for retry.
     */
    @TypeAlias("EmailFailed")
    data class Failed(
        @Id override val id: ObjectId,
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionId: ConnectionId,
        override val messageUid: String,
        override val messageId: String?,
        val subject: String,
        val from: String,
        val to: List<String>,
        val cc: List<String>,
        val sentDate: Instant?,
        override val receivedDate: Instant,
        val textBody: String?,
        val htmlBody: String?,
        val attachments: List<EmailAttachment>,
        val folder: String,
        val indexingError: String,
    ) : EmailMessageIndexDocument() {
        override val state: String = "FAILED"
    }
}

/**
 * Email attachment metadata.
 * Content NOT stored - downloaded on-demand for indexing.
 */
data class EmailAttachment(
    val filename: String,
    val contentType: String,
    val size: Long,
    val contentId: String? = null,
)
