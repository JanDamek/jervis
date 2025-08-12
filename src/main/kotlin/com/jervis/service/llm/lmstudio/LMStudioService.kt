package com.jervis.service.llm.lmstudio

import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant

/**
 * Service for interacting with LM Studio API
 */
@Service
class LMStudioService {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastModelsCheck: Instant = Instant.EPOCH
    private var cachedModels: List<LMStudioModel> = emptyList()

    /**
     * Test connection to LM Studio server
     *
     * @param url The URL of the LM Studio server (base URL, e.g. http://localhost:1234/v1)
     * @return True if connection is successful, false otherwise
     */
    fun testConnection(url: String): Boolean {
        try {
            logger.debug { "Testing connection to LM Studio server at $url" }

            // Extract base URL (remove /chat/completions if present)
            val baseUrl = url.replace("/chat/completions", "")

            // Try to get models list
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            val entity = HttpEntity<String>(headers)

            restTemplate.exchange("$baseUrl/v1/models", HttpMethod.GET, entity, ModelsResponse::class.java)

            logger.info { "Successfully connected to LM Studio server at $url" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to LM Studio server at $url: ${e.message}" }
            return false
        }
    }

    /**
     * Get list of available models from LM Studio server
     *
     * @param url The URL of the LM Studio server (base URL, e.g. http://localhost:1234/v1)
     * @return List of available models, or empty list if connection fails
     */
    fun getAvailableModels(url: String): List<LMStudioModel> {
        // Only check models every 60 seconds to avoid too many calls
        val now = Instant.now()
        if (Duration
                .between(
                    lastModelsCheck,
                    now,
                ).seconds < 60 &&
            lastModelsCheck != Instant.EPOCH &&
            cachedModels.isNotEmpty()
        ) {
            return cachedModels
        }

        try {
            logger.debug { "Getting available models from LM Studio server at $url" }

            // Extract base URL (remove /chat/completions if present)
            val baseUrl = url.replace("/chat/completions", "")

            // Get models list
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            val entity = HttpEntity<String>(headers)

            val response = restTemplate.exchange("$baseUrl/v1/models", HttpMethod.GET, entity, ModelsResponse::class.java)
            val models = response.body?.data ?: emptyList()

            // Cache the models
            cachedModels = models
            lastModelsCheck = now

            logger.info { "Found ${models.size} models on LM Studio server at $url" }
            return models
        } catch (e: RestClientException) {
            logger.warn { "Failed to get models from LM Studio server at $url: ${e.message}" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Error getting models from LM Studio server at $url: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Get list of embedding models from LM Studio server
     *
     * @param url The URL of the LM Studio server (base URL, e.g. http://localhost:1234/v1)
     * @return List of embedding models, or empty list if connection fails
     */
    fun getEmbeddingModels(url: String): List<LMStudioModel> {
        // Get all models and filter for embedding models
        // Typically embedding models have "embed" in their name
        return getAvailableModels(url).filter {
            it.id.contains("embed", ignoreCase = true) ||
                it.id.contains("embedding", ignoreCase = true)
        }
    }
}

/**
 * Response from LM Studio /models endpoint
 */
data class ModelsResponse(
    val data: List<LMStudioModel>,
    val `object`: String,
)

/**
 * Model information from LM Studio
 */
data class LMStudioModel(
    val id: String,
    val `object`: String,
    val owned_by: String,
)
