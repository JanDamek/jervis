package com.jervis.service.gateway

import kotlinx.coroutines.sync.Semaphore
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Rate limiter for embedding API calls.
 * Limits concurrent embedding requests to prevent API overload.
 */
@Component
class EmbeddingRateLimiter {
    private val logger = KotlinLogging.logger {}
    private val semaphore = Semaphore(50) // Max 50 concurrent embedding requests

    /**
     * Executes the given block with rate limiting.
     * Waits indefinitely if limit is reached (no timeout).
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            logger.debug { "Embedding permit acquired, available=${semaphore.availablePermits}" }
            return block()
        } finally {
            semaphore.release()
            logger.debug { "Embedding permit released, available=${semaphore.availablePermits}" }
        }
    }
}
