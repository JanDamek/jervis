package com.jervis.chat

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatSessionDocument — a foreground chat session.
 *
 * One active (non-archived) session per user. Messages are stored
 * in chat_messages collection linked via conversationId = this.id.
 *
 * @property id Session ObjectId (used as conversationId in chat_messages)
 * @property userId User identifier (currently always "jan")
 * @property createdAt When the session was created
 * @property lastMessageAt When the last message was sent (for ordering)
 * @property title Auto-generated title from first message (optional)
 * @property archived Whether the session is archived (new session created on next chat)
 */
@Document(collection = "chat_sessions")
@CompoundIndex(name = "user_archived_idx", def = "{'userId': 1, 'archived': 1, 'lastMessageAt': -1}")
data class ChatSessionDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val userId: String = "jan",
    val createdAt: Instant = Instant.now(),
    var lastMessageAt: Instant = Instant.now(),
    var title: String? = null,
    var archived: Boolean = false,
    var lastClientId: String? = null,
    var lastProjectId: String? = null,
    var lastGroupId: String? = null,
    /** Phase 5 draft persistence: unsent text per conversation. Key "__main__" = main chat. */
    var drafts: Map<String, String>? = null,
    /** UI settings: sidebar width, collapsed state, etc. Synced to server for cross-device restoration. */
    var uiSettings: Map<String, String>? = null,
)
