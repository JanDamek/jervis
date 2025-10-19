package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequestDto(
    val text: String,
    val context: ChatRequestContext,
    val wsSessionId: String? = null,
)
