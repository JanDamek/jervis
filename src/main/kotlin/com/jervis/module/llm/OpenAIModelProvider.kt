package com.jervis.module.llm

import com.jervis.module.llmcoordinator.LlmResponse
import com.jervis.service.SettingService
import mu.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant
// Import shared data classes
import com.jervis.module.llm.OpenAIRequest
import com.jervis.module.llm.OpenAIMessage
import com.jervis.module.llm.OpenAIResponse
import com.jervis.module.llm.OpenAIChoice
import com.jervis.module.llm.OpenAIUsage

/**
 * Implementation of LlmModelProvider for OpenAI models
 */
class OpenAIModelProvider(
    private val apiEndpoint: String,
    private val modelName: String,
    private val settingService: SettingService,
    private val providerType: ModelProviderType,
) : LlmModelProvider {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastHealthCheck: Instant = Instant.EPOCH
    private var isHealthy: Boolean = false

    override suspend fun processQuery(
        query: String,
        options: Map<String, Any>,
    ): LlmResponse {
        logger.info { "Processing query with OpenAI model: ${query.take(50)}..." }
        val startTime = Instant.now()

        try {
            // Extract options with defaults
            val temperature = options["temperature"] as? Float ?: 0.7f
            val maxTokens = options["max_tokens"] as? Int ?: 2048
            val systemPrompt = options["system_prompt"] as? String ?: "You are a helpful assistant."

            // Get API key
            val apiKey = settingService.getOpenaiApiKey()
            if (apiKey.isBlank() || apiKey == "none") {
                throw IllegalStateException("OpenAI API key not configured. Please set it in the settings.")
            }

            // Create request headers
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.setBearerAuth(apiKey)

            // Create messages
            val messages = mutableListOf<OpenAIMessage>()
            messages.add(OpenAIMessage(role = "system", content = systemPrompt))
            messages.add(OpenAIMessage(role = "user", content = query))

            // Create request body
            val request = OpenAIRequest(
                model = modelName,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
            )

            // Make API call
            val entity = HttpEntity(request, headers)
            val response = restTemplate.postForObject(apiEndpoint, entity, OpenAIResponse::class.java)
                ?: throw RuntimeException("Failed to get response from OpenAI API")

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "OpenAI model response received in $duration ms" }

            // Convert to LlmResponse
            return LlmResponse(
                answer = response.choices.firstOrNull()?.message?.content ?: "No response from OpenAI model",
                model = response.model,
                promptTokens = response.usage.promptTokens,
                completionTokens = response.usage.completionTokens,
                totalTokens = response.usage.totalTokens,
                finishReason = response.choices.firstOrNull()?.finishReason ?: "unknown",
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with OpenAI model after $duration ms: ${e.message}" }
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
            logger.debug { "Checking health of OpenAI model provider..." }

            // Get API key
            val apiKey = settingService.getOpenaiApiKey()
            if (apiKey.isBlank() || apiKey == "none") {
                isHealthy = false
                lastHealthCheck = now
                logger.warn { "OpenAI API key not configured" }
                return false
            }

            // Create a simple request to check if the API is available
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.setBearerAuth(apiKey)

            val messages = listOf(
                OpenAIMessage(role = "system", content = "You are a helpful assistant."),
                OpenAIMessage(role = "user", content = "Hello")
            )

            val request = OpenAIRequest(
                model = modelName,
                messages = messages,
                maxTokens = 5,
                temperature = 0.0f,
            )

            val entity = HttpEntity(request, headers)
            restTemplate.postForObject(apiEndpoint, entity, OpenAIResponse::class.java)

            // If we get here, the API is available
            isHealthy = true
            lastHealthCheck = now
            logger.info { "OpenAI model provider is healthy" }
            return true
        } catch (e: RestClientException) {
            isHealthy = false
            lastHealthCheck = now
            logger.warn { "OpenAI model provider is not available: ${e.message}" }
            return false
        } catch (e: Exception) {
            isHealthy = false
            lastHealthCheck = now
            logger.error(e) { "Error checking health of OpenAI model provider: ${e.message}" }
            return false
        }
    }

    override fun getName(): String = "OpenAI"

    override fun getModel(): String = modelName

    override fun getType(): ModelProviderType = providerType
}
