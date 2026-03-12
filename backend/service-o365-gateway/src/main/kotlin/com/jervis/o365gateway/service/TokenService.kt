package com.jervis.o365gateway.service

import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Token lifecycle: get from cache, fall back to browser pool.
 *
 * Cache layers:
 *  1. In-memory ConcurrentHashMap (TTL 5 min)
 *  2. Browser pool REST API
 */
class TokenService(
    private val browserPoolClient: BrowserPoolClient,
    private val cacheTtlSeconds: Long = 300, // 5 minutes
) {
    private data class CachedToken(
        val token: String,
        val cachedAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CachedToken>()

    suspend fun getToken(clientId: String): String? {
        // 1. Check in-memory cache
        cache[clientId]?.let { cached ->
            if (Instant.now().epochSecond - cached.cachedAt.epochSecond < cacheTtlSeconds) {
                return cached.token
            }
            cache.remove(clientId)
        }

        // 2. Fetch from browser pool
        val tokenResponse = browserPoolClient.getToken(clientId)
        if (tokenResponse != null) {
            cache[clientId] = CachedToken(
                token = tokenResponse.token,
                cachedAt = Instant.now(),
            )
            logger.debug { "Cached token for client $clientId (age=${tokenResponse.ageSeconds}s)" }
            return tokenResponse.token
        }

        logger.warn { "No valid token available for client $clientId" }
        return null
    }

    fun invalidateCache(clientId: String) {
        cache.remove(clientId)
    }
}
