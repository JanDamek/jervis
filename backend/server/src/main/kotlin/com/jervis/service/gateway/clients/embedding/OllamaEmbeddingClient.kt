package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.WebClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.configuration.properties.OllamaProperties
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.WebClientResponseException.NotFound
import mu.KotlinLogging

@Service
class OllamaEmbeddingClient(
    private val webClientFactory: WebClientFactory,
    private val ollamaProps: OllamaProperties,
) : EmbeddingProviderClient {
    // Embeddings are CPU-friendly and memory-bound; use the CPU (qualifier) Ollama endpoint
    private val client by lazy { webClientFactory.getWebClient("ollama.qualifier") }
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
            val response =
                client
                    .post()
                    .uri("/api/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody<OllamaEmbeddingResponse>()

            response.embedding
        } catch (error: Exception) {
            throw when (error) {
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
        }
    }

    private suspend fun ensureModelAvailable(model: String) {
        try {
            val showBody = mapOf("name" to model)
            client
                .post()
                .uri("/api/show")
                .bodyValue(showBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
            logger.debug { "Ollama embedding model available: $model" }
        } catch (e: WebClientResponseException) {
            logger.info { "Embedding model '$model' not present (status=${e.statusCode}). Pulling before first use..." }
            val body = mapOf("name" to model)
            val resp =
                client
                    .post()
                    .uri("/api/pull")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody<Map<String, Any>>()
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
