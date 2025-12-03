package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.KtorClientFactory
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import org.springframework.stereotype.Service
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody

@Service
class LmStudioEmbeddingClient(
    private val ktorClientFactory: KtorClientFactory,
) : EmbeddingProviderClient {
    private val client by lazy { ktorClientFactory.getHttpClient("lmStudio") }
    override val provider = ModelProviderEnum.LM_STUDIO

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        val body = mapOf("model" to model, "input" to text)

        return try {
            val response = client.post("/v1/embeddings") {
                setBody(body)
            }.body<LmStudioEmbeddingResponse>()

            response.data
                .firstOrNull()
                ?.embedding ?: emptyList()
        } catch (error: Exception) {
            throw when (error) {
                is ClientRequestException ->
                    RuntimeException(
                        "Connection error to LM Studio: ${error.message}",
                        error,
                    )

                is ResponseException ->
                    RuntimeException(
                        "LM Studio API error: ${error.response.status}",
                        error,
                    )

                else -> error
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioEmbeddingResponse(
        val data: List<LmStudioEmbeddingData> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LmStudioEmbeddingData(
        val embedding: List<Float> = emptyList(),
        val index: Int = 0,
    )
}
