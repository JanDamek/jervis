package com.jervis.di

import com.jervis.api.SecurityConstants
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IBugTrackerSetupService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.ICodingAgentSettingsService
import com.jervis.service.IConnectionService
import com.jervis.service.IDeviceTokenService
import com.jervis.service.IIndexingQueueService
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
 * Platform-specific HTTP client creation with SSL configuration
 */
expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Stateless factory for HTTP clients, RPC clients, and service stubs.
 *
 * Connection lifecycle is managed by [RpcConnectionManager] — this object
 * only creates instances without storing mutable state.
 */
object NetworkModule {

    /**
     * Create HTTP client with common configuration.
     * Supports self-signed certificates for all platforms.
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

    /**
     * Create a KtorRpcClient that opens a WebSocket connection to the server's /rpc endpoint.
     */
    fun createRpcClient(
        baseUrl: String,
        httpClient: HttpClient,
    ): KtorRpcClient {
        val cleanBaseUrl = baseUrl.trimEnd('/')

        // Convert HTTP(S) URLs to WebSocket URLs and append /rpc path
        val rpcUrl = "${cleanBaseUrl}/rpc"
            .replace("https://", "wss://")
            .replace("http://", "ws://")

        val parsedUrl = io.ktor.http.Url(rpcUrl)

        return httpClient.rpc {
            url {
                protocol = parsedUrl.protocol
                host = parsedUrl.host
                port = if (parsedUrl.specifiedPort == 0) parsedUrl.protocol.defaultPort else parsedUrl.specifiedPort
                encodedPath = parsedUrl.encodedPath  // Use the /rpc path from parsed URL
            }
            // Serialization already configured via installKrpc in HttpClient
        }
    }

    /**
     * Create all service stubs from an RPC client.
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
            notificationService = rpcClient.withService<INotificationService>(),
            bugTrackerSetupService = rpcClient.withService<IBugTrackerSetupService>(),
            integrationSettingsService = rpcClient.withService<IIntegrationSettingsService>(),
            codingAgentSettingsService = rpcClient.withService<ICodingAgentSettingsService>(),
            whisperSettingsService = rpcClient.withService<IWhisperSettingsService>(),
            pollingIntervalService = rpcClient.withService<IPollingIntervalService>(),
            projectGroupService = rpcClient.withService<IProjectGroupService>(),
            environmentService = rpcClient.withService<IEnvironmentService>(),
            meetingService = rpcClient.withService<IMeetingService>(),
            transcriptCorrectionService = rpcClient.withService<ITranscriptCorrectionService>(),
            deviceTokenService = rpcClient.withService<IDeviceTokenService>(),
            indexingQueueService = rpcClient.withService<IIndexingQueueService>(),
        )

    /**
     * Immutable container for all service stubs.
     * A new instance is created on each reconnect by [RpcConnectionManager].
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
        val notificationService: INotificationService,
        val bugTrackerSetupService: IBugTrackerSetupService,
        val integrationSettingsService: IIntegrationSettingsService,
        val codingAgentSettingsService: ICodingAgentSettingsService,
        val whisperSettingsService: IWhisperSettingsService,
        val pollingIntervalService: IPollingIntervalService,
        val projectGroupService: IProjectGroupService,
        val environmentService: IEnvironmentService,
        val meetingService: IMeetingService,
        val transcriptCorrectionService: ITranscriptCorrectionService,
        val deviceTokenService: IDeviceTokenService,
        val indexingQueueService: IIndexingQueueService,
    )
}
