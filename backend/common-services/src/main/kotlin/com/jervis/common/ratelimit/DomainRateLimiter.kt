package com.jervis.common.ratelimit

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Global rate limiter that tracks and enforces rate limits per domain.
 *
 * Features:
 * - Rate limiting per domain (not per service or connection)
 * - Automatic TTL-based cleanup of expired domains
 * - Thread-safe concurrent access
 * - Configurable per-connection rate limits
 *
 * Usage:
 * - Integrated into HttpClient interceptor
 * - Automatically extracts domain from URL
 * - Applies rate limit before each request
 * - Waits if rate limit is exceeded
 */
class DomainRateLimiter(
    private val config: RateLimitConfig,
) {
    private val domainStates = ConcurrentHashMap<String, DomainRateLimitState>()
    private val mutex = Mutex()

    companion object {
        private const val ttlSeconds = 300L // 5 minutes
    }

    /**
     * Acquire permission to make a request to the given domain.
     * Blocks if rate limit is exceeded.
     *
     * @param domain The target domain (e.g., "api.example.com")
     */
    suspend fun acquire(domain: String) {
        val state = getOrCreateState(domain)

        mutex.withLock {
            state.acquire(config)
        }

        cleanupExpiredDomains()
    }

    /**
     * Get or create rate limit state for a domain.
     */
    private fun getOrCreateState(domain: String): DomainRateLimitState =
        domainStates.computeIfAbsent(domain) {
            DomainRateLimitState(domain)
        }

    /**
     * Remove expired domains from the map (TTL-based cleanup).
     * Runs periodically to prevent memory leaks.
     */
    private fun cleanupExpiredDomains() {
        val now = Instant.now()
        val expiredDomains =
            domainStates.entries
                .filter { (_, state) ->
                    val elapsed = now.epochSecond - state.lastAccessTime.epochSecond
                    elapsed > ttlSeconds
                }.map { it.key }

        if (expiredDomains.isNotEmpty()) {
            expiredDomains.forEach { domain ->
                domainStates.remove(domain)
            }
        }
    }

    /**
     * Get current stats for monitoring/debugging.
     */
    fun getStats(): Map<String, DomainStats> =
        domainStates.mapValues { (_, state) ->
            DomainStats(
                domain = state.domain,
                requestsInLastSecond = state.requestsInLastSecond.size,
                requestsInLastMinute = state.requestsInLastMinute.size,
                lastAccessTime = state.lastAccessTime,
            )
        }

    data class DomainStats(
        val domain: String,
        val requestsInLastSecond: Int,
        val requestsInLastMinute: Int,
        val lastAccessTime: Instant,
    )
}

/**
 * Rate limit state for a single domain.
 * Tracks request timestamps in sliding windows.
 */
private class DomainRateLimitState(
    val domain: String,
) {
    val requestsInLastSecond = mutableListOf<Instant>()
    val requestsInLastMinute = mutableListOf<Instant>()
    var lastAccessTime: Instant = Instant.now()

    /**
     * Acquire permission to make a request.
     * Blocks if rate limit is exceeded.
     */
    suspend fun acquire(config: RateLimitConfig) {
        lastAccessTime = Instant.now()

        // Clean up old requests outside the sliding windows
        cleanupOldRequests()

        // Check per-second limit
        while (requestsInLastSecond.size >= config.maxRequestsPerSecond) {
            val oldestRequest = requestsInLastSecond.first()
            val elapsed = Instant.now().toEpochMilli() - oldestRequest.toEpochMilli()
            val waitTime = 1000 - elapsed

            if (waitTime > 0) {
                delay(waitTime)
                cleanupOldRequests()
            } else {
                cleanupOldRequests()
            }
        }

        // Check per-minute limit
        while (requestsInLastMinute.size >= config.maxRequestsPerMinute) {
            val oldestRequest = requestsInLastMinute.first()
            val elapsed = Instant.now().toEpochMilli() - oldestRequest.toEpochMilli()
            val waitTime = 60000 - elapsed

            if (waitTime > 0) {
                delay(waitTime)
                cleanupOldRequests()
            } else {
                cleanupOldRequests()
            }
        }

        // Record this request
        val now = Instant.now()
        requestsInLastSecond.add(now)
        requestsInLastMinute.add(now)
    }

    /**
     * Remove requests that are outside the sliding windows.
     */
    private fun cleanupOldRequests() {
        val now = Instant.now()
        val oneSecondAgo = now.minusSeconds(1)
        val oneMinuteAgo = now.minusSeconds(60)

        requestsInLastSecond.removeAll { it.isBefore(oneSecondAgo) }
        requestsInLastMinute.removeAll { it.isBefore(oneMinuteAgo) }
    }
}
