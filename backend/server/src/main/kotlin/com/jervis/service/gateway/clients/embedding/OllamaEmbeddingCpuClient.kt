package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.KtorClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Embedding provider client targeting dedicated Ollama embedding instance (separate port).
 * Use provider OLLAMA_EMBEDDING and endpoint key "ollama.embedding" in KtorClientFactory.
 */
@Service
class OllamaEmbeddingCpuClient(
    private val ktorClientFactory: KtorClientFactory,
) : EmbeddingProviderClient {
    private val client by lazy { ktorClientFactory.getHttpClient("ollama.embedding") }
    override val provider = ModelProviderEnum.OLLAMA_EMBEDDING
    private val logger = KotlinLogging.logger {}

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        ensureModelAvailable(model)
        val request =
            OllamaEmbeddingRequest(
                model = model,
                prompt = text,
            )
        return try {
            val response =
                client
                    .post("/api/embeddings") {
                        setBody(request)
                    }.body<OllamaEmbeddingResponse>()
            response.embedding
        } catch (error: Exception) {
            throw when (error) {
                is ClientRequestException -> {
                    RuntimeException("ConnectionDocument error to Ollama: ${error.message}", error)
                }

                is ResponseException -> {
                    RuntimeException(
                        "Ollama API error: ${error.response.status}",
                        error,
                    )
                }

                else -> {
                    error
                }
            }
        }
    }

    private suspend fun ensureModelAvailable(model: String) {
        try {
            val showRequest = OllamaModelRequest(name = model)
            client
                .post("/api/show") {
                    setBody(showRequest)
                }.bodyAsText() // we don't need structured body here
            logger.debug { "Embedding model available on embedding endpoint: $model" }
        } catch (e: ResponseException) {
            logger.info { "Embedding model '$model' not present (status=${e.response.status}). Pulling..." }
            val pullRequest = OllamaModelRequest(name = model)
            client
                .post("/api/pull") {
                    setBody(pullRequest)
                }.bodyAsText() // ignore body content; just ensure call succeeds
            logger.info { "Ollama pull for embedding model completed on embedding endpoint: $model" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to ensure embedding model '$model' on embedding endpoint" }
        }
    }

    @Serializable
    data class OllamaEmbeddingRequest(
        val model: String,
        val prompt: String,
    )

    @Serializable
    data class OllamaModelRequest(
        val name: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class OllamaEmbeddingResponse(
        val embedding: List<Float> = emptyList(),
    )
}
