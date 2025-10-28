package com.jervis.config

import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IEmailAccountService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskSchedulingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

@Configuration
class HttpInterfaceClientConfig {
    private fun createHttpServiceProxyFactory(webClient: WebClient): HttpServiceProxyFactory {
        val adapter = WebClientAdapter.create(webClient)
        return HttpServiceProxyFactory.builderFor(adapter).build()
    }

    @Bean
    fun agentOrchestratorClient(webClient: WebClient): IAgentOrchestratorService =
        createHttpServiceProxyFactory(webClient).createClient(IAgentOrchestratorService::class.java)

    @Bean
    fun taskSchedulingClient(webClient: WebClient): ITaskSchedulingService =
        createHttpServiceProxyFactory(webClient).createClient(ITaskSchedulingService::class.java)

    @Bean
    fun projectClient(webClient: WebClient): IProjectService =
        createHttpServiceProxyFactory(webClient).createClient(IProjectService::class.java)

    @Bean
    fun clientServiceClient(webClient: WebClient): IClientService =
        createHttpServiceProxyFactory(webClient).createClient(IClientService::class.java)

    @Bean
    fun clientProjectLinkClient(webClient: WebClient): IClientProjectLinkService =
        createHttpServiceProxyFactory(webClient).createClient(IClientProjectLinkService::class.java)

    @Bean
    fun gitConfigurationClient(webClient: WebClient): IGitConfigurationService =
        createHttpServiceProxyFactory(webClient).createClient(IGitConfigurationService::class.java)

    @Bean
    fun emailAccountClient(webClient: WebClient): IEmailAccountService =
        createHttpServiceProxyFactory(webClient).createClient(IEmailAccountService::class.java)
}
