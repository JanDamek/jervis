package com.jervis.service.gateway.clients

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.service.gateway.ProviderClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<AnthropicContent> = emptyList(),
    val stop_reason: String? = null,
    val usage: AnthropicUsage? = null,
)

data class AnthropicContent(
    val type: String = "text",
    val text: String = "",
)

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
        val messages = listOf(mapOf("role" to "user", "content" to userPrompt))

        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
                "max_tokens" to (config.maxTokens ?: 1024),
            )
        if (!systemPrompt.isNullOrBlank()) body["system"] = systemPrompt
        body["temperature"] = creativityConfig.temperature

        val response: AnthropicMessagesResponse =
            webClient
                .post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .awaitBody()

        val answer =
            response.content
                .firstOrNull()
                ?.text
                .orEmpty()
        val finishReason = response.stop_reason ?: "stop"
        val responseModel = response.model ?: model
        val promptTokens = response.usage?.input_tokens ?: 0
        val completionTokens = response.usage?.output_tokens ?: 0
        val totalTokens = promptTokens + completionTokens

        return LlmResponse(
            answer = answer,
            model = responseModel,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            finishReason = finishReason,
        )
    }

    private fun getCreativityConfig(prompt: PromptConfig) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")
}
