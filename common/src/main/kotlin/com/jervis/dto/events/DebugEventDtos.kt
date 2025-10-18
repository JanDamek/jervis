package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
sealed class DebugEventDto {
    @Serializable
    data class SessionStarted(
        val sessionId: String,
        val promptType: String,
        val systemPrompt: String,
        val userPrompt: String,
    ) : DebugEventDto()

    @Serializable
    data class ResponseChunk(
        val sessionId: String,
        val chunk: String,
    ) : DebugEventDto()

    @Serializable
    data class SessionCompleted(
        val sessionId: String,
    ) : DebugEventDto()
}
