package com.jervis.common.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Executes an RPC call with retry logic for connection-loss errors.
 * Non-network errors propagate immediately.
 */
suspend fun <T> withRpcRetry(
    name: String = "RPC",
    maxRetries: Int = Int.MAX_VALUE,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 30_000,
    reconnect: (suspend () -> Unit)? = null,
    block: suspend () -> T
): T {
    require(maxRetries > 0) { "maxRetries must be > 0" }

    var lastException: Exception? = null
    val cappedMaxDelay = maxDelayMs.coerceAtLeast(0)
    var currentDelay = initialDelayMs.coerceAtLeast(0)
    if (cappedMaxDelay > 0 && currentDelay > cappedMaxDelay) {
        currentDelay = cappedMaxDelay
    }
    var attempt = 0

    while (attempt < maxRetries) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastException = e
            val message = e.message ?: ""
            
            if (!isRetryableRpcError(message)) {
                throw e
            } else if (loweredMessageContainsTimeout(message)) {
                // Fail fast on timeouts as requested
                logger.error { "$name timed out, fail fast" }
                throw e
            } else {
                attempt += 1
                if (attempt < maxRetries) {
                    logger.warn { "$name attempt $attempt failed with '$message', retrying in ${currentDelay}ms..." }

                    if (reconnect != null) {
                        try {
                            logger.info { "Attempting to reconnect $name..." }
                            reconnect()
                        } catch (reconnectException: Exception) {
                            logger.error(reconnectException) { "Failed to reconnect $name" }
                        }
                    }

                    if (currentDelay > 0) {
                        delay(currentDelay)
                    }
                    currentDelay = nextDelay(currentDelay, cappedMaxDelay)
                } else {
                    logger.error { "$name failed after $maxRetries attempts: $message" }
                }
            }
        }
    }
    
    throw lastException ?: IllegalStateException("$name failed without exception")
}

private fun isRetryableRpcError(message: String): Boolean {
    if (message.isBlank()) return false
    val lowered = message.lowercase()
    return lowered.contains("rpcclient was cancelled") ||
        lowered.contains("channel was closed") ||
        lowered.contains("connection refused") ||
        lowered.contains("connection reset") ||
        lowered.contains("broken pipe") ||
        lowered.contains("failed to connect") ||
        loweredMessageContainsTimeout(lowered)
}

private fun loweredMessageContainsTimeout(message: String): Boolean {
    val lowered = message.lowercase()
    return lowered.contains("timed out") ||
        lowered.contains("timeout")
}

private fun nextDelay(currentDelay: Long, maxDelay: Long): Long {
    if (maxDelay <= 0 || currentDelay <= 0) return 0
    return if (currentDelay >= maxDelay / 2) maxDelay else currentDelay * 2
}
