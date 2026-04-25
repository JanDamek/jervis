package com.jervis.dto.chat

import kotlinx.serialization.Serializable

@Serializable
data class ChatHistoryDto(
    val messages: List<ChatMessageDto> = emptyList(),
    val hasMore: Boolean = false,
    val oldestMessageId: String? = null,
    val compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    val activeClientId: String? = null,
    val activeProjectId: String? = null,
    val activeGroupId: String? = null,
    val userTaskCount: Int = 0,
    val backgroundMessageCount: Int = 0,
    /**
     * Conversation thread id (= ChatMessageDocument.conversationId) — used by
     * the UI to attach a `subscribeChatThread` Flow after the initial pull.
     * Null when no session exists yet (fresh client, no message ever sent).
     */
    val sessionId: String? = null,
)
