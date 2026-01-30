package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.KtorClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class OllamaEmbeddingClient(
    private val ktorClientFactory: KtorClientFactory,
) : EmbeddingProviderClient {
    private val client by lazy { ktorClientFactory.getHttpClient("ollama.qualifier") }
    override val provider = ModelProviderEnum.OLLAMA
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

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
                client.post("/api/embeddings") {
                    setBody(request)
                }
            val responseText = response.bodyAsText()
            val responseBody = json.decodeFromString<OllamaEmbeddingResponse>(responseText)

            if (responseBody.embedding.isEmpty()) {
                logger.warn { "Ollama returned empty embedding for model $model. Response: $responseText" }
            }

            responseBody.embedding
        } catch (error: Exception) {
            throw when (error) {
                is ClientRequestException -> {
                    RuntimeException(
                        "ConnectionDocument error to Ollama: ${error.message}",
                        error,
                    )
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
                }.bodyAsText()
            logger.debug { "Ollama embedding model available: $model" }
        } catch (e: ResponseException) {
            logger.info { "Embedding model '$model' not present (status=${e.response.status}). Pulling before first use..." }
            val pullRequest = OllamaModelRequest(name = model)
            val resp =
                client
                    .post("/api/pull") {
                        setBody(pullRequest)
                    }.bodyAsText()
            logger.info { "Ollama pull for embedding model completed: $model -> $resp" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to ensure embedding model '$model' is available (attempted pull if needed)" }
        }
    }

    @kotlinx.serialization.Serializable
    data class OllamaEmbeddingRequest(
        val model: String,
        val prompt: String,
    )

    @kotlinx.serialization.Serializable
    data class OllamaModelRequest(
        val name: String,
    )

    @kotlinx.serialization.Serializable
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingResponse(
        val embedding: List<Float> = emptyList(),
    )
}
