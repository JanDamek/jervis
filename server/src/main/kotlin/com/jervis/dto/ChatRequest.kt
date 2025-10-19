package com.jervis.dto

data class ChatRequest(
    val text: String,
    val context: ChatRequestContext,
)
