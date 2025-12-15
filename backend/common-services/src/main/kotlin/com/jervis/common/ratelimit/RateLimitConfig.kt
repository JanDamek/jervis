package com.jervis.common.ratelimit

/**
 * Rate limit configuration.
 * Applied per domain/host (not per connection).
 *
 * Shared between:
 * - service-atlassian (Jira/Confluence API calls)
 * - server module (Link scraper, generic HTTP clients)
 */
data class RateLimitConfig(
    val maxRequestsPerSecond: Int,
    val maxRequestsPerMinute: Int,
)
