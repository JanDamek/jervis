package com.jervis.service.gateway

import com.jervis.configuration.ConnectionPoolProperties
import com.jervis.configuration.EndpointProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.RetrysProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.sqrt

@Service
class EmbeddingGatewayImpl(
    private val modelsProperties: ModelsProperties,
    private val endpoints: EndpointProperties,
    private val retriesProperties: RetrysProperties,
    private val connectionPoolProperties: ConnectionPoolProperties,
    @Qualifier("lmStudioWebClient") private val lmStudioClient: WebClient,
    @Qualifier("ollamaWebClient") private val ollamaClient: WebClient,
    @Qualifier("openaiWebClient") private val openaiClient: WebClient,
) : EmbeddingGateway {
    private val logger = KotlinLogging.logger {}

    // Per-provider semaphores for rate limiting
    private val providerSemaphores = ConcurrentHashMap<String, Semaphore>()

    init {
        if (connectionPoolProperties.isEmbeddingRateLimitingEnabled()) {
            initializeSemaphores()
        }
    }

    private fun initializeSemaphores() {
        // Initialize semaphores for each model type and provider combination
        modelsProperties.models.entries.forEach { (modelType, modelDetails) ->
            if (modelType.name.startsWith("EMBEDDING")) {
                modelDetails.forEach { modelDetail ->
                    modelDetail.provider?.let { provider ->
                        val providerKey = provider.name.lowercase()
                        if (!providerSemaphores.containsKey(providerKey)) {
                            val permits =
                                connectionPoolProperties.getEmbeddingMaxConcurrentRequests(
                                    providerKey,
                                    modelDetail.maxRequests,
                                )
                            providerSemaphores[providerKey] = Semaphore(permits)
                        }
                    }
                }
            }
        }
    }

    override suspend fun callEmbedding(
        type: ModelType,
        text: String,
    ): List<Float> {
        val candidates = modelsProperties.models[type].orEmpty()
        check(candidates.isNotEmpty()) { "No embedding model candidates configured for $type" }

        val trimmed = text.ifBlank { "" }

        // Debug logging for embedding request details
        logger.debug { "Embedding Request - type=$type, text length=${text.length}, text preview=$text" }

        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            try {
                val result = doCallEmbedding(provider, candidate.model, trimmed)
                return l2Normalize(result)
            } catch (t: Throwable) {
                lastError = t
                logger.warn(t) { "Embedding candidate $index ($provider:${candidate.model}) failed, trying next if available" }
            }
        }
        throw IllegalStateException("All embedding candidates failed for $type", lastError)
    }

    private suspend fun doCallEmbedding(
        provider: ModelProvider,
        model: String,
        text: String,
    ): List<Float> {
        val providerKey = provider.name.lowercase()
        val semaphore =
            if (connectionPoolProperties.isEmbeddingRateLimitingEnabled()) {
                providerSemaphores[providerKey]
            } else {
                null
            }

        return if (semaphore != null) {
            semaphore.acquire()
            try {
                executeProviderCall(provider, model, text)
            } finally {
                semaphore.release()
            }
        } else {
            executeProviderCall(provider, model, text)
        }
    }

    private suspend fun executeProviderCall(
        provider: ModelProvider,
        model: String,
        text: String,
    ): List<Float> =
        when (provider) {
            ModelProvider.LM_STUDIO -> callLmStudioEmbedding(model, text)
            ModelProvider.OLLAMA -> callOllamaEmbedding(model, text)
            ModelProvider.OPENAI -> callOpenAiEmbedding(model, text)
            ModelProvider.ANTHROPIC -> error("Anthropic does not provide embeddings API")
        }

    private suspend fun callLmStudioEmbedding(
        model: String,
        input: String,
    ): List<Float> {
        val providerKey = "lm_studio"
        val retryCount = retriesProperties.getEmbeddingRetryCount(providerKey)
        val backoffDuration = retriesProperties.getEmbeddingBackoffDuration(providerKey)
        val maxBackoffDuration = retriesProperties.getEmbeddingMaxBackoffDuration(providerKey)

        val body = mapOf("model" to model, "input" to input)
        return lmStudioClient
            .post()
            .uri("/v1/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(LmStudioEmbResp::class.java)
            .retryWhen(
                Retry
                    .backoff(retryCount.toLong(), backoffDuration)
                    .maxBackoff(maxBackoffDuration)
                    .filter { it is WebClientRequestException || it is WebClientResponseException }
                    .onRetryExhaustedThrow { _, retrySignal ->
                        RuntimeException(
                            "LM Studio embedding request failed after ${retrySignal.totalRetries()} retries",
                            retrySignal.failure(),
                        )
                    },
            ).onErrorMap { error ->
                when (error) {
                    is WebClientRequestException ->
                        RuntimeException(
                            "Connection error to LM Studio: ${error.message}",
                            error,
                        )

                    is WebClientResponseException ->
                        RuntimeException(
                            "LM Studio API error: ${error.statusCode} - ${error.responseBodyAsString}",
                            error,
                        )

                    else -> error
                }
            }.awaitSingle()
            .data
            .firstOrNull()
            ?.embedding ?: emptyList()
    }

    private suspend fun callOllamaEmbedding(
        model: String,
        prompt: String,
    ): List<Float> {
        val providerKey = "ollama"
        val retryCount = retriesProperties.getEmbeddingRetryCount(providerKey)
        val backoffDuration = retriesProperties.getEmbeddingBackoffDuration(providerKey)
        val maxBackoffDuration = retriesProperties.getEmbeddingMaxBackoffDuration(providerKey)

        val body = mapOf("model" to model, "prompt" to prompt)
        return ollamaClient
            .post()
            .uri("/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(OllamaEmbResp::class.java)
            .retryWhen(
                Retry
                    .backoff(retryCount.toLong(), backoffDuration)
                    .maxBackoff(maxBackoffDuration)
                    .filter { it is WebClientRequestException || it is WebClientResponseException }
                    .onRetryExhaustedThrow { _, retrySignal ->
                        RuntimeException(
                            "Ollama embedding request failed after ${retrySignal.totalRetries()} retries",
                            retrySignal.failure(),
                        )
                    },
            ).onErrorMap { error ->
                when (error) {
                    is WebClientRequestException ->
                        RuntimeException(
                            "Connection error to Ollama: ${error.message}",
                            error,
                        )

                    is WebClientResponseException ->
                        RuntimeException(
                            "Ollama API error: ${error.statusCode} - ${error.responseBodyAsString}",
                            error,
                        )

                    else -> error
                }
            }.awaitSingle()
            .embedding
    }

    private suspend fun callOpenAiEmbedding(
        model: String,
        input: String,
    ): List<Float> {
        val providerKey = "openai"
        val retryCount = retriesProperties.getEmbeddingRetryCount(providerKey)
        val backoffDuration = retriesProperties.getEmbeddingBackoffDuration(providerKey)
        val maxBackoffDuration = retriesProperties.getEmbeddingMaxBackoffDuration(providerKey)

        val body = mapOf("model" to model, "input" to input)
        return openaiClient
            .post()
            .uri("/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(OpenAiEmbResp::class.java)
            .retryWhen(
                Retry
                    .backoff(retryCount.toLong(), backoffDuration)
                    .maxBackoff(maxBackoffDuration)
                    .filter { it is WebClientRequestException || it is WebClientResponseException }
                    .onRetryExhaustedThrow { _, retrySignal ->
                        RuntimeException(
                            "OpenAI embedding request failed after ${retrySignal.totalRetries()} retries",
                            retrySignal.failure(),
                        )
                    },
            ).onErrorMap { error ->
                when (error) {
                    is WebClientRequestException ->
                        RuntimeException(
                            "Connection error to OpenAI: ${error.message}",
                            error,
                        )

                    is WebClientResponseException ->
                        RuntimeException(
                            "OpenAI API error: ${error.statusCode} - ${error.responseBodyAsString}",
                            error,
                        )

                    else -> error
                }
            }.awaitSingle()
            .data
            .firstOrNull()
            ?.embedding ?: emptyList()
    }

    private fun l2Normalize(vec: List<Float>): List<Float> {
        var sum = 0.0
        for (v in vec) sum += (v * v)
        val norm = sqrt(sum).takeIf { it != 0.0 } ?: 1.0
        val inv = (1.0 / norm).toFloat()
        return vec.map { it * inv }
    }

    // Minimal response DTOs
    private data class LmStudioEmbResp(
        val data: List<LmStudioEmbData> = emptyList(),
    )

    private data class LmStudioEmbData(
        val embedding: List<Float> = emptyList(),
        val index: Int = 0,
    )

    private data class OllamaEmbResp(
        val embedding: List<Float> = emptyList(),
    )

    private data class OpenAiEmbResp(
        val data: List<OpenAiEmbData> = emptyList(),
    )

    private data class OpenAiEmbData(
        val embedding: List<Float> = emptyList(),
        val index: Int = 0,
    )
}
