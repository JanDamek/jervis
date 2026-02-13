package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatHistoryDto(
    val messages: List<ChatMessageDto> = emptyList(),
    val hasMore: Boolean = false,
    val oldestSequence: Long? = null,
    val compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
)
