package com.jervis.service.gateway

import com.jervis.configuration.EndpointProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.sqrt

@Service
class EmbeddingGatewayImpl(
    private val modelsProperties: ModelsProperties,
    private val endpoints: EndpointProperties,
) : EmbeddingGateway {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

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
            val key = semaphoreKey(type, index)
            val capacity = candidate.concurrency ?: defaultConcurrency(provider)
            val semaphore = semaphores.computeIfAbsent(key) { Semaphore(capacity) }

            val timeout = candidate.timeoutMs
            try {
                return if (timeout != null && timeout > 0) {
                    withTimeout(timeout) {
                        withPermit(semaphore) {
                            doCallEmbedding(
                                provider,
                                candidate.model,
                                trimmed,
                            )
                        }
                    }
                } else {
                    withPermit(semaphore) { doCallEmbedding(provider, candidate.model, trimmed) }
                }
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
        withContext(Dispatchers.IO) {
            val vec: List<Float> =
                when (provider) {
                    ModelProvider.LM_STUDIO -> callLmStudioEmbedding(model, text)
                    ModelProvider.OLLAMA -> callOllamaEmbedding(model, text)
                    ModelProvider.OPENAI -> callOpenAiEmbedding(model, text)
                    ModelProvider.ANTHROPIC -> error("Anthropic does not provide embeddings API")
                }
            l2Normalize(vec)
        }

    private fun callLmStudioEmbedding(
        model: String,
        input: String,
    ): List<Float> {
        val url = "${endpoints.lmStudio.baseUrl?.trimEnd('/') ?: "http://localhost:1234"}/v1/embeddings"
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = mapOf("model" to model, "input" to input)
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), LmStudioEmbResp::class.java)
        return resp?.data?.firstOrNull()?.embedding ?: emptyList()
    }

    private fun callOllamaEmbedding(
        model: String,
        prompt: String,
    ): List<Float> {
        val url = OllamaUrl.buildApiUrl(endpoints.ollama.baseUrl ?: "http://localhost:11434", "/embeddings")
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = mapOf("model" to model, "prompt" to prompt)
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), OllamaEmbResp::class.java)
        return resp?.embedding ?: emptyList()
    }

    private fun callOpenAiEmbedding(
        model: String,
        input: String,
    ): List<Float> {
        val base = endpoints.openai.baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"
        val url = "$base/embeddings"
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("Authorization", "Bearer ${endpoints.openai.apiKey.orEmpty()}")
            }
        val body = mapOf("model" to model, "input" to input)
        val resp = restTemplate.postForObject(url, HttpEntity(body, headers), OpenAiEmbResp::class.java)
        return resp?.data?.firstOrNull()?.embedding ?: emptyList()
    }

    private fun semaphoreKey(
        type: ModelType,
        index: Int,
    ) = "${type.name}#$index"

    private fun defaultConcurrency(provider: ModelProvider): Int =
        when (provider) {
            ModelProvider.LM_STUDIO -> 2
            ModelProvider.OLLAMA -> 4
            ModelProvider.OPENAI -> 10
            ModelProvider.ANTHROPIC -> 10
        }

    private suspend fun <T> withPermit(
        sem: Semaphore,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) {
                sem.acquire()
            }
            block()
        } finally {
            sem.release()
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
