package com.jervis.whatsapp

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.infrastructure.polling.PollingStatusEnum
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * WhatsApp message index — tracking which WhatsApp messages have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * FLOW:
 * 1. WhatsAppPollingHandler reads from whatsapp_scrape_messages → saves as NEW
 * 2. WhatsAppContinuousIndexer creates task → converts to INDEXED
 * 3. Qualifier stores to RAG/Graph with sourceUrn
 */
@Document(collection = "whatsapp_message_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_msgid_idx", def = "{'connectionId': 1, 'messageId': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
    CompoundIndex(name = "chat_idx", def = "{'chatName': 1, 'createdDateTime': 1}"),
)
data class WhatsAppMessageIndexDocument(
    val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val state: PollingStatusEnum = PollingStatusEnum.NEW,

    /** Unique message ID (synthetic from scrape hash) */
    val messageId: String,
    /** Chat context */
    val chatName: String? = null,
    val isGroup: Boolean = false,

    /** Message content */
    val from: String? = null,
    val body: String? = null,
    val createdDateTime: Instant,

    /** Attachment info */
    val attachmentType: String? = null,
    val attachmentDescription: String? = null,

    /** Indexing metadata */
    val indexingError: String? = null,
)
