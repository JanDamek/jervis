package com.jervis.service.gateway.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.RetrysProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.service.gateway.EmbeddingProviderClient
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry

@JsonIgnoreProperties(ignoreUnknown = true)
data class OllamaEmbeddingResponse(
    val embedding: List<Float> = emptyList(),
)

@Service
class OllamaEmbeddingClient(
    @Qualifier("ollamaWebClient") private val client: WebClient,
    private val retriesProperties: RetrysProperties
) : EmbeddingProviderClient {
    
    override val provider = ModelProvider.OLLAMA
    
    override suspend fun call(model: String, text: String): List<Float> {
        val providerKey = "ollama"
        val retryCount = retriesProperties.getEmbeddingRetryCount(providerKey)
        val backoffDuration = retriesProperties.getEmbeddingBackoffDuration(providerKey)
        val maxBackoffDuration = retriesProperties.getEmbeddingMaxBackoffDuration(providerKey)

        val body = mapOf("model" to model, "prompt" to text)
        
        return client
            .post()
            .uri("/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(OllamaEmbeddingResponse::class.java)
            .retryWhen(
                Retry
                    .backoff(retryCount.toLong(), backoffDuration)
                    .maxBackoff(maxBackoffDuration)
                    .filter { it is WebClientRequestException || it is WebClientResponseException }
                    .onRetryExhaustedThrow { _, retrySignal ->
                        RuntimeException(
                            "Ollama embedding request failed after ${retrySignal.totalRetries()} retries",
                            retrySignal.failure(),
                        )
                    },
            ).onErrorMap { error ->
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
            }.awaitSingle()
            .embedding
    }
}