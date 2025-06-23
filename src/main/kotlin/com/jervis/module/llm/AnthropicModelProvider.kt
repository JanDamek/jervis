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
import com.jervis.module.llm.AnthropicRequest
import com.jervis.module.llm.AnthropicResponse
import com.jervis.module.llm.AnthropicUsage
import com.jervis.module.llm.ContentBlock
import com.jervis.module.llm.Message

/**
 * Implementation of LlmModelProvider for Anthropic models
 */
class AnthropicModelProvider(
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
        logger.info { "Processing query with Anthropic model: ${query.take(50)}..." }
        val startTime = Instant.now()

        try {
            // Extract options with defaults
            val temperature = options["temperature"] as? Float ?: 0.7f
            val maxTokens = options["max_tokens"] as? Int ?: 2048
            val systemPrompt = options["system_prompt"] as? String ?: "You are a helpful assistant."

            // Get API key
            val apiKey = settingService.getAnthropicApiKey()
            if (apiKey.isBlank() || apiKey == "none") {
                throw IllegalStateException("Anthropic API key not configured. Please set it in the settings.")
            }

            // Get API version
            val apiVersion = settingService.getAnthropicApiVersionValue()

            // Create request headers
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.set("x-api-key", apiKey)
            headers.set("anthropic-version", apiVersion)

            // Create messages
            val messages = mutableListOf<Message>()
            messages.add(Message(role = "user", content = query))

            // Create request body
            val request = AnthropicRequest(
                model = modelName,
                system = systemPrompt,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
            )

            // Make API call
            val entity = HttpEntity(request, headers)
            val response = restTemplate.postForObject(apiEndpoint, entity, AnthropicResponse::class.java)
                ?: throw RuntimeException("Failed to get response from Anthropic API")

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "Anthropic model response received in $duration ms" }

            // Convert to LlmResponse
            return LlmResponse(
                answer = response.content.firstOrNull()?.text ?: "No response from Anthropic model",
                model = response.model,
                promptTokens = response.usage.inputTokens,
                completionTokens = response.usage.outputTokens,
                totalTokens = response.usage.inputTokens + response.usage.outputTokens,
                finishReason = "stop", // Anthropic doesn't provide finish reason
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with Anthropic model after $duration ms: ${e.message}" }
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
            logger.debug { "Checking health of Anthropic model provider..." }

            // Get API key
            val apiKey = settingService.getAnthropicApiKey()
            if (apiKey.isBlank() || apiKey == "none") {
                isHealthy = false
                lastHealthCheck = now
                logger.warn { "Anthropic API key not configured" }
                return false
            }

            // Get API version
            val apiVersion = settingService.getAnthropicApiVersionValue()

            // Create a simple request to check if the API is available
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.set("x-api-key", apiKey)
            headers.set("anthropic-version", apiVersion)

            val messages = listOf(Message(role = "user", content = "Hello"))

            val request = AnthropicRequest(
                model = modelName,
                system = "You are a helpful assistant.",
                messages = messages,
                maxTokens = 5,
                temperature = 0.0f,
            )

            val entity = HttpEntity(request, headers)
            restTemplate.postForObject(apiEndpoint, entity, AnthropicResponse::class.java)

            // If we get here, the API is available
            isHealthy = true
            lastHealthCheck = now
            logger.info { "Anthropic model provider is healthy" }
            return true
        } catch (e: RestClientException) {
            isHealthy = false
            lastHealthCheck = now
            logger.warn { "Anthropic model provider is not available: ${e.message}" }
            return false
        } catch (e: Exception) {
            isHealthy = false
            lastHealthCheck = now
            logger.error(e) { "Error checking health of Anthropic model provider: ${e.message}" }
            return false
        }
    }

    override fun getName(): String = "Anthropic"

    override fun getModel(): String = modelName

    override fun getType(): ModelProviderType = providerType
}
