package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.service.gateway.clients.ProviderClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

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

@Service
class AnthropicClient(
    @Qualifier("anthropicWebClient") private val webClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    override val provider: ModelProvider = ModelProvider.ANTHROPIC

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
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

    private fun buildMessages(userPrompt: String): List<Map<String, Any>> = listOf(mapOf("role" to "user", "content" to userPrompt))

    private fun buildRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        systemPrompt: String?,
        creativityConfig: com.jervis.configuration.prompts.CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to (config.maxTokens ?: 1024),
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
}
