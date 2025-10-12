package com.jervis.service.gateway.clients

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
    suspend fun callWithStreaming(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
        debugSessionId: String? = null,
    ): Flow<StreamChunk> {
        // Default fallback implementation for clients that don't support streaming
        val response = call(model, systemPrompt, userPrompt, config, prompt, estimatedTokens)
        return flowOf(
            StreamChunk(
                content = response.answer,
                isComplete = true,
                metadata =
                    mapOf(
                        "model" to response.model,
                        "prompt_tokens" to response.promptTokens,
                        "completion_tokens" to response.completionTokens,
                        "total_tokens" to response.totalTokens,
                        "finish_reason" to response.finishReason,
                    ),
            ),
        )
    }
}

/**
 * Represents a chunk of streaming response from an LLM provider
 */
data class StreamChunk(
    val content: String,
    val isComplete: Boolean = false,
    val metadata: Map<String, Any> = emptyMap(),
)
