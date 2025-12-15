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
 * Factory for creating WebClients for external compute services that use HttpExchange.
 *
 * Architecture:
 * - WebClient (Spring WebFlux) ONLY for external compute services with @HttpExchange: joern, tika, whisper, atlassian, aider, coding
 * - These services use @HttpExchange interfaces for declarative HTTP clients
 * - For LLM providers (OpenAI, Anthropic, Google, Ollama, LM Studio) and other HTTP services (searxng), use KtorClientFactory
 *
 * Note: This factory is being phased out. New services should use Ktor (see KtorClientFactory).
 */
@Component
class WebClientFactory(
    private val webClientProperties: WebClientProperties,
    private val retryProperties: RetryProperties,
    private val endpoints: EndpointProperties,
) {
    private val webClientBuilder = WebClient.builder()

    /**
     * Registry of configured WebClients for external compute services with @HttpExchange.
     */
    private val webClients: Map<String, WebClient> by lazy {
        buildMap {
            put("tika", createWebClient(endpoints.tika.baseUrl, webClientProperties.buffers.tikaMaxInMemoryBytes))
            put("joern", createWebClient(endpoints.joern.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put(
                "whisper",
                createWebClient(endpoints.whisper.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes),
            )
            put(
                "atlassian",
                createWebClient(endpoints.atlassian.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes),
            )
            put("aider", createWebClient(endpoints.aider.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes))
            put(
                "coding",
                createWebClient(endpoints.coding.baseUrl, webClientProperties.buffers.defaultMaxInMemoryBytes),
            )
        }
    }

    /**
     * Get WebClient by endpoint name (external compute services with @HttpExchange only).
     * @param endpointName The endpoint name ("tika", "joern", "whisper", "atlassian", "aider", "coding")
     * @return Configured WebClient
     * @throws IllegalArgumentException if endpoint not found
     */
    fun getWebClient(endpointName: String): WebClient =
        webClients[endpointName]
            ?: throw IllegalArgumentException("WebClient not found for endpoint: $endpointName. Available: ${webClients.keys}")

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
                            throwable.message?.contains(
                                "ConnectionDocument prematurely closed",
                                ignoreCase = true,
                            ) == true ||
                            throwable.message?.contains("ConnectionDocument reset", ignoreCase = true) == true
                    },
            )
        }
}
