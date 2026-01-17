package com.jervis.di

import com.jervis.api.SecurityConstants
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IConnectionService
import com.jervis.service.IErrorLogService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IPendingTaskService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.IUserTaskService
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.encodedPath
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Platform-specific HTTP client creation with SSL configuration
 * Each platform implements its own way of handling self-signed certificates
 */
expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Network module for dependency injection
 * Creates all service instances via RPC or A2A
 */
object NetworkModule {
    /**
     * Create HTTP client with common configuration
     * Supports self-signed certificates for all platforms
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun createHttpClient(): HttpClient =
        createPlatformHttpClient {
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }

            installKrpc {
                serialization {
                    cbor()
                }
            }

            defaultRequest {
                headers[SecurityConstants.CLIENT_HEADER] = SecurityConstants.CLIENT_TOKEN
                headers[SecurityConstants.PLATFORM_HEADER] = SecurityConstants.PLATFORM_DESKTOP
            }
        }

    fun createRpcClient(
        baseUrl: String,
        httpClient: HttpClient = createHttpClient(),
    ): KtorRpcClient {
        val cleanBaseUrl = baseUrl.trimEnd('/')

        // Convert HTTP(S) URLs to WebSocket URLs for RPC
        val wsUrl = cleanBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        val url = io.ktor.http.Url(wsUrl)

        return httpClient.rpc {
            url {
                protocol = url.protocol
                host = url.host
                port = if (url.specifiedPort == 0) url.protocol.defaultPort else url.specifiedPort
                encodedPath = "rpc"
            }
        }
    }

    /**
     * Create services directly from base URL.
     * Preferred method for UI applications - hides RPC client implementation.
     */
    fun createServicesFromUrl(
        baseUrl: String,
        httpClient: HttpClient = createHttpClient(),
    ): Services {
        val rpcClient = createRpcClient(baseUrl, httpClient)
        return createServices(rpcClient)
    }

    /**
     * Create all service instances from RPC client
     * UI applications (Desktop/iOS/Android) MUST use RPC only
     */
    fun createServices(rpcClient: KtorRpcClient): Services =
        Services(
            projectService = rpcClient.withService<IProjectService>(),
            clientService = rpcClient.withService<IClientService>(),
            clientProjectLinkService = rpcClient.withService<IClientProjectLinkService>(),
            userTaskService = rpcClient.withService<IUserTaskService>(),
            ragSearchService = rpcClient.withService<IRagSearchService>(),
            taskSchedulingService = rpcClient.withService<ITaskSchedulingService>(),
            agentOrchestratorService = rpcClient.withService<IAgentOrchestratorService>(),
            errorLogService = rpcClient.withService<IErrorLogService>(),
            gitConfigurationService = rpcClient.withService<IGitConfigurationService>(),
            pendingTaskService = rpcClient.withService<IPendingTaskService>(),
            connectionService = rpcClient.withService<IConnectionService>(),
        )

    /**
     * Container for all services
     */
    data class Services(
        val projectService: IProjectService,
        val clientService: IClientService,
        val clientProjectLinkService: IClientProjectLinkService,
        val userTaskService: IUserTaskService,
        val ragSearchService: IRagSearchService,
        val taskSchedulingService: ITaskSchedulingService,
        val agentOrchestratorService: IAgentOrchestratorService,
        val errorLogService: IErrorLogService,
        val gitConfigurationService: IGitConfigurationService,
        val pendingTaskService: IPendingTaskService,
        val connectionService: IConnectionService,
    )
}
