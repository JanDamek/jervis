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
        prompt: PromptConfig,
    ): LlmResponse {
        val creativityConfig = getCreativityConfig(prompt)
        val messages = mutableListOf<Map<String, Any>>()
        if (!systemPrompt.isNullOrBlank()) messages += mapOf("role" to "system", "content" to systemPrompt)
        messages += mapOf("role" to "user", "content" to userPrompt)

        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
            )
        body["temperature"] = creativityConfig.temperature
        body["top_p"] = creativityConfig.topP
        config.maxTokens?.let { body["max_tokens"] = it }

        val response: OpenAiStyleResponse =
            webClient
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .awaitBody()

        val answer =
            response.choices
                .firstOrNull()
                ?.message
                ?.content
                .orEmpty()
        val finishReason = response.choices.firstOrNull()?.finish_reason ?: "stop"
        val responseModel = response.model ?: model
        val promptTokens = response.usage?.prompt_tokens ?: 0
        val completionTokens = response.usage?.completion_tokens ?: 0
        val totalTokens = response.usage?.total_tokens ?: (promptTokens + completionTokens)

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
