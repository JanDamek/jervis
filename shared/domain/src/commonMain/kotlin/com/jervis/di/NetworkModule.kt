package com.jervis.di

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import com.jervis.api.SecurityConstants
import com.jervis.rpc.A2ARPCClient
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
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.cbor.cbor
import kotlinx.serialization.json.Json
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService

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
    fun createHttpClient(): HttpClient =
        createPlatformHttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
                cbor()
            }

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

    suspend fun createRpcClient(
        baseUrl: String,
        httpClient: HttpClient = createHttpClient()
    ): KtorRpcClient {
        // Clean URL - remove trailing slash
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = io.ktor.http.Url(cleanBaseUrl)

        return httpClient.rpc {
            url {
                protocol = url.protocol
                host = url.host
                // Only set port if explicitly specified and different from default
                // For HTTPS (443) and HTTP (80), don't set port - use protocol default
                val defaultPort = if (url.protocol == URLProtocol.HTTPS) 443 else 80
                if (url.port != -1 && url.port != defaultPort) {
                    port = url.port
                }
                encodedPath = "rpc"
            }
        }
    }

    fun createA2AClient(
        baseUrl: String,
        httpClient: HttpClient,
    ): A2AClient {
        val normalized = baseUrl.trimEnd('/')
        // If baseUrl already points to /a2a/system, use it as is
        val a2aUrl = if (normalized.endsWith("/a2a/system")) {
            normalized
        } else {
            // Otherwise, assume it's the root URL and append /a2a/system
            normalized + "/a2a/system"
        }
        val transport = HttpJSONRPCClientTransport(a2aUrl, httpClient)
        // Resolver should point to the agent server's root
        val resolverBaseUrl = if (normalized.endsWith("/a2a/system")) {
            normalized.removeSuffix("/a2a/system")
        } else {
            normalized
        }
        val agentCardResolver = UrlAgentCardResolver(baseUrl = resolverBaseUrl, path = "/a2a/system")
        return A2AClient(transport = transport, agentCardResolver = agentCardResolver)
    }

    /**
     * Create all service instances from RPC client
     * UI applications (Desktop/iOS/Android) MUST use RPC only
     */
    fun createServices(rpcClient: KtorRpcClient): Services {
        return Services(
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
    }

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

