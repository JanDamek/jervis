package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class OllamaGenerateResponse(
    val model: String? = null,
    val response: String = "",
    val done_reason: String? = null,
    val prompt_eval_count: Int? = null,
    val eval_count: Int? = null,
    val created_at: String? = null,
)

@Service
class OllamaClient(
    @Qualifier("ollamaWebClient") private val webClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    override val provider: ModelProvider = ModelProvider.OLLAMA

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
    ): LlmResponse {
        val creativityConfig = getCreativityConfig(prompt)
        val options = buildOptions(creativityConfig, config)
        val requestBody = buildRequestBody(model, userPrompt, systemPrompt, options)

        val response: OllamaGenerateResponse =
            webClient
                .post()
                .uri("/api/generate")
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody()

        return parseResponse(response, model)
    }

    private fun buildOptions(
        creativityConfig: com.jervis.configuration.prompts.CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val temperatureOption =
            creativityConfig.temperature
                .takeIf { it > 0.0 }
                ?.let { mapOf("temperature" to it) }
                ?: emptyMap()

        val topPOption =
            creativityConfig.topP
                .takeIf { it in 0.0..1.0 }
                ?.let { mapOf("top_p" to it) }
                ?: emptyMap()

        val maxTokensOption =
            config.maxTokens
                ?.takeIf { it > 0 }
                ?.let { mapOf("num_predict" to it) }
                ?: emptyMap()

        return temperatureOption + topPOption + maxTokensOption
    }

    private fun buildRequestBody(
        model: String,
        userPrompt: String,
        systemPrompt: String?,
        options: Map<String, Any>,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "prompt" to userPrompt,
                "stream" to false,
            )

        val systemField =
            systemPrompt
                ?.takeUnless { it.isBlank() }
                ?.let { mapOf("system" to it) }
                ?: emptyMap()

        val optionsField =
            options
                .takeUnless { it.isEmpty() }
                ?.let { mapOf("options" to it) }
                ?: emptyMap()

        return baseBody + systemField + optionsField
    }

    private fun parseResponse(
        response: OllamaGenerateResponse,
        fallbackModel: String,
    ): LlmResponse =
        LlmResponse(
            answer = response.response,
            model = response.model ?: fallbackModel,
            promptTokens = response.prompt_eval_count ?: 0,
            completionTokens = response.eval_count ?: 0,
            totalTokens = calculateTotalTokens(response),
            finishReason = response.done_reason ?: "stop",
        )

    private fun calculateTotalTokens(response: OllamaGenerateResponse): Int = (response.prompt_eval_count ?: 0) + (response.eval_count ?: 0)

    private fun getCreativityConfig(prompt: PromptConfig) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")
}
