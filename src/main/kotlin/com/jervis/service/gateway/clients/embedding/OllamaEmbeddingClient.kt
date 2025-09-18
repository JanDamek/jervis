package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.domain.model.ModelProvider
import kotlinx.coroutines.reactive.awaitSingle
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

        return client
            .post()
            .uri("/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(OllamaEmbeddingResponse::class.java)
            .onErrorMap { error ->
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
            }.map { response -> response.embedding }
            .awaitSingle()
    }
}
