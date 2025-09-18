package com.jervis.service.gateway

import com.jervis.configuration.ConnectionPoolProperties
import com.jervis.configuration.ModelsProperties
import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import com.jervis.service.gateway.clients.EmbeddingProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.math.sqrt

@Service
class EmbeddingGateway(
    private val modelsProperties: ModelsProperties,
    private val connectionPoolProperties: ConnectionPoolProperties,
    private val clients: List<EmbeddingProviderClient>,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    // Per-provider semaphores for rate limiting
    private val providerSemaphores = ConcurrentHashMap<String, Semaphore>()

    init {
        if (connectionPoolProperties.isEmbeddingRateLimitingEnabled()) {
            initializeSemaphores()
        }
    }

    private fun initializeSemaphores() {
        // Initialize semaphores for each model type and provider combination
        modelsProperties.models.entries.forEach { (modelType, modelDetails) ->
            if (modelType.name.startsWith("EMBEDDING")) {
                modelDetails.forEach { modelDetail ->
                    modelDetail.provider?.let { provider ->
                        val providerKey = provider.name.lowercase()
                        if (!providerSemaphores.containsKey(providerKey)) {
                            val permits =
                                connectionPoolProperties.getEmbeddingMaxConcurrentRequests(
                                    providerKey,
                                    modelDetail.maxRequests,
                                )
                            providerSemaphores[providerKey] = Semaphore(permits)
                        }
                    }
                }
            }
        }
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
                val result = doCallEmbedding(provider, candidate.model, trimmed)
                return l2Normalize(result)
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
        val providerKey = provider.name.lowercase()
        val semaphore =
            if (connectionPoolProperties.isEmbeddingRateLimitingEnabled()) {
                providerSemaphores[providerKey]
            } else {
                null
            }

        return if (semaphore != null) {
            semaphore.acquire()
            try {
                executeProviderCall(provider, model, text)
            } finally {
                semaphore.release()
            }
        } else {
            executeProviderCall(provider, model, text)
        }
    }

    private suspend fun executeProviderCall(
        provider: ModelProvider,
        model: String,
        text: String,
    ): List<Float> {
        val client = clients.first { it.provider == provider }
        return client.call(model, text)
    }

    private fun l2Normalize(vec: List<Float>): List<Float> {
        var sum = 0.0
        for (v in vec) sum += (v * v)
        val norm = sqrt(sum).takeIf { it != 0.0 } ?: 1.0
        val inv = (1.0 / norm).toFloat()
        return vec.map { it * inv }
    }
}
