package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class LmStudioClient(
    private val webClientFactory: WebClientFactory,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    private val webClient: WebClient by lazy { webClientFactory.getWebClient("lmStudio") }
    private val logger = KotlinLogging.logger {}

    override val provider: ModelProviderEnum = ModelProviderEnum.LM_STUDIO

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
    ): LlmResponse {
        // Use streaming implementation and collect the full response
        val responseBuilder = StringBuilder()
        var finalMetadata: Map<String, Any> = emptyMap()

        callWithStreaming(model, systemPrompt, userPrompt, config, prompt, estimatedTokens, null)
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
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> =
        flow {
            val creativityConfig = getCreativityConfig(prompt)
            val messages = buildMessagesList(systemPrompt, userPrompt)
            val requestBody = buildRequestBody(model, messages, creativityConfig, config)

            val responseFlow =
                webClient
                    .post()
                    .uri("/v1/chat/completions")
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

            responseFlow.collect { sseChunk ->
                if (sseChunk.startsWith("data: ")) {
                    val jsonPart = sseChunk.substring(6).trim()

                    // Check for completion signal
                    if (jsonPart == "[DONE]") {
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
                        return@collect
                    }

                    try {
                        val mapper = jacksonObjectMapper()
                        val jsonNode = mapper.readTree(jsonPart)

                        val choices = jsonNode.get("choices")
                        if (choices != null && choices.isArray && choices.size() > 0) {
                            val firstChoice = choices.get(0)
                            val delta = firstChoice.get("delta")
                            val content = delta?.get("content")?.asText() ?: ""
                            val chunkFinishReason = firstChoice.get("finish_reason")?.asText()

                            if (content.isNotEmpty()) {
                                responseBuilder.append(content)
                                emit(StreamChunk(content = content, isComplete = false))
                            }

                            // Extract metadata from usage if present
                            val usage = jsonNode.get("usage")
                            if (usage != null) {
                                totalPromptTokens = usage.get("prompt_tokens")?.asInt() ?: 0
                                totalCompletionTokens = usage.get("completion_tokens")?.asInt() ?: 0
                                finalModel = jsonNode.get("model")?.asText() ?: model
                            }

                            if (chunkFinishReason != null) {
                                finishReason = chunkFinishReason
                            }
                        }
                    } catch (e: Exception) {
                        logger.error { "Error parsing LM Studio streaming response: ${e.message}" }
                    }
                }
            }
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
                "stream" to true,
            )

        // max_tokens: Maximum tokens for response (output only)
        return config.numPredict
            ?.let { baseBody + ("max_tokens" to it) }
            ?: baseBody
    }

    private fun getCreativityConfig(prompt: PromptConfig) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioResponse(
        val id: String? = null,
        val created: Long? = null,
        val model: String? = null,
        val choices: List<LmStudioChoice> = emptyList(),
        val usage: LmStudioUsage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioChoice(
        val index: Int = 0,
        val message: LmStudioMessage = LmStudioMessage(),
        val finish_reason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioMessage(
        val role: String = "assistant",
        val content: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioUsage(
        val prompt_tokens: Int? = null,
        val completion_tokens: Int? = null,
        val total_tokens: Int? = null,
    )
}
