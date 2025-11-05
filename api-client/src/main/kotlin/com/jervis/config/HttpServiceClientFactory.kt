package com.jervis.config

import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

/**
 * Simple factory to create Spring HTTP interface proxies from a WebClient.
 * Keeps client creation unified between desktop (api-client) and Android mobile code.
 */
object HttpServiceClientFactory {
    fun create(webClient: WebClient): HttpServiceProxyFactory =
        HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build()
}