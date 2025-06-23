package com.jervis.module.indexer.provider

import com.jervis.service.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

/**
 * OpenAI implementation of the EmbeddingProvider interface.
 * This provider generates embeddings using the OpenAI API.
 */
class OpenAIEmbeddingProvider(
    private val settingService: SettingService,
    private val restTemplate: RestTemplate = RestTemplate(),
) : EmbeddingProvider {
    private val logger = KotlinLogging.logger {}
    private val dimension: Int
    private val apiKey: String
    private val apiUrl: String
    private val model: String

    init {
        apiKey = settingService.getOpenaiApiKey()
        apiUrl = "https://api.openai.com"
        model = "text-embedding-3-large"

        // OpenAI models have fixed dimensions
        dimension = 3072
        logger.info { "Initialized OpenAI embedding provider with model: $model, dimension: $dimension" }
    }

    override fun getDimension(): Int = dimension

    override fun predict(text: String): List<Float> {
        if (text.isBlank()) return List(dimension) { 0f }

        try {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.set("Authorization", "Bearer $apiKey")

            val requestBody =
                mapOf(
                    "model" to model,
                    "input" to text,
                )

            val request = HttpEntity(requestBody, headers)
            val response =
                restTemplate.postForObject(
                    "$apiUrl/v1/embeddings",
                    request,
                    OpenAIEmbeddingResponse::class.java,
                )

            return response?.data?.firstOrNull()?.embedding ?: List(dimension) { 0f }
        } catch (e: HttpClientErrorException) {
            logger.error(e) { "Error calling OpenAI API: ${e.responseBodyAsString}" }
            return List(dimension) { 0f }
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding with OpenAI: ${e.message}" }
            return List(dimension) { 0f }
        }
    }
}

/**
 * Data classes for parsing OpenAI API responses
 */
data class OpenAIEmbeddingResponse(
    val data: List<OpenAIEmbeddingData>,
    val model: String,
    val usage: OpenAIUsage,
)

data class OpenAIEmbeddingData(
    val embedding: List<Float>,
    val index: Int,
    val `object`: String, // Using backticks to escape the reserved keyword
)

data class OpenAIUsage(
    val prompt_tokens: Int,
    val total_tokens: Int,
)
