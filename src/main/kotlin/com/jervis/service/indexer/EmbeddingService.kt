package com.jervis.service.indexer

import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import com.jervis.domain.model.ModelProvider
import com.jervis.events.SettingsChangeEvent
import com.jervis.service.indexer.provider.EmbeddingProvider
import com.jervis.service.indexer.provider.EmbeddingProviderFactory
import com.jervis.service.setting.SettingService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class EmbeddingService(
    private val settingService: SettingService,
    private val embeddingProviderFactory: EmbeddingProviderFactory,
) {
    private val logger = KotlinLogging.logger {}
    lateinit var modelName: String
    var embeddingDimension = 768
    private var externalProvider: EmbeddingProvider? = null
    private var embeddingPredictor: Predictor<String, FloatArray>? = null
    private var embeddingModel: ZooModel<String, FloatArray>? = null

    // Available internal embedding models with their configurations
    private val availableEmbeddingModels = mapOf(
        "sentence-transformers/all-MiniLM-L6-v2" to EmbeddingModelConfig(
            url = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2",
            dimension = 384,
            description = "Sentence Transformers all-MiniLM-L6-v2 - 384 dimensions, multilingual, good performance/speed balance"
        ),
        "sentence-transformers/all-mpnet-base-v2" to EmbeddingModelConfig(
            url = "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-mpnet-base-v2", 
            dimension = 768,
            description = "Sentence Transformers all-mpnet-base-v2 - 768 dimensions, high quality embeddings"
        ),
        "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2" to EmbeddingModelConfig(
            url = "djl://ai.djl.huggingface.pytorch/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
            dimension = 384,
            description = "Paraphrase Multilingual MiniLM-L12-v2 - 384 dimensions, multilingual support"
        ),
        "intfloat/e5-small-v2" to EmbeddingModelConfig(
            url = "djl://ai.djl.huggingface.pytorch/intfloat/e5-small-v2",
            dimension = 384,
            description = "E5 Small v2 - 384 dimensions, efficient and high quality"
        ),
        "intfloat/e5-base-v2" to EmbeddingModelConfig(
            url = "djl://ai.djl.huggingface.pytorch/intfloat/e5-base-v2",
            dimension = 768,
            description = "E5 Base v2 - 768 dimensions, balanced performance"
        )
    )

    data class EmbeddingModelConfig(
        val url: String,
        val dimension: Int,
        val description: String
    )

    /**
     * Get available internal embedding models for configuration
     * @return Map of model names to their configurations
     */
    fun getAvailableEmbeddingModels(): Map<String, EmbeddingModelConfig> = availableEmbeddingModels

    @PostConstruct
    fun initialize() = runBlocking {
        try {
            val (modelType, embeddingModelName) = settingService.getEmbeddingModel()
            modelName = embeddingModelName
            logger.info { "Initializing embedding model type: $modelType" }

            when (modelType) {
                ModelProvider.OPENAI,
                ModelProvider.LM_STUDIO,
                ModelProvider.OLLAMA,
                ModelProvider.ANTHROPIC,
                -> {
                    logger.info { "External embedding selected, initializing external provider" }

                    try {
                        // Initialize external provider
                        externalProvider = embeddingProviderFactory.createProvider()
                        // Update embedding dimension based on the provider
                        embeddingDimension = externalProvider!!.getDimension()
                        logger.info { "External embedding provider initialized with dimension: $embeddingDimension" }
                    } catch (e: Exception) {
                        error("Error initializing external embedding provider: ${e.message}")
                    }
                }

                ModelProvider.DJL -> {
                    logger.info { "Internal embedding selected, initializing DJL model: $embeddingModelName" }
                    
                    // Get model configuration from available models
                    var modelConfig = availableEmbeddingModels[embeddingModelName]
                    var actualModelName = embeddingModelName
                    
                    if (modelConfig == null) {
                        val availableModels = availableEmbeddingModels.keys.joinToString(", ")
                        logger.warn { "Unsupported embedding model: $embeddingModelName. Available models: $availableModels" }
                        
                        // Fall back to the first available model
                        val fallbackModelName = availableEmbeddingModels.keys.first()
                        modelConfig = availableEmbeddingModels[fallbackModelName]!!
                        actualModelName = fallbackModelName
                        
                        logger.warn { "Falling back to default model: $fallbackModelName" }
                        
                        // Update the settings to reflect the fallback choice
                        try {
                            settingService.setEmbeddingModel(ModelProvider.DJL, fallbackModelName)
                            logger.info { "Updated embedding model setting to: $fallbackModelName" }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to update embedding model setting, but continuing with fallback model" }
                        }
                    }
                    
                    modelName = actualModelName
                    logger.info { "Using model: ${modelConfig.description}" }
                    
                    val criteria =
                        Criteria
                            .builder()
                            .setTypes(String::class.java, FloatArray::class.java)
                            .optModelUrls(modelConfig.url)
                            .optEngine("PyTorch")
                            .build()
                    embeddingModel = criteria.loadModel()
                    embeddingPredictor = embeddingModel!!.newPredictor()
                    embeddingDimension = modelConfig.dimension
                    
                    logger.info { "DJL embedding model initialized successfully with dimension: $embeddingDimension" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error initializing embedding model: ${e.message}" }
            throw RuntimeException("Failed to initialize embedding model", e)
        }
    }

    @EventListener(SettingsChangeEvent::class)
    fun handleSettingsChangeEvent() {
        logger.info { "Settings changed, reinitializing embedding service" }
        clear()
        initialize()
    }

    @PreDestroy
    fun clear() {
        // Close the external provider if needed
        externalProvider = null

        // Close the internal model if it's open
        embeddingPredictor?.close()
        embeddingPredictor = null
        embeddingModel?.close()
        embeddingModel = null
    }

    /**
     * Generate embedding for a single text string
     *
     * @param text The text to generate embedding for
     * @return The embedding as a list of floats
     */
    suspend fun generateEmbedding(text: String): List<Float> {
        if (text.isBlank()) return List(embeddingDimension) { 0f }

        // Check if we're using external embedding
        val modelType = settingService.embeddingModelType
        if (modelType != ModelProvider.DJL) {
            // If we have an external provider, use it
            return if (externalProvider != null) {
                logger.info { "Using external embedding provider for: ${text.take(50)}..." }
                externalProvider!!.predict(text)
            } else {
                error("External embedding selected but provider is not initialized, returning default embedding")
            }
        }

        // For internal embedding, process the text if needed
        val processedText = if (modelName.contains("e5", ignoreCase = true)) "passage: $text" else text

        // Embedding models handle tokenization internally
        // Use raw text for embedding - let the model handle tokenization internally
        return try {
            val embedding = embeddingPredictor?.predict(processedText)?.toList()
            embedding ?: List(embeddingDimension) { 0f }
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding" }
            List(embeddingDimension) { 0f }
        }
    }

    /**
     * Generate embedding for a single text string (non-blocking version)
     *
     * @param text The text to generate embedding for
     * @return The embedding as a list of floats
     */
    suspend fun generateEmbeddingSuspend(text: String): List<Float> =
        coroutineScope {
            generateEmbedding(text)
        }

    /**
     * Generate embeddings for a list of texts
     *
     * @param texts The list of texts to generate embeddings for
     * @return A list of embeddings, one for each input text
     */
    suspend fun generateEmbedding(texts: List<String>): List<List<Float>> = texts.map { generateEmbedding(it) }

    /**
     * Generate embedding for a query
     *
     * @param query The query to generate embedding for
     * @return The embedding as a list of floats
     */
    suspend fun generateQueryEmbedding(query: String): List<Float> {
        // Check if we're using external embedding
        val modelType = settingService.embeddingModelType
        if (modelType != ModelProvider.DJL) {
            // If we have an external provider, use it
            return if (externalProvider != null) {
                logger.info { "Using external embedding provider for query: ${query.take(50)}..." }
                externalProvider!!.predict(query)
            } else {
                logger.warn { "External embedding selected but provider is not initialized, returning default embedding" }
                List(embeddingDimension) { 0f }
            }
        }

        // For internal embedding, process the query if needed
        // For E5 models, prepend "query:" to differentiate from passages
        val processedQuery = if (modelName.contains("e5", ignoreCase = true)) "query: $query" else query

        return generateEmbedding(processedQuery)
    }
}
