package com.jervis.configuration

import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.connection.RateLimitConfig
import com.jervis.service.http.DomainRateLimiter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URL

private val logger = KotlinLogging.logger {}

/**
 * Configuration for global Ktor HttpClient with rate limiting.
 *
 * Features:
 * - Single global HttpClient bean for all external HTTP calls
 * - Automatic rate limiting per domain
 * - Automatic authorization header injection from BaseConnection
 * - Content negotiation (JSON)
 * - Timeout configuration
 *
 * Usage:
 * - For external APIs (Atlassian, link scraper, etc.)
 * - NOT for internal services (@HttpExchange - use Spring WebClient)
 */
@Configuration
class HttpClientConfiguration {

    /**
     * Global rate limiter instance.
     * Shared across all HTTP requests to enforce per-domain rate limits.
     */
    @Bean
    fun domainRateLimiter(): DomainRateLimiter {
        return DomainRateLimiter(
            defaultConfig = RateLimitConfig(
                maxRequestsPerSecond = 10,
                maxRequestsPerMinute = 100,
                enabled = true
            ),
            ttlSeconds = 300 // 5 minutes TTL for unused domains
        )
    }

    /**
     * Global Ktor HttpClient bean.
     *
     * Pre-configured with:
     * - CIO engine (coroutine-based)
     * - Rate limiting per domain
     * - Automatic auth header injection
     * - JSON content negotiation
     * - Request/response logging (debug level)
     */
    @Bean
    fun httpClient(domainRateLimiter: DomainRateLimiter): HttpClient {
        return HttpClient(CIO) {
            // JSON serialization
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = false
                    isLenient = true
                })
            }

            // Default timeout (can be overridden per request)
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 30000
            }

            // Rate limiting interceptor
            install(createRateLimitPlugin(domainRateLimiter))

            // Authorization header injection
            install(createAuthPlugin())

            // Request logging (debug level)
            install(createLoggingPlugin())

            // Default headers
            defaultRequest {
                header(HttpHeaders.Accept, ContentType.Application.Json)
                header(HttpHeaders.UserAgent, "Jervis/1.0")
            }
        }
    }

    /**
     * Rate limiting plugin - applies rate limits before each request.
     */
    private fun createRateLimitPlugin(rateLimiter: DomainRateLimiter) = createClientPlugin("RateLimitPlugin") {
        onRequest { request, _ ->
            val url = request.url.toString()
            val domain = extractDomain(url)

            // Get rate limit config from Connection if available
            val connection = request.attributes.getOrNull(ConnectionKey)
            val config = connection?.rateLimitConfig

            logger.debug { "Rate limiting request to domain: $domain" }
            runBlocking {
                rateLimiter.acquire(domain, config)
            }
        }
    }

    /**
     * Authorization plugin - injects auth headers from Connection.
     * Credentials must be provided via ConnectionCredentialsKey attribute.
     */
    private fun createAuthPlugin() = createClientPlugin("AuthPlugin") {
        onRequest { request, _ ->
            val connection = request.attributes.getOrNull(ConnectionKey)
            val credentials = request.attributes.getOrNull(ConnectionCredentialsKey)

            if (connection is Connection.HttpConnection && credentials != null) {
                when (credentials) {
                    is HttpCredentials.Basic -> {
                        request.header(HttpHeaders.Authorization, credentials.toAuthHeader())
                    }
                    is HttpCredentials.Bearer -> {
                        request.header(HttpHeaders.Authorization, credentials.toAuthHeader())
                    }
                    is HttpCredentials.ApiKey -> {
                        request.header(credentials.headerName, credentials.apiKey)
                    }
                }
                logger.debug { "Added ${connection.authType} authentication for ${connection.name}" }
            }
        }
    }

    /**
     * Logging plugin - logs requests and responses at debug level.
     */
    private fun createLoggingPlugin() = createClientPlugin("LoggingPlugin") {
        onRequest { request, _ ->
            logger.debug { "HTTP ${request.method.value} ${request.url}" }
        }
    }

    /**
     * Extract domain from URL for rate limiting.
     */
    private fun extractDomain(url: String): String {
        return try {
            URL(url).host
        } catch (e: Exception) {
            logger.warn { "Failed to extract domain from URL: $url" }
            url
        }
    }
}

/**
 * Attribute key for passing Connection through request attributes.
 */
val ConnectionKey = AttributeKey<Connection>("Connection")

/**
 * Attribute key for passing decrypted HttpCredentials through request attributes.
 */
val ConnectionCredentialsKey = AttributeKey<HttpCredentials>("ConnectionCredentials")
