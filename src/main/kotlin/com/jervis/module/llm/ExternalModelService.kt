package com.jervis.module.llm

import com.jervis.entity.LlmModelType
import com.jervis.module.llmcoordinator.LlmResponse
import com.jervis.service.SettingService
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.context.ApplicationEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.time.Instant

/**
 * Enum representing the type of language model provider.
 */
enum class ModelProviderType {
    /**
     * Provider for simple, quick tasks.
     */
    SIMPLE,

    /**
     * Provider for more complex tasks, especially for content containing programming code.
     */
    COMPLEX,

    /**
     * Provider for finalizing tasks, used for final refinement of content.
     */
    FINALIZATION,
}

/**
 * Service for managing external model calls.
 * This service provides methods for calling external models via different model types:
 * 1. SIMPLE - For simple, quick tasks
 * 2. COMPLEX - For more complex tasks, especially for content containing programming code
 * 3. FINALIZATION - For finalizing tasks, used for final refinement of content
 */
@Service
class ExternalModelService(
    private val settingService: SettingService,
) {
    private val logger = KotlinLogging.logger {}

    // Map of model providers by type
    private val modelProviders = mutableMapOf<ModelProviderType, LlmModelProvider>()

    @PostConstruct
    fun initialize() {
        logger.info { "Initializing External Model Service" }

        // Initialize model providers
        initializeModelProviders()
    }

    /**
     * Initialize the model providers based on settings
     */
    private fun initializeModelProviders() {
        try {
            // Initialize all three model types
            initializeSimpleModel()
            initializeComplexModel()
            initializeFinalizationModel()
        } catch (e: Exception) {
            logger.error(e) { "Error initializing external model providers: ${e.message}" }
        }
    }

    /**
     * Initialize the simple model provider
     */
    private fun initializeSimpleModel() {
        val (modelType, modelName) = settingService.getModelSimple()

        when (modelType) {
            LlmModelType.LM_STUDIO -> {
                if (settingService.isLmStudioEnabled()) {
                    val endpoint = settingService.getLmStudioUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.SIMPLE] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.SIMPLE,
                            )
                        logger.info { "Initialized LM Studio model provider for SIMPLE with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            LlmModelType.OLLAMA -> {
                if (settingService.isOllamaEnabled()) {
                    val endpoint = settingService.getOllamaUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.SIMPLE] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.SIMPLE,
                            )
                        logger.info { "Initialized Ollama model provider for SIMPLE with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            LlmModelType.OPENAI -> {
                // Implementation for OpenAI would go here
                logger.info { "OpenAI model provider for SIMPLE not implemented yet" }
            }

            LlmModelType.ANTHROPIC -> {
                // Implementation for Anthropic would go here
                logger.info { "Anthropic model provider for SIMPLE not implemented yet" }
            }
        }
    }

    /**
     * Initialize the complex model provider
     */
    private fun initializeComplexModel() {
        val (modelType, modelName) = settingService.getModelComplex()

        when (modelType) {
            LlmModelType.LM_STUDIO -> {
                if (settingService.isLmStudioEnabled()) {
                    val endpoint = settingService.getLmStudioUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.COMPLEX] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.COMPLEX,
                            )
                        logger.info { "Initialized LM Studio model provider for COMPLEX with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            LlmModelType.OLLAMA -> {
                if (settingService.isOllamaEnabled()) {
                    val endpoint = settingService.getOllamaUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.COMPLEX] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.COMPLEX,
                            )
                        logger.info { "Initialized Ollama model provider for COMPLEX with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            LlmModelType.OPENAI -> {
                // Implementation for OpenAI would go here
                logger.info { "OpenAI model provider for COMPLEX not implemented yet" }
            }

            LlmModelType.ANTHROPIC -> {
                // Implementation for Anthropic would go here
                logger.info { "Anthropic model provider for COMPLEX not implemented yet" }
            }
        }
    }

    /**
     * Initialize the finalization model provider
     */
    private fun initializeFinalizationModel() {
        val (modelType, modelName) = settingService.getModelFinalizing()

        when (modelType) {
            LlmModelType.LM_STUDIO -> {
                if (settingService.isLmStudioEnabled()) {
                    val endpoint = settingService.getLmStudioUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.FINALIZATION] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.FINALIZATION,
                            )
                        logger.info {
                            "Initialized LM Studio model provider for FINALIZATION with endpoint: $endpoint and model: $modelName"
                        }
                    }
                }
            }

            LlmModelType.OLLAMA -> {
                if (settingService.isOllamaEnabled()) {
                    val endpoint = settingService.getOllamaUrl()
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelProviderType.FINALIZATION] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                providerType = ModelProviderType.FINALIZATION,
                            )
                        logger.info { "Initialized Ollama model provider for FINALIZATION with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            LlmModelType.OPENAI -> {
                // Implementation for OpenAI would go here
                logger.info { "OpenAI model provider for FINALIZATION not implemented yet" }
            }

            LlmModelType.ANTHROPIC -> {
                // Implementation for Anthropic would go here
                logger.info { "Anthropic model provider for FINALIZATION not implemented yet" }
            }
        }
    }

    /**
     * Reset the model providers when settings change
     */
    @EventListener
    fun handleSettingsChangeEvent(event: SettingsChangeEvent) {
        logger.info { "Settings changed, reinitializing external model providers" }
        initializeModelProviders()
    }

    /**
     * Get a model provider by type
     *
     * @param type The type of model provider
     * @return The model provider, or null if not available
     */
    fun getModelProvider(type: ModelProviderType): LlmModelProvider? = modelProviders[type]

    /**
     * Get all available model providers
     *
     * @return List of available model providers
     */
    fun getAvailableProviders(): List<LlmModelProvider> = modelProviders.values.toList()
}

/**
 * Event class for settings changes
 */
class SettingsChangeEvent(
    source: Any,
) : ApplicationEvent(source)

/**
 * Implementation of LlmModelProvider for LM Studio models
 */
class LmStudioModelProvider(
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

    override fun getType(): ModelProviderType = providerType
}

/**
 * Implementation of LlmModelProvider for Ollama models
 */
class OllamaModelProvider(
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
                restTemplate.postForObject("$apiEndpoint/api/generate", entity, OllamaResponse::class.java)
                    ?: throw RuntimeException("Failed to get response from Ollama API")

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
            val response = restTemplate.getForObject("$apiEndpoint/api/tags", OllamaTagsResponse::class.java)

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

    override fun getType(): ModelProviderType = providerType
}

/**
 * Data classes for Ollama API
 */
data class OllamaRequest(
    val model: String,
    val prompt: String,
    val options: OllamaOptions,
)

data class OllamaOptions(
    val temperature: Float,
    val numPredict: Int,
)

data class OllamaResponse(
    val model: String,
    val response: String,
    val promptEvalCount: Int,
    val evalCount: Int,
)

data class OllamaTagsResponse(
    val models: List<OllamaModel>,
)

data class OllamaModel(
    val name: String,
    val size: Long,
    val modified: String,
)
