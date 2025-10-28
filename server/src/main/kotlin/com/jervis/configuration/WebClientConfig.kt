package com.jervis.configuration

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import java.io.IOException
import java.net.ConnectException
import java.time.Duration

@Configuration
class WebClientConfig(
    private val connectionPoolProperties: ConnectionPoolProperties,
    private val timeoutsProperties: TimeoutsProperties,
    private val retryProperties: RetryProperties,
) {
    @Bean
    @Qualifier("lmStudioWebClient")
    fun lmStudioWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.lmStudio.baseUrl?.trimEnd('/') ?: DEFAULT_LM_STUDIO_URL)
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(
                endpoints.ollama.primary.baseUrl
                    ?.trimEnd('/') ?: DEFAULT_OLLAMA_URL,
            ).defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("ollamaQualifierWebClient")
    fun ollamaQualifierWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(
                endpoints.ollama.qualifier.baseUrl
                ?.trimEnd('/') ?: DEFAULT_OLLAMA_URL
                    )
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("openaiWebClient")
    fun openaiWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.openai.baseUrl?.trimEnd('/') ?: DEFAULT_OPENAI_API)
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                val key = endpoints.openai.apiKey
                if (!key.isNullOrBlank()) headers["Authorization"] = "Bearer $key"
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("anthropicWebClient")
    fun anthropicWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.anthropic.baseUrl?.trimEnd('/') ?: DEFAULT_ANTHROPIC_API)
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                val key = endpoints.anthropic.apiKey
                if (!key.isNullOrBlank()) headers["x-api-key"] = key
                headers["anthropic-version"] = ANTHROPIC_VERSION
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("searxngWebClient")
    fun searxngWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.searxng.baseUrl?.trimEnd('/') ?: DEFAULT_SEARXNG_URL)
            .defaultHeaders { headers ->
                headers.accept = listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    private fun defaultExchangeStrategies(): ExchangeStrategies =
        ExchangeStrategies
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(DEFAULT_MAX_IN_MEMORY_BYTES) }
            .build()

    private fun createHttpClientWithTimeouts(): HttpClient {
        val connectionProvider =
            ConnectionProvider
                .builder("embedding-pool")
                .maxConnections(connectionPoolProperties.getWebClientMaxConnections())
                .maxIdleTime(connectionPoolProperties.getWebClientMaxIdleTime())
                .maxLifeTime(connectionPoolProperties.getWebClientMaxLifeTime())
                .pendingAcquireTimeout(connectionPoolProperties.getWebClientPendingAcquireTimeout())
                .pendingAcquireMaxCount(connectionPoolProperties.webclient.pendingAcquireMaxCount)
                .evictInBackground(connectionPoolProperties.getWebClientEvictInBackground())
                .build()

        return HttpClient
            .create(connectionProvider)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (timeoutsProperties.webclient.connectTimeoutSeconds * 1000).toInt(),
            )
        // Removed ReadTimeoutHandler and WriteTimeoutHandler to eliminate timeouts for long-running LLM operations
        // Removed responseTimeout to allow unlimited response time for heavy model processing
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
                        throwable is ConnectException || throwable is IOException && throwable !is WebClientResponseException
                    },
            )
        }

    companion object {
        private const val DEFAULT_MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024 // 8 MB
        private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
        private const val DEFAULT_LM_STUDIO_URL = "http://localhost:1234"
        private const val DEFAULT_OPENAI_API = "https://api.openai.com/v1"
        private const val DEFAULT_ANTHROPIC_API = "https://api.anthropic.com"
        private const val DEFAULT_SEARXNG_URL = "http://192.168.100.117:30053"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
