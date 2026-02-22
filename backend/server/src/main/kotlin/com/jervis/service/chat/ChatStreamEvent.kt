package com.jervis.service.chat

/**
 * SSE event from Python /chat endpoint.
 *
 * Maps 1:1 to Python ChatStreamEvent model.
 * Types: token, tool_call, tool_result, done, error, thinking
 */
data class ChatStreamEvent(
    val type: String,
    val content: String = "",
    val metadata: Map<String, Any?> = emptyMap(),
)
