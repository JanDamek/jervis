package com.jervis.di

import com.jervis.api.SecurityConstants
import com.jervis.service.agent.IAgentOrchestratorService
import com.jervis.service.agent.IAgentQuestionService
import com.jervis.service.agent.IAutoResponseSettingsService
import com.jervis.service.chat.IChatService
import com.jervis.service.client.IClientService
import com.jervis.service.connection.IConnectionService
import com.jervis.service.connection.IPollingIntervalService
import com.jervis.service.environment.IEnvironmentResourceService
import com.jervis.service.environment.IEnvironmentService
import com.jervis.service.error.IErrorLogService
import com.jervis.service.git.IGitConfigurationService
import com.jervis.service.git.IGpgCertificateService
import com.jervis.service.guidelines.IGuidelinesService
import com.jervis.service.kb.IKbDocumentService
import com.jervis.service.meeting.IJobLogsService
import com.jervis.service.meeting.IMeetingHelperService
import com.jervis.service.meeting.IMeetingService
import com.jervis.service.meeting.ISpeakerService
import com.jervis.service.meeting.ITranscriptCorrectionService
import com.jervis.service.notification.IDeviceTokenService
import com.jervis.service.notification.INotificationService
import com.jervis.service.preferences.IOpenRouterSettingsService
import com.jervis.service.preferences.ISystemConfigService
import com.jervis.service.project.IClientProjectLinkService
import com.jervis.service.project.IProjectService
import com.jervis.service.projectgroup.IProjectGroupService
import com.jervis.service.rag.IRagSearchService
import com.jervis.service.task.IIndexingQueueService
import com.jervis.service.task.IPendingTaskService
import com.jervis.service.task.ITaskGraphService
import com.jervis.service.task.ITaskSchedulingService
import com.jervis.service.task.IUserTaskService
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
            gpgCertificateService = rpcClient.withService<IGpgCertificateService>(),
            pollingIntervalService = rpcClient.withService<IPollingIntervalService>(),
            projectGroupService = rpcClient.withService<IProjectGroupService>(),
            environmentService = rpcClient.withService<IEnvironmentService>(),
            meetingService = rpcClient.withService<IMeetingService>(),
            transcriptCorrectionService = rpcClient.withService<ITranscriptCorrectionService>(),
            deviceTokenService = rpcClient.withService<IDeviceTokenService>(),
            indexingQueueService = rpcClient.withService<IIndexingQueueService>(),
            environmentResourceService = rpcClient.withService<IEnvironmentResourceService>(),
            systemConfigService = rpcClient.withService<ISystemConfigService>(),
            chatService = rpcClient.withService<IChatService>(),
            guidelinesService = rpcClient.withService<IGuidelinesService>(),
            kbDocumentService = rpcClient.withService<IKbDocumentService>(),
            openRouterSettingsService = rpcClient.withService<IOpenRouterSettingsService>(),
            speakerService = rpcClient.withService<ISpeakerService>(),
            meetingHelperService = rpcClient.withService<IMeetingHelperService>(),
            taskGraphService = rpcClient.withService<ITaskGraphService>(),
            jobLogsService = rpcClient.withService<IJobLogsService>(),
            agentQuestionService = rpcClient.withService<IAgentQuestionService>(),
            autoResponseSettingsService = rpcClient.withService<IAutoResponseSettingsService>(),
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
        val gpgCertificateService: IGpgCertificateService,
        val pollingIntervalService: IPollingIntervalService,
        val projectGroupService: IProjectGroupService,
        val environmentService: IEnvironmentService,
        val meetingService: IMeetingService,
        val transcriptCorrectionService: ITranscriptCorrectionService,
        val deviceTokenService: IDeviceTokenService,
        val indexingQueueService: IIndexingQueueService,
        val environmentResourceService: IEnvironmentResourceService,
        val systemConfigService: ISystemConfigService,
        val chatService: IChatService,
        val guidelinesService: IGuidelinesService,
        val kbDocumentService: IKbDocumentService,
        val openRouterSettingsService: IOpenRouterSettingsService,
        val speakerService: ISpeakerService,
        val meetingHelperService: IMeetingHelperService,
        val taskGraphService: ITaskGraphService,
        val jobLogsService: IJobLogsService,
        val agentQuestionService: IAgentQuestionService,
        val autoResponseSettingsService: IAutoResponseSettingsService,
    ) {
        /** Number of service stubs in this container. */
        fun countServices(): Int = 32

        override fun toString() = "Services(${countServices()} stubs)"
    }
}
