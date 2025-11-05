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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class OpenAiClient(
    @Qualifier("openaiWebClient") private val webClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    override val provider: ModelProviderEnum = ModelProviderEnum.OPENAI

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

        val response: OpenAiStyleResponse =
            webClient
                .post()
                .uri("/chat/completions")
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
        prompt: PromptConfigBase,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> =
        flow {
            val creativityConfig = getCreativityConfig(prompt)
            val messages = buildMessagesList(systemPrompt, userPrompt)
            val requestBody = buildStreamingRequestBody(model, messages, creativityConfig, config)

            val responseFlow =
                webClient
                    .post()
                    .uri("/chat/completions")
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
                        // Log error but continue streaming
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
            )

        // max_tokens: Maximum tokens for response (output only)
        return config.numPredict
            ?.let { baseBody + ("max_tokens" to it) }
            ?: baseBody
    }

    private fun parseResponse(
        response: OpenAiStyleResponse,
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

    private fun calculateTotalTokens(usage: OpenAiUsage?): Int = (usage?.prompt_tokens ?: 0) + (usage?.completion_tokens ?: 0)

    private fun buildStreamingRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        creativityConfig: CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody = buildRequestBody(model, messages, creativityConfig, config)
        return baseBody + mapOf("stream" to true)
    }

    private fun getCreativityConfig(prompt: PromptConfigBase) =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiStyleResponse(
        val id: String? = null,
        val created: Long? = null,
        val model: String? = null,
        val choices: List<OpenAiChoice> = emptyList(),
        val usage: OpenAiUsage? = null,
        val `object`: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiChoice(
        val index: Int = 0,
        val message: OpenAiMessage = OpenAiMessage(),
        val finish_reason: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiMessage(
        val role: String = "assistant",
        val content: String? = null,
        val name: String? = null,
        val refusal: String? = null,
        val tool_calls: List<OpenAiToolCall>? = null,
        val function_call: OpenAiFunctionCall? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiToolCall(
        val id: String,
        val type: String,
        val function: OpenAiFunction,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiFunction(
        val name: String,
        val arguments: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiFunctionCall(
        val name: String,
        val arguments: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiUsage(
        val prompt_tokens: Int? = null,
        val completion_tokens: Int? = null,
        val total_tokens: Int? = null,
    )
}
