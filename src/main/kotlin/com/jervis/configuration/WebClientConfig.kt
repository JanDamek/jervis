package com.jervis.configuration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {
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
                if (!key.isNullOrBlank()) headers.set("Authorization", "Bearer $key")
            }.exchangeStrategies(defaultExchangeStrategies())
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
                if (!key.isNullOrBlank()) headers.set("x-api-key", key)
                headers.set("anthropic-version", ANTHROPIC_VERSION)
            }.exchangeStrategies(defaultExchangeStrategies())
            .build()

    private fun defaultExchangeStrategies(): ExchangeStrategies =
        ExchangeStrategies
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(DEFAULT_MAX_IN_MEMORY_BYTES) }
            .build()

    companion object {
        private const val DEFAULT_MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024 // 8 MB
        private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
        private const val DEFAULT_LM_STUDIO_URL = "http://localhost:1234"
        private const val DEFAULT_OPENAI_API = "https://api.openai.com/v1"
        private const val DEFAULT_ANTHROPIC_API = "https://api.anthropic.com"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
