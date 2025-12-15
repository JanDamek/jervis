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
import org.springframework.stereotype.Service

@Service
class OpenAiEmbeddingClient(
    private val ktorClientFactory: KtorClientFactory,
) : EmbeddingProviderClient {
    private val client by lazy { ktorClientFactory.getHttpClient("openai") }
    override val provider = ModelProviderEnum.OPENAI

    override suspend fun call(
        model: String,
        text: String,
    ): List<Float> {
        val body = mapOf("model" to model, "input" to text)

        return try {
            val response =
                client
                    .post("/embeddings") {
                        setBody(body)
                    }.body<OpenAiEmbeddingResponse>()

            response.data
                .firstOrNull()
                ?.embedding ?: emptyList()
        } catch (error: Exception) {
            throw when (error) {
                is ClientRequestException -> {
                    RuntimeException(
                        "ConnectionDocument error to OpenAI: ${error.message}",
                        error,
                    )
                }

                is ResponseException -> {
                    RuntimeException(
                        "OpenAI API error: ${error.response.status}",
                        error,
                    )
                }

                else -> {
                    error
                }
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
