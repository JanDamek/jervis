package com.jervis.configuration

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
import java.time.Duration

@Configuration
class WebClientConfig(
    private val connectionPoolProperties: ConnectionPoolProperties,
    private val timeoutsProperties: TimeoutsProperties,
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
            .build()

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(
        builder: WebClient.Builder,
        endpoints: EndpointProperties,
    ): WebClient =
        builder
            .baseUrl(endpoints.ollama.baseUrl?.trimEnd('/') ?: DEFAULT_OLLAMA_URL)
            .defaultHeaders { headers ->
                headers.contentType = MediaType.APPLICATION_JSON
                headers.accept = listOf(MediaType.APPLICATION_JSON)
            }.exchangeStrategies(defaultExchangeStrategies())
            .clientConnector(ReactorClientHttpConnector(createHttpClientWithTimeouts()))
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
                (timeoutsProperties.webclient.connectTimeoutMinutes * 60 * 1000).toInt(),
            ).responseTimeout(Duration.ofMinutes(timeoutsProperties.webclient.responseTimeoutMinutes))
        // Removed ReadTimeoutHandler and WriteTimeoutHandler to eliminate timeouts
    }

    companion object {
        private const val DEFAULT_MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024 // 8 MB
        private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
        private const val DEFAULT_LM_STUDIO_URL = "http://localhost:1234"
        private const val DEFAULT_OPENAI_API = "https://api.openai.com/v1"
        private const val DEFAULT_ANTHROPIC_API = "https://api.anthropic.com"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
