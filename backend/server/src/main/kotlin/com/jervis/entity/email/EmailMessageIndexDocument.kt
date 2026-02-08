package com.jervis.entity.email

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.domain.PollingStatusEnum
import org.bson.types.ObjectId
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
 * 3. Qualifier stores to RAG/Graph with sourceUrn
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
data class EmailMessageIndexDocument(
    val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val messageUid: String,
    val messageId: String? = null,
    val state: PollingStatusEnum = PollingStatusEnum.NEW,
    val receivedDate: Instant,
    val subject: String? = null,
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val sentDate: Instant? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val attachments: List<EmailAttachment> = emptyList(),
    val folder: String = "INBOX",
    val indexingError: String? = null,
)

/**
 * Email attachment metadata.
 */
data class EmailAttachment(
    val filename: String,
    val contentType: String,
    val size: Long,
    val contentId: String? = null,
)
