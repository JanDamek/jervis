package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

@Service
class OpenAiEmbeddingClient(
    @Qualifier("openaiWebClient") private val client: WebClient,
) : EmbeddingProviderClient {
    override val provider = ModelProviderEnum.OPENAI

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        val body = mapOf("model" to model, "input" to text)

        return try {
            val response =
                client
                    .post()
                    .uri("/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody<OpenAiEmbeddingResponse>()

            response.data
                .firstOrNull()
                ?.embedding ?: emptyList()
        } catch (error: Exception) {
            throw when (error) {
                is WebClientRequestException ->
                    RuntimeException(
                        "Connection error to OpenAI: ${error.message}",
                        error,
                    )

                is WebClientResponseException ->
                    RuntimeException(
                        "OpenAI API error: ${error.statusCode} - ${error.responseBodyAsString}",
                        error,
                    )

                else -> error
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiEmbeddingResponse(
        val data: List<OpenAiEmbeddingData> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OpenAiEmbeddingData(
        val embedding: List<Float> = emptyList(),
        val index: Int = 0,
    )
}
