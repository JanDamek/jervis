package com.jervis.module.indexer

import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import com.jervis.entity.EmbeddingModelType
import com.jervis.module.indexer.provider.EmbeddingProvider
import com.jervis.module.indexer.provider.EmbeddingProviderFactory
import com.jervis.module.llm.SettingsChangeEvent
import com.jervis.service.SettingService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class EmbeddingService(
    private val settingService: SettingService,
    private val embeddingProviderFactory: EmbeddingProviderFactory,
) {
    private val logger = KotlinLogging.logger {}
    private lateinit var modelName: String
    private var embeddingDimension = 768
    private var externalProvider: EmbeddingProvider? = null
    private var embeddingPredictor: Predictor<String, FloatArray>? = null
    private var embeddingModel: ZooModel<String, FloatArray>? = null

    fun getEmbeddingDimension(): Int = embeddingDimension

    fun getModelName(): String = modelName

    @PostConstruct
    fun initialize() {
        try {
            val (modelType, modelName) = settingService.getEmbeddingModel()
            this.modelName = modelName
            logger.info { "Initializing embedding model type: $modelType" }

            when (modelType) {
                EmbeddingModelType.OPENAI,
                EmbeddingModelType.LM_STUDIO,
                EmbeddingModelType.OLLAMA,
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

                EmbeddingModelType.INTERNAL -> {
                    logger.info { "Internal embedding selected, initializing DJL model" }
                    val criteria =
                        Criteria
                            .builder()
                            .setTypes(String::class.java, FloatArray::class.java)
                            .optModelUrls("djl://ai.djl.huggingface.pytorch/$modelName")
                            .optEngine("PyTorch")
                            .build()
                    embeddingModel = criteria.loadModel()
                    embeddingPredictor = embeddingModel!!.newPredictor()
                    embeddingDimension = embeddingPredictor!!.predict(modelName).size
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
    fun generateEmbedding(text: String): List<Float> {
        if (text.isBlank()) return List(embeddingDimension) { 0f }

        // Check if we're using external embedding
        val modelType = settingService.getEmbeddingModelTypeEnum()
        if (modelType != EmbeddingModelType.INTERNAL) {
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
    fun generateEmbedding(texts: List<String>): List<List<Float>> = texts.map { generateEmbedding(it) }

    /**
     * Generate embeddings for a list of texts (non-blocking version)
     *
     * @param texts The list of texts to generate embeddings for
     * @return A list of embeddings, one for each input text
     */
    suspend fun generateEmbeddingSuspend(texts: List<String>): List<List<Float>> =
        coroutineScope {
            texts.map { generateEmbeddingSuspend(it) }
        }

    /**
     * Generate embedding for a query
     *
     * @param query The query to generate embedding for
     * @return The embedding as a list of floats
     */
    fun generateQueryEmbedding(query: String): List<Float> {
        // Check if we're using external embedding
        val modelType = settingService.getEmbeddingModelTypeEnum()
        if (modelType != EmbeddingModelType.INTERNAL) {
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

    /**
     * Generate embedding for a query (non-blocking version)
     *
     * @param query The query to generate embedding for
     * @return The embedding as a list of floats
     */
    suspend fun generateQueryEmbeddingSuspend(query: String): List<Float> =
        coroutineScope {
            generateQueryEmbedding(query)
        }
}
