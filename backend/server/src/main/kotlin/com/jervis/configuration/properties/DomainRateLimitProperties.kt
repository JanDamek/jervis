package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Configuration for domain-based rate limiting and HTTP client timeouts.
 *
 * Controls default rate limits applied per domain for all HTTP requests
 * and timeout settings for the HttpClient.
 */
@Component
@ConfigurationProperties(prefix = "jervis.http-client")
data class DomainRateLimitProperties(
    /**
     * Default maximum requests per second per domain.
     * Default: 10 req/sec
     */
    var maxRequestsPerSecond: Int = 10,

    /**
     * Default maximum requests per minute per domain.
     * Default: 100 req/min
     */
    var maxRequestsPerMinute: Int = 100,

    /**
     * Whether rate limiting is enabled by default.
     * Default: true
     */
    var rateLimitEnabled: Boolean = true,

    /**
     * TTL in seconds for unused domain rate limiters.
     * Limiters are cleaned up after this period of inactivity.
     * Default: 300 seconds (5 minutes)
     */
    var rateLimitTtlSeconds: Long = 300,

    /**
     * Request timeout in milliseconds.
     * Default: 30000ms (30 seconds)
     */
    var requestTimeoutMs: Long = 30000,

    /**
     * Connection timeout in milliseconds.
     * Default: 10000ms (10 seconds)
     */
    var connectTimeoutMs: Long = 10000,

    /**
     * Socket timeout in milliseconds.
     * Default: 30000ms (30 seconds)
     */
    var socketTimeoutMs: Long = 30000
)
