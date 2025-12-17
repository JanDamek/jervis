package com.jervis.configuration

import com.jervis.configuration.properties.DomainRateLimitProperties
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.service.http.RateLimitingPlugin
import com.jervis.service.ratelimit.DomainRateLimiterService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
    fun httpClient(
        rateLimiterService: DomainRateLimiterService,
        properties: DomainRateLimitProperties,
    ): HttpClient =
        HttpClient(CIO) {
            // JSON serialization
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        prettyPrint = false
                        isLenient = true
                    },
                )
            }

            // Default timeout (can be overridden per request)
            install(HttpTimeout) {
                requestTimeoutMillis = properties.requestTimeoutMs
                connectTimeoutMillis = properties.connectTimeoutMs
                socketTimeoutMillis = properties.socketTimeoutMs
            }

            // Rate limiting interceptor (Bucket4j-based service)
            install(RateLimitingPlugin) {
                rateLimiter = rateLimiterService
            }

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

    /**
     * Authorization plugin - injects auth headers from ConnectionDocument.
     * Credentials must be provided via ConnectionCredentialsKey attribute.
     */
    private fun createAuthPlugin() =
        createClientPlugin("AuthPlugin") {
            onRequest { request, _ ->
                val connection = request.attributes.getOrNull(ConnectionDocumentKey)
                val credentials = request.attributes.getOrNull(ConnectionCredentialsKey)

                if (connection?.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP && credentials != null) {
                    when (credentials) {
                        is HttpCredentials.Basic -> {
                            request.header(
                                HttpHeaders.Authorization,
                                credentials.toAuthHeader(),
                            )
                        }

                        is HttpCredentials.Bearer -> {
                            request.header(
                                HttpHeaders.Authorization,
                                credentials.toAuthHeader(),
                            )
                        }
                    }
                    logger.debug { "Added authentication header for ${connection.name}" }
                }
            }
        }

    /**
     * Logging plugin - logs requests and responses at debug level.
     */
    private fun createLoggingPlugin() =
        createClientPlugin("LoggingPlugin") {
            onRequest { request, _ ->
                logger.debug { "HTTP ${request.method.value} ${request.url}" }
            }
        }
}

/**
 * Attribute key for passing ConnectionDocument through request attributes.
 */
val ConnectionDocumentKey = AttributeKey<ConnectionDocument>("ConnectionDocument")

/**
 * Attribute key for passing decrypted HttpCredentials through request attributes.
 */
val ConnectionCredentialsKey = AttributeKey<HttpCredentials>("ConnectionCredentials")
