package com.jervis.service.llm.ollama

import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant

/**
 * Service for interacting with Ollama API
 */
@Service
class OllamaService {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastModelsCheck: Instant = Instant.EPOCH
    private var cachedModels: List<OllamaModel> = emptyList()

    /**
     * Test connection to Ollama server
     *
     * @param url The URL of the Ollama server
     * @return True if connection is successful, false otherwise
     */
    fun testConnection(url: String): Boolean {
        try {
            logger.debug { "Testing connection to Ollama server at $url" }
            restTemplate.getForObject("$url/api/tags", OllamaTagsResponse::class.java)
            logger.info { "Successfully connected to Ollama server at $url" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to Ollama server at $url: ${e.message}" }
            return false
        }
    }

    /**
     * Get list of available models from Ollama server
     *
     * @param url The URL of the Ollama server
     * @return List of available models, or empty list if connection fails
     */
    fun getAvailableModels(url: String): List<OllamaModel> {
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
            logger.debug { "Getting available models from Ollama server at $url" }
            val response = restTemplate.getForObject("$url/api/tags", OllamaTagsResponse::class.java)
            val models = response?.models ?: emptyList()

            // Cache the models
            cachedModels = models
            lastModelsCheck = now

            logger.info { "Found ${models.size} models on Ollama server at $url" }
            return models
        } catch (e: RestClientException) {
            logger.warn { "Failed to get models from Ollama server at $url: ${e.message}" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Error getting models from Ollama server at $url: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Get list of embedding models from Ollama server
     *
     * @param url The URL of the Ollama server
     * @return List of embedding models, or empty list if connection fails
     */
    fun getEmbeddingModels(url: String): List<OllamaModel> {
        // Get all models and filter for embedding models
        // Typically embedding models have "embed" in their name
        return getAvailableModels(url).filter {
            it.name.contains("embed", ignoreCase = true) ||
                it.name.contains("embedding", ignoreCase = true)
        }
    }
}
