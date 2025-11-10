package com.jervis.dto.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DebugEventDto {
    @Serializable
    @SerialName("SessionStarted")
    data class SessionStarted(
        val sessionId: String,
        val promptType: String,
        val systemPrompt: String,
        val userPrompt: String,
        val clientId: String? = null,
        val clientName: String? = null,
    ) : DebugEventDto()

    @Serializable
    @SerialName("ResponseChunk")
    data class ResponseChunkDto(
        val sessionId: String,
        val chunk: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("SessionCompleted")
    data class SessionCompletedDto(
        val sessionId: String,
    ) : DebugEventDto()
}
