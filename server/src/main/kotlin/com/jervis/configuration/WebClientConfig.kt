package com.jervis.configuration

import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.RetryProperties
import com.jervis.configuration.properties.WebClientProperties
import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import java.io.IOException
import java.net.ConnectException
import java.time.Duration

@Configuration
class WebClientConfig(
    private val webClientProperties: WebClientProperties,
    private val retryProperties: RetryProperties,
) {
    @Bean
    @Qualifier("lmStudioWebClient")
    fun lmStudioWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.lmStudio.baseUrl.trimEnd('/'))
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
                    .trimEnd('/'),
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
                    .trimEnd('/'),
            ).defaultHeaders { headers ->
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
            .baseUrl(endpoints.openai.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                val key = endpoints.openai.apiKey
                if (key.isNotBlank()) headers["Authorization"] = "Bearer $key"
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
            .baseUrl(endpoints.anthropic.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                val key = endpoints.anthropic.apiKey
                if (key.isNotBlank()) headers["x-api-key"] = key
                headers["anthropic-version"] = ANTHROPIC_VERSION
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("googleWebClient")
    fun googleWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.google.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
                val key = endpoints.google.apiKey
                if (key.isNotBlank()) headers["x-goog-api-key"] = key
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
            .baseUrl(endpoints.searxng.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.accept = listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("tikaWebClient")
    fun ocrWebClient(
        builder: WebClient.Builder,
        clients: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(clients.tika.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("joernWebClient")
    fun joernWebClient(
        builder: WebClient.Builder,
        clients: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(clients.joern.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
            .filter(createRetryFilter())
            .build()

    @Bean
    @Qualifier("whisperWebClient")
    fun whisperWebClient(
        builder: WebClient.Builder,
        clients: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(clients.whisper.baseUrl.trimEnd('/'))
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
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
            )
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
                        throwable is ConnectException || throwable is IOException
                    },
            )
        }

    companion object {
        private const val DEFAULT_MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024 // 8 MB
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
