package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.WebClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

@Service
class OllamaEmbeddingClient(
    private val webClientFactory: WebClientFactory,
) : EmbeddingProviderClient {
    private val client by lazy { webClientFactory.getWebClient("ollama.primary") }
    override val provider = ModelProviderEnum.OLLAMA

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        // Ollama embeddings API expects { model, input } at /api/embeddings
        val body = mapOf("model" to model, "input" to text)

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingResponse(
        val embedding: List<Float> = emptyList(),
    )
}
