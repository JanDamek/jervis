package com.jervis.service.gateway.clients.llm

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class GoogleLlmClient(
    @Qualifier("googleWebClient") private val webClient: WebClient,
    private val promptsConfiguration: PromptsConfiguration,
) : ProviderClient {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    override val provider: ModelProviderEnum = ModelProviderEnum.GOOGLE

    override suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfigBase,
        estimatedTokens: Int,
    ): LlmResponse {
        val responseBuilder = StringBuilder()
        var finalMetadata: Map<String, Any> = emptyMap()

        callWithStreaming(model, systemPrompt, userPrompt, config, prompt, estimatedTokens)
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
            finishReason = finalMetadata["finish_reason"] as? String ?: "STOP",
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
    ): Flow<StreamChunk> =
        flow {
            logger.debug { "GOOGLE_LLM: Streaming call model=$model, tokens=$estimatedTokens" }

            val creativityConfig = getCreativityConfig(prompt)
            val contents = buildList {
                // Gemini v1 does not support systemInstruction; include system prompt as the first user message
                systemPrompt?.takeUnless { it.isBlank() }?.let {
                    add(GoogleContent(role = "user", parts = listOf(GooglePart(text = it))))
                }
                add(GoogleContent(role = "user", parts = listOf(GooglePart(text = userPrompt))))
            }
            val requestBody = buildRequestBody(contents, creativityConfig, config)

            // Safe debug: log shape only, not actual content
            runCatching {
                val partsLengths = contents.map { c -> c.parts.sumOf { it.text?.length ?: 0 } }
                logger.debug { "GOOGLE_LLM: payload shape contents=${contents.size}, partsLengths=$partsLengths, maxOut=${config.numPredict}" }
            }

            val responseFlow =
                webClient
                    .post()
                    // Google Generative Language streaming endpoint (v1beta) with SSE
                    .uri("/v1beta/models/$model:streamGenerateContent?alt=sse")
                    .bodyValue(requestBody)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(String::class.java)
                    .asFlow()

            var totalPromptTokens = 0
            var totalCompletionTokens = 0
            var finishReason = "STOP"

            responseFlow.collect { line ->
                val raw = line.trim()
                if (raw.isBlank()) return@collect
                // Handle Server-Sent Events framing from Google streaming API
                val payload =
                    when {
                        raw.startsWith("data:", ignoreCase = true) -> raw.substringAfter("data:").trim()
                        raw.startsWith(":") || raw.startsWith("event:", ignoreCase = true) -> return@collect
                        else -> raw
                    }
                if (payload.isBlank() || payload == "[DONE]") return@collect
                try {
                    val response = json.decodeFromString<GoogleStreamResponse>(payload)

                    response.candidates.firstOrNull()?.let { candidate ->
                        candidate.content?.parts?.forEach { part ->
                            part.text?.let { text ->
                                if (text.isNotEmpty()) {
                                    emit(
                                        StreamChunk(
                                            content = text,
                                            isComplete = false,
                                            metadata = emptyMap(),
                                        ),
                                    )
                                }
                            }
                        }

                        candidate.finishReason?.let {
                            finishReason = it
                        }
                    }

                    response.usageMetadata?.let { usage ->
                        totalPromptTokens = usage.promptTokenCount
                        totalCompletionTokens = usage.candidatesTokenCount
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error parsing Google streaming response: ${e.message}. Line='$raw'" }
                }
            }

            emit(
                StreamChunk(
                    content = "",
                    isComplete = true,
                    metadata =
                        mapOf(
                            "model" to model,
                            "prompt_tokens" to totalPromptTokens,
                            "completion_tokens" to totalCompletionTokens,
                            "total_tokens" to (totalPromptTokens + totalCompletionTokens),
                            "finish_reason" to finishReason,
                        ),
                ),
            )

            logger.debug { "GOOGLE_LLM: Streaming completed" }
        }

    private fun buildRequestBody(
        contents: List<GoogleContent>,
        creativityConfig: CreativityConfig,
        config: ModelsProperties.ModelDetail,
    ): GoogleRequest {
        val generationConfig =
            GoogleGenerationConfig(
                temperature = creativityConfig.temperature,
                topP = creativityConfig.topP,
                maxOutputTokens = config.numPredict,
            )

        return GoogleRequest(
            contents = contents,
            generationConfig = generationConfig,
        )
    }

    private fun getCreativityConfig(prompt: PromptConfigBase): CreativityConfig =
        promptsConfiguration.creativityLevels[prompt.modelParams.creativityLevel]
            ?: throw IllegalStateException("No creativity level configuration found for ${prompt.modelParams.creativityLevel}")

    @Serializable
    data class GoogleRequest(
        val contents: List<GoogleContent>,
        val generationConfig: GoogleGenerationConfig,
    )

    @Serializable
    data class GoogleContent(
        val role: String,
        val parts: List<GooglePart>,
    )

    @Serializable
    data class GooglePart(
        val text: String? = null,
    )

    @Serializable
    data class GoogleGenerationConfig(
        val temperature: Double,
        val topP: Double,
        val maxOutputTokens: Int? = null,
    )


    @Serializable
    data class GoogleStreamResponse(
        val candidates: List<GoogleCandidate> = emptyList(),
        val usageMetadata: GoogleUsageMetadata? = null,
    )

    @Serializable
    data class GoogleCandidate(
        val content: GoogleContent? = null,
        val finishReason: String? = null,
    )

    @Serializable
    data class GoogleUsageMetadata(
        val promptTokenCount: Int = 0,
        val candidatesTokenCount: Int = 0,
        val totalTokenCount: Int = 0,
    )
}
