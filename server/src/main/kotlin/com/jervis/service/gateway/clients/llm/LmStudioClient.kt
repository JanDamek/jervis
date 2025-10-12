package com.jervis.service.gateway.clients.llm

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.CreativityConfig
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.service.gateway.clients.OpenAiClient
import com.jervis.service.gateway.clients.ProviderClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class LmStudioClient(
    @Qualifier("lmStudioWebClient") private val webClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    override val provider: ModelProvider = ModelProvider.LM_STUDIO

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse {
        val creativityConfig = getCreativityConfig(prompt)
        val messages = buildMessagesList(systemPrompt, userPrompt)
        val requestBody = buildRequestBody(model, messages, creativityConfig, config)

        val response: OpenAiClient.OpenAiStyleResponse =
            webClient
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody()

        return parseResponse(response, model)
    }

    private fun buildMessagesList(
        systemPrompt: String?,
        userPrompt: String,
    ): List<Map<String, Any>> {
        val systemMessage =
            systemPrompt
                ?.takeUnless { it.isBlank() }
                ?.let { listOf(mapOf("role" to "system", "content" to it)) }
                ?: emptyList()

        val userMessage = listOf(mapOf("role" to "user", "content" to userPrompt))

        return systemMessage + userMessage
    }

    private fun buildRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        creativityConfig: CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "messages" to messages,
                "temperature" to creativityConfig.temperature,
                "top_p" to creativityConfig.topP,
            )

        // max_tokens: Maximum tokens for response (output only)
        return config.numPredict
            ?.let { baseBody + ("max_tokens" to it) }
            ?: baseBody
    }

    private fun parseResponse(
        response: OpenAiClient.OpenAiStyleResponse,
        fallbackModel: String,
    ): LlmResponse {
        val firstChoice = response.choices.firstOrNull()

        return LlmResponse(
            answer = firstChoice?.message?.content ?: "",
            model = response.model ?: fallbackModel,
            promptTokens = response.usage?.prompt_tokens ?: 0,
            completionTokens = response.usage?.completion_tokens ?: 0,
            totalTokens = response.usage?.total_tokens ?: calculateTotalTokens(response.usage),
            finishReason = firstChoice?.finish_reason ?: "stop",
        )
    }

    private fun calculateTotalTokens(usage: OpenAiClient.OpenAiUsage?): Int = (usage?.prompt_tokens ?: 0) + (usage?.completion_tokens ?: 0)

    private fun getCreativityConfig(prompt: PromptConfigBase) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")
}
