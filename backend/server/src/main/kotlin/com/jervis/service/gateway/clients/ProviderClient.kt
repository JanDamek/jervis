package com.jervis.service.gateway.clients

import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import kotlinx.coroutines.flow.Flow

interface ProviderClient {
    val provider: ModelProviderEnum

    suspend fun call(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
    ): LlmResponse

    /**
     * Streaming version of the call method. The default implementation falls back to regular call.
     * Providers that support streaming should override this method.
     */
    fun callWithStreaming(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String? = null,
    ): Flow<StreamChunk>
}
