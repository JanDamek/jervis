package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.prompts.CreativityConfig
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.ProviderClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class OllamaClient(
    @Qualifier("ollamaWebClient") private val primaryWebClient: WebClient,
    @Qualifier("ollamaQualifierWebClient") private val qualifierWebClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    private val logger = KotlinLogging.logger {}

    override val provider: ModelProviderEnum = ModelProviderEnum.OLLAMA

    /**
     * Select appropriate WebClient based on ModelType from prompt config.
     * QUALIFIER type uses separate endpoint (CPU server), others use primary (GPU server).
     */
    private fun selectWebClient(prompt: PromptConfigBase): WebClient =
        when (prompt.modelParams.modelType) {
            com.jervis.domain.model.ModelTypeEnum.QUALIFIER -> qualifierWebClient
            else -> primaryWebClient
        }

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse {
        val webClient = selectWebClient(prompt)
        return callWithWebClient(webClient, model, systemPrompt, userPrompt, config, prompt, estimatedTokens)
    }

    /**
     * Internal implementation with explicit WebClient for reuse by OllamaQualifierClient
     */
    suspend fun callWithWebClient(
        webClient: WebClient,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse {
        val responseBuilder = StringBuilder()
        var finalMetadata: Map<String, Any> = emptyMap()

        callWithStreamingWebClient(webClient, model, systemPrompt, userPrompt, config, prompt, estimatedTokens)
            .collect { chunk ->
                responseBuilder.append(chunk.content)
                if (chunk.isComplete) {
                    finalMetadata = chunk.metadata
                }
            }

        return LlmResponse(
            answer = responseBuilder.toString(),
            model = finalMetadata["model"] as? String ?: model,
            promptTokens = finalMetadata["prompt_tokens"] as? Int ?: 0,
            completionTokens = finalMetadata["completion_tokens"] as? Int ?: 0,
            totalTokens = finalMetadata["total_tokens"] as? Int ?: 0,
            finishReason = finalMetadata["finish_reason"] as? String ?: "stop",
        )
    }

    override fun callWithStreaming(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> {
        val webClient = selectWebClient(prompt)
        return callWithStreamingWebClient(webClient, model, systemPrompt, userPrompt, config, prompt, estimatedTokens)
    }

    /**
     * Internal implementation with explicit WebClient for reuse by OllamaQualifierClient
     */
    fun callWithStreamingWebClient(
        webClient: WebClient,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): Flow<StreamChunk> =
        flow {
            val creativityConfig = getCreativityConfig(prompt)
            val options = buildOptions(creativityConfig, config, estimatedTokens)
            val keepAlive: String? =
                when (prompt.modelParams.modelType) {
                    com.jervis.domain.model.ModelTypeEnum.QUALIFIER -> "1h"
                    else -> null
                }
            val requestBody = buildRequestBody(model, userPrompt, systemPrompt, options, keepAlive)

            val responseFlow =
                webClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(String::class.java)
                    .asFlow()

            val responseBuilder = StringBuilder()
            var totalPromptTokens = 0
            var totalCompletionTokens = 0
            var finalModel = model
            var finishReason = "stop"

            responseFlow.collect { line ->
                if (line.isNotBlank()) {
                    try {
                        val mapper = jacksonObjectMapper()
                        val jsonNode = mapper.readTree(line)

                        val content = jsonNode.get("response")?.asText() ?: ""
                        val isDone = jsonNode.get("done")?.asBoolean() ?: false

                        if (content.isNotEmpty()) {
                            responseBuilder.append(content)

                            emit(StreamChunk(content = content, isComplete = false))
                        }

                        if (isDone) {
                            // Extract final metadata
                            totalPromptTokens = jsonNode.get("prompt_eval_count")?.asInt() ?: 0
                            totalCompletionTokens = jsonNode.get("eval_count")?.asInt() ?: 0
                            finalModel = jsonNode.get("model")?.asText() ?: model
                            finishReason = jsonNode.get("done_reason")?.asText() ?: "stop"

                            // Emit final chunk with metadata
                            emit(
                                StreamChunk(
                                    content = "",
                                    isComplete = true,
                                    metadata =
                                        mapOf(
                                            "model" to finalModel,
                                            "prompt_tokens" to totalPromptTokens,
                                            "completion_tokens" to totalCompletionTokens,
                                            "total_tokens" to (totalPromptTokens + totalCompletionTokens),
                                            "finish_reason" to finishReason,
                                        ),
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        logger.error { "Error parsing Ollama streaming response: ${e.message}" }
                        // Continue processing other chunks
                    }
                }
            }
        }

    private fun buildOptions(
        creativityConfig: CreativityConfig,
        config: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
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

        // num_predict: Maximum tokens for response (from configuration, default 4096)
        val numPredict = config.numPredict ?: 4096
        val maxTokensOption = mapOf("num_predict" to numPredict)

        // num_ctx: Total context window = input + output (calculated dynamically)
        val numCtx = estimatedTokens + numPredict
        val contextLength = config.contextLength ?: 32768

        // Validate: num_ctx should not exceed model's maximum capacity
        val finalNumCtx = minOf(numCtx, contextLength)

        if (numCtx > contextLength) {
            logger.warn {
                "Calculated num_ctx ($numCtx = $estimatedTokens input + $numPredict output) " +
                    "exceeds model capacity ($contextLength). Capping at $finalNumCtx."
            }
        }

        val contextLengthOption = mapOf("num_ctx" to finalNumCtx)

        return temperatureOption + topPOption + maxTokensOption + contextLengthOption
    }

    private fun buildRequestBody(
        model: String,
        userPrompt: String,
        systemPrompt: String?,
        options: Map<String, Any>,
        keepAlive: String?,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "prompt" to userPrompt,
                "stream" to true,
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

        val keepAliveField =
            keepAlive
                ?.takeUnless { it.isBlank() }
                ?.let { mapOf("keep_alive" to it) }
                ?: emptyMap()

        return baseBody + systemField + optionsField + keepAliveField
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

    private fun getCreativityConfig(prompt: PromptConfigBase) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaGenerateResponse(
        val model: String? = null,
        val response: String = "",
        val done_reason: String? = null,
        val prompt_eval_count: Int? = null,
        val eval_count: Int? = null,
        val created_at: String? = null,
    )
}
