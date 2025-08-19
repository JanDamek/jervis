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
class OllamaService(
    private val settingService: com.jervis.service.setting.SettingService,
) {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastModelsCheck: Instant = Instant.EPOCH
    private var cachedModels: List<OllamaModel> = emptyList()

    /**
     * Test connection to Ollama server
     *
     * The user can enter base in multiple forms (with/without scheme, with/without /api).
     * We'll normalize to /api/version first as a lightweight health check,
     * and fallback to /api/tags if needed.
     */
    fun testConnection(url: String): Boolean {
        var connected = false
        try {
            logger.debug { "Testing connection to Ollama server at $url" }
            val versionUrl = OllamaUrl.buildApiUrl(url, "/version")
            val version = restTemplate.getForObject(versionUrl, OllamaVersionResponse::class.java)
            if (version?.version?.isNotBlank() == true) {
                logger.info { "Successfully connected to Ollama server at $url (version=${version.version})" }
                connected = true
            }
        } catch (e: Exception) {
            logger.debug(e) { "Version endpoint failed, will try tags: ${e.message}" }
        }
        if (!connected) {
            try {
                val tagsUrl = OllamaUrl.buildApiUrl(url, "/tags")
                restTemplate.getForObject(tagsUrl, OllamaTagsResponse::class.java)
                logger.info { "Successfully connected to Ollama server at $url via /api/tags" }
                connected = true
            } catch (e: Exception) {
                logger.error(e) { "Failed to connect to Ollama server at $url: ${e.message}" }
                return false
            }
        }

        // If connected, verify required models for OLLAMA provider
        val required = mutableListOf<String>()
        try {
            if (settingService.embeddingModelType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                required += settingService.embeddingModelName
            }
            if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                required += settingService.modelSimpleName
            }
            if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                required += settingService.modelComplexName
            }
            if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.OLLAMA) {
                required += settingService.modelFinalizingName
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not resolve required Ollama models from settings: ${e.message}" }
        }

        if (required.isEmpty()) return true

        val available = getAvailableModels(url).map { it.name }.toSet()
        val missing = required.filter { it.isNotBlank() && !available.contains(it) }
        if (missing.isNotEmpty()) {
            logger.warn { "Ollama missing required models: ${missing.joinToString(", ")} (available=${available.size})" }
            return false
        }
        return true
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
            val tagsUrl = OllamaUrl.buildApiUrl(url, "/tags")
            val response = restTemplate.getForObject(tagsUrl, OllamaTagsResponse::class.java)
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
    /**
     * Run a short prompt against a specific model to verify it works.
     * Returns answer text or null if failed.
     */
    fun quickChat(url: String, model: String, prompt: String = "Hello"): String? {
        return try {
            val headers = org.springframework.http.HttpHeaders()
            headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
            val request = org.springframework.http.HttpEntity(
                OllamaRequest(
                    model = model,
                    prompt = prompt,
                    options = OllamaOptions(temperature = 0.0f, numPredict = 32)
                ),
                headers
            )
            val resp = restTemplate.postForObject(
                OllamaUrl.buildApiUrl(url, "/generate"),
                request,
                OllamaResponse::class.java
            )
            resp?.response
        } catch (e: Exception) {
            logger.warn(e) { "Ollama quickChat failed for model $model: ${e.message}" }
            null
        }
    }
}
