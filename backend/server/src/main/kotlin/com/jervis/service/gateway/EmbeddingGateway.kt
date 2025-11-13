package com.jervis.service.gateway

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service

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

                // Fail fast on invalid embeddings
                require(rawEmbedding.isNotEmpty()) {
                    "Embedding provider $provider returned empty embedding for model ${candidate.model}"
                }
                require(rawEmbedding.all { it.isFinite() }) {
                    "Non-finite values detected in embedding for model ${candidate.model}"
                }
                candidate.dimension?.let { expected ->
                    require(rawEmbedding.size == expected) {
                        "Embedding dimension mismatch for model ${candidate.model}: expected $expected, got ${rawEmbedding.size}"
                    }
                }

                // Return raw embedding without additional normalization
                // Modern embedding models (BGE-M3, nomic-embed) return pre-normalized embeddings
                // Double normalization would distort the vectors
                rawEmbedding
            }
        }

    /**
     * Minimal text sanitization for embedding providers
     * Preserves structure (newlines) for code embeddings while ensuring JSON compatibility
     */
    private fun sanitizeTextForEmbedding(text: String): String {
        return text
            .replace("\"", "'") // Replace quotes with apostrophes for JSON safety
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), " ") // Remove control chars only
            .replace(Regex("\\s+"), " ") // Normalize multiple spaces (but preserve single newlines)
            .trim() // Remove leading/trailing spaces
    }
}
