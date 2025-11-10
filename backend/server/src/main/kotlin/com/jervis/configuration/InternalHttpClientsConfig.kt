package com.jervis.configuration

import com.jervis.common.client.IJoernClient
import com.jervis.common.client.ITikaClient
import com.jervis.common.client.IWhisperClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

@Configuration
class InternalHttpClientsConfig {
    private fun factory(webClient: WebClient): HttpServiceProxyFactory =
        HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build()

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
}
