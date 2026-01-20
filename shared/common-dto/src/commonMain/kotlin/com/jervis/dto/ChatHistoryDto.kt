package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatHistoryDto(
    val messages: List<ChatMessageDto> = emptyList(),
)
