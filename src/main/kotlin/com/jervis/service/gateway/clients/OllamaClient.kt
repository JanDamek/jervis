package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
        val options = mutableMapOf<String, Any>()
        if (creativityConfig.temperature > 0.0) options["temperature"] = creativityConfig.temperature
        if (creativityConfig.topP in 0.0..1.0) options["top_p"] = creativityConfig.topP
        config.maxTokens?.takeIf { it > 0 }?.let { options["num_predict"] = it }

        val body =
            mutableMapOf<String, Any>(
                "model" to model,
                "prompt" to userPrompt,
                "stream" to false,
            )
        if (!systemPrompt.isNullOrBlank()) body["system"] = systemPrompt
        if (options.isNotEmpty()) body["options"] = options

        val response: OllamaGenerateResponse =
            webClient
                .post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .awaitBody()

        return LlmResponse(
            answer = response.response,
            model = response.model ?: model,
            promptTokens = response.prompt_eval_count ?: 0,
            completionTokens = response.eval_count ?: 0,
            totalTokens = (response.prompt_eval_count ?: 0) + (response.eval_count ?: 0),
            finishReason = response.done_reason ?: "stop",
        )
    }

    private fun getCreativityConfig(prompt: PromptConfig) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")
}
