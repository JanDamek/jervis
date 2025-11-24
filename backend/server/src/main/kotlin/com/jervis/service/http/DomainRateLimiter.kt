package com.jervis.service.http

import com.jervis.entity.connection.RateLimitConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

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
    private val defaultConfig: RateLimitConfig = RateLimitConfig(),
    private val ttlSeconds: Long = 300 // 5 minutes TTL for unused domains
) {
    private val domainStates = ConcurrentHashMap<String, DomainRateLimitState>()
    private val mutex = Mutex()

    /**
     * Acquire permission to make a request to the given domain.
     * Blocks if rate limit is exceeded.
     *
     * @param domain The target domain (e.g., "api.example.com")
     * @param config Rate limit configuration (uses default if null)
     */
    suspend fun acquire(domain: String, config: RateLimitConfig? = null) {
        val effectiveConfig = config ?: defaultConfig

        if (!effectiveConfig.enabled) {
            return // Rate limiting disabled
        }

        val state = getOrCreateState(domain, effectiveConfig)

        mutex.withLock {
            state.acquire(effectiveConfig)
        }

        // Cleanup expired domains periodically
        cleanupExpiredDomains()
    }

    /**
     * Get or create rate limit state for a domain.
     */
    private fun getOrCreateState(domain: String, config: RateLimitConfig): DomainRateLimitState {
        return domainStates.computeIfAbsent(domain) {
            logger.debug { "Creating rate limit state for domain: $domain" }
            DomainRateLimitState(domain, config)
        }
    }

    /**
     * Remove expired domains from the map (TTL-based cleanup).
     * Runs periodically to prevent memory leaks.
     */
    private fun cleanupExpiredDomains() {
        val now = Instant.now()
        val expiredDomains = domainStates.entries
            .filter { (_, state) ->
                val elapsed = now.epochSecond - state.lastAccessTime.epochSecond
                elapsed > ttlSeconds
            }
            .map { it.key }

        if (expiredDomains.isNotEmpty()) {
            logger.debug { "Cleaning up ${expiredDomains.size} expired domains from rate limiter" }
            expiredDomains.forEach { domain ->
                domainStates.remove(domain)
            }
        }
    }

    /**
     * Get current stats for monitoring/debugging.
     */
    fun getStats(): Map<String, DomainStats> {
        return domainStates.mapValues { (_, state) ->
            DomainStats(
                domain = state.domain,
                requestsInLastSecond = state.requestsInLastSecond.size,
                requestsInLastMinute = state.requestsInLastMinute.size,
                lastAccessTime = state.lastAccessTime
            )
        }
    }

    data class DomainStats(
        val domain: String,
        val requestsInLastSecond: Int,
        val requestsInLastMinute: Int,
        val lastAccessTime: Instant
    )
}

/**
 * Rate limit state for a single domain.
 * Tracks request timestamps in sliding windows.
 */
private class DomainRateLimitState(
    val domain: String,
    initialConfig: RateLimitConfig
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
                logger.debug { "Rate limit exceeded for $domain (per second), waiting ${waitTime}ms" }
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
                logger.debug { "Rate limit exceeded for $domain (per minute), waiting ${waitTime}ms" }
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
