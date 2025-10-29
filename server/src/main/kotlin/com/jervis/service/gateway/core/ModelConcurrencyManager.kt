package com.jervis.service.gateway.core

import com.jervis.configuration.ModelsProperties
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages concurrency limits per model using Semaphores.
 * Each model can have its own concurrency limit defined in application.yml.
 * If no limit is specified, allows unlimited concurrent calls.
 *
 * This ensures back-pressure: if a model's concurrency limit is reached,
 * callers will suspend until a permit is available.
 */
@Component
class ModelConcurrencyManager {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /**
     * Executes the given block with concurrency control for the specified model.
     * If model has concurrency limit, waits for permit. Otherwise executes immediately.
     */
    suspend fun <T> withConcurrencyControl(
        candidate: ModelsProperties.ModelDetail,
        block: suspend () -> T,
    ): T {
        val concurrency = candidate.concurrency
        if (concurrency == null || concurrency <= 0) {
            // No limit specified, execute immediately
            return block()
        }

        val modelKey = "${candidate.provider}:${candidate.model}"
        val semaphore =
            semaphores.computeIfAbsent(modelKey) {
                logger.info { "Initializing semaphore for model $modelKey with concurrency=$concurrency" }
                Semaphore(concurrency)
            }

        return semaphore.withPermit {
            logger.debug { "Model permit acquired for $modelKey, available=${semaphore.availablePermits}/$concurrency" }
            try {
                block()
            } finally {
                logger.debug { "Model permit released for $modelKey, available=${semaphore.availablePermits}/$concurrency" }
            }
        }
    }
}
