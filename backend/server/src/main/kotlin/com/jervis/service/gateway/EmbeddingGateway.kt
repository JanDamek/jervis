package com.jervis.service.gateway

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.math.sqrt

@Service
class EmbeddingGateway(
    private val modelsProperties: ModelsProperties,
    private val clients: List<EmbeddingProviderClient>,
    private val rateLimiter: EmbeddingRateLimiter,
    private val modelConcurrencyManager: com.jervis.service.gateway.core.ModelConcurrencyManager,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun callEmbedding(
        type: ModelTypeEnum,
        text: String,
    ): List<Float> {
        val candidates = modelsProperties.models[type].orEmpty()
        check(candidates.isNotEmpty()) { "No embedding model candidates configured for $type" }

        // Universal text sanitization for all embedding providers
        val sanitizedText = sanitizeTextForEmbedding(text.trim())

        // Debug logging for embedding request details
        logger.debug { "Embedding Request - type=$type, text length=${sanitizedText.length}" }

        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val provider = candidate.provider ?: continue
            try {
                return executeWithControls(candidate, sanitizedText)
            } catch (t: Throwable) {
                lastError = t
                logger.warn(t) { "Embedding candidate $index ($provider:${candidate.model}) failed, trying next if available" }
            }
        }
        throw IllegalStateException("All embedding candidates failed for $type", lastError)
    }

    private suspend fun executeWithControls(
        candidate: ModelsProperties.ModelDetail,
        text: String,
    ): List<Float> =
        modelConcurrencyManager.withConcurrencyControl(candidate) {
            rateLimiter.execute {
                val provider =
                    candidate.provider
                        ?: throw IllegalStateException("Provider not specified for candidate ${candidate.model}")
                val client = clients.first { it.provider == provider }
                val rawEmbedding = client.call(candidate.model, text)
                normalizeL2(rawEmbedding)
            }
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

    /**
     * Universal text sanitization for all embedding providers
     * Fixes issues with \n characters in LM Studio, OpenAI, and Ollama
     */
    private fun sanitizeTextForEmbedding(text: String): String {
        return text
            .replace(Regex("\\s"), " ") // Replace all whitespace characters (including \n, \r, \t) with spaces
            .replace("\"", "'") // Replace quotes with apostrophes for JSON safety
            .replace(Regex("\\\\[nrt]"), " ") // Replace escaped characters \\n, \\r, \\t
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), " ") // Remove control chars
            .trim() // Remove leading/trailing spaces
            .take(8192) // Limit length to reasonable maximum
    }
}
