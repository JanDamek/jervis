package com.jervis.configuration

import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.KtorClientProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Factory for creating Ktor HttpClients for LLM provider communication and web search.
 *
 * Features:
 * - Per-provider authentication headers
 * - JSON serialization with kotlinx.serialization
 * - Basic retry logic for transient failures
 */
@Component
class KtorClientFactory(
    private val ktorClientProperties: KtorClientProperties,
    private val endpoints: EndpointProperties,
) {
    /**
     * Registry of configured Ktor HttpClients for LLM providers and search, initialized lazily on first access.
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
    private fun createHttpClient(baseUrl: String): HttpClient =
        HttpClient(CIO) {
            val uri = URI(baseUrl)

            defaultRequest {
                url {
                    takeFrom(uri)
                }
            }

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
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }

            expectSuccess = false
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClientWithAuth(
        baseUrl: String,
        apiKey: String,
        authHeadersConfig: (DefaultRequest.DefaultRequestBuilder, String) -> Unit,
    ): HttpClient =
        HttpClient(CIO) {
            val uri = URI(baseUrl)

            defaultRequest {
                url {
                    takeFrom(uri)
                }
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)

                if (apiKey.isNotBlank()) {
                    authHeadersConfig(this, apiKey)
                }
            }

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
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }

            expectSuccess = false
        }
}
