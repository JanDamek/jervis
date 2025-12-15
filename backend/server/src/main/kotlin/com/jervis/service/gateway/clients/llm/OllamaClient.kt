package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.KtorClientFactory
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.gateway.StreamChunk
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.service.gateway.clients.ProviderClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
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
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class OllamaClient(
    private val ktorClientFactory: KtorClientFactory,
) : ProviderClient {
    private val primaryHttpClient: HttpClient by lazy { ktorClientFactory.getHttpClient("ollama.primary") }
    private val qualifierHttpClient: HttpClient by lazy { ktorClientFactory.getHttpClient("ollama.qualifier") }
    private val logger = KotlinLogging.logger {}

    private val ensuredModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val provider: ModelProviderEnum = ModelProviderEnum.OLLAMA

    /**
     * Select appropriate HttpClient based on ModelType from prompt config.
     * QUALIFIER type uses separate endpoint (CPU server), others use primary (GPU server).
     */
    private fun selectHttpClient(prompt: PromptConfig): HttpClient =
        when (prompt.modelParams.modelType) {
            ModelTypeEnum.QUALIFIER -> qualifierHttpClient
            else -> primaryHttpClient
        }

    override suspend fun call(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
    ): LlmResponse {
        val httpClient = selectHttpClient(prompt)
        return callWithHttpClient(httpClient, model, systemPrompt, userPrompt, config, estimatedTokens)
    }

    /**
     * Internal implementation with explicit HttpClient for reuse by OllamaQualifierClient
     */
    suspend fun callWithHttpClient(
        httpClient: HttpClient,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
    ): LlmResponse {
        val responseBuilder = StringBuilder()
        var finalMetadata: Map<String, Any> = emptyMap()

        callWithStreamingHttpClient(httpClient, model, systemPrompt, userPrompt, config, estimatedTokens)
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
        systemPrompt: String,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String?,
    ): Flow<StreamChunk> {
        val httpClient = selectHttpClient(prompt)
        return callWithStreamingHttpClient(httpClient, model, systemPrompt, userPrompt, config, estimatedTokens)
    }

    /**
     * Internal implementation with explicit HttpClient for reuse by OllamaQualifierClient
     */
    fun callWithStreamingHttpClient(
        httpClient: HttpClient,
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
    ): Flow<StreamChunk> =
        flow {
            ensureModelAvailable(httpClient, model)

            val options = buildOptions(config, estimatedTokens)
            val requestBody = buildRequestBody(model, userPrompt, systemPrompt, options)

            val response: HttpResponse =
                httpClient.post("/api/generate") {
                    contentType(ContentType.Text.EventStream)
                    setBody(requestBody)
                }

            val responseBuilder = StringBuilder()
            var totalPromptTokens: Int
            var totalCompletionTokens: Int
            var finalModel: String
            var finishReason: String

            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
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

    private suspend fun ensureModelAvailable(
        httpClient: HttpClient,
        model: String,
    ) {
        if (ensuredModels.contains(model)) return
        try {
            val showBody = mapOf("name" to model)
            httpClient
                .post("/api/show") {
                    setBody(showBody)
                }.body<Map<String, Any>>()
            logger.debug { "Ollama model available: $model" }
            ensuredModels.add(model)
        } catch (e: ResponseException) {
            logger.info { "Model '$model' not present on Ollama (status=${e.response.status}). Pulling before first use..." }
            pullModelBlocking(httpClient, model)
            ensuredModels.add(model)
        } catch (e: Exception) {
            // If unknown error during show, attempt pull once
            logger.warn(e) { "Checking model '$model' failed, attempting pull..." }
            pullModelBlocking(httpClient, model)
            ensuredModels.add(model)
        }
    }

    private suspend fun pullModelBlocking(
        httpClient: HttpClient,
        model: String,
    ) {
        val body = mapOf("name" to model)
        val resp =
            httpClient
                .post("/api/pull") {
                    setBody(body)
                }.body<Map<String, Any>>()
        logger.info { "Ollama pull completed for $model before first use: $resp" }
    }

    private fun buildOptions(
        config: ModelsProperties.ModelDetail,
        estimatedTokens: Int,
    ): Map<String, Any> {
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

        return maxTokensOption + contextLengthOption
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

        return baseBody + systemField + optionsField
    }
}
