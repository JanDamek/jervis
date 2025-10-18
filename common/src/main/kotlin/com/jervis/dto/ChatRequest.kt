package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val text: String,
    val context: ChatRequestContext,
)
