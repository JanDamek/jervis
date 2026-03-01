package com.jervis.dto

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
)
