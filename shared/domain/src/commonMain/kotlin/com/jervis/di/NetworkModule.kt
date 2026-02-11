package com.jervis.di

import com.jervis.api.SecurityConstants
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IBugTrackerSetupService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.ICodingAgentSettingsService
import com.jervis.service.IConnectionService
import com.jervis.service.IDeviceTokenService
import com.jervis.service.IEnvironmentService
import com.jervis.service.IErrorLogService
import com.jervis.service.IGitConfigurationService
import com.jervis.service.IIntegrationSettingsService
import com.jervis.service.IMeetingService
import com.jervis.service.ITranscriptCorrectionService
import com.jervis.service.INotificationService
import com.jervis.service.IPendingTaskService
import com.jervis.service.IPollingIntervalService
import com.jervis.service.IProjectGroupService
import com.jervis.service.IProjectService
import com.jervis.service.IRagSearchService
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.IUserTaskService
import com.jervis.service.IWhisperSettingsService
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.http.encodedPath
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Duration.Companion.seconds

/**
 * Interface for UI applications to handle RPC reconnection.
 */
interface UiRpcReconnectHandler {
    suspend fun reconnect()
}

/**
 * Platform-specific HTTP client creation with SSL configuration
 */
expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Network module for dependency injection
 * Creates all service instances via RPC or A2A
 */
