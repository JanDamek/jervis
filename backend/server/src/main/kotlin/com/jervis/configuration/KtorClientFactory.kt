package com.jervis.configuration

import com.jervis.api.SecurityConstants
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.KtorClientProperties
import com.jervis.configuration.properties.RetryProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.cbor.cbor
import io.ktor.serialization.kotlinx.json.json
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import kotlinx.rpc.krpc.serialization.cbor.cbor as rpcCbor

/**
 * Factory for creating Ktor HttpClients for LLM provider communication and other HTTP services.
 *
 * Architecture:
 * - Ktor-based (coroutines-first) for all LLM providers (OpenAI, Anthropic, Google, Ollama, LM Studio)
 * - Ktor-based for web search (searxng)
 * - WebClient (Spring WebFlux) only for external compute services with @HttpExchange (joern, tika, whisper)
 *
 * Features:
 * - Automatic retry with exponential backoff for transient failures
 * - ConnectionDocument pooling and timeout management
 * - Per-provider authentication headers
 * - JSON serialization with kotlinx.serialization
 */
@Component
class KtorClientFactory(
    private val ktorClientProperties: KtorClientProperties,
    private val retryProperties: RetryProperties,
    private val endpoints: EndpointProperties,
) {
    /**
     * Registry of configured Ktor HttpClients, initialized lazily on first access.
     */
    private val httpClients: Map<String, HttpClient> by lazy {
        buildMap {
            put("lmStudio", createHttpClient(endpoints.lmStudio.baseUrl))
            put("ollama.primary", createHttpClient(endpoints.ollama.primary.baseUrl))
            put("ollama.qualifier", createHttpClient(endpoints.ollama.qualifier.baseUrl))
            put("ollama.embedding", createHttpClient(endpoints.ollama.embedding.baseUrl))
            put(
                "openai",
                createHttpClientWithAuth(endpoints.openai.baseUrl, endpoints.openai.apiKey) { request, key ->
                    request.header("Authorization", "Bearer $key")
                },
            )
            put(
                "anthropic",
                createHttpClientWithAuth(endpoints.anthropic.baseUrl, endpoints.anthropic.apiKey) { request, key ->
                    request.header("x-api-key", key)
                    request.header("anthropic-version", ktorClientProperties.apiVersions.anthropicVersion)
                },
            )
            put(
                "google",
                createHttpClientWithAuth(endpoints.google.baseUrl, endpoints.google.apiKey) { request, key ->
                    request.header("x-goog-api-key", key)
                },
            )
            put("searxng", createHttpClient(endpoints.searxng.baseUrl))
            put("aider", createHttpClient(endpoints.aider.baseUrl.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/a2a")))
            put("coding", createHttpClient(endpoints.coding.baseUrl.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/a2a")))
            put("tika", createHttpClient(endpoints.tika.baseUrl, isRpc = true))
            put("joern", createHttpClient(endpoints.joern.baseUrl, isRpc = true))
            put("whisper", createHttpClient(endpoints.whisper.baseUrl, isRpc = true))
            put("atlassian", createHttpClient(endpoints.atlassian.baseUrl, isRpc = true))
            put("junie", createHttpClient(endpoints.junie.baseUrl.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/a2a")))
        }
    }

    /**
     * Get Ktor HttpClient by endpoint name.
     * @param endpointName The endpoint name (e.g., "openai", "ollama.primary", "anthropic")
     * @return Configured HttpClient
     * @throws IllegalArgumentException if endpoint not found
     */
    fun getHttpClient(endpointName: String): HttpClient =
        httpClients[endpointName]
            ?: throw IllegalArgumentException("HttpClient not found for endpoint: $endpointName")

    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClient(baseUrl: String, isRpc: Boolean = false): HttpClient =
        HttpClient(CIO) {
            val uri = URI(baseUrl)
            // Base URL
            defaultRequest {
                url {
                    val protocolStr = uri.scheme ?: "http"
                    takeFrom(
                        "$protocolStr://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${
                            uri.path.trimEnd(
                                '/',
                            )
                        }/",
                    )
                }
                // Only for internal communication (not for Atlassian Cloud)
                if (!uri.host.contains("atlassian.net")) {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    header(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)
                }
            }

            // JSON serialization
            install(ContentNegotiation) {
                json(
                    kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        isLenient = true
                        prettyPrint = false
                        explicitNulls = false
                    },
                )
                cbor()
            }

            install(WebSockets)

            if (isRpc) {
                installKrpc {
                    serialization {
                        rpcCbor()
                    }
                }
            }

            // Timeouts
            install(HttpTimeout) {
                requestTimeoutMillis = ktorClientProperties.timeouts.requestTimeoutMillis
                connectTimeoutMillis = ktorClientProperties.timeouts.connectTimeoutMillis.toLong()
                socketTimeoutMillis = ktorClientProperties.timeouts.socketTimeoutMillis
            }

            // ConnectionDocument pooling
            engine {
                maxConnectionsCount = ktorClientProperties.connectionPool.maxConnections
                endpoint {
                    maxConnectionsPerRoute = ktorClientProperties.connectionPool.maxConnectionsPerRoute
                    connectAttempts = 1 // Retries handled by plugin below
                    keepAliveTime = ktorClientProperties.connectionPool.keepAliveTimeMillis
                    connectTimeout = ktorClientProperties.timeouts.connectTimeoutMillis.toLong()
                }
            }

            // Logging (optional, for debugging)
            if (ktorClientProperties.logging.enabled) {
                install(Logging) {
                    level = LogLevel.valueOf(ktorClientProperties.logging.level)
                    logger =
                        object : Logger {
                            private val log = KotlinLogging.logger("ktor.client")

                            override fun log(message: String) {
                                log.debug { message }
                            }
                        }
                }
            }

            // Retry logic with exponential backoff
            install(HttpRequestRetry) {
                maxRetries = retryProperties.ktor.maxAttempts
                retryOnServerErrors(maxRetries = retryProperties.ktor.maxAttempts)
                exponentialDelay(
                    base = 2.0,
                    maxDelayMs = retryProperties.ktor.maxBackoffMillis,
                )
            }

            expectSuccess = false
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClientWithAuth(
        baseUrl: String,
        apiKey: String,
        isRpc: Boolean = false,
        authHeadersConfig: (DefaultRequest.DefaultRequestBuilder, String) -> Unit,
    ): HttpClient =
        HttpClient(CIO) {
            val uri = URI(baseUrl)
            // Base URL and authentication
            defaultRequest {
                url {
                    val protocolStr = uri.scheme ?: "http"
                    takeFrom(
                        "$protocolStr://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${
                            uri.path.trimEnd(
                                '/',
                            )
                        }/",
                    )
                }
                // Only for internal communication (not for Atlassian Cloud)
                if (!uri.host.contains("atlassian.net")) {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    header(SecurityConstants.CLIENT_HEADER, SecurityConstants.CLIENT_TOKEN)

                    if (apiKey.isNotBlank()) {
                        authHeadersConfig(this, apiKey)
                    }
                }
            }

            // JSON serialization
            install(ContentNegotiation) {
                json(
                    kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                        isLenient = true
                        prettyPrint = false
                        explicitNulls = false
                    },
                )
                cbor()
            }

            install(WebSockets)

            if (isRpc) {
                installKrpc {
                    serialization {
                        rpcCbor()
                    }
                }
            }

            // Timeouts
            install(HttpTimeout) {
                requestTimeoutMillis = ktorClientProperties.timeouts.requestTimeoutMillis
                connectTimeoutMillis = ktorClientProperties.timeouts.connectTimeoutMillis.toLong()
                socketTimeoutMillis = ktorClientProperties.timeouts.socketTimeoutMillis
            }

            // ConnectionDocument pooling
            engine {
                maxConnectionsCount = ktorClientProperties.connectionPool.maxConnections
                endpoint {
                    maxConnectionsPerRoute = ktorClientProperties.connectionPool.maxConnectionsPerRoute
                    connectAttempts = 1
                    keepAliveTime = ktorClientProperties.connectionPool.keepAliveTimeMillis
                    connectTimeout = ktorClientProperties.timeouts.connectTimeoutMillis.toLong()
                }
            }

            // Logging (optional)
            if (ktorClientProperties.logging.enabled) {
                install(Logging) {
                    level = LogLevel.valueOf(ktorClientProperties.logging.level)
                    logger =
                        object : Logger {
                            private val log = KotlinLogging.logger("ktor.client")

                            override fun log(message: String) {
                                log.debug { message }
                            }
                        }
                }
            }

            // Retry logic
            install(HttpRequestRetry) {
                maxRetries = retryProperties.ktor.maxAttempts
                retryOnServerErrors(maxRetries = retryProperties.ktor.maxAttempts)
                exponentialDelay(
                    base = 2.0,
                    maxDelayMs = retryProperties.ktor.maxBackoffMillis,
                )
            }

            expectSuccess = false
        }
}
