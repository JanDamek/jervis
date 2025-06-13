package com.jervis.module.indexer

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

/**
 * Service for generating embeddings for text.
 * This service provides methods for converting text into vector embeddings.
 * It supports different models for code and text, configurable via properties.
 */
@Service
class EmbeddingService(
    @Value("\${embedding.code.model:BAAI/bge-small-en-v1.5}") private val codeModelName: String,
    @Value("\${embedding.text.model:intfloat/multilingual-e5-large}") private val textModelName: String,
    @Value("\${embedding.code.dimension:384}") private val codeDimension: Int,
    @Value("\${embedding.text.dimension:1024}") private val textDimension: Int,
    @Value("\${embedding.cache.enabled:true}") private val cacheEnabled: Boolean
) {
    private val logger = KotlinLogging.logger {}

    private lateinit var codeModel: ZooModel<String, FloatArray>
    private lateinit var textModel: ZooModel<String, FloatArray>
    private lateinit var codePredictor: Predictor<String, FloatArray>
    private lateinit var textPredictor: Predictor<String, FloatArray>

    // Cache for embeddings to avoid recomputing the same text
    private val embeddingCache = ConcurrentHashMap<Pair<String, Boolean>, List<Float>>()

    @PostConstruct
    fun initialize() {
        try {
            logger.info { "Initializing embedding models..." }

            // Initialize code embedding model
            logger.info { "Loading code embedding model: $codeModelName" }
            codeModel = loadModel(codeModelName)
            codePredictor = codeModel.newPredictor()

            // Initialize text embedding model
            logger.info { "Loading text embedding model: $textModelName" }
            textModel = loadModel(textModelName)
            textPredictor = textModel.newPredictor()

            logger.info { "Embedding models initialized successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize embedding models: ${e.message}" }
            throw RuntimeException("Failed to initialize embedding models", e)
        }
    }

    @PreDestroy
    fun cleanup() {
        try {
            logger.info { "Closing embedding models..." }
            if (::codePredictor.isInitialized) codePredictor.close()
            if (::textPredictor.isInitialized) textPredictor.close()
            if (::codeModel.isInitialized) codeModel.close()
            if (::textModel.isInitialized) textModel.close()
            logger.info { "Embedding models closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing embedding models: ${e.message}" }
        }
    }

    /**
     * Load a Hugging Face model for embeddings
     * 
     * @param modelName The name of the model to load
     * @return The loaded model
     */
    private fun loadModel(modelName: String): ZooModel<String, FloatArray> {
        val criteria = Criteria.builder()
            .setTypes(String::class.java, FloatArray::class.java)
            .optModelUrls("djl://ai.djl.huggingface.pytorch/$modelName")
            .optEngine("PyTorch")
            .build()

        return criteria.loadModel()
    }

    /**
     * Generate an embedding for the given text
     * 
     * @param text The text to generate an embedding for
     * @param isCode Whether the text is code (true) or natural language (false)
     * @return The embedding vector
     */
    fun generateEmbedding(text: String, isCode: Boolean = false): List<Float> {
        if (text.isBlank()) {
            // Return zero vector for empty text
            return List(if (isCode) codeDimension else textDimension) { 0f }
        }

        // Check cache first if enabled
        val cacheKey = Pair(text, isCode)
        if (cacheEnabled && embeddingCache.containsKey(cacheKey)) {
            return embeddingCache[cacheKey]!!
        }

        try {
            val predictor = if (isCode) codePredictor else textPredictor
            val embedding = predictor.predict(text).toList()

            // Cache the result if caching is enabled
            if (cacheEnabled) {
                embeddingCache[cacheKey] = embedding
            }

            return embedding
        } catch (e: Exception) {
            logger.error(e) { "Error generating embedding: ${e.message}" }
            // Fallback to zero vector in case of error
            return List(if (isCode) codeDimension else textDimension) { 0f }
        }
    }

    /**
     * Generate an embedding for code
     * 
     * @param code The code to generate an embedding for
     * @return The embedding vector
     */
    fun generateCodeEmbedding(code: String): List<Float> {
        return generateEmbedding(code, true)
    }

    /**
     * Generate an embedding for text
     * 
     * @param text The text to generate an embedding for
     * @return The embedding vector
     */
    fun generateTextEmbedding(text: String): List<Float> {
        return generateEmbedding(text, false)
    }

    /**
     * Generate embeddings for multiple texts
     * 
     * @param texts The texts to generate embeddings for
     * @param isCode Whether the texts are code (true) or natural language (false)
     * @return A list of embedding vectors
     */
    fun generateEmbeddings(texts: List<String>, isCode: Boolean = false): List<List<Float>> {
        return texts.map { generateEmbedding(it, isCode) }
    }

    /**
     * Generate embeddings for multiple code snippets
     * 
     * @param codeSnippets The code snippets to generate embeddings for
     * @return A list of embedding vectors
     */
    fun generateCodeEmbeddings(codeSnippets: List<String>): List<List<Float>> {
        return generateEmbeddings(codeSnippets, true)
    }

    /**
     * Generate embeddings for multiple text snippets
     * 
     * @param textSnippets The text snippets to generate embeddings for
     * @return A list of embedding vectors
     */
    fun generateTextEmbeddings(textSnippets: List<String>): List<List<Float>> {
        return generateEmbeddings(textSnippets, false)
    }

    /**
     * Calculate the cosine similarity between two embeddings
     * 
     * @param embedding1 The first embedding
     * @param embedding2 The second embedding
     * @return The cosine similarity between the embeddings
     */
    fun cosineSimilarity(embedding1: List<Float>, embedding2: List<Float>): Float {
        if (embedding1.size != embedding2.size) {
            throw IllegalArgumentException("Embeddings must have the same dimension")
        }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        return dotProduct / (Math.sqrt(norm1.toDouble()) * Math.sqrt(norm2.toDouble())).toFloat()
    }
}
