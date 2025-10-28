package com.jervis.service.gateway.clients.llm

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.service.gateway.clients.ProviderClient
import com.jervis.service.gateway.clients.StreamChunk
import kotlinx.coroutines.flow.Flow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

/**
 * Dedicated client for OLLAMA_QUALIFIER provider (CPU endpoint).
 * Delegates all actual work to OllamaClient implementation.
 */
@Service
class OllamaQualifierClient(
    @Qualifier("ollamaQualifierWebClient") private val webClient: WebClient,
    private val ollamaClient: OllamaClient,
) : ProviderClient {
    override val provider: ModelProvider = ModelProvider.OLLAMA_QUALIFIER

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse =
        ollamaClient.callWithWebClient(
            webClient = webClient,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            config = config,
            prompt = prompt,
            estimatedTokens = estimatedTokens,
        )

    override fun callWithStreaming(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> =
        ollamaClient.callWithStreamingWebClient(
            webClient = webClient,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            config = config,
            prompt = prompt,
            estimatedTokens = estimatedTokens,
        )
}