object NetworkModule {
    private var _services: Services? = null
    private var _rpcClient: KtorRpcClient? = null
    private var _baseUrl: String? = null
    private var _httpClient: HttpClient? = null

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
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 120_000
            }

            install(WebSockets) {
                pingInterval = 20.seconds
                maxFrameSize = Long.MAX_VALUE
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
        httpClient: HttpClient,
    ): KtorRpcClient {
        val cleanBaseUrl = baseUrl.trimEnd('/')

        // Convert HTTP(S) URLs to WebSocket URLs for RPC
        val wsUrl =
            cleanBaseUrl
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
        _baseUrl = baseUrl
        _httpClient = httpClient

        val rpcClient = createRpcClient(baseUrl, httpClient)
        _rpcClient = rpcClient

        val services = createServices(rpcClient)
        _services = services
        return services
    }

    /**
     * Reconnect RPC client and refresh all service stubs.
     * Recreates the HttpClient to handle cases where the underlying engine
     * is in a broken state (common on iOS after background/foreground transitions).
     */
    suspend fun reconnect() {
        val baseUrl = _baseUrl ?: return

        println("NetworkModule: Reconnecting to $baseUrl (recreating HttpClient)...")
        try {
            // Close old RPC client to release WebSocket resources
            try { _rpcClient?.close() } catch (_: Exception) {}

            // Close old HttpClient — on iOS the Darwin engine can get stuck
            try { _httpClient?.close() } catch (_: Exception) {}

            // Create fresh HttpClient and RPC client
            val newHttpClient = createHttpClient()
            _httpClient = newHttpClient

            val newRpcClient = createRpcClient(baseUrl, newHttpClient)
            _rpcClient = newRpcClient

            // Refresh service stubs in the container
            _services?.let { current ->
                current.updateFrom(newRpcClient)
            }
            println("NetworkModule: Reconnection successful")
        } catch (e: Exception) {
            println("NetworkModule: Reconnection failed: ${e.message}")
            throw e
        }
    }

    /**
     * Create all service instances from RPC client
     * UI applications (Desktop/iOS/Android) MUST use RPC only
     */
    fun createServices(rpcClient: KtorRpcClient): Services = Services(rpcClient).apply { updateFrom(rpcClient) }

    /**
     * Container for all services
     */
    class Services(
        initialRpcClient: KtorRpcClient,
    ) {
        var projectService: IProjectService = initialRpcClient.withService<IProjectService>()
            private set
        var clientService: IClientService = initialRpcClient.withService<IClientService>()
            private set
        var clientProjectLinkService: IClientProjectLinkService =
            initialRpcClient.withService<IClientProjectLinkService>()
            private set
        var userTaskService: IUserTaskService = initialRpcClient.withService<IUserTaskService>()
            private set
        var ragSearchService: IRagSearchService = initialRpcClient.withService<IRagSearchService>()
            private set
        var taskSchedulingService: ITaskSchedulingService = initialRpcClient.withService<ITaskSchedulingService>()
            private set
        var agentOrchestratorService: IAgentOrchestratorService =
            initialRpcClient.withService<IAgentOrchestratorService>()
            private set
        var errorLogService: IErrorLogService = initialRpcClient.withService<IErrorLogService>()
            private set
        var gitConfigurationService: IGitConfigurationService = initialRpcClient.withService<IGitConfigurationService>()
            private set
        var pendingTaskService: IPendingTaskService = initialRpcClient.withService<IPendingTaskService>()
            private set
        var connectionService: IConnectionService = initialRpcClient.withService<IConnectionService>()
            private set
        var notificationService: INotificationService = initialRpcClient.withService<INotificationService>()
            private set
        var bugTrackerSetupService: IBugTrackerSetupService = initialRpcClient.withService<IBugTrackerSetupService>()
            private set
        var integrationSettingsService: IIntegrationSettingsService =
            initialRpcClient.withService<IIntegrationSettingsService>()
            private set
        var codingAgentSettingsService: ICodingAgentSettingsService =
            initialRpcClient.withService<ICodingAgentSettingsService>()
            private set
        var whisperSettingsService: IWhisperSettingsService =
            initialRpcClient.withService<IWhisperSettingsService>()
            private set
        var pollingIntervalService: IPollingIntervalService =
            initialRpcClient.withService<IPollingIntervalService>()
            private set
        var projectGroupService: IProjectGroupService = initialRpcClient.withService<IProjectGroupService>()
            private set
        var environmentService: IEnvironmentService = initialRpcClient.withService<IEnvironmentService>()
            private set
        var meetingService: IMeetingService = initialRpcClient.withService<IMeetingService>()
            private set
        var transcriptCorrectionService: ITranscriptCorrectionService =
            initialRpcClient.withService<ITranscriptCorrectionService>()
            private set
        var deviceTokenService: IDeviceTokenService = initialRpcClient.withService<IDeviceTokenService>()
            private set

        fun updateFrom(rpcClient: KtorRpcClient) {
            projectService = rpcClient.withService<IProjectService>()
            clientService = rpcClient.withService<IClientService>()
            clientProjectLinkService = rpcClient.withService<IClientProjectLinkService>()
            userTaskService = rpcClient.withService<IUserTaskService>()
            ragSearchService = rpcClient.withService<IRagSearchService>()
            taskSchedulingService = rpcClient.withService<ITaskSchedulingService>()
            agentOrchestratorService = rpcClient.withService<IAgentOrchestratorService>()
            errorLogService = rpcClient.withService<IErrorLogService>()
            gitConfigurationService = rpcClient.withService<IGitConfigurationService>()
            pendingTaskService = rpcClient.withService<IPendingTaskService>()
            connectionService = rpcClient.withService<IConnectionService>()
            notificationService = rpcClient.withService<INotificationService>()
            bugTrackerSetupService = rpcClient.withService<IBugTrackerSetupService>()
            integrationSettingsService = rpcClient.withService<IIntegrationSettingsService>()
            codingAgentSettingsService = rpcClient.withService<ICodingAgentSettingsService>()
            whisperSettingsService = rpcClient.withService<IWhisperSettingsService>()
            pollingIntervalService = rpcClient.withService<IPollingIntervalService>()
            projectGroupService = rpcClient.withService<IProjectGroupService>()
            environmentService = rpcClient.withService<IEnvironmentService>()
            meetingService = rpcClient.withService<IMeetingService>()
            transcriptCorrectionService = rpcClient.withService<ITranscriptCorrectionService>()
            deviceTokenService = rpcClient.withService<IDeviceTokenService>()
        }
    }

    /**
     * Access to reconnection handler for repositories.
     */
    val reconnectHandler =
        object : UiRpcReconnectHandler {
            override suspend fun reconnect() {
                this@NetworkModule.reconnect()
            }
        }
}
