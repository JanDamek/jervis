package com.jervis.o365gateway.service

import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Per-client rate limiter respecting Graph API limits.
 *
 * Teams: 5 req/s/user for list/get chats.
 * Default: 4 req/s (safety margin under 5).
 *
 * Uses a simple token-bucket approach with fixed interval.
 */
class GraphRateLimiter(
    private val maxPerSecond: Double = 4.0,
) {
    private data class ClientState(
        @Volatile var lastRequestTimeNanos: Long = 0L,
    )

    private val clients = ConcurrentHashMap<String, ClientState>()
    private val intervalNanos = (1_000_000_000.0 / maxPerSecond).toLong()

    suspend fun acquire(clientId: String) {
        val state = clients.computeIfAbsent(clientId) { ClientState() }
        val now = System.nanoTime()
        val elapsed = now - state.lastRequestTimeNanos

        if (elapsed < intervalNanos && state.lastRequestTimeNanos != 0L) {
            val waitMs = (intervalNanos - elapsed) / 1_000_000
            if (waitMs > 0) {
                logger.debug { "Rate limiting client $clientId: waiting ${waitMs}ms" }
                delay(waitMs)
            }
        }
        state.lastRequestTimeNanos = System.nanoTime()
    }
}
