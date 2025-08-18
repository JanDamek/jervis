package com.jervis.service.llm

import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.events.SettingsChangeEvent
import com.jervis.service.llm.anthropic.AnthropicModelProvider
import com.jervis.service.llm.lmstudio.LmStudioModelProvider
import com.jervis.service.llm.ollama.OllamaModelProvider
import com.jervis.service.llm.openai.OpenAIModelProvider
import com.jervis.service.setting.SettingService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

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
    private val modelProviders = mutableMapOf<ModelType, LlmModelProvider>()

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
        val modelName = settingService.modelSimpleName
        when (val modelType = settingService.modelSimpleType) {
            ModelProvider.LM_STUDIO -> {
                if (settingService.lmStudioEnabled) {
                    val endpoint = settingService.lmStudioUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.SIMPLE] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.SIMPLE,
                            )
                        logger.info { "Initialized LM Studio model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OLLAMA -> {
                if (settingService.ollamaEnabled) {
                    val endpoint = settingService.ollamaUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.SIMPLE] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.SIMPLE,
                            )
                        logger.info { "Initialized Ollama model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OPENAI -> {
                val apiKey = settingService.openaiApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.openaiApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.SIMPLE] =
                            OpenAIModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.SIMPLE,
                            )
                        logger.info { "Initialized OpenAI model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.ANTHROPIC -> {
                val apiKey = settingService.anthropicApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.anthropicApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.SIMPLE] =
                            AnthropicModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.SIMPLE,
                            )
                        logger.info { "Initialized Anthropic model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            else -> error("No suitable model provider configured")
        }
    }

    /**
     * Initialize the complex model provider
     */
    private fun initializeComplexModel() {
        val modelType = settingService.modelComplexType
        val modelName = settingService.modelComplexName

        when (modelType) {
            ModelProvider.LM_STUDIO -> {
                if (settingService.lmStudioEnabled) {
                    val endpoint = settingService.lmStudioUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.COMPLEX] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.COMPLEX,
                            )
                        logger.info { "Initialized LM Studio model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OLLAMA -> {
                if (settingService.ollamaEnabled) {
                    val endpoint = settingService.ollamaUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.COMPLEX] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.COMPLEX,
                            )
                        logger.info { "Initialized Ollama model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OPENAI -> {
                val apiKey = settingService.openaiApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.openaiApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.COMPLEX] =
                            OpenAIModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.COMPLEX,
                            )
                        logger.info { "Initialized OpenAI model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.ANTHROPIC -> {
                val apiKey = settingService.anthropicApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.anthropicApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.COMPLEX] =
                            AnthropicModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.COMPLEX,
                            )
                        logger.info { "Initialized Anthropic model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            else -> error("No suitable model provider configured")
        }
    }

    /**
     * Initialize the finalization model provider
     */
    private fun initializeFinalizationModel() {
        val (modelType, modelName) = runBlocking { settingService.getModelFinalizing() }

        when (modelType) {
            ModelProvider.LM_STUDIO -> {
                if (settingService.lmStudioEnabled) {
                    val endpoint = settingService.lmStudioUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.FINALIZER] =
                            LmStudioModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.FINALIZER,
                            )
                        logger.info { "Initialized LM Studio model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OLLAMA -> {
                if (settingService.ollamaEnabled) {
                    val endpoint = settingService.ollamaUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.FINALIZER] =
                            OllamaModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.FINALIZER,
                            )
                        logger.info { "Initialized Ollama model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.OPENAI -> {
                val apiKey = settingService.openaiApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.openaiApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.FINALIZER] =
                            OpenAIModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.FINALIZER,
                            )
                        logger.info { "Initialized OpenAI model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            ModelProvider.ANTHROPIC -> {
                val apiKey = settingService.anthropicApiKey
                if (apiKey.isNotBlank() && apiKey != "none") {
                    val endpoint = settingService.anthropicApiUrl
                    if (endpoint.isNotBlank() && modelName.isNotBlank()) {
                        modelProviders[ModelType.FINALIZER] =
                            AnthropicModelProvider(
                                apiEndpoint = endpoint,
                                modelName = modelName,
                                settingService = settingService,
                                modelType = ModelType.FINALIZER,
                            )
                        logger.info { "Initialized Anthropic model provider with endpoint: $endpoint and model: $modelName" }
                    }
                }
            }

            else -> error("No suitable model provider configured")
        }
    }

    /**
     * Reset the model providers when settings change
     */
    @EventListener(SettingsChangeEvent::class)
    fun handleSettingsChangeEvent() {
        logger.info { "Settings changed, reinitializing external model providers" }
        initializeModelProviders()
    }

    /**
     * Get a model provider by type
     *
     * @param type The type of model provider
     * @return The model provider, or null if not available
     */
    fun getModelProvider(type: ModelType): LlmModelProvider? = modelProviders[type]
}
