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
class LMStudioService(
    private val settingService: com.jervis.service.setting.SettingService,
) {
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

            val response = restTemplate.exchange("$baseUrl/v1/models", HttpMethod.GET, entity, ModelsResponse::class.java)

            logger.info { "Successfully connected to LM Studio server at $url" }

            // Verify required models configured for LM_STUDIO provider
            val available = response.body?.data?.map { it.id }?.toSet() ?: emptySet()
            val required = mutableListOf<String>()
            try {
                if (settingService.embeddingModelType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                    required += settingService.embeddingModelName
                }
                if (settingService.modelSimpleType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                    required += settingService.modelSimpleName
                }
                if (settingService.modelComplexType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                    required += settingService.modelComplexName
                }
                if (settingService.modelFinalizingType == com.jervis.domain.model.ModelProvider.LM_STUDIO) {
                    required += settingService.modelFinalizingName
                }
            } catch (e: Exception) {
                logger.warn(e) { "Could not resolve required LM Studio models from settings: ${e.message}" }
            }

            if (required.isEmpty()) return true

            val missing = required.filter { it.isNotBlank() && !available.contains(it) }
            if (missing.isNotEmpty()) {
                logger.warn { "LM Studio missing required models: ${missing.joinToString(", ")} (available=${available.size})" }
                return false
            }

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
    /**
     * Run a short prompt against a specific LM Studio model to verify it works.
     * Returns answer text or null if failed.
     */
    fun quickChat(url: String, model: String, prompt: String = "Hello"): String? {
        return try {
            // Extract base URL (remove /chat/completions if present)
            val baseUrl = url.replace("/chat/completions", "")
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            val messages = listOf(com.jervis.domain.llm.Message(role = "user", content = prompt))
            val request = com.jervis.domain.llm.ChatCompletionRequest(
                model = model,
                messages = messages,
                temperature = 0.0f,
                maxTokens = 32
            )
            val entity = HttpEntity(request, headers)
            val response = restTemplate.postForObject(
                "$baseUrl/v1/chat/completions",
                entity,
                com.jervis.domain.llm.ChatCompletionResponse::class.java
            )
            response?.choices?.firstOrNull()?.message?.content
        } catch (e: Exception) {
            logger.warn(e) { "LM Studio quickChat failed for model $model: ${e.message}" }
            null
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
