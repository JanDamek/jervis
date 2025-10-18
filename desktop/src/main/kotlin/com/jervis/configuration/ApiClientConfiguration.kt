package com.jervis.configuration

import com.jervis.client.ApiClientFactory
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientIndexingService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IDebugWindowService
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.IIndexingService
import com.jervis.service.IProjectService
import com.jervis.service.ITaskContextService
import com.jervis.service.ITaskQueryService
import com.jervis.service.ITaskSchedulingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class ApiClientConfiguration {
    @Value("\${jervis.server.url}")
    private lateinit var serverUrl: String

    private val notificationsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Bean
    @Primary
    fun projectService(): IProjectService = ApiClientFactory.createProjectService(serverUrl)

    @Bean
    @Primary
    fun clientService(): IClientService = ApiClientFactory.createClientService(serverUrl)

    @Bean
    @Primary
    fun clientProjectLinkService(): IClientProjectLinkService = ApiClientFactory.createClientProjectLinkService(serverUrl)

    @Bean
    @Primary
    fun indexingService(): IIndexingService = ApiClientFactory.createIndexingService(serverUrl)

    @Bean
    @Primary
    fun clientIndexingService(): IClientIndexingService = ApiClientFactory.createClientIndexingService(serverUrl)

    @Bean
    @Primary
    fun agentOrchestratorService(): IAgentOrchestratorService = ApiClientFactory.createAgentOrchestratorService(serverUrl)

    @Bean
    @Primary
    fun taskContextService(): ITaskContextService = ApiClientFactory.createTaskContextService(serverUrl)

    @Bean
    @Primary
    fun taskQueryService(): ITaskQueryService = ApiClientFactory.createTaskQueryService(serverUrl)

    @Bean
    @Primary
    fun taskSchedulingService(): ITaskSchedulingService = ApiClientFactory.createTaskSchedulingService(serverUrl)

    @Bean
    fun indexingMonitorService(): IIndexingMonitorService = ApiClientFactory.createIndexingMonitorService(serverUrl)

    @Bean
    fun notificationsClient(applicationEventPublisher: ApplicationEventPublisher): com.jervis.client.NotificationsWebSocketClient =
        ApiClientFactory.createNotificationsClient(serverUrl, applicationEventPublisher).also { it.start() }

    @Bean
    fun debugClient(debugWindowService: IDebugWindowService): com.jervis.client.DebugWebSocketClient =
        ApiClientFactory.createDebugClient(serverUrl, debugWindowService).also { it.start() }
}
