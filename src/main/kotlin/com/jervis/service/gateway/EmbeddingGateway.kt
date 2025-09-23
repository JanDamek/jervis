package com.jervis.service.gateway

import com.jervis.configuration.ModelsProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.math.sqrt

@Service
class EmbeddingGateway(
    private val modelsProperties: ModelsProperties,
    private val clients: List<EmbeddingProviderClient>,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun callEmbedding(
        type: ModelType,
        text: String,
    ): List<Float> {
        val candidates = modelsProperties.models[type].orEmpty()
        check(candidates.isNotEmpty()) { "No embedding model candidates configured for $type" }

        val trimmed = text.trim()

        // Debug logging for embedding request details
        logger.debug { "Embedding Request - type=$type, text length=${trimmed.length}, text preview=$trimmed" }

        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            try {
                return doCallEmbedding(provider, candidate.model, trimmed)
            } catch (t: Throwable) {
                lastError = t
                logger.warn(t) { "Embedding candidate $index ($provider:${candidate.model}) failed, trying next if available" }
            }
        }
        throw IllegalStateException("All embedding candidates failed for $type", lastError)
    }

    private suspend fun doCallEmbedding(
        provider: ModelProvider,
        model: String,
        text: String,
    ): List<Float> {
        return executeProviderCall(provider, model, text)
    }

    private suspend fun executeProviderCall(
        provider: ModelProvider,
        model: String,
        text: String,
    ): List<Float> {
        val client = clients.first { it.provider == provider }
        val rawEmbedding = client.call(model, text)
        return normalizeL2(rawEmbedding)
    }

    /**
     * Applies L2 normalization to the embedding vector.
     * L2 normalization scales the vector so that its L2 norm (Euclidean length) equals 1.
     * This ensures consistent similarity calculations and improves RAG performance.
     */
    private fun normalizeL2(embedding: List<Float>): List<Float> {
        if (embedding.isEmpty()) {
            return embedding
        }
        
        // Calculate L2 norm (Euclidean length)
        val l2Norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        
        // Avoid division by zero
        if (l2Norm == 0.0f) {
            logger.warn { "L2_NORMALIZATION_WARNING: Zero vector encountered, returning original embedding" }
            return embedding
        }
        
        // Normalize each component by dividing by L2 norm
        val normalizedEmbedding = embedding.map { it / l2Norm }
        
        logger.debug { 
            "L2_NORMALIZATION_APPLIED: Original norm=$l2Norm, normalized norm=${
                sqrt(normalizedEmbedding.sumOf { (it * it).toDouble() }).toFloat()
            }, dimensions=${embedding.size}" 
        }
        
        return normalizedEmbedding
    }
}
