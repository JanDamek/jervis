package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(
    val role: String = "",
    val content: String = "",
)
