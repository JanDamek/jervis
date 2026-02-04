package com.jervis.service.ratelimit

import com.jervis.configuration.properties.RateLimitProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Domain-based API rate limiter using Bucket4j token bucket algorithm.
 *
 * Architecture:
 * - One token bucket per domain (e.g., "tepsivo.atlassian.net", "api.openai.com")
 * - Token bucket: max capacity with refill rate (e.g., 100 tokens, refill 10/sec)
 * - Adaptive rate limiting: fast burst → normal → sustained based on processed item count
 * - Thread-safe for concurrent API calls (uses ConcurrentHashMap)
 * - Non-blocking suspend functions (uses delay, not Thread.sleep)
 *
 * Token Bucket vs Fixed Window:
 * - Token bucket allows bursts up to capacity, then throttles to refill rate
 * - Better for APIs with burst tolerance (e.g., Atlassian allows initial burst)
 *
 * Phases (configurable via application.yml):
 * 1. Fast burst: First 100 items → 100 req/sec capacity
 * 2. Normal: Items 101-500 → 10 req/sec
 * 3. Sustained: Items 500+ → 1 req/sec (prevents API bans)
 *
 * Usage:
 * ```kotlin
 * rateLimiter.acquirePermit("https://tepsivo.atlassian.net/wiki/api/v2/pages")
 * // Make API call (rate limited automatically)
 * ```
 */
@Service
class DomainRateLimiterService(
    private val props: RateLimitProperties,
) {
    private val domainBuckets = ConcurrentHashMap<String, Bucket>()
    private val domainItemCounts = ConcurrentHashMap<String, Int>()

    /**
     * Acquire permit before making API call to the given URL.
     * Blocks until permit is available according to current rate limit phase.
     *
     * Internal servers (192.168.x.x, 10.x.x.x, 172.16-31.x.x, localhost) are exempt from rate limiting.
     *
     * @param url Full URL of the API endpoint
     */
    suspend fun acquirePermit(url: String) {
        val domain = extractDomain(url)
        if (domain == null) {
            logger.warn { "Could not extract domain from URL: $url" }
            return
        }

        // Exempt internal/private IP addresses and localhost from rate limiting
        if (isInternalAddress(domain)) {
            logger.debug { "Skipping rate limit for internal address: $domain" }
            return
        }

        // Increment item count for this domain
        val itemCount = domainItemCounts.compute(domain) { _, count -> (count ?: 0) + 1 }!!

        // Determine current phase and apply delay
        val delayMs = calculateDelay(itemCount)

        // Log phase transitions
        logPhaseTransition(domain, itemCount)

        if (delayMs > 0) {
            delay(delayMs)
        }

        // Also use bucket for additional smoothing (prevents bursts within phase)
        val bucket = domainBuckets.computeIfAbsent(domain) { createBucket(itemCount) }

        // Try to consume a token (non-blocking check)
        if (!bucket.tryConsume(1)) {
            // If bucket is empty, wait for refill
            val probe = bucket.tryConsumeAndReturnRemaining(1)
            if (!probe.isConsumed) {
                val waitMs = probe.nanosToWaitForRefill / 1_000_000
                if (waitMs > 0) {
                    logger.debug { "Rate limit for domain $domain: waiting ${waitMs}ms for refill" }
                    delay(waitMs)
                    bucket.tryConsume(1)
                }
            }
        }
    }

    /**
     * Calculate delay based on current item count and phase.
     */
    private fun calculateDelay(itemCount: Int): Long =
        when {
            itemCount <= props.phase1ItemCount -> props.phase1DelayMs
            itemCount <= props.phase1ItemCount + props.phase2ItemCount -> props.phase2DelayMs
            else -> props.phase3DelayMs
        }

    /**
     * Log when transitioning between rate limit phases.
     */
    private fun logPhaseTransition(
        domain: String,
        itemCount: Int,
    ) {
        when (itemCount) {
            props.phase1ItemCount + 1 -> {
                logger.info {
                    "[$domain] Phase 1→2: Processed ${props.phase1ItemCount} items, " +
                        "switching to ${1000 / props.phase2DelayMs} req/sec"
                }
            }
            props.phase1ItemCount + props.phase2ItemCount + 1 -> {
                logger.info {
                    "[$domain] Phase 2→3: Processed ${props.phase1ItemCount + props.phase2ItemCount} items, " +
                        "switching to ${1000 / props.phase3DelayMs} req/sec to prevent API bans"
                }
            }
        }
    }

    /**
     * Create a token bucket for the current phase.
     * Bucket provides additional smoothing on top of delay-based rate limiting.
     */
    private fun createBucket(itemCount: Int): Bucket {
        val capacity: Long
        val refillTokens: Long
        val refillPeriod: Duration

        when {
            itemCount <= props.phase1ItemCount -> {
                // Phase 1: Fast burst - 100 req/sec capacity
                capacity = 100
                refillTokens = 100
                refillPeriod = Duration.ofSeconds(1)
            }
            itemCount <= props.phase1ItemCount + props.phase2ItemCount -> {
                // Phase 2: Normal - 10 req/sec
                capacity = 10
                refillTokens = 10
                refillPeriod = Duration.ofSeconds(1)
            }
            else -> {
                // Phase 3: Sustained - 1 req/sec
                capacity = 1
                refillTokens = 1
                refillPeriod = Duration.ofSeconds(1)
            }
        }

        val bandwidth =
            Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(refillTokens, refillPeriod)
                .build()

        return Bucket.builder()
            .addLimit(bandwidth)
            .build()
    }

    /**
     * Extract domain from URL for rate limiting key.
     * Example: "https://tepsivo.atlassian.net/wiki/api/v2/pages" → "tepsivo.atlassian.net"
     */
    private fun extractDomain(url: String): String? =
        runCatching {
            URI(url).host
        }.getOrNull()

    /**
     * Check if domain/host is an internal/private address that should be exempt from rate limiting.
     *
     * Exempts:
     * - Private IPv4: 192.168.x.x, 10.x.x.x, 172.16-31.x.x
     * - Localhost: localhost, 127.x.x.x, ::1
     * - Internal server: 192.168.100.117 (explicitly mentioned)
     *
     * @param domain Host/domain extracted from URL
     * @return true if internal address, false otherwise
     */
    private fun isInternalAddress(domain: String): Boolean {
        // Localhost patterns
        if (domain == "localhost" || domain == "::1" || domain.startsWith("127.")) {
            return true
        }

        // K8s internal services
        if (domain.startsWith("jervis-")) {
            return true
        }

        // Private IPv4 ranges (RFC 1918)
        if (domain.startsWith("192.168.") || domain.startsWith("10.")) {
            return true
        }

        // 172.16.0.0 - 172.31.255.255
        if (domain.startsWith("172.")) {
            val parts = domain.split(".")
            if (parts.size >= 2) {
                val secondOctet = parts[1].toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Reset rate limit state for a domain (useful for testing or admin tools).
     */
    fun resetDomain(domain: String) {
        domainBuckets.remove(domain)
        domainItemCounts.remove(domain)
        logger.info { "Reset rate limit state for domain: $domain" }
    }

    /**
     * Get current item count for a domain (for monitoring/debugging).
     */
    fun getDomainItemCount(domain: String): Int = domainItemCounts[domain] ?: 0
}
