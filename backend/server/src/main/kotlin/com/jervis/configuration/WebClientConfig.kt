package com.jervis.configuration

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * WebClient configuration that provides @Qualifier beans for dependency injection.
 * All WebClients are now created dynamically by WebClientFactory based on EndpointProperties.
 */
@Configuration
class WebClientConfig(
    private val webClientFactory: WebClientFactory,
) {
    @Bean
    @Qualifier("lmStudioWebClient")
    fun lmStudioWebClient(): WebClient = webClientFactory.getWebClient("lmStudio")

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(): WebClient = webClientFactory.getWebClient("ollama.primary")

    @Bean
    @Qualifier("ollamaQualifierWebClient")
    fun ollamaQualifierWebClient(): WebClient = webClientFactory.getWebClient("ollama.qualifier")

    @Bean
    @Qualifier("openaiWebClient")
    fun openaiWebClient(): WebClient = webClientFactory.getWebClient("openai")

    @Bean
    @Qualifier("anthropicWebClient")
    fun anthropicWebClient(): WebClient = webClientFactory.getWebClient("anthropic")

    @Bean
    @Qualifier("googleWebClient")
    fun googleWebClient(): WebClient = webClientFactory.getWebClient("google")

    @Bean
    @Qualifier("searxngWebClient")
    fun searxngWebClient(): WebClient = webClientFactory.getWebClient("searxng")

    @Bean
    @Qualifier("tikaWebClient")
    fun tikaWebClient(): WebClient = webClientFactory.getWebClient("tika")

    @Bean
    @Qualifier("joernWebClient")
    fun joernWebClient(): WebClient = webClientFactory.getWebClient("joern")

    @Bean
    @Qualifier("whisperWebClient")
    fun whisperWebClient(): WebClient = webClientFactory.getWebClient("whisper")
}
