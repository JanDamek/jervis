package com.jervis.service.indexer.provider

import com.jervis.service.setting.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate

/**
 * LM Studio implementation of the EmbeddingProvider interface.
 * This provider generates embeddings using the LM Studio API.
 */
class LMStudioEmbeddingProvider(
    settingService: SettingService,
    private val restTemplate: RestTemplate = RestTemplate(),
) : EmbeddingProvider {
    private val logger = KotlinLogging.logger {}
    private val dimension: Int
    private val apiUrl: String = settingService.lmStudioUrl
    private val model: String = settingService.embeddingModelName

    init {

        // Initialize dimension by making a test call to the API
        dimension =
            try {
                val testEmbedding = generateEmbedding("test")
                testEmbedding.size
            } catch (e: Exception) {
                logger.error(e) { "Error determining embedding dimension: ${e.message}" }
                768 // Default dimension if we can't determine it
            }

        logger.info { "Initialized LM Studio embedding provider with model: $model, dimension: $dimension" }
    }

    override fun getDimension(): Int = dimension

    override fun predict(text: String): List<Float> {
        if (text.isBlank()) return List(dimension) { 0f }

        return try {
            generateEmbedding(text)
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding with LM Studio: ${e.message}" }
            List(dimension) { 0f }
        }
    }

    private fun generateEmbedding(text: String): List<Float> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

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
                LMStudioEmbeddingResponse::class.java,
            )

        return response?.data?.firstOrNull()?.embedding ?: List(dimension) { 0f }
    }
}

/**
 * Data classes for parsing LM Studio API responses
 */
data class LMStudioEmbeddingResponse(
    val data: List<LMStudioEmbeddingData>,
    val model: String,
)

data class LMStudioEmbeddingData(
    val embedding: List<Float>,
    val index: Int,
    val `object`: String,
)
