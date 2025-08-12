package com.jervis.service.indexer.provider

import com.jervis.service.setting.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate

/**
 * OLLama implementation of the EmbeddingProvider interface.
 * This provider generates embeddings using the OLLama API.
 */
class OLLamaEmbeddingProvider(
    private val settingService: SettingService,
    private val restTemplate: RestTemplate = RestTemplate(),
) : EmbeddingProvider {
    private val logger = KotlinLogging.logger {}
    private val dimension: Int
    private val apiUrl: String
    private val model: String

    init {
        apiUrl = settingService.ollamaUrl
        model = settingService.embeddingModelName

        // Initialize dimension by making a test call to the API
        dimension =
            try {
                val testEmbedding = generateEmbedding("test")
                testEmbedding.size
            } catch (e: Exception) {
                logger.error(e) { "Error determining embedding dimension: ${e.message}" }
                768 // Default dimension if we can't determine it
            }

        logger.info { "Initialized OLLama embedding provider with model: $model, dimension: $dimension" }
    }

    override fun getDimension(): Int = dimension

    override fun predict(text: String): List<Float> {
        if (text.isBlank()) return List(dimension) { 0f }

        return try {
            generateEmbedding(text)
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding with OLLama: ${e.message}" }
            List(dimension) { 0f }
        }
    }

    private fun generateEmbedding(text: String): List<Float> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val requestBody =
            mapOf(
                "model" to model,
                "prompt" to text,
            )

        val request = HttpEntity(requestBody, headers)
        val response =
            restTemplate.postForObject(
                "$apiUrl/api/embeddings",
                request,
                OLLamaEmbeddingResponse::class.java,
            )

        return response?.embedding ?: List(dimension) { 0f }
    }
}

/**
 * Data class for parsing OLLama API responses
 */
data class OLLamaEmbeddingResponse(
    val embedding: List<Float>,
)
