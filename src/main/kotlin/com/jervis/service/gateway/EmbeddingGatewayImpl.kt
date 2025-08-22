package com.jervis.service.gateway

import com.jervis.configuration.EndpointProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import kotlin.math.sqrt

@Service
class EmbeddingGatewayImpl(
    private val modelsProperties: ModelsProperties,
    private val endpoints: EndpointProperties,
    @Qualifier("lmStudioWebClient") private val lmStudioClient: WebClient,
    @Qualifier("ollamaWebClient") private val ollamaClient: WebClient,
    @Qualifier("openaiWebClient") private val openaiClient: WebClient,
) : EmbeddingGateway {
    private val logger = KotlinLogging.logger {}

    override suspend fun callEmbedding(
        type: ModelType,
        text: String,
    ): List<Float> {
        val candidates = modelsProperties.models[type].orEmpty()
        check(candidates.isNotEmpty()) { "No embedding model candidates configured for $type" }

        val trimmed = text.ifBlank { "" }
        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            val timeout = candidate.timeoutMs
            try {
                val result =
                    if (timeout != null && timeout > 0) {
                        withTimeout(timeout) { doCallEmbedding(provider, candidate.model, trimmed) }
                    } else {
                        doCallEmbedding(provider, candidate.model, trimmed)
                    }
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
        val body = mapOf("model" to model, "input" to input)
        val resp: LmStudioEmbResp =
            lmStudioClient
                .post()
                .uri("/v1/embeddings")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        return resp.data.firstOrNull()?.embedding ?: emptyList()
    }

    private suspend fun callOllamaEmbedding(
        model: String,
        prompt: String,
    ): List<Float> {
        val body = mapOf("model" to model, "prompt" to prompt)
        val resp: OllamaEmbResp =
            ollamaClient
                .post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        return resp.embedding
    }

    private suspend fun callOpenAiEmbedding(
        model: String,
        input: String,
    ): List<Float> {
        val body = mapOf("model" to model, "input" to input)
        val resp: OpenAiEmbResp =
            openaiClient
                .post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .awaitBody()
        return resp.data.firstOrNull()?.embedding ?: emptyList()
    }

    private fun l2Normalize(vec: List<Float>): List<Float> {
        var sum = 0.0
        for (v in vec) sum += (v * v)
        val norm = sqrt(sum).takeIf { it != 0.0 } ?: 1.0
        val inv = (1.0 / norm).toFloat()
        return vec.map { it * inv }
    }
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
