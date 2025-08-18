package com.jervis.service.indexer

import ai.djl.Device
import ai.djl.repository.zoo.Criteria
import com.jervis.service.indexer.provider.DjlEmbeddingProvider
import com.jervis.service.setting.SettingService
import com.jervis.util.EmbeddingUtils
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.Closeable

@Service
class MultiEmbeddingService(
    private val settingService: SettingService
) : Closeable {
    private val logger = KotlinLogging.logger {}
    private val embeddingProviders = mutableMapOf<String, DjlEmbeddingProvider>()
    private val embeddingConfigs = mutableMapOf<String, EmbeddingConfig>()
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    
    data class EmbeddingConfig(
        val id: String,
        val modelName: String,
        val djlUrl: String, // Fixed DJL URL
        val dimensions: Int,
        val targetCollection: String,
        val prefixes: PrefixConfig,
        val poolSize: Int = 4
    )
    
    data class PrefixConfig(
        val document: String,
        val query: String
    )
    
    enum class EmbeddingType {
        TEXT, CODE
    }
    
    @PostConstruct
    fun initialize() {
        // Disable DJL-based initialization; use EmbeddingService (LM Studio-only) instead.
        logger.info { "MultiEmbeddingService: DJL providers disabled; delegating to EmbeddingService for embeddings." }
    }
    
    private fun loadEmbeddingConfigurations() {
        // Text embeddings - fixed DJL URL for HuggingFace
        embeddingConfigs["e5_text_768"] = EmbeddingConfig(
            id = "e5_text_768",
            modelName = "intfloat/multilingual-e5-base",
            djlUrl = "djl://ai.djl.huggingface/sentence-transformers/intfloat/multilingual-e5-base",
            dimensions = 768,
            targetCollection = "semantic_text",
            prefixes = PrefixConfig(
                document = "passage: ",
                query = "query: "
            )
        )
        
        // Code embeddings
        embeddingConfigs["jina_code_768"] = EmbeddingConfig(
            id = "jina_code_768",
            modelName = "jinaai/jina-embeddings-v2-base-code",
            djlUrl = "djl://ai.djl.huggingface/sentence-transformers/jinaai/jina-embeddings-v2-base-code",
            dimensions = 768,
            targetCollection = "semantic_code",
            prefixes = PrefixConfig(
                document = "",
                query = ""
            )
        )
    }
    
    private fun initializeProviders() {
        embeddingConfigs.values.forEach { config ->
            try {
                val criteria = Criteria.builder()
                    .setTypes(List::class.java as Class<List<String>>, List::class.java as Class<List<FloatArray>>)
                    .optModelUrls(config.djlUrl) // Fixed URL
                    .optEngine("PyTorch")
                    .optDevice(Device.cpu())
                    .build() as Criteria<List<String>, List<FloatArray>>
                    
                val provider = DjlEmbeddingProvider(criteria, config.poolSize)
                embeddingProviders[config.id] = provider
                logger.info { "Initialized DJL provider: ${config.id}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize provider: ${config.id}" }
            }
        }
    }
    
    /**
     * Generate embeddings with cache and batch processing
     */
    suspend fun generateEmbeddingsBatch(
        contents: List<String>,
        embeddingType: EmbeddingType,
        forQuery: Boolean = false
    ): List<FloatArray> = coroutineScope {
        val configId = when (embeddingType) {
            EmbeddingType.TEXT -> "e5_text_768"
            EmbeddingType.CODE -> "jina_code_768"
        }
        
        val config = embeddingConfigs[configId] ?: throw IllegalStateException("Config not found")
        val provider = embeddingProviders[configId] ?: throw IllegalStateException("Provider not found")
        
        // Apply prefixes with guard
        val prefix = if (forQuery) config.prefixes.query else config.prefixes.document
        val prefixedContents = contents.map { EmbeddingUtils.applyPrefix(it, prefix) }
        
        // Check cache first
        val cachedResults = mutableMapOf<String, FloatArray>()
        val uncachedContents = mutableListOf<String>()
        val uncachedIndices = mutableListOf<Int>()
        
        prefixedContents.forEachIndexed { index, content ->
            val cacheKey = "${configId}:${content.hashCode()}"
            val cached = embeddingCache[cacheKey]
            if (cached != null) {
                cachedResults[index.toString()] = cached
            } else {
                uncachedContents.add(content)
                uncachedIndices.add(index)
            }
        }
        
        // Batch process uncached content
        val newEmbeddings = if (uncachedContents.isNotEmpty()) {
            uncachedContents.chunked(16).map { batch -> // Batch size of 16
                async {
                    provider.predictBatch(batch)
                }
            }.awaitAll().flatten()
        } else {
            emptyList()
        }
        
        // Cache new embeddings
        newEmbeddings.forEachIndexed { batchIndex, embedding ->
            val originalIndex = uncachedIndices[batchIndex]
            val content = prefixedContents[originalIndex]
            val cacheKey = "${configId}:${content.hashCode()}"
            embeddingCache[cacheKey] = embedding
        }
        
        // Combine results
        val finalResults = Array<FloatArray?>(contents.size) { null }
        
        cachedResults.forEach { (indexStr, embedding) ->
            finalResults[indexStr.toInt()] = embedding
        }
        
        uncachedIndices.forEachIndexed { batchIndex, originalIndex ->
            finalResults[originalIndex] = newEmbeddings[batchIndex]
        }
        
        return@coroutineScope finalResults.filterNotNull()
    }
    
    /**
     * Generate text embedding
     */
    suspend fun generateTextEmbedding(text: String, forQuery: Boolean = false): FloatArray {
        return generateEmbeddingsBatch(listOf(text), EmbeddingType.TEXT, forQuery).first()
    }
    
    /**
     * Generate code embedding
     */
    suspend fun generateCodeEmbedding(code: String, forQuery: Boolean = false): FloatArray {
        return generateEmbeddingsBatch(listOf(code), EmbeddingType.CODE, forQuery).first()
    }
    
    /**
     * Generate embeddings for multiple types (for fan-out search)
     */
    suspend fun generateMultiTypeEmbeddings(
        query: String,
        forQuery: Boolean = true
    ): Map<String, FloatArray> = coroutineScope {
        val textTask = async { generateTextEmbedding(query, forQuery) }
        val codeTask = async { generateCodeEmbedding(query, forQuery) }
        
        mapOf(
            "semantic_text" to textTask.await(),
            "semantic_code" to codeTask.await()
        )
    }
    
    /**
     * Reinitialize providers (for health check recovery)
     */
    fun reinitializeProviders() {
        logger.info { "Reinitializing embedding providers..." }
        close()
        embeddingProviders.clear()
        initializeProviders()
    }
    
    override fun close() {
        embeddingProviders.values.forEach { it.close() }
        embeddingCache.clear()
    }
}