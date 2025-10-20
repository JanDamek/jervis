package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val text: String,
    val context: ChatRequestContextDto,
    val wsSessionId: String? = null,
)
