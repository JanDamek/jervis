package com.jervis.service.gateway.core

import com.jervis.domain.model.ModelProviderEnum
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val modelLogger = KotlinLogging.logger {}

/**
 * Concurrency limiter per (provider, model) pair.
 *
 * When a model specifies its own `concurrency` (in models-config.yaml → ModelsProperties.ModelDetail.concurrency),
 * this manager enforces it using a semaphore. If no per-model limit is specified, calls pass through.
 *
 * Always acquire model-level semaphore first or consistently in the same order across the app
 * to avoid deadlocks if combined with other semaphores.
 */
@Component
class ModelConcurrencyManager {
    private data class Key(val provider: ModelProviderEnum, val model: String)

    private val semaphores = ConcurrentHashMap<Key, Semaphore>()

    suspend fun <T> withConcurrencyControl(
        provider: ModelProviderEnum,
        model: String,
        limit: Int?,
        block: suspend () -> T,
    ): T {
        val cap = limit ?: return block()
        if (cap <= 0) return block()

        val key = Key(provider, model)
        val semaphore = semaphores.computeIfAbsent(key) {
            modelLogger.info { "Initializing model semaphore for $provider/$model with limit=$cap" }
            Semaphore(cap)
        }

        if (semaphore.availablePermits == 0) {
            modelLogger.debug { "Model at capacity for $provider/$model (limit=$cap). Waiting for permit..." }
        }

        return semaphore.withPermit {
            modelLogger.trace { "Model permit acquired for $provider/$model, available=${semaphore.availablePermits}/$cap" }
            try {
                block()
            } finally {
                modelLogger.trace { "Model permit released for $provider/$model, available=${semaphore.availablePermits}/$cap" }
            }
        }
    }
}
