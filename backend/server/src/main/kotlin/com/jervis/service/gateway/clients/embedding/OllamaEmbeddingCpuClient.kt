package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.KtorClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.configuration.properties.OllamaProperties
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Embedding provider client targeting dedicated Ollama embedding instance (separate port).
 * Use provider OLLAMA_EMBEDDING and endpoint key "ollama.embedding" in KtorClientFactory.
 */
@Service
class OllamaEmbeddingCpuClient(
    private val ktorClientFactory: KtorClientFactory,
    private val ollamaProps: OllamaProperties,
) : EmbeddingProviderClient {
    private val client by lazy { ktorClientFactory.getHttpClient("ollama.embedding") }
    override val provider = ModelProviderEnum.OLLAMA_EMBEDDING
    private val logger = KotlinLogging.logger {}
    private val defaultKeepAlive: String get() = ollamaProps.keepAlive.default

    override suspend fun call(model: String, text: String): List<Float> {
        ensureModelAvailable(model)
        val body = mapOf(
            "model" to model,
            "input" to text,
            "keep_alive" to defaultKeepAlive.takeUnless { it.isBlank() },
        ).filterValues { it != null }
        return try {
            val response = client.post("/api/embeddings") {
                setBody(body)
            }.body<OllamaEmbeddingResponse>()
            response.embedding
        } catch (error: Exception) {
            throw when (error) {
                is ClientRequestException -> RuntimeException("Connection error to Ollama: ${error.message}", error)
                is ResponseException -> RuntimeException(
                    "Ollama API error: ${error.response.status}",
                    error,
                )
                else -> error
            }
        }
    }

    private suspend fun ensureModelAvailable(model: String) {
        try {
            val showBody = mapOf("name" to model)
            client.post("/api/show") {
                setBody(showBody)
            }.body<Map<String, Any>>()
            logger.debug { "Embedding model available on embedding endpoint: $model" }
        } catch (e: ResponseException) {
            logger.info { "Embedding model '$model' not present (status=${e.response.status}). Pulling..." }
            val body = mapOf("name" to model)
            client.post("/api/pull") {
                setBody(body)
            }.body<Map<String, Any>>()
            logger.info { "Ollama pull for embedding model completed on embedding endpoint: $model" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to ensure embedding model '$model' on embedding endpoint" }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingResponse(
        val embedding: List<Float> = emptyList(),
    )
}
