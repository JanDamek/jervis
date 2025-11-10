package com.jervis.dto.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DebugEventDto {
    @Serializable
    @SerialName("LLM_CALL_STARTED")
    data class LlmCallStarted(
        val sessionId: String,
        val provider: String,
        val model: String,
        val systemPrompt: String,
        val userPrompt: String,
        val clientId: String? = null,
        val clientName: String? = null,
        val timestamp: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("LLM_CALL_COMPLETED")
    data class LlmCallCompleted(
        val sessionId: String,
        val provider: String,
        val model: String,
        val response: String,
        val tokensUsed: Int? = null,
        val durationMs: Long,
        val timestamp: String,
    ) : DebugEventDto()

    @Serializable
    @SerialName("LLM_CALL_FAILED")
    data class LlmCallFailed(
        val sessionId: String,
        val provider: String,
        val model: String,
        val error: String,
        val timestamp: String,
    ) : DebugEventDto()
}
