package com.jervis.service.debug

sealed class DebugEvent {
    data class SessionStarted(
        val sessionId: String,
        val promptType: String,
        val systemPrompt: String,
        val userPrompt: String,
    ) : DebugEvent()

    data class ResponseChunk(
        val sessionId: String,
        val chunk: String,
    ) : DebugEvent()

    data class SessionCompleted(
        val sessionId: String,
    ) : DebugEvent()
}
