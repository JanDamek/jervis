package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.WebClientFactory
import com.jervis.configuration.prompts.CreativityConfig
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.ProviderClient
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class AnthropicClient(
    private val webClientFactory: WebClientFactory,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    private val webClient: WebClient by lazy { webClientFactory.getWebClient("anthropic") }
    override val provider: ModelProviderEnum = ModelProviderEnum.ANTHROPIC

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
    ): LlmResponse {
        val creativityConfig = getCreativityConfig(prompt)
        val messages = buildMessages(userPrompt)
        val requestBody = buildRequestBody(model, messages, systemPrompt, creativityConfig, config)

        val response: AnthropicMessagesResponse =
            webClient
                .post()
                .uri("/v1/messages")
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody()

        return parseResponse(response, model)
    }

    override fun callWithStreaming(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> =
        kotlinx.coroutines.flow.flow {
            val response = call(model, systemPrompt, userPrompt, config, prompt, estimatedTokens)

            emit(StreamChunk(content = response.answer, isComplete = false))

            emit(
                StreamChunk(
                    content = "",
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

    private fun buildMessages(userPrompt: String): List<Map<String, Any>> = listOf(mapOf("role" to "user", "content" to userPrompt))

    private fun buildRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        systemPrompt: String?,
        creativityConfig: CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to (config.numPredict ?: 4096), // Maximum tokens for response (output only)
                "temperature" to creativityConfig.temperature,
            )

        val systemField =
            systemPrompt
                ?.takeUnless { it.isBlank() }
                ?.let { mapOf("system" to it) }
                ?: emptyMap()

        return baseBody + systemField
    }

    private fun parseResponse(
        response: AnthropicMessagesResponse,
        fallbackModel: String,
    ): LlmResponse {
        val answer =
            response.content
                .firstOrNull()
                ?.text
                .orEmpty()
        val promptTokens = response.usage?.input_tokens ?: 0
        val completionTokens = response.usage?.output_tokens ?: 0

        return LlmResponse(
            answer = answer,
            model = response.model ?: fallbackModel,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            finishReason = response.stop_reason ?: "stop",
        )
    }

    private fun getCreativityConfig(prompt: PromptConfig) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnthropicMessagesResponse(
        val id: String? = null,
        val model: String? = null,
        val content: List<AnthropicContent> = emptyList(),
        val stop_reason: String? = null,
        val usage: AnthropicUsage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnthropicContent(
        val type: String = "text",
        val text: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnthropicUsage(
        val input_tokens: Int? = null,
        val output_tokens: Int? = null,
    )
}
