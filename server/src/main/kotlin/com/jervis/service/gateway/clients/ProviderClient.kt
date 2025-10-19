package com.jervis.service.gateway.clients

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import kotlinx.coroutines.flow.Flow

interface ProviderClient {
    val provider: ModelProvider

    suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse

    /**
     * Streaming version of the call method. Default implementation falls back to regular call.
     * Providers that support streaming should override this method.
     */
    fun callWithStreaming(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
        debugSessionId: String? = null,
    ): Flow<StreamChunk>
}

/**
 * Represents a chunk of streaming response from an LLM provider
 */
data class StreamChunk(
    val content: String,
    val isComplete: Boolean = false,
    val metadata: Map<String, Any> = emptyMap(),
)
