package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.KtorClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.configuration.properties.OllamaProperties
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import org.springframework.stereotype.Service
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import mu.KotlinLogging

@Service
class OllamaEmbeddingClient(
    private val ktorClientFactory: KtorClientFactory,
    private val ollamaProps: OllamaProperties,
) : EmbeddingProviderClient {
    // Embeddings are CPU-friendly and memory-bound; use the CPU (qualifier) Ollama endpoint
    private val client by lazy { ktorClientFactory.getHttpClient("ollama.qualifier") }
    override val provider = ModelProviderEnum.OLLAMA
    private val logger = KotlinLogging.logger {}
    private val defaultKeepAlive: String get() = ollamaProps.keepAlive.default

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        // Ensure model exists (pull if missing)
        ensureModelAvailable(model)

        // Ollama embeddings API expects { model, input } at /api/embeddings
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
                is ClientRequestException ->
                    RuntimeException(
                        "Connection error to Ollama: ${error.message}",
                        error,
                    )

                is ResponseException ->
                    RuntimeException(
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
            logger.debug { "Ollama embedding model available: $model" }
        } catch (e: ResponseException) {
            logger.info { "Embedding model '$model' not present (status=${e.response.status}). Pulling before first use..." }
            val body = mapOf("name" to model)
            val resp = client.post("/api/pull") {
                setBody(body)
            }.body<Map<String, Any>>()
            logger.info { "Ollama pull for embedding model completed: $model -> $resp" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to ensure embedding model '$model' is available (attempted pull if needed)" }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingResponse(
        val embedding: List<Float> = emptyList(),
    )
}
