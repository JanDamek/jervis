package com.jervis.config

import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientIndexingService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.IIndexingService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskContextService
import com.jervis.service.ITaskQueryService
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
    fun clientIndexingClient(webClient: WebClient): IClientIndexingService =
        createHttpServiceProxyFactory(webClient).createClient(IClientIndexingService::class.java)

    @Bean
    fun indexingMonitorClient(webClient: WebClient): IIndexingMonitorService =
        createHttpServiceProxyFactory(webClient).createClient(IIndexingMonitorService::class.java)

    @Bean
    fun taskSchedulingClient(webClient: WebClient): ITaskSchedulingService =
        createHttpServiceProxyFactory(webClient).createClient(ITaskSchedulingService::class.java)

    @Bean
    fun taskContextClient(webClient: WebClient): ITaskContextService =
        createHttpServiceProxyFactory(webClient).createClient(ITaskContextService::class.java)

    @Bean
    fun taskQueryClient(webClient: WebClient): ITaskQueryService =
        createHttpServiceProxyFactory(webClient).createClient(ITaskQueryService::class.java)

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
    fun indexingServiceClient(webClient: WebClient): IIndexingService =
        createHttpServiceProxyFactory(webClient).createClient(IIndexingService::class.java)
}
