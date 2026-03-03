package com.jervis.ui.chat

import com.jervis.dto.ui.ChatMessage

/**
 * Display items for the chat list — either standalone messages or threaded background tasks.
 * Used by ChatArea to render flat messages and thread cards in a single LazyColumn.
 */
sealed interface ChatDisplayItem {
    /** Timestamp used for ordering items by last activity. */
    val sortTimestamp: String?

    /** Standalone message (not part of any thread). */
    data class Standalone(val message: ChatMessage) : ChatDisplayItem {
        override val sortTimestamp get() = message.timestamp
    }

    /** Background task thread — header card + user/assistant replies. */
    data class Thread(
        val taskId: String,
        val header: ChatMessage,
        val replies: List<ChatMessage>,
        val unreadCount: Int = 0,
    ) : ChatDisplayItem {
        override val sortTimestamp: String?
            get() = replies.lastOrNull()?.timestamp ?: header.timestamp
    }
}
