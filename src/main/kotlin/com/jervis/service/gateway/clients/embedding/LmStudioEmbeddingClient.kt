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
data class LmStudioEmbeddingResponse(
    val data: List<LmStudioEmbeddingData> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LmStudioEmbeddingData(
    val embedding: List<Float> = emptyList(),
    val index: Int = 0,
)

@Service
class LmStudioEmbeddingClient(
    @Qualifier("lmStudioWebClient") private val client: WebClient,
) : EmbeddingProviderClient {
    override val provider = ModelProvider.LM_STUDIO

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        val body = mapOf("model" to model, "input" to text)

        return client
            .post()
            .uri("/v1/embeddings")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(LmStudioEmbeddingResponse::class.java)
            .onErrorMap { error ->
                when (error) {
                    is WebClientRequestException ->
                        RuntimeException(
                            "Connection error to LM Studio: ${error.message}",
                            error,
                        )

                    is WebClientResponseException ->
                        RuntimeException(
                            "LM Studio API error: ${error.statusCode} - ${error.responseBodyAsString}",
                            error,
                        )

                    else -> error
                }
            }.map { response ->
                response.data
                    .firstOrNull()
                    ?.embedding ?: emptyList()
            }.awaitSingle()
    }
}
