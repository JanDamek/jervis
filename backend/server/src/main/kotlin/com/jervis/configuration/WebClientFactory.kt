package com.jervis.configuration

import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.RetryProperties
import com.jervis.configuration.properties.WebClientProperties
import io.netty.channel.ChannelOption
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import java.io.IOException
import java.net.ConnectException
import java.time.Duration

/**
 * Factory for creating WebClients based on endpoint configuration.
 * Provides dynamic WebClient creation with proper configuration per endpoint type.
 */
@Component
class WebClientFactory(
    private val webClientProperties: WebClientProperties,
    private val retryProperties: RetryProperties,
    private val endpoints: EndpointProperties,
) {
    private val webClientBuilder = WebClient.builder()

    /**
     * Registry of configured WebClients, initialized lazily on first access.
     */
    private val webClients: Map<String, WebClient> by lazy {
        buildMap {
            put("lmStudio", createWebClient(endpoints.lmStudio.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put("ollama.primary", createWebClient(endpoints.ollama.primary.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put("ollama.qualifier", createWebClient(endpoints.ollama.qualifier.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put("ollama.embedding", createWebClient(endpoints.ollama.embedding.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put(
                "openai",
                createWebClientWithAuth(endpoints.openai.baseUrl, endpoints.openai.apiKey) { key ->
                    listOf("Authorization" to "Bearer $key")
                },
            )
            put(
                "anthropic",
                createWebClientWithAuth(endpoints.anthropic.baseUrl, endpoints.anthropic.apiKey) { key ->
                    listOf(
                        "x-api-key" to key,
                        "anthropic-version" to webClientProperties.apiVersions.anthropicVersion,
                    )
                },
            )
            put(
                "google",
                createWebClientWithAuth(endpoints.google.baseUrl, endpoints.google.apiKey) { key ->
                    listOf("x-goog-api-key" to key)
                },
            )

            put("searxng", createWebClient(endpoints.searxng.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put("tika", createWebClient(endpoints.tika.baseUrl, webClientProperties.buffers.tikaMaxInMemoryBytes))
            put("joern", createWebClient(endpoints.joern.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put("whisper", createWebClient(endpoints.whisper.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
        }
    }

    /**
     * Get WebClient by endpoint name.
     * @param endpointName The endpoint name (e.g., "openai", "ollama.primary", "anthropic")
     * @return Configured WebClient
     * @throws IllegalArgumentException if endpoint not found
     */
    fun getWebClient(endpointName: String): WebClient =
        webClients[endpointName]
            ?: throw IllegalArgumentException("WebClient not found for endpoint: $endpointName")

    private fun createWebClient(
        baseUrl: String,
        maxBufferSize: Int,
    ): WebClient =
        webClientBuilder
            .baseUrl(baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(createExchangeStrategies(maxBufferSize))
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    private fun createWebClientWithAuth(
        baseUrl: String,
        apiKey: String,
        headersProvider: (String) -> List<Pair<String, String>>,
    ): WebClient =
        webClientBuilder
            .baseUrl(baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                if (apiKey.isNotBlank()) {
                    headersProvider(apiKey).forEach { (name, value) ->
                        headers[name] = value
                    }
                }
            }.exchangeStrategies(createExchangeStrategies(webClientProperties.buffers.defaultMaxInMemoryBytes))
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    private fun createExchangeStrategies(maxBufferSize: Int): ExchangeStrategies =
        ExchangeStrategies
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(maxBufferSize) }
            .build()

    private fun createHttpClientWithTimeouts(): HttpClient {
        val connectionProvider =
            ConnectionProvider
                .builder("webclient-pool")
                .maxConnections(webClientProperties.connectionPool.maxConnections)
                .maxIdleTime(webClientProperties.connectionPool.maxIdleTime)
                .maxLifeTime(webClientProperties.connectionPool.maxLifeTime)
                .pendingAcquireTimeout(webClientProperties.connectionPool.pendingAcquireTimeout)
                .pendingAcquireMaxCount(webClientProperties.connectionPool.pendingAcquireMaxCount)
                .evictInBackground(webClientProperties.connectionPool.evictInBackground)
                .build()

        return HttpClient
            .create(connectionProvider)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                webClientProperties.timeouts.connectTimeoutMillis,
            ).responseTimeout(Duration.ofMillis(webClientProperties.timeouts.responseTimeoutMillis))
    }

    private fun createRetryFilter() =
        {
            request: org.springframework.web.reactive.function.client.ClientRequest,
            next: org.springframework.web.reactive.function.client.ExchangeFunction,
            ->
            next.exchange(request).retryWhen(
                Retry
                    .backoff(
                        retryProperties.webclient.maxAttempts,
                        Duration.ofMillis(retryProperties.webclient.initialBackoffMillis),
                    ).maxBackoff(Duration.ofMillis(retryProperties.webclient.maxBackoffMillis))
                    .filter { throwable ->
                        val isTimeout =
                            throwable is java.util.concurrent.TimeoutException ||
                                throwable is io.netty.handler.timeout.ReadTimeoutException ||
                                throwable is io.netty.handler.timeout.WriteTimeoutException ||
                                throwable.cause is java.util.concurrent.TimeoutException ||
                                throwable.cause is io.netty.handler.timeout.ReadTimeoutException

                        if (isTimeout) {
                            return@filter false
                        }

                        // Retry only on transient connection errors
                        throwable is ConnectException ||
                            (throwable is IOException && !isTimeout) ||
                            throwable.message?.contains("Connection prematurely closed", ignoreCase = true) == true ||
                            throwable.message?.contains("Connection reset", ignoreCase = true) == true
                    },
            )
        }

}
