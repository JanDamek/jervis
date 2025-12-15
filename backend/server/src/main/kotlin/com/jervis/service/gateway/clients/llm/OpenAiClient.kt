package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.KtorClientFactory
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.ProviderClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Service

@Service
class OpenAiClient(
    private val ktorClientFactory: KtorClientFactory,
) : ProviderClient {
    private val httpClient: HttpClient by lazy { ktorClientFactory.getHttpClient("openai") }
    override val provider: ModelProviderEnum = ModelProviderEnum.OPENAI

    override suspend fun call(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
    ): LlmResponse {
        val messages = buildMessagesList(systemPrompt, userPrompt)
        val requestBody = buildRequestBody(model, messages, config)

        val response: OpenAiStyleResponse =
            httpClient
                .post("/chat/completions") {
                    setBody(requestBody)
                }.body()

        return parseResponse(response, model)
    }

    override fun callWithStreaming(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> =
        flow {
            val messages = buildMessagesList(systemPrompt, userPrompt)
            val requestBody = buildStreamingRequestBody(model, messages, config)

            val response: HttpResponse =
                httpClient.post("/chat/completions") {
                    contentType(ContentType.Text.EventStream)
                    setBody(requestBody)
                }

            val responseBuilder = StringBuilder()
            var totalPromptTokens = 0
            var totalCompletionTokens = 0
            var finalModel = model
            var finishReason = "stop"

            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val sseChunk = channel.readUTF8Line() ?: break
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
                        break
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
                    } catch (_: Exception) {
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
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody =
            mapOf(
                "model" to model,
                "messages" to messages,
            )

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
        config: ModelsProperties.ModelDetail,
    ): Map<String, Any> {
        val baseBody = buildRequestBody(model, messages, config)
        return baseBody + mapOf("stream" to true)
    }

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
