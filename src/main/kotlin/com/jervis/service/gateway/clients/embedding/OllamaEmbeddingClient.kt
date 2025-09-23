package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.domain.model.ModelProvider
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

@JsonIgnoreProperties(ignoreUnknown = true)
data class OllamaEmbeddingResponse(
    val embedding: List<Float> = emptyList(),
)

@Service
class OllamaEmbeddingClient(
    @Qualifier("ollamaWebClient") private val client: WebClient,
) : EmbeddingProviderClient {
    override val provider = ModelProvider.OLLAMA

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        val body = mapOf("model" to model, "prompt" to text)

        return try {
            val response = client
                .post()
                .uri("/embeddings")
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
}
