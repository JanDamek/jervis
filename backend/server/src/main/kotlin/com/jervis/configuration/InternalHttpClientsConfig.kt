package com.jervis.configuration

import com.jervis.common.client.IJoernClient
import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import com.jervis.common.client.IAiderClient
import com.jervis.common.client.ICodingEngineClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

@Configuration
class InternalHttpClientsConfig(
    private val webClientFactory: WebClientFactory,
) {
    private fun factory(webClient: WebClient): HttpServiceProxyFactory =
        HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build()

    @Bean
    @Qualifier("tikaWebClient")
    fun tikaWebClient(): WebClient = webClientFactory.getWebClient("tika")

    @Bean
    @Qualifier("joernWebClient")
    fun joernWebClient(): WebClient = webClientFactory.getWebClient("joern")

    @Bean
    @Qualifier("whisperWebClient")
    fun whisperWebClient(): WebClient = webClientFactory.getWebClient("whisper")

    @Bean
    @Qualifier("atlassianWebClient")
    fun atlassianWebClient(): WebClient = webClientFactory.getWebClient("atlassian")

    @Bean
    @Qualifier("aiderWebClient")
    fun aiderWebClient(): WebClient = webClientFactory.getWebClient("aider")

    @Bean
    @Qualifier("codingWebClient")
    fun codingWebClient(): WebClient = webClientFactory.getWebClient("coding")

    @Bean
    fun tikaClient(
        @Qualifier("tikaWebClient") webClient: WebClient,
    ): ITikaClient = factory(webClient).createClient(ITikaClient::class.java)

    @Bean
    fun joernClient(
        @Qualifier("joernWebClient") webClient: WebClient,
    ): IJoernClient = factory(webClient).createClient(IJoernClient::class.java)

    @Bean
    fun whisperClient(
        @Qualifier("whisperWebClient") webClient: WebClient,
    ): IWhisperClient = factory(webClient).createClient(IWhisperClient::class.java)

    @Bean
    fun atlassianClient(
        @Qualifier("atlassianWebClient") webClient: WebClient,
    ): IAtlassianClient = factory(webClient).createClient(IAtlassianClient::class.java)

    @Bean
    fun aiderClient(
        @Qualifier("aiderWebClient") webClient: WebClient,
    ): IAiderClient = factory(webClient).createClient(IAiderClient::class.java)

    @Bean
    fun codingEngineClient(
        @Qualifier("codingWebClient") webClient: WebClient,
    ): ICodingEngineClient = factory(webClient).createClient(ICodingEngineClient::class.java)
}
