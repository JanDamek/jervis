package com.jervis.module.llm

import com.jervis.module.llmcoordinator.LlmResponse
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant
// Import shared data classes
import com.jervis.module.llm.Message
import com.jervis.module.llm.ChatCompletionRequest
import com.jervis.module.llm.ChatCompletionResponse

/**
 * Implementation of LlmModelProvider that uses a model for simple, quick tasks.
 * This provider connects to LM Studio running with a smaller, quantized model.
 */
@Component
class SimpleLanguageModelProvider(
    @Value("\${llm.gpu.endpoint:http://localhost:1234/v1/chat/completions}") private val apiEndpoint: String,
    @Value("\${llm.gpu.model:phi-2}") private val modelName: String,
    @Value("\${llm.gpu.timeout:10000}") private val timeoutMs: Long
) : LlmModelProvider {
    private val logger = KotlinLogging.logger {}
    private val restTemplate = RestTemplate()
    private var lastHealthCheck: Instant = Instant.EPOCH
    private var isHealthy: Boolean = false

    /**
     * Process a query using the simple language model.
     *
     * @param query The user query
     * @param systemPrompt The system prompt to guide the model's behavior
     * @param options Additional options for processing
     * @return The language model response
     */
    override suspend fun processQuery(
        query: String,
        options: Map<String, Any>
    ): LlmResponse {
        // Extract system prompt from options or use default
        val systemPrompt = options["system_prompt"] as? String ?: ""
        logger.info { "Processing query with Simple model: ${query.take(50)}..." }
        val startTime = Instant.now()

        try {
            // Extract options with defaults
            val temperature = options["temperature"] as? Float ?: 0.7f
            val maxTokens = options["max_tokens"] as? Int ?: 1024

            // Create request headers
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON

            // Create messages
            val messages = mutableListOf<Message>()
            if (systemPrompt.isNotBlank()) {
                messages.add(Message(role = "system", content = systemPrompt))
            }
            messages.add(Message(role = "user", content = query))

            // Create request body
            val request = ChatCompletionRequest(
                model = modelName,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens
            )

            // Make API call
            val entity = HttpEntity(request, headers)
            val response = restTemplate.postForObject(apiEndpoint, entity, ChatCompletionResponse::class.java)
                ?: throw RuntimeException("Failed to get response from Simple model API")

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "Simple model response received in $duration ms" }

            // Convert to LlmResponse
            return LlmResponse(
                answer = response.choices.firstOrNull()?.message?.content ?: "No response from Simple model",
                model = response.model,
                promptTokens = response.usage.promptTokens,
                completionTokens = response.usage.completionTokens,
                totalTokens = response.usage.totalTokens,
                finishReason = response.choices.firstOrNull()?.finishReason ?: "unknown"
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with Simple model after $duration ms: ${e.message}" }
            throw e
        }
    }

    /**
     * Check if the provider is available by making a simple API call.
     *
     * @return True if the provider is available, false otherwise
     */
    override suspend fun isAvailable(): Boolean {
        // Only check health every 30 seconds to avoid too many calls
        val now = Instant.now()
        if (Duration.between(lastHealthCheck, now).seconds < 30 && lastHealthCheck != Instant.EPOCH) {
            return isHealthy
        }

        try {
            logger.debug { "Checking health of Simple model provider..." }

            // Create a simple request to check if the API is available
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON

            val messages = listOf(Message(role = "user", content = "Hello"))

            val request = ChatCompletionRequest(
                model = modelName,
                messages = messages,
                temperature = 0.0f,
                maxTokens = 5
            )

            val entity = HttpEntity(request, headers)
            restTemplate.postForObject(apiEndpoint, entity, ChatCompletionResponse::class.java)

            // If we get here, the API is available
            isHealthy = true
            lastHealthCheck = now
            logger.info { "Simple model provider is healthy" }
            return true
        } catch (e: RestClientException) {
            isHealthy = false
            lastHealthCheck = now
            logger.warn { "Simple model provider is not available: ${e.message}" }
            return false
        } catch (e: Exception) {
            isHealthy = false
            lastHealthCheck = now
            logger.error(e) { "Error checking health of Simple model provider: ${e.message}" }
            return false
        }
    }

    /**
     * Get the name of the provider.
     *
     * @return The name of the provider
     */
    override fun getName(): String = "Simple-LMStudio"

    /**
     * Get the model used by this provider.
     *
     * @return The model name
     */
    override fun getModel(): String = modelName

    /**
     * Get the type of the provider.
     *
     * @return The provider type (SIMPLE)
     */
    override fun getType(): ModelProviderType = ModelProviderType.SIMPLE
}
