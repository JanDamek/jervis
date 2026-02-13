package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val text: String,
    val context: ChatRequestContextDto,
    val attachments: List<AttachmentDto> = emptyList(),
    /**
     * True if this message is being replayed from chat history (UI reconnect/reload).
     * When true, the message is only saved to DB for display — does NOT trigger task processing.
     * When false (default), this is a new user message that should trigger agent processing.
     */
    val isHistoryReplay: Boolean = false,
)
