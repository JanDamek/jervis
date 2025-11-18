package com.jervis.service.http

import com.jervis.service.ratelimit.DomainRateLimiterService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Centralized HTTP client factory with integrated domain-based rate limiting.
 *
 * Architecture:
 * - Single source of truth for all outbound HTTP calls from Jervis
 * - Automatic rate limiting per domain (e.g., *.atlassian.net, api.openai.com)
 * - Internal/private IP addresses exempt from rate limiting
 * - Ktor-based (coroutines-first) for consistency across codebase
 *
 * Rate Limiting Strategy:
 * - Adaptive 3-phase approach: fast burst → normal → sustained
 * - Shared bucket per domain (Confluence + Jira share same limit)
 * - Prevents API bans while maximizing throughput
 *
 * Usage:
 * ```kotlin
 * @Service
 * class MyApiClient(private val httpFactory: RateLimitedHttpClientFactory) {
 *     suspend fun fetchData(url: String) {
 *         val client = httpFactory.createClient()
 *         val response = client.get(url) // Rate limit applied automatically
 *         client.close()
 *     }
 * }
 * ```
 *
 * Internal addresses (exempt from rate limiting):
 * - 192.168.x.x, 10.x.x.x, 172.16-31.x.x
 * - localhost, 127.x.x.x, ::1
 */
@Service
class RateLimitedHttpClientFactory(
    private val rateLimiter: DomainRateLimiterService,
) {
    /**
     * Create HTTP client with automatic rate limiting based on request domain.
     *
     * @param maxInMemorySize Maximum buffer size for response bodies (default 8 MB)
     * @param requestTimeoutMs Timeout for individual requests (default 60s)
     * @param connectTimeoutMs Connection establishment timeout (default 30s)
     * @param socketTimeoutMs Socket read timeout (default 60s)
     */
    fun createClient(
        maxInMemorySize: Int = DEFAULT_MAX_IN_MEMORY_SIZE,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        socketTimeoutMs: Long = DEFAULT_SOCKET_TIMEOUT_MS,
    ): HttpClient =
        HttpClient(CIO) {
            // AUTOMATIC rate limiting plugin - intercepts ALL requests
            install(RateLimitingPlugin) {
                this.rateLimiter = this@RateLimitedHttpClientFactory.rateLimiter
            }

            // JSON serialization
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        isLenient = true
                    },
                )
            }

            // Timeouts
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = socketTimeoutMs
            }

            // Default headers
            defaultRequest {
                contentType(ContentType.Application.Json)
            }

            // Engine configuration
            engine {
                // Connection pool
                maxConnectionsCount = 1000
                // CIO engine doesn't support per-route configuration
                // Timeouts are handled by HttpTimeout plugin above
            }

            // Don't throw on non-2xx responses (let caller handle)
            expectSuccess = false
        }

    /**
     * DEPRECATED: Rate limiting is now automatic via RateLimitingPlugin.
     * This method is kept for backward compatibility but does nothing.
     *
     * @param url Full URL of the request
     */
    @Deprecated(
        "Rate limiting is now automatic via Ktor plugin. No need to call this manually.",
        ReplaceWith(""),
        DeprecationLevel.WARNING,
    )
    suspend fun acquirePermit(url: String) {
        // No-op: Rate limiting is handled by RateLimitingPlugin automatically
        // Kept for backward compatibility during migration
    }

    companion object {
        // Default buffer size for response bodies (8 MB)
        // Callers can override for specific use cases (e.g., large file downloads)
        private const val DEFAULT_MAX_IN_MEMORY_SIZE = 8 * 1024 * 1024

        // Default timeouts (conservative for reliability)
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L // 60 seconds
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000L // 30 seconds
        private const val DEFAULT_SOCKET_TIMEOUT_MS = 60_000L // 60 seconds
    }
}
