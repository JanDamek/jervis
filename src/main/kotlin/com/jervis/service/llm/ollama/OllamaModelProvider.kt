package com.jervis.service.llm.ollama

import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelType
import com.jervis.service.llm.LlmModelProvider
import com.jervis.service.setting.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant

/**
 * Implementation of LlmModelProvider for Ollama models
 */
class OllamaModelProvider(
    private val apiEndpoint: String,
    private val modelName: String,
    private val settingService: SettingService,
    private val modelType: ModelType,
) : LlmModelProvider {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastHealthCheck: Instant = Instant.EPOCH
    private var isHealthy: Boolean = false

    override suspend fun processQuery(
        query: String,
        options: Map<String, Any>,
    ): LlmResponse {
        logger.info { "Processing query with Ollama model: ${query.take(50)}..." }
        val startTime = Instant.now()

        try {
            // Extract options with defaults
            val temperature = options["temperature"] as? Float ?: 0.7f
            val maxTokens = options["max_tokens"] as? Int ?: 2048

            // Create request headers
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON

            // Create Ollama request
            val ollamaRequest =
                OllamaRequest(
                    model = modelName,
                    prompt = query,
                    options =
                        OllamaOptions(
                            temperature = temperature,
                            numPredict = maxTokens,
                        ),
                )

            // Make API call to Ollama
            val entity = HttpEntity(ollamaRequest, headers)
            val response =
                restTemplate.postForObject(
                    OllamaUrl.buildApiUrl(apiEndpoint, "/generate"),
                    entity,
                    OllamaResponse::class.java,
                ) ?: throw RuntimeException("Failed to get response from Ollama API")

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "Ollama model response received in $duration ms" }

            // Convert to LlmResponse
            return LlmResponse(
                answer = response.response,
                model = modelName,
                promptTokens = response.promptEvalCount,
                completionTokens = response.evalCount,
                totalTokens = response.promptEvalCount + response.evalCount,
                finishReason = "stop", // Ollama doesn't provide finish reason
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with Ollama model after $duration ms: ${e.message}" }
            throw e
        }
    }

    override suspend fun isAvailable(): Boolean {
        // Only check health every 60 seconds to avoid too many calls
        val now = Instant.now()
        if (Duration.between(lastHealthCheck, now).seconds < 60 && lastHealthCheck != Instant.EPOCH) {
            return isHealthy
        }

        try {
            logger.debug { "Checking health of Ollama model provider..." }

            // Check if the Ollama server is available by listing models
            val response = restTemplate.getForObject(OllamaUrl.buildApiUrl(apiEndpoint, "/tags"), OllamaTagsResponse::class.java)

            // Check if our model is available
            val modelAvailable = response?.models?.any { it.name == modelName } ?: false

            isHealthy = modelAvailable
            lastHealthCheck = now

            if (modelAvailable) {
                logger.info { "Ollama model provider is healthy and model $modelName is available" }
            } else {
                logger.warn { "Ollama server is available but model $modelName is not found" }
            }

            return modelAvailable
        } catch (e: RestClientException) {
            isHealthy = false
            lastHealthCheck = now
            logger.warn { "Ollama model provider is not available: ${e.message}" }
            return false
        } catch (e: Exception) {
            isHealthy = false
            lastHealthCheck = now
            logger.error(e) { "Error checking health of Ollama model provider: ${e.message}" }
            return false
        }
    }

    override fun getName(): String = "Ollama-External"

    override fun getModel(): String = modelName

    override fun getType(): ModelType = modelType
}
