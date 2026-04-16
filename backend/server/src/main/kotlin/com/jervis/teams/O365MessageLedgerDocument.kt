package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Per-connection per-chat ledger with "what's new?" state.
 *
 * Owned by the O365 browser pod — the pod is the sole writer. The Kotlin
 * server reads for UI badges, urgency triggers, and the polling handler's
 * delta calculation.
 *
 * Mirror on the WhatsApp side: `whatsapp_message_ledger` with identical
 * shape. Deliberate duplication keeps provider indexers decoupled.
 */
@Document(collection = "o365_message_ledger")
@CompoundIndexes(
    CompoundIndex(name = "connection_chat_unique", def = "{'connectionId': 1, 'chatId': 1}", unique = true),
    CompoundIndex(name = "connection_unread_idx", def = "{'connectionId': 1, 'unreadCount': -1}"),
)
data class O365MessageLedgerDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId? = null,
    val chatId: String,
    val chatName: String,
    val isDirect: Boolean,
    val isGroup: Boolean,
    val lastSeenAt: Instant? = null,
    val lastMessageAt: Instant? = null,
    val unreadCount: Int = 0,
    val unreadDirectCount: Int = 0,
    val lastUrgentAt: Instant? = null,
    val lastNotifiedAt: Instant? = null,
)
