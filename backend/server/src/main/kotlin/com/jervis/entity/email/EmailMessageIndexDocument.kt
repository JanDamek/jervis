package com.jervis.entity.email

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Email message index document - stores complete email data from IMAP/POP3.
 *
 * State machine: NEW -> INDEXED -> ARCHIVED
 * - NEW: Just discovered, needs indexing to RAG
 * - INDEXED: Successfully indexed to RAG
 * - ARCHIVED: Old message, can be cleaned up
 *
 * This document stores COMPLETE email data fetched from email server.
 * ContinuousIndexer reads these (no email server calls) and indexes to RAG.
 */
@Document(collection = "email_message_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_uid_idx", def = "{'connectionId': 1, 'messageUid': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
)
data class EmailMessageIndexDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val connectionId: ObjectId,

    // Email identifiers
    val messageUid: String, // IMAP UID or POP3 message ID
    val messageId: String?, // Message-ID header (RFC 5322)

    // Email headers
    val subject: String,
    val from: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val sentDate: Instant?,
    val receivedDate: Instant,

    // Email content
    val textBody: String?, // Plain text body
    val htmlBody: String?, // HTML body

    // Attachments
    val attachments: List<EmailAttachment> = emptyList(),

    // Metadata
    val folder: String = "INBOX", // IMAP folder name
    val size: Long? = null, // Message size in bytes
    val flags: List<String> = emptyList(), // IMAP flags (SEEN, FLAGGED, etc.)

    // Indexing state
    val state: String = "NEW", // NEW, INDEXED, ARCHIVED
    val indexedAt: Instant? = null,
    val indexingError: String? = null,

    // Timestamps
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Email attachment metadata.
 * Content is NOT stored here - will be downloaded on-demand for indexing.
 */
data class EmailAttachment(
    val filename: String,
    val contentType: String,
    val size: Long,
    val contentId: String? = null, // For inline attachments
)
