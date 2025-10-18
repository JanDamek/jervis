package com.jervis.client

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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.springframework.context.ApplicationEventPublisher

object ApiClientFactory {
    private fun createHttpClient(timeoutMillis: Long = 15_000): HttpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = timeoutMillis
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                    },
                )
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }

    fun createProjectService(baseUrl: String): IProjectService {
        val httpClient = createHttpClient()
        return ProjectRestClient(httpClient, baseUrl)
    }

    fun createClientService(baseUrl: String): IClientService {
        val httpClient = createHttpClient()
        return ClientRestClient(httpClient, baseUrl)
    }

    fun createClientProjectLinkService(baseUrl: String): IClientProjectLinkService {
        val httpClient = createHttpClient()
        return ClientProjectLinkRestClient(httpClient, baseUrl)
    }

    fun createIndexingService(baseUrl: String): IIndexingService {
        val httpClient = createHttpClient()
        return IndexingRestClient(httpClient, baseUrl)
    }

    fun createClientIndexingService(baseUrl: String): IClientIndexingService {
        val httpClient = createHttpClient()
        return ClientIndexingRestClient(httpClient, baseUrl)
    }

    fun createAgentOrchestratorService(baseUrl: String): IAgentOrchestratorService {
        val httpClient = createHttpClient() // Fire-and-forget with immediate response
        return AgentOrchestratorRestClient(httpClient, baseUrl)
    }

    fun createIndexingMonitorService(baseUrl: String): IIndexingMonitorService {
        val httpClient = createHttpClient()
        return IndexingMonitorRestClient(httpClient, baseUrl)
    }

    fun createTaskContextService(baseUrl: String): ITaskContextService {
        val httpClient = createHttpClient()
        return TaskContextRestClient(httpClient, baseUrl)
    }

    fun createTaskQueryService(baseUrl: String): ITaskQueryService {
        val httpClient = createHttpClient()
        return TaskQueryRestClient(httpClient, baseUrl)
    }

    fun createTaskSchedulingService(baseUrl: String): ITaskSchedulingService {
        val httpClient = createHttpClient()
        return TaskSchedulingRestClient(httpClient, baseUrl)
    }

    fun createNotificationsClient(
        baseUrl: String,
        eventPublisher: ApplicationEventPublisher,
    ): NotificationsWebSocketClient = NotificationsWebSocketClient(baseUrl, eventPublisher)

    fun createDebugClient(
        baseUrl: String,
        debugWindowService: IDebugWindowService,
    ): DebugWebSocketClient = DebugWebSocketClient(baseUrl, debugWindowService)
}
