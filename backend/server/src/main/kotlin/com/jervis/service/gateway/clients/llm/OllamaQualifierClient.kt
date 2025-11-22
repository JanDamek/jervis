package com.jervis.service.gateway.clients.llm

import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.ProviderClient
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
    override val provider: ModelProviderEnum = ModelProviderEnum.OLLAMA_QUALIFIER

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
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
        prompt: PromptConfig,
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
