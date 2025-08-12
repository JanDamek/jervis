package com.jervis.service.llm.lmstudio

import com.jervis.domain.llm.ChatCompletionRequest
import com.jervis.domain.llm.ChatCompletionResponse
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.llm.Message
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
 * Implementation of LlmModelProvider for LM Studio models
 */
class LmStudioModelProvider(
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
        logger.info { "Processing query with LM Studio model: ${query.take(50)}..." }
        val startTime = Instant.now()

        try {
            // Extract options with defaults
            val temperature = options["temperature"] as? Float ?: 0.7f
            val maxTokens = options["max_tokens"] as? Int ?: 2048

            // Create request headers
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON

            // Create messages
            val messages = mutableListOf<Message>()
            messages.add(Message(role = "user", content = query))

            // Create request body
            val request =
                ChatCompletionRequest(
                    model = modelName,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )

            // Make API call
            val entity = HttpEntity(request, headers)
            val response =
                restTemplate.postForObject(apiEndpoint, entity, ChatCompletionResponse::class.java)
                    ?: throw RuntimeException("Failed to get response from LM Studio model API")

            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.info { "LM Studio model response received in $duration ms" }

            // Convert to LlmResponse
            return LlmResponse(
                answer =
                    response.choices
                        .firstOrNull()
                        ?.message
                        ?.content ?: "No response from LM Studio model",
                model = response.model,
                promptTokens = response.usage.promptTokens,
                completionTokens = response.usage.completionTokens,
                totalTokens = response.usage.totalTokens,
                finishReason = response.choices.firstOrNull()?.finishReason ?: "unknown",
            )
        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = Duration.between(startTime, endTime).toMillis()
            logger.error(e) { "Error processing query with LM Studio model after $duration ms: ${e.message}" }
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
            logger.debug { "Checking health of LM Studio model provider..." }

            // Create a simple request to check if the API is available
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON

            val messages = listOf(Message(role = "user", content = "Hello"))

            val request =
                ChatCompletionRequest(
                    model = modelName,
                    messages = messages,
                    temperature = 0.0f,
                    maxTokens = 5,
                )

            val entity = HttpEntity(request, headers)
            restTemplate.postForObject(apiEndpoint, entity, ChatCompletionResponse::class.java)

            // If we get here, the API is available
            isHealthy = true
            lastHealthCheck = now
            logger.info { "LM Studio model provider is healthy" }
            return true
        } catch (e: RestClientException) {
            isHealthy = false
            lastHealthCheck = now
            logger.warn { "LM Studio model provider is not available: ${e.message}" }
            return false
        } catch (e: Exception) {
            isHealthy = false
            lastHealthCheck = now
            logger.error(e) { "Error checking health of LM Studio model provider: ${e.message}" }
            return false
        }
    }

    override fun getName(): String = "LmStudio-External"

    override fun getModel(): String = modelName

    override fun getType(): ModelType = modelType
}
