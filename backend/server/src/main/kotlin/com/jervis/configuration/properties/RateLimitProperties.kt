package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration for adaptive API rate limiting to prevent bans.
 *
 * Rate limits are applied per domain (e.g., atlassian.net) to respect API quotas.
 *
 * Adaptive strategy:
 * - Phase 1 (fast burst): First N items with minimal delay
 * - Phase 2 (normal): Next M items with moderate delay
 * - Phase 3 (sustained): Remaining items with conservative delay
 *
 * Example for Atlassian Cloud API:
 * - First 100 items: 0ms delay (fast initial sync)
 * - Items 101-500: 100ms delay (10 req/sec)
 * - Items 500+: 1000ms delay (1 req/sec, prevents rate limit)
 */
@Component
@ConfigurationProperties(prefix = "jervis.rate-limit")
data class RateLimitProperties(
    /**
     * Phase 1: Fast burst - number of items to process quickly at startup.
     * Default: 100 items
     */
    var phase1ItemCount: Int = 100,

    /**
     * Phase 1: Delay in milliseconds between items during fast burst.
     * Default: 0ms (no delay)
     */
    var phase1DelayMs: Long = 0,

    /**
     * Phase 2: Normal rate - number of items after phase 1 before switching to phase 3.
     * Default: 400 items (so phase 2 ends at item 500)
     */
    var phase2ItemCount: Int = 400,

    /**
     * Phase 2: Delay in milliseconds between items during normal rate.
     * Default: 100ms (10 requests/sec)
     */
    var phase2DelayMs: Long = 100,

    /**
     * Phase 3: Sustained rate - delay for all remaining items to prevent API bans.
     * Default: 1000ms (1 request/sec)
     */
    var phase3DelayMs: Long = 1000,
)
