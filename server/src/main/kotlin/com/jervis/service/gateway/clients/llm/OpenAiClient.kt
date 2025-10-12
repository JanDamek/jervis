package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfigBase
import com.jervis.configuration.prompts.PromptsConfiguration
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider
import com.jervis.service.debug.DesktopDebugWindowService
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
    private val debugWindowService: DesktopDebugWindowService,
) : ProviderClient {
    override val provider: ModelProvider = ModelProvider.OPENAI

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

    override suspend fun callWithStreaming(
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

            val responseBuffer = StringBuilder()
            var finalMetadata: Map<String, Any> = emptyMap()

            responseFlow.collect { sseChunk ->
                val parsedChunk = parseStreamChunk(sseChunk)

                if (parsedChunk.content.isNotEmpty()) {
                    responseBuffer.append(parsedChunk.content)

                    // Update debug window if session is active
                    debugSessionId?.let { sessionId ->
                        debugWindowService.appendResponse(sessionId, parsedChunk.content)
                    }
                }

                if (parsedChunk.isComplete) {
                    finalMetadata = parsedChunk.metadata
                }

                emit(parsedChunk)
            }

            // Emit final chunk with complete response and metadata
            emit(
                StreamChunk(
                    content = "",
                    isComplete = true,
                    metadata = finalMetadata + mapOf("full_response" to responseBuffer.toString()),
                ),
            )
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
        creativityConfig: com.jervis.configuration.prompts.CreativityConfig,
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
        creativityConfig: com.jervis.configuration.prompts.CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody = buildRequestBody(model, messages, creativityConfig, config)
        return baseBody + mapOf("stream" to true)
    }

    private fun parseStreamChunk(sseChunk: String): StreamChunk {
        if (sseChunk.startsWith("data: ")) {
            val jsonPart = sseChunk.substring(6).trim()

            // Check for completion signal
            if (jsonPart == "[DONE]") {
                return StreamChunk("", isComplete = true)
            }

            return try {
                val mapper = jacksonObjectMapper()
                val jsonNode = mapper.readTree(jsonPart)

                val choices = jsonNode.get("choices")
                if (choices != null && choices.isArray && choices.size() > 0) {
                    val firstChoice = choices.get(0)
                    val delta = firstChoice.get("delta")
                    val content = delta?.get("content")?.asText() ?: ""
                    val finishReason = firstChoice.get("finish_reason")?.asText()

                    val usage = jsonNode.get("usage")
                    val metadata =
                        if (usage != null) {
                            mapOf(
                                "model" to (jsonNode.get("model")?.asText() ?: ""),
                                "prompt_tokens" to (usage.get("prompt_tokens")?.asInt() ?: 0),
                                "completion_tokens" to (usage.get("completion_tokens")?.asInt() ?: 0),
                                "total_tokens" to (usage.get("total_tokens")?.asInt() ?: 0),
                                "finish_reason" to (finishReason ?: ""),
                            )
                        } else {
                            emptyMap()
                        }

                    StreamChunk(
                        content = content,
                        isComplete = finishReason != null,
                        metadata = metadata,
                    )
                } else {
                    StreamChunk("") // Empty chunk for malformed data
                }
            } catch (e: Exception) {
                // Log error but continue streaming
                StreamChunk("") // Return empty chunk on parse error
            }
        }
        return StreamChunk("") // Return empty chunk for non-data lines
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
