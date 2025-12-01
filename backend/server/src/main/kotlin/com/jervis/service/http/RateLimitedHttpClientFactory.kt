package com.jervis.service.http

import com.jervis.service.ratelimit.DomainRateLimiterService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

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
     * @param requestTimeoutMs Timeout for individual requests (default 60s)
     * @param connectTimeoutMs Connection establishment timeout (default 30s)
     * @param socketTimeoutMs Socket read timeout (default 60s)
     */
    fun createClient(
        requestTimeoutMs: Long,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
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

            defaultRequest {
                contentType(ContentType.Application.Json)
            }

            engine {
                maxConnectionsCount = 1000
            }

            expectSuccess = false
        }
}
