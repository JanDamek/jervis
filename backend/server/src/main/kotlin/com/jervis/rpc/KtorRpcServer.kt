package com.jervis.rpc

import com.jervis.common.types.ConnectionId
import com.jervis.dto.chat.ChatResponseDto
import com.jervis.dto.chat.ChatResponseType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import com.jervis.rpc.internal.installInternalEnvironmentApi
import com.jervis.rpc.internal.installInternalOpenRouterApi
import com.jervis.rpc.internal.installInternalProjectManagementApi
import com.jervis.rpc.internal.installInternalMeetingVideoApi
import com.jervis.rpc.internal.installInternalMergeRequestApi
import com.jervis.rpc.internal.installInternalBugTrackerApi
import com.jervis.rpc.internal.installInternalO365CapabilitiesApi
import com.jervis.rpc.internal.installInternalO365NotifyApi
import com.jervis.rpc.internal.installInternalO365SessionApi
import com.jervis.rpc.internal.installInternalWhatsAppSessionApi
import com.jervis.rpc.internal.installInternalWhatsAppCapabilitiesApi
import com.jervis.rpc.internal.installInternalTaskApi
import com.jervis.agent.AgentOrchestratorRpcImpl
import com.jervis.agent.AgentQuestionRpcImpl
import com.jervis.agent.AutoResponseSettingsRpcImpl
import com.jervis.client.ClientRpcImpl
import com.jervis.connection.ConnectionRpcImpl
import com.jervis.connection.PollingIntervalRpcImpl
import com.jervis.environment.EnvironmentRpcImpl
import com.jervis.environment.EnvironmentResourceRpcImpl
import com.jervis.git.rpc.GitConfigurationRpcImpl
import com.jervis.git.rpc.GpgCertificateRpcImpl
import com.jervis.git.rpc.JobLogsRpcImpl
import com.jervis.guidelines.GuidelinesRpcImpl
import com.jervis.meeting.installMeetingHelperApi
import com.jervis.rpc.internal.installInternalFinanceApi
import com.jervis.rpc.internal.installInternalAttachmentApi
import com.jervis.meeting.installWatchMeetingApi
import com.jervis.preferences.DeviceTokenRpcImpl
import com.jervis.preferences.SystemConfigRpcImpl
import com.jervis.project.ClientProjectLinkRpcImpl
import com.jervis.project.ProjectRpcImpl
import com.jervis.projectgroup.ProjectGroupRpcImpl
import com.jervis.task.IndexingQueueRpcImpl
import com.jervis.task.PendingTaskRpcImpl
import com.jervis.task.TaskGraphRpcImpl
import com.jervis.task.TaskSchedulingRpcImpl
import com.jervis.task.UserTaskRpcImpl
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
@OptIn(ExperimentalSerializationApi::class)
class KtorRpcServer(
    private val clientRpcImpl: ClientRpcImpl,
    private val projectRpcImpl: ProjectRpcImpl,
    private val userTaskRpcImpl: UserTaskRpcImpl,
    private val taskSchedulingRpcImpl: TaskSchedulingRpcImpl,
    private val agentOrchestratorRpcImpl: AgentOrchestratorRpcImpl,
    private val errorLogRpcImpl: ErrorLogRpcImpl,
    private val connectionRpcImpl: ConnectionRpcImpl,
    private val gitConfigurationRpcImpl: GitConfigurationRpcImpl,
    private val clientProjectLinkRpcImpl: ClientProjectLinkRpcImpl,
    private val pendingTaskRpcImpl: PendingTaskRpcImpl,
    private val notificationRpcImpl: NotificationRpcImpl,
    private val pollingIntervalRpcImpl: PollingIntervalRpcImpl,
    private val meetingRpcImpl: com.jervis.meeting.MeetingRpcImpl,
    private val transcriptCorrectionRpcImpl: com.jervis.meeting.TranscriptCorrectionRpcImpl,
    private val deviceTokenRpcImpl: DeviceTokenRpcImpl,
    private val deviceTokenRepository: com.jervis.preferences.DeviceTokenRepository,
    private val indexingQueueRpcImpl: IndexingQueueRpcImpl,
    private val projectGroupRpcImpl: ProjectGroupRpcImpl,
    private val environmentRpcImpl: EnvironmentRpcImpl,
    private val environmentResourceRpcImpl: EnvironmentResourceRpcImpl,
    private val gpgCertificateRpcImpl: GpgCertificateRpcImpl,
    private val environmentResourceService: com.jervis.environment.EnvironmentResourceService,
    private val environmentService: com.jervis.environment.EnvironmentService,
    private val environmentK8sService: com.jervis.environment.EnvironmentK8sService,
    private val orchestratorWorkflowTracker: com.jervis.agent.OrchestratorWorkflowTracker,
    private val orchestratorStatusHandler: com.jervis.agent.OrchestratorStatusHandler,
    private val oauth2Service: com.jervis.infrastructure.oauth2.OAuth2Service,
    private val taskRepository: com.jervis.task.TaskRepository,
    private val taskService: com.jervis.task.TaskService,
    private val systemConfigRpcImpl: SystemConfigRpcImpl,
    private val userTaskService: com.jervis.task.UserTaskService,
    private val backgroundEngine: com.jervis.task.BackgroundEngine,
    private val chatRpcImpl: com.jervis.chat.ChatRpcImpl,
    private val guidelinesRpcImpl: GuidelinesRpcImpl,
    private val kbDocumentRpcImpl: KbDocumentRpcImpl,
    private val openRouterSettingsRpcImpl: OpenRouterSettingsRpcImpl,
    private val speakerRpcImpl: com.jervis.meeting.SpeakerRpcImpl,
    private val taskGraphRpcImpl: TaskGraphRpcImpl,
    private val jobLogsRpcImpl: JobLogsRpcImpl,
    private val agentQuestionRpcImpl: AgentQuestionRpcImpl,
    private val autoResponseSettingsRpcImpl: AutoResponseSettingsRpcImpl,
    private val guidelinesService: com.jervis.guidelines.GuidelinesService,
    private val filteringRulesService: com.jervis.filtering.FilteringRulesService,
    private val chatService: com.jervis.chat.ChatService,
    private val chatMessageService: com.jervis.chat.ChatMessageService,
    // Dependencies for internal routing modules (injected, used by install*Api extensions)
    private val clientService: com.jervis.client.ClientService,
    private val projectService: com.jervis.project.ProjectService,
    private val connectionService: com.jervis.connection.ConnectionService,
    private val gitRepoCreationService: com.jervis.git.GitRepositoryCreationService,
    private val projectTemplateService: com.jervis.project.ProjectTemplateService,
    private val applicationEventPublisher: org.springframework.context.ApplicationEventPublisher,
    private val gitHubClient: com.jervis.git.client.GitHubClient,
    private val gitLabClient: com.jervis.git.client.GitLabClient,
    private val reviewLanguageResolver: com.jervis.infrastructure.llm.ReviewLanguageResolver,
    private val bugTrackerService: com.jervis.bugtracker.BugTrackerService,
    private val whisperRestClient: com.jervis.meeting.WhisperRestClient,
    private val whisperProperties: com.jervis.infrastructure.config.properties.WhisperProperties,
    private val ttsProperties: com.jervis.infrastructure.config.properties.TtsProperties,
    private val meetingHelperService: com.jervis.meeting.MeetingHelperService,
    private val financialRpcImpl: com.jervis.finance.FinancialRpcImpl,
    private val financialService: com.jervis.finance.FinancialService,
    private val urgencyConfigRpcImpl: com.jervis.urgency.UrgencyConfigRpcImpl,
    private val timeTrackingRpcImpl: com.jervis.timetracking.TimeTrackingRpcImpl,
    private val timeTrackingService: com.jervis.timetracking.TimeTrackingService,
    private val proactiveScheduler: com.jervis.proactive.ProactiveScheduler,
    private val emailMessageIndexRepository: com.jervis.email.EmailMessageIndexRepository,
    private val directoryStructureService: com.jervis.infrastructure.storage.DirectoryStructureService,
    private val connectionRepository: com.jervis.connection.ConnectionRepository,
    private val o365DiscoveredResourceRepository: com.jervis.teams.O365DiscoveredResourceRepository,
    private val fcmPushService: com.jervis.infrastructure.notification.FcmPushService,
    private val apnsPushService: com.jervis.infrastructure.notification.ApnsPushService,
    private val preferenceService: com.jervis.preferences.PreferenceService,
    private val pythonOrchestratorClient: com.jervis.agent.PythonOrchestratorClient,
    private val contractRepository: com.jervis.finance.ContractRepository,
    private val gitRepositoryService: com.jervis.git.service.GitRepositoryService,
    private val meetingAttendApprovalService: com.jervis.meeting.MeetingAttendApprovalService,
    private val pendingTaskService: com.jervis.task.PendingTaskService,
    private val httpClient: io.ktor.client.HttpClient,
    private val meetingRepository: com.jervis.meeting.MeetingRepository,
    private val browserPodMeetingClient: com.jervis.meeting.BrowserPodMeetingClient,
) {
    private val logger = KotlinLogging.logger {}
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @PostConstruct
    fun start() {
        val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 5500
        // Use a separate thread to not block Spring startup
        Thread {
            try {
                server =
                    embeddedServer(Netty, port = port, host = "0.0.0.0") {
                        install(WebSockets) {
                            maxFrameSize = Long.MAX_VALUE
                            // Aligned with client pingInterval = 10s (NetworkModule.kt).
                            // Fast detection of dead clients so the server doesn't keep
                            // stale WebSocket sessions. Max detection window ~15s.
                            pingPeriodMillis = 10_000  // 10 seconds (was 30s)
                            timeoutMillis = 5_000      // 5 seconds (was 15s)
                        }
                        install(ContentNegotiation) {
                            json(Json { ignoreUnknownKeys = true })
                        }

                        routing {
                            // Public API (Watch / voice queries + recording)
                            installVoiceChatApi(taskRepository, taskService, whisperRestClient, whisperProperties, chatService, ttsProperties)
                            installWatchMeetingApi(meetingRpcImpl)
                            installMeetingHelperApi(meetingHelperService)
                            installInternalFinanceApi(financialService, contractRepository)
                            // Time tracking + proactive triggers migrated to gRPC
                            // (jervis.server.Server{TimeTracking,Proactive}Service).
                            installInternalAttachmentApi(emailMessageIndexRepository, directoryStructureService)

                            // Internal REST API modules (Python orchestrator → Kotlin)
                            // Chat context migrated to gRPC (jervis.server.ServerChatContextService).
                            installInternalTaskApi(taskRepository, taskService, userTaskService, preferenceService, pendingTaskService, fcmPushService, apnsPushService, chatRpcImpl)
                            // Guidelines + filter-rules + urgency migrated to gRPC
                            // (jervis.server.Server{Guidelines,FilterRules,Urgency}Service).
                            installInternalEnvironmentApi(environmentService, environmentK8sService)
                            installInternalOpenRouterApi(openRouterSettingsRpcImpl)
                            installInternalProjectManagementApi(clientService, projectService, connectionService, projectTemplateService)
                            // Git repo + workspace ops migrated to gRPC (jervis.server.ServerGitService).
                            installInternalMergeRequestApi(taskRepository, projectService, connectionService, gitHubClient, gitLabClient, reviewLanguageResolver)
                            // Cache invalidation migrated to gRPC (jervis.server.ServerCacheService).
                            // Meeting read surface migrated to gRPC (jervis.server.ServerMeetingsService).
                            // Meeting-attend approval + presence migrated to gRPC
                            // (jervis.server.ServerMeetingAttendService).
                            // Meeting recording bridge migrated to gRPC (jervis.server.ServerMeetingRecordingBridgeService).
                            installInternalMeetingVideoApi(meetingRepository, directoryStructureService)
                            // Meeting-alone leave/stay migrated to gRPC (jervis.server.ServerMeetingAloneService).
                            // Chat-approval broadcast + resolved migrated to gRPC
                            // (jervis.server.ServerChatApprovalService).
                            // Visual capture bridge migrated to gRPC (jervis.server.ServerVisualCaptureService).
                            installInternalBugTrackerApi(projectService, connectionService, gitHubClient, gitLabClient, bugTrackerService)
                            installInternalO365SessionApi(connectionRepository, taskRepository, notificationRpcImpl, fcmPushService, apnsPushService, deviceTokenRepository)
                            installInternalO365CapabilitiesApi(connectionRepository, notificationRpcImpl, fcmPushService, apnsPushService)
                            installInternalO365NotifyApi(connectionRepository, taskRepository, notificationRpcImpl, fcmPushService, apnsPushService, deviceTokenRepository)
                            // O365 discovered resources + user-activity migrated to gRPC
                            // (jervis.server.Server{O365DiscoveredResources,UserActivity}Service).
                            installInternalWhatsAppSessionApi(connectionRepository, taskRepository, notificationRpcImpl, fcmPushService, apnsPushService)
                            installInternalWhatsAppCapabilitiesApi(connectionRepository, notificationRpcImpl, fcmPushService, apnsPushService)
                            // Connection relogin approval migrated to gRPC
                            // (jervis.server.ServerConnectionService).

                            get("/") {
                                call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                            }

                            // OAuth2 routes
                            get("/oauth2/authorize/{connectionId}") {
                                val connectionId =
                                    call.parameters["connectionId"]
                                        ?: return@get call.respondText(
                                            "Missing connectionId",
                                            status = HttpStatusCode.BadRequest,
                                        )

                                try {
                                    val response =
                                        oauth2Service.getAuthorizationUrl(ConnectionId(ObjectId(connectionId)))
                                    call.respondRedirect(response.authorizationUrl)
                                } catch (e: Exception) {
                                    logger.error(e) { "OAuth2 authorization failed" }
                                    call.respondText(
                                        "Authorization failed: ${e.message}",
                                        status = HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/oauth2/callback") {
                                val code = call.parameters["code"]
                                val state = call.parameters["state"]
                                val error = call.parameters["error"]
                                val errorDescription = call.parameters["error_description"]

                                if (error != null) {
                                    return@get call.respondText(
                                        "<html><body><h1>Authorization Failed</h1><p>$error: $errorDescription</p></body></html>",
                                        io.ktor.http.ContentType.Text.Html,
                                        HttpStatusCode.BadRequest,
                                    )
                                }

                                if (code == null || state == null) {
                                    return@get call.respondText(
                                        "<html><body><h1>Invalid Request</h1><p>Missing code or state parameter.</p></body></html>",
                                        io.ktor.http.ContentType.Text.Html,
                                        HttpStatusCode.BadRequest,
                                    )
                                }

                                when (val result = oauth2Service.handleCallback(code, state)) {
                                    is com.jervis.infrastructure.oauth2.OAuth2CallbackResult.Success -> {
                                        call.respondText(
                                            """
                                            <html>
                                            <body>
                                                <h1>Authorization Successful!</h1>
                                                <p>You can now close this window and return to the application.</p>
                                                <script>
                                                    setTimeout(function() {
                                                        window.close();
                                                    }, 2000);
                                                </script>
                                            </body>
                                            </html>
                                            """.trimIndent(),
                                            io.ktor.http.ContentType.Text.Html,
                                        )
                                    }

                                    is com.jervis.infrastructure.oauth2.OAuth2CallbackResult.InvalidState -> {
                                        call.respondText(
                                            "<html><body><h1>Invalid State</h1><p>Authorization state not found or expired.</p></body></html>",
                                            io.ktor.http.ContentType.Text.Html,
                                            HttpStatusCode.BadRequest,
                                        )
                                    }

                                    is com.jervis.infrastructure.oauth2.OAuth2CallbackResult.Error -> {
                                        call.respondText(
                                            "<html><body><h1>Authorization Failed</h1><p>${result.message}</p></body></html>",
                                            io.ktor.http.ContentType.Text.Html,
                                            HttpStatusCode.InternalServerError,
                                        )
                                    }
                                }
                            }

                            // Internal endpoint: orchestrator sends correction progress here
                            post("/internal/correction-progress") {
                                try {
                                    val body = call.receive<CorrectionProgressCallback>()
                                    launch {
                                        notificationRpcImpl.emitMeetingCorrectionProgress(
                                            meetingId = body.meetingId,
                                            clientId = body.clientId,
                                            percent = body.percent,
                                            chunksDone = body.chunksDone,
                                            totalChunks = body.totalChunks,
                                            message = body.message,
                                            tokensGenerated = body.tokensGenerated,
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process correction progress callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: orchestrator sends task progress here
                            post("/internal/orchestrator-progress") {
                                try {
                                    val body = call.receive<OrchestratorProgressCallback>()
                                    // Track workflow step for final message
                                    orchestratorWorkflowTracker.addStep(
                                        taskId = body.taskId,
                                        node = body.node,
                                        tools = emptyList(), // Tools extraction not yet implemented
                                    )
                                    launch {
                                        // 1. Persist step to DB for history
                                        try {
                                            val taskId = com.jervis.common.types.TaskId(org.bson.types.ObjectId(body.taskId))
                                            taskService.appendOrchestratorStep(
                                                taskId,
                                                com.jervis.task.OrchestratorStepRecord(
                                                    timestamp = java.time.Instant.now(),
                                                    node = body.node,
                                                    message = body.message,
                                                    goalIndex = body.goalIndex,
                                                    totalGoals = body.totalGoals,
                                                    stepIndex = body.stepIndex,
                                                    totalSteps = body.totalSteps,
                                                ),
                                            )
                                        } catch (e: Exception) {
                                            logger.debug(e) { "Failed to persist orchestrator step for task ${body.taskId}" }
                                        }
                                        // 2. Emit to live event stream for real-time UI
                                        notificationRpcImpl.emitOrchestratorTaskProgress(
                                            taskId = body.taskId,
                                            clientId = body.clientId,
                                            node = body.node,
                                            message = body.message,
                                            percent = body.percent,
                                            goalIndex = body.goalIndex,
                                            totalGoals = body.totalGoals,
                                            stepIndex = body.stepIndex,
                                            totalSteps = body.totalSteps,
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process orchestrator progress callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: memory graph changed — triggers UI refresh
                            post("/internal/memory-graph-changed") {
                                try {
                                    launch {
                                        notificationRpcImpl.emitMemoryGraphChanged()
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.debug(e) { "Failed to process memory-graph-changed" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json)
                                }
                            }

                            // Internal endpoint: orchestrator sends status changes here (done/error/interrupted)
                            // Handles BOTH UI notification (via NotificationRpc) AND task state transition (via StatusHandler)
                            post("/internal/orchestrator-status") {
                                try {
                                    val body = call.receive<OrchestratorStatusCallback>()
                                    launch {
                                        // 1. Broadcast to UI
                                        notificationRpcImpl.emitOrchestratorTaskStatusChange(
                                            taskId = body.taskId,
                                            clientId = body.clientId,
                                            threadId = body.threadId,
                                            status = body.status,
                                            summary = body.summary,
                                            error = body.error,
                                            interruptAction = body.interruptAction,
                                            interruptDescription = body.interruptDescription,
                                            branch = body.branch,
                                            artifacts = body.artifacts,
                                        )
                                        // 2. Handle task state transition (PROCESSING → DONE/ERROR/USER_TASK)
                                        orchestratorStatusHandler.handleStatusChange(
                                            taskId = body.taskId,
                                            status = body.status,
                                            summary = body.summary,
                                            error = body.error,
                                            interruptAction = body.interruptAction,
                                            interruptDescription = body.interruptDescription,
                                            branch = body.branch,
                                            artifacts = body.artifacts,
                                            keepEnvironmentRunning = body.keepEnvironmentRunning,
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process orchestrator status callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: thinking graph update push from background orchestrator
                            post("/internal/thinking-graph-update") {
                                try {
                                    val body = call.receive<ThinkingGraphUpdateCallback>()
                                    launch {
                                        chatRpcImpl.pushThinkingGraphUpdate(
                                            taskId = body.taskId,
                                            taskTitle = body.taskTitle,
                                            graphId = body.graphId,
                                            status = body.status,
                                            message = body.message,
                                            metadata = body.metadata ?: emptyMap(),
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process thinking graph update callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: orchestrator fetches GPG key for agent commit signing
                            get("/internal/gpg-key/{clientId}") {
                                val clientId = call.parameters["clientId"] ?: return@get call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing clientId\"}",
                                    io.ktor.http.ContentType.Application.Json,
                                    HttpStatusCode.BadRequest,
                                )
                                val gpgKeyId = call.request.queryParameters["gpgKeyId"]
                                try {
                                    val keyInfo = gpgCertificateRpcImpl.getActiveKey(clientId, gpgKeyId)
                                    if (keyInfo != null) {
                                        val json = kotlinx.serialization.json.Json.encodeToString(
                                            GpgKeyResponse.serializer(),
                                            GpgKeyResponse(
                                                keyId = keyInfo.keyId,
                                                userName = keyInfo.userName,
                                                userEmail = keyInfo.userEmail,
                                                privateKeyArmored = keyInfo.privateKeyArmored,
                                                passphrase = keyInfo.passphrase,
                                            ),
                                        )
                                        call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                    } else {
                                        call.respondText(
                                            "{\"ok\":true,\"key\":null}",
                                            io.ktor.http.ContentType.Application.Json,
                                        )
                                    }
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to fetch GPG key for client $clientId" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // --- Internal endpoint: GPU idle notification from Ollama Router ---
                            post("/internal/gpu-idle") {
                                try {
                                    backgroundEngine.onGpuIdle()
                                    call.respondText(
                                        "{\"ok\":true}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to handle GPU idle notification" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // --- Internal endpoints: environment resource inspection (MCP server calls these) ---

                            get("/internal/environment/{ns}/resources") {
                                val ns = call.parameters["ns"] ?: return@get call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing namespace\"}",
                                    io.ktor.http.ContentType.Application.Json,
                                    HttpStatusCode.BadRequest,
                                )
                                val type = call.request.queryParameters["type"] ?: "all"
                                try {
                                    val resources = environmentResourceService.listResources(ns, type)
                                    val jsonStr = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.json.JsonElement.serializer(),
                                        toJsonElement(mapOf("ok" to true, "data" to resources)),
                                    )
                                    call.respondText(jsonStr, io.ktor.http.ContentType.Application.Json)
                                } catch (e: IllegalStateException) {
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.Forbidden,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to list resources in $ns" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/environment/{ns}/pods/{name}/logs") {
                                val ns = call.parameters["ns"] ?: return@get call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                val name = call.parameters["name"] ?: return@get call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 100
                                try {
                                    val logs = environmentResourceService.getPodLogs(ns, name, tail)
                                    call.respondText(logs, io.ktor.http.ContentType.Text.Plain)
                                } catch (e: IllegalStateException) {
                                    call.respondText(
                                        "Access denied: ${e.message}",
                                        io.ktor.http.ContentType.Text.Plain,
                                        HttpStatusCode.Forbidden,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to get pod logs for $name in $ns" }
                                    call.respondText(
                                        "Error: ${e.message}",
                                        io.ktor.http.ContentType.Text.Plain,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/environment/{ns}/deployments/{name}") {
                                val ns = call.parameters["ns"] ?: return@get call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                val name = call.parameters["name"] ?: return@get call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val details = environmentResourceService.getDeploymentDetails(ns, name)
                                    val jsonStr = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.json.JsonElement.serializer(),
                                        toJsonElement(mapOf("ok" to true, "data" to details)),
                                    )
                                    call.respondText(jsonStr, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to get deployment $name in $ns" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/environment/{ns}/deployments/{name}/scale") {
                                val ns = call.parameters["ns"] ?: return@post call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                val name = call.parameters["name"] ?: return@post call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val body = call.receive<ScaleRequest>()
                                    environmentResourceService.scaleDeployment(ns, name, body.replicas)
                                    call.respondText(
                                        "{\"ok\":true,\"message\":\"Scaled $name to ${body.replicas} replicas\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to scale $name in $ns" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/environment/{ns}/deployments/{name}/restart") {
                                val ns = call.parameters["ns"] ?: return@post call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                val name = call.parameters["name"] ?: return@post call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    environmentResourceService.restartDeployment(ns, name)
                                    call.respondText(
                                        "{\"ok\":true,\"message\":\"Restart triggered for $name\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to restart $name in $ns" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/environment/{ns}/status") {
                                val ns = call.parameters["ns"] ?: return@get call.respondText(
                                    "{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val status = environmentResourceService.getNamespaceStatus(ns)
                                    val jsonStr = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.json.JsonElement.serializer(),
                                        toJsonElement(mapOf("ok" to true, "data" to status)),
                                    )
                                    call.respondText(jsonStr, io.ktor.http.ContentType.Application.Json)
                                } catch (e: IllegalStateException) {
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.Forbidden,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to get namespace status for $ns" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: orchestrator creates tracker issues
                            post("/internal/tracker/create-issue") {
                                try {
                                    val body = call.receive<TrackerCreateIssueRequest>()
                                    // TODO Phase 2: delegate to provider service via ProviderRegistry
                                    logger.info { "TRACKER_CREATE: title='${body.title}' project=${body.projectId}" }
                                    call.respondText(
                                        "{\"ok\":true,\"message\":\"Issue creation placeholder\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to create tracker issue" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: orchestrator updates tracker issues
                            post("/internal/tracker/update-issue") {
                                try {
                                    val body = call.receive<TrackerUpdateIssueRequest>()
                                    // TODO Phase 2: delegate to provider service via ProviderRegistry
                                    logger.info { "TRACKER_UPDATE: issue=${body.issueKey} project=${body.projectId}" }
                                    call.respondText(
                                        "{\"ok\":true,\"message\":\"Issue update placeholder\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to update tracker issue" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: AgentTaskWatcher fetches tasks waiting for agent
                            get("/internal/tasks/by-state") {
                                val stateStr = call.request.queryParameters["state"]
                                    ?: return@get call.respondText(
                                        "[]", io.ktor.http.ContentType.Application.Json,
                                    )
                                try {
                                    val state = com.jervis.dto.task.TaskStateEnum.valueOf(stateStr)
                                    val tasks = mutableListOf<Map<String, Any?>>()
                                    taskRepository.findByStateOrderByCreatedAtAsc(state).collect { task ->
                                        tasks.add(
                                            mapOf(
                                                "id" to task.id.toString(),
                                                "agentJobName" to task.agentJobName,
                                                "orchestratorThreadId" to task.orchestratorThreadId,
                                                "agentJobStartedAt" to task.agentJobStartedAt?.toString(),
                                                "sourceUrn" to task.sourceUrn.value,
                                                "agentJobWorkspacePath" to task.agentJobWorkspacePath,
                                                "agentJobAgentType" to task.agentJobAgentType,
                                                "taskName" to task.taskName,
                                                "content" to task.content,
                                                "clientId" to task.clientId.toString(),
                                                "projectId" to task.projectId?.toString(),
                                                "mergeRequestUrl" to task.mergeRequestUrl,
                                            ),
                                        )
                                    }
                                    val jsonStr = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.json.JsonElement.serializer(),
                                        toJsonElement(tasks),
                                    )
                                    call.respondText(jsonStr, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to fetch tasks by state: $stateStr" }
                                    call.respondText(
                                        "[]", io.ktor.http.ContentType.Application.Json,
                                    )
                                }
                            }

                            // Internal endpoint: AgentTaskWatcher reports agent job completion
                            post("/internal/tasks/{taskId}/agent-completed") {
                                val taskIdStr = call.parameters["taskId"]
                                    ?: return@post call.respondText(
                                        "{\"ok\":false}", io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.BadRequest,
                                    )
                                try {
                                    val taskId = com.jervis.common.types.TaskId(org.bson.types.ObjectId(taskIdStr))
                                    val task = taskRepository.getById(taskId)
                                        ?: return@post call.respondText(
                                            "{\"ok\":false,\"error\":\"Task not found\"}",
                                            io.ktor.http.ContentType.Application.Json,
                                            HttpStatusCode.NotFound,
                                        )
                                    // Transition back to PROCESSING (graph will resume)
                                    val updated = task.copy(
                                        state = com.jervis.dto.task.TaskStateEnum.PROCESSING,
                                        agentJobState = "COMPLETED",
                                    )
                                    taskRepository.save(updated)
                                    call.respondText(
                                        "{\"ok\":true}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to update task after agent completion: $taskIdStr" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: orchestrator sets task to CODING with job info
                            post("/internal/tasks/{taskId}/agent-dispatched") {
                                val taskIdStr = call.parameters["taskId"]
                                    ?: return@post call.respondText(
                                        "{\"ok\":false}", io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.BadRequest,
                                    )
                                try {
                                    val body = call.receive<AgentDispatchedRequest>()
                                    val taskId = com.jervis.common.types.TaskId(org.bson.types.ObjectId(taskIdStr))
                                    val task = taskRepository.getById(taskId)
                                        ?: return@post call.respondText(
                                            "{\"ok\":false,\"error\":\"Task not found\"}",
                                            io.ktor.http.ContentType.Application.Json,
                                            HttpStatusCode.NotFound,
                                        )
                                    val updated = task.copy(
                                        state = com.jervis.dto.task.TaskStateEnum.CODING,
                                        agentJobName = body.jobName,
                                        agentJobState = "RUNNING",
                                        agentJobStartedAt = java.time.Instant.now(),
                                        agentJobWorkspacePath = body.workspacePath,
                                        agentJobAgentType = body.agentType,
                                    )
                                    taskRepository.save(updated)
                                    call.respondText(
                                        "{\"ok\":true}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to mark task as agent-dispatched: $taskIdStr" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: Python orchestrator streams LLM tokens (legacy, no-op)
                            post("/internal/streaming-token") {
                                try {
                                    call.receive<StreamingTokenRequest>() // consume body
                                    call.respondText(
                                        "{\"ok\":true}",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.debug(e) { "Failed to emit streaming token" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: KB microservice pushes progress events here (real-time)
                            post("/internal/kb-progress") {
                                try {
                                    val body = call.receive<KbProgressCallback>()
                                    launch {
                                        // 1. Persist step to DB for history
                                        val taskId = com.jervis.common.types.TaskId(org.bson.types.ObjectId(body.taskId))
                                        taskService.appendQualificationStep(
                                            taskId,
                                            com.jervis.task.QualificationStepRecord(
                                                timestamp = java.time.Instant.now(),
                                                step = body.step,
                                                message = body.message,
                                                metadata = (body.metadata ?: emptyMap()) + ("agent" to "simple_qualifier"),
                                            ),
                                        )
                                        // 2. Emit to live event stream for real-time UI
                                        notificationRpcImpl.emitQualificationProgress(
                                            taskId = body.taskId,
                                            clientId = body.clientId,
                                            message = body.message,
                                            step = body.step,
                                            metadata = (body.metadata ?: emptyMap()) + ("agent" to "simple_qualifier"),
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process KB progress callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: KB microservice signals async processing completion
                            post("/internal/kb-done") {
                                try {
                                    val body = call.receive<KbCompletionCallback>()
                                    logger.info { "KB_DONE_CALLBACK: taskId=${body.taskId} status=${body.status}" }

                                    launch {
                                        try {
                                            val taskId = com.jervis.common.types.TaskId(ObjectId(body.taskId))
                                            val task = taskRepository.getById(taskId)

                                            if (task == null) {
                                                logger.error { "KB_DONE_CALLBACK: task not found taskId=${body.taskId}" }
                                                return@launch
                                            }

                                            if (task.state != com.jervis.dto.task.TaskStateEnum.INDEXING) {
                                                logger.warn { "KB_DONE_CALLBACK: task not in INDEXING state taskId=${body.taskId} state=${task.state}" }
                                                return@launch
                                            }

                                            if (body.status == "error") {
                                                val errorMsg = body.error ?: "KB processing failed"
                                                logger.error { "KB_DONE_CALLBACK: KB reported error taskId=${body.taskId} error=$errorMsg" }
                                                taskService.markAsError(task, errorMsg)

                                                // Emit final progress step
                                                taskService.appendQualificationStep(
                                                    taskId,
                                                    com.jervis.task.QualificationStepRecord(
                                                        timestamp = java.time.Instant.now(),
                                                        step = "error",
                                                        message = "KB chyba: $errorMsg",
                                                        metadata = mapOf("agent" to "simple_qualifier"),
                                                    ),
                                                )
                                                notificationRpcImpl.emitQualificationProgress(
                                                    taskId = body.taskId,
                                                    clientId = body.clientId,
                                                    message = "KB chyba: $errorMsg",
                                                    step = "error",
                                                    metadata = mapOf("agent" to "simple_qualifier"),
                                                )
                                                return@launch
                                            }

                                            // Parse result and run routing
                                            val r = body.result
                                            if (r == null) {
                                                taskService.markAsError(task, "KB returned done but no result")
                                                return@launch
                                            }

                                            // Save KB extraction outputs (summary + entities) to
                                            // the task document so the Python /qualify agent has them
                                            // as input. kbActionable is forced to false here — KB has
                                            // no authority over actionability.
                                            taskService.saveKbResult(
                                                taskId,
                                                kbSummary = r.summary,
                                                kbEntities = r.entities,
                                                kbActionable = false,
                                            )

                                            // Evaluate user filtering rules. These are USER-configured
                                            // rules ("always urgent from X", "ignore newsletters from Y")
                                            // and are NOT a KB decision — they live in Kotlin and are
                                            // independent of KB output. We still skip the qualifier on
                                            // an explicit IGNORE rule because there is no point asking
                                            // the LLM to re-evaluate something the user said to skip.
                                            // @mention overrides IGNORE.
                                            val filterAction = try {
                                                val sourceType = com.jervis.qualifier.KbDoneRouter.extractSourceType(task.sourceUrn)
                                                filteringRulesService.evaluate(
                                                    sourceType = sourceType,
                                                    subject = r.summary,
                                                    body = task.content,
                                                    labels = r.entities,
                                                )
                                            } catch (e: Exception) {
                                                logger.warn(e) { "KB_DONE_CALLBACK: filter eval failed taskId=${body.taskId} (non-fatal)" }
                                                null
                                            }

                                            if (filterAction == com.jervis.dto.filtering.FilterAction.IGNORE && !task.mentionsJervis) {
                                                taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.DONE)
                                                logger.info { "KB_DONE_CALLBACK: filtered IGNORE taskId=${body.taskId} (user rule)" }
                                                return@launch
                                            }

                                            // 2026-04-11 — KB MUST NOT make routing decisions.
                                            // The previous fast-path
                                            //     if (!r.hasActionableContent && !task.mentionsJervis)
                                            //         { state=DONE; return }
                                            // gave the KB extraction layer authority over
                                            // whether the task gets routed at all. KB has no tools
                                            // for that decision (no kb_search at decision time, no
                                            // user history, no active task awareness) — only the
                                            // Python /qualify agent does. Removed the fast-path.
                                            // Every KB-done callback now ALWAYS dispatches to /qualify,
                                            // and the qualifier itself decides DONE/QUEUED/USER_TASK/...
                                            //
                                            // The KB-supplied fields (hasActionableContent, urgency,
                                            // suggestedActions, ...) are passed to /qualify as HINTS
                                            // only — never as authoritative routing signals. They
                                            // will be removed from the wire format in a follow-up
                                            // refactor (see memory: architecture-kb-no-qualification.md).

                                            // Always dispatch to Python /qualify — qualifier decides
                                            // DONE / QUEUED / URGENT_ALERT / ESCALATE / DECOMPOSE.
                                            try {
                                                val qualifyRequest = com.jervis.agent.QualifyRequestDto(
                                                    taskId = body.taskId,
                                                    clientId = body.clientId,
                                                    sourceUrn = task.sourceUrn.value,
                                                    summary = r.summary,
                                                    entities = r.entities,
                                                    suggestedActions = r.suggestedActions,
                                                    urgency = r.urgency,
                                                    actionType = r.actionType,
                                                    estimatedComplexity = r.estimatedComplexity,
                                                    isAssignedToMe = r.isAssignedToMe,
                                                    hasFutureDeadline = r.hasFutureDeadline,
                                                    suggestedDeadline = r.suggestedDeadline,
                                                    suggestedAgent = r.suggestedAgent,
                                                    affectedFiles = r.affectedFiles,
                                                    relatedKbNodes = r.relatedKbNodes,
                                                    hasAttachments = task.hasAttachments,
                                                    attachmentCount = task.attachmentCount,
                                                    attachments = task.attachments.mapIndexed { idx, att ->
                                                        com.jervis.agent.QualifyAttachmentDto(
                                                            filename = att.filename,
                                                            contentType = att.mimeType,
                                                            size = att.sizeBytes,
                                                            index = idx,
                                                        )
                                                    },
                                                    content = task.content,
                                                    mentionsJervis = task.mentionsJervis,
                                                )
                                                val qualifyResponse = pythonOrchestratorClient.qualify(qualifyRequest)
                                                if (qualifyResponse != null) {
                                                    logger.info {
                                                        "KB_DONE_CALLBACK: taskId=${body.taskId} → dispatched to /qualify " +
                                                            "threadId=${qualifyResponse.threadId}"
                                                    }
                                                } else {
                                                    // Fallback: if /qualify fails, go straight to QUEUED
                                                    taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                                    logger.warn {
                                                        "KB_DONE_CALLBACK: taskId=${body.taskId} → /qualify failed, fallback to QUEUED"
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // Fallback: if /qualify fails, go straight to QUEUED
                                                logger.warn(e) {
                                                    "KB_DONE_CALLBACK: taskId=${body.taskId} → /qualify dispatch failed, fallback to QUEUED"
                                                }
                                                taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                            }
                                        } catch (e: Exception) {
                                            logger.error(e) { "KB_DONE_CALLBACK: failed to process result taskId=${body.taskId}" }
                                        }
                                    }

                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process KB completion callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Internal endpoint: Python qualification agent reports result
                            post("/internal/qualification-done") {
                                try {
                                    val body = call.receive<QualificationDoneCallback>()
                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} decision=${body.decision} priority=${body.priorityScore}" }

                                    launch {
                                        try {
                                            val taskId = com.jervis.common.types.TaskId(ObjectId(body.taskId))
                                            val task = taskRepository.getById(taskId)

                                            if (task == null) {
                                                logger.error { "QUALIFICATION_DONE: task not found taskId=${body.taskId}" }
                                                return@launch
                                            }

                                            // Save qualification results to task
                                            taskService.saveQualificationResult(
                                                taskId = taskId,
                                                priorityScore = body.priorityScore,
                                                priorityReason = body.reason,
                                                actionType = body.actionType,
                                                estimatedComplexity = body.estimatedComplexity,
                                                qualifierContext = body.contextSummary + "\n\n" + body.suggestedApproach,
                                            )

                                            // Save qualifier-generated summary to task.
                                            // Only use contextSummary (actual task summary) — NEVER use reason
                                            // as summary. Reason is for errors/decisions ("Qualification error: ...")
                                            // and should stay in errorMessage/priorityReason, not hijack the summary
                                            // field which sidebar uses as task display name.
                                            val qualSummary = body.contextSummary.takeIf { it.isNotBlank() }
                                            if (qualSummary != null) {
                                                taskService.saveSummary(taskId, qualSummary)
                                            }

                                            // Phase 3: clear needsQualification flag — the qualifier
                                            // has produced its decision and the task is no longer
                                            // pending re-evaluation by the RequalificationLoop.
                                            taskService.clearNeedsQualification(taskId)

                                            when (body.decision) {
                                                "DONE" -> {
                                                    taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.DONE)
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → DONE (${body.reason})" }
                                                }
                                                "ESCALATE" -> {
                                                    // Phase 3: qualifier wants the user to make a call.
                                                    // Transition the existing task to USER_TASK with the
                                                    // qualifier's question/context — never create a wrapper.
                                                    taskService.transitionToUserTask(
                                                        task = task,
                                                        pendingQuestion = body.pendingUserQuestion ?: body.reason,
                                                        questionContext = body.userQuestionContext ?: body.contextSummary,
                                                    )
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → USER_TASK (ESCALATE: ${body.reason})" }
                                                }
                                                "DECOMPOSE" -> {
                                                    // Phase 3: qualifier broke the work into sub-tasks.
                                                    // Create child TaskDocuments, set parent → BLOCKED
                                                    // with blockedByTaskIds = child IDs. When all
                                                    // children DONE, TaskService.unblockChildrenOfParent
                                                    // walks up and flags the parent for re-qualification.
                                                    if (body.subTasks.isEmpty()) {
                                                        logger.warn { "QUALIFICATION_DONE: DECOMPOSE with empty sub_tasks taskId=${body.taskId}, falling back to QUEUED" }
                                                        taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                                    } else {
                                                        taskService.decomposeTask(task, body.subTasks)
                                                        logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → BLOCKED (DECOMPOSE: ${body.subTasks.size} children)" }
                                                    }
                                                }
                                                "REOPEN" -> {
                                                    // Qualifier found a related DONE task that should
                                                    // be reopened because new evidence arrived.
                                                    val targetId = body.targetTaskId
                                                    if (targetId.isNullOrBlank()) {
                                                        logger.warn { "QUALIFICATION_DONE: REOPEN with no target_task_id taskId=${body.taskId}, falling back to QUEUED" }
                                                        taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                                    } else {
                                                        pendingTaskService.reopen(targetId, "Reopened by qualifier: ${body.reason}")
                                                        taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.DONE)
                                                        logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → REOPEN target=$targetId (${body.reason})" }
                                                    }
                                                }
                                                "URGENT_ALERT" -> {
                                                    taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                                    // Push urgent alert
                                                    if (chatRpcImpl.isUserOnline()) {
                                                        try {
                                                            chatRpcImpl.pushUrgentAlert(
                                                                sourceUrn = task.sourceUrn.value,
                                                                taskId = task.id.toString(),
                                                                taskName = task.taskName,
                                                                summary = body.alertMessage ?: body.reason,
                                                                suggestedAction = null,
                                                                taskContent = task.content,
                                                            )
                                                        } catch (e: Exception) {
                                                            logger.warn(e) { "QUALIFICATION_DONE: failed to push urgent alert" }
                                                        }
                                                    }
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → QUEUED (URGENT)" }
                                                }
                                                else -> {
                                                    // QUEUED (default)
                                                    taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.QUEUED)
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → QUEUED (priority=${body.priorityScore})" }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            logger.error(e) { "QUALIFICATION_DONE: failed to process taskId=${body.taskId}" }
                                        }
                                    }

                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process qualification-done callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // --- Chat internal endpoints (Python → Kotlin) ---

                            // GPU reservation: Python registers start/end of chat processing
                            post("/internal/foreground-start") {
                                try {
                                    backgroundEngine.reserveGpuForChat()
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to reserve GPU for chat" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }
                            post("/internal/reserve-gpu-for-chat") {
                                try {
                                    backgroundEngine.reserveGpuForChat()
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to reserve GPU for chat" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }

                            post("/internal/foreground-end") {
                                try {
                                    backgroundEngine.releaseGpuForChat()
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to release GPU for chat" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }
                            post("/internal/release-gpu-for-chat") {
                                try {
                                    backgroundEngine.releaseGpuForChat()
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to release GPU for chat" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }

                            // Chat on cloud: GPU remains available for background tasks
                            post("/internal/chat-on-cloud") {
                                try {
                                    backgroundEngine.reportChatOnCloud()
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to report chat-on-cloud" }
                                    call.respondText("{\"ok\":false}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }

                            // Active chat topics for qualification agent
                            get("/internal/active-chat-topics") {
                                try {
                                    val session = chatService.getOrCreateActiveSession()
                                    val messages = chatMessageService.getLastMessages(session.id, 10)
                                    val topicsJson = kotlinx.serialization.json.buildJsonObject {
                                        put("clientId", kotlinx.serialization.json.JsonPrimitive(session.lastClientId ?: ""))
                                        put("projectId", kotlinx.serialization.json.JsonPrimitive(session.lastProjectId ?: ""))
                                        put("topics", kotlinx.serialization.json.buildJsonArray {
                                            messages
                                                .filter { it.role == com.jervis.chat.MessageRole.USER || it.role == com.jervis.chat.MessageRole.ASSISTANT }
                                                .forEach { msg ->
                                                    add(kotlinx.serialization.json.buildJsonObject {
                                                        put("role", kotlinx.serialization.json.JsonPrimitive(msg.role.name.lowercase()))
                                                        put("content", kotlinx.serialization.json.JsonPrimitive(msg.content))
                                                    })
                                                }
                                        })
                                    }
                                    call.respondText(topicsJson.toString(), io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to get active chat topics" }
                                    call.respondText("""{"topics":[],"clientId":"","projectId":""}""", io.ktor.http.ContentType.Application.Json)
                                }
                            }

                            // Create background task from chat
                            post("/internal/create-background-task") {
                                try {
                                    val body = call.receive<CreateBackgroundTaskRequest>()
                                    val clientId = com.jervis.common.types.ClientId.fromString(body.clientId)
                                    val projectId = body.projectId?.let { com.jervis.common.types.ProjectId.fromString(it) }
                                    val correlationId = ObjectId().toHexString()

                                    val task = taskService.createTask(
                                        taskType = com.jervis.dto.task.TaskTypeEnum.INSTANT,
                                        content = body.description,
                                        clientId = clientId,
                                        correlationId = correlationId,
                                        sourceUrn = com.jervis.common.types.SourceUrn("chat:background-task"),
                                        projectId = projectId,
                                        state = com.jervis.dto.task.TaskStateEnum.QUEUED,
                                        taskName = body.title,
                                    )

                                    call.respondText(
                                        """{"taskId":"${task.id}","title":"${body.title}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to create background task" }
                                    call.respondText(
                                        """{"error":"${e.message}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Dispatch coding agent from chat
                            post("/internal/dispatch-coding-agent") {
                                try {
                                    val body = call.receive<DispatchCodingAgentRequest>()
                                    val clientId = com.jervis.common.types.ClientId.fromString(body.clientId)
                                    val projectId = com.jervis.common.types.ProjectId.fromString(body.projectId)
                                    val correlationId = ObjectId().toHexString()

                                    val sourceUrn = body.sourceUrn ?: "chat:coding-agent"
                                    var task = taskService.createTask(
                                        taskType = com.jervis.dto.task.TaskTypeEnum.INSTANT,
                                        content = body.taskDescription,
                                        clientId = clientId,
                                        correlationId = correlationId,
                                        sourceUrn = com.jervis.common.types.SourceUrn(sourceUrn),
                                        projectId = projectId,
                                        state = com.jervis.dto.task.TaskStateEnum.QUEUED,
                                        taskName = body.taskDescription.take(100),
                                    )
                                    // Persist review metadata if this is a code-review fix task
                                    if (body.mergeRequestUrl != null) {
                                        task = task.copy(mergeRequestUrl = body.mergeRequestUrl)
                                        taskRepository.save(task)
                                    }

                                    call.respondText(
                                        """{"taskId":"${task.id}","dispatched":true}""",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to dispatch coding agent" }
                                    call.respondText(
                                        """{"error":"${e.message}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Search user tasks
                            get("/internal/user-tasks") {
                                try {
                                    val query = call.request.queryParameters["query"] ?: ""
                                    val maxResults = call.request.queryParameters["maxResults"]?.toIntOrNull() ?: 5

                                    val result = userTaskService.findPagedTasks(
                                        query = query.ifBlank { null },
                                        offset = 0,
                                        limit = maxResults,
                                    )

                                    val tasksJson = result.items.map { task ->
                                        mapOf(
                                            "id" to task.id.toString(),
                                            "title" to (task.sourceUrn?.toString() ?: ""),
                                            "state" to task.state.name,
                                            "question" to (task.pendingUserQuestion ?: ""),
                                            "context" to (task.userQuestionContext ?: ""),
                                            "clientId" to (task.clientId?.toString() ?: ""),
                                        )
                                    }

                                    call.respondText(
                                        Json.encodeToString<List<Map<String, String>>>(tasksJson),
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to search user tasks" }
                                    call.respondText("[]", io.ktor.http.ContentType.Application.Json)
                                }
                            }

                            // Get single user task by ID
                            get("/internal/user-tasks/{taskId}") {
                                try {
                                    val taskIdStr = call.parameters["taskId"] ?: ""
                                    val taskId = com.jervis.common.types.TaskId(ObjectId(taskIdStr))
                                    val task = userTaskService.getTaskByIdOrNull(taskId)

                                    if (task != null) {
                                        val taskMap = mapOf(
                                            "id" to task.id.toString(),
                                            "title" to (task.sourceUrn?.toString() ?: ""),
                                            "state" to task.state.name,
                                            "question" to (task.pendingUserQuestion ?: ""),
                                            "context" to (task.userQuestionContext ?: ""),
                                            "clientId" to (task.clientId?.toString() ?: ""),
                                        )
                                        call.respondText(
                                            Json.encodeToString<Map<String, String>>(taskMap),
                                            io.ktor.http.ContentType.Application.Json,
                                        )
                                    } else {
                                        call.respondText("{}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.NotFound)
                                    }
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to get user task" }
                                    call.respondText("{}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                }
                            }

                            // Respond to user task
                            post("/internal/respond-to-user-task") {
                                try {
                                    val body = call.receive<RespondToUserTaskRequest>()

                                    userTaskRpcImpl.sendToAgent(
                                        taskId = body.taskId,
                                        routingMode = com.jervis.dto.user.TaskRoutingMode.DIRECT_TO_AGENT,
                                        additionalInput = body.response,
                                    )

                                    call.respondText(
                                        """{"ok":true,"taskId":"${body.taskId}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to respond to user task" }
                                    call.respondText(
                                        """{"error":"${e.message}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Dismiss user task(s) — move to DONE without processing
                            post("/internal/dismiss-user-tasks") {
                                try {
                                    val body = call.receive<DismissUserTasksRequest>()
                                    var dismissed = 0
                                    for (taskId in body.taskIds) {
                                        try {
                                            val task = taskRepository.getById(com.jervis.common.types.TaskId(org.bson.types.ObjectId(taskId)))
                                            if (task != null && task.state == com.jervis.dto.task.TaskStateEnum.USER_TASK) {
                                                taskService.updateState(task, com.jervis.dto.task.TaskStateEnum.DONE)
                                                dismissed++
                                            }
                                        } catch (e: Exception) {
                                            logger.warn { "Failed to dismiss task $taskId: ${e.message}" }
                                        }
                                    }
                                    call.respondText(
                                        """{"ok":true,"dismissed":$dismissed}""",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to dismiss user tasks" }
                                    call.respondText(
                                        """{"error":"${e.message}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Classify meeting
                            post("/internal/classify-meeting") {
                                try {
                                    val body = call.receive<ClassifyMeetingRequest>()
                                    val dto = com.jervis.dto.meeting.MeetingClassifyDto(
                                        meetingId = body.meetingId,
                                        clientId = body.clientId,
                                        projectId = body.projectId,
                                        title = body.title,
                                    )
                                    val result = meetingRpcImpl.classifyMeeting(dto)
                                    call.respondText(
                                        """{"ok":true,"meetingId":"${result.id}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to classify meeting" }
                                    call.respondText(
                                        """{"error":"${e.message}"}""",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            // Unclassified meetings moved to gRPC
                            // (jervis.server.ServerMeetingsService.ListUnclassified).

                            rpc("/rpc") {
                                rpcConfig {
                                    serialization {
                                        cbor()
                                    }
                                }

                                registerService<com.jervis.service.client.IClientService> { clientRpcImpl }
                                registerService<com.jervis.service.project.IProjectService> { projectRpcImpl }
                                registerService<com.jervis.service.task.IUserTaskService> { userTaskRpcImpl }
                                registerService<com.jervis.service.task.ITaskSchedulingService> { taskSchedulingRpcImpl }
                                registerService<com.jervis.service.agent.IAgentOrchestratorService> { agentOrchestratorRpcImpl }
                                registerService<com.jervis.service.error.IErrorLogService> { errorLogRpcImpl }
                                registerService<com.jervis.service.connection.IConnectionService> { connectionRpcImpl }
                                registerService<com.jervis.service.git.IGitConfigurationService> { gitConfigurationRpcImpl }
                                registerService<com.jervis.service.project.IClientProjectLinkService> { clientProjectLinkRpcImpl }
                                registerService<com.jervis.service.task.IPendingTaskService> { pendingTaskRpcImpl }
                                registerService<com.jervis.service.notification.INotificationService> { notificationRpcImpl }
                                registerService<com.jervis.service.git.IGpgCertificateService> { gpgCertificateRpcImpl }
                                registerService<com.jervis.service.connection.IPollingIntervalService> { pollingIntervalRpcImpl }
                                registerService<com.jervis.service.meeting.IMeetingService> { meetingRpcImpl }
                                registerService<com.jervis.service.meeting.ITranscriptCorrectionService> { transcriptCorrectionRpcImpl }
                                registerService<com.jervis.service.notification.IDeviceTokenService> { deviceTokenRpcImpl }
                                registerService<com.jervis.service.task.IIndexingQueueService> { indexingQueueRpcImpl }
                                registerService<com.jervis.service.projectgroup.IProjectGroupService> { projectGroupRpcImpl }
                                registerService<com.jervis.service.environment.IEnvironmentService> { environmentRpcImpl }
                                registerService<com.jervis.service.environment.IEnvironmentResourceService> { environmentResourceRpcImpl }
                                registerService<com.jervis.service.preferences.ISystemConfigService> { systemConfigRpcImpl }
                                registerService<com.jervis.service.urgency.IUrgencyConfigRpc> { urgencyConfigRpcImpl }
                                registerService<com.jervis.service.chat.IChatService> { chatRpcImpl }
                                registerService<com.jervis.service.guidelines.IGuidelinesService> { guidelinesRpcImpl }
                                registerService<com.jervis.service.kb.IKbDocumentService> { kbDocumentRpcImpl }
                                registerService<com.jervis.service.preferences.IOpenRouterSettingsService> { openRouterSettingsRpcImpl }
                                registerService<com.jervis.service.meeting.ISpeakerService> { speakerRpcImpl }
                                registerService<com.jervis.service.meeting.IMeetingHelperService> { meetingHelperService }
                                registerService<com.jervis.service.finance.IFinancialService> { financialRpcImpl }
                                registerService<com.jervis.service.task.ITaskGraphService> { taskGraphRpcImpl }
                                registerService<com.jervis.service.meeting.IJobLogsService> { jobLogsRpcImpl }
                                registerService<com.jervis.service.agent.IAgentQuestionService> { agentQuestionRpcImpl }
                                registerService<com.jervis.service.agent.IAutoResponseSettingsService> { autoResponseSettingsRpcImpl }
                                registerService<com.jervis.service.timetracking.ITimeTrackingService> { timeTrackingRpcImpl }
                            }
                        }
                    }
                server?.start(wait = true)
            } catch (e: Exception) {
                logger.error(e) { "Ktor RPC server failed to start or crashed" }
                System.exit(1)
            }
        }.start()
        logger.info { "Ktor RPC server thread started on port $port at /rpc (CBOR) with /health endpoint" }
    }

    @PreDestroy
    fun stop() {
        server?.stop(1000, 2000)
        logger.info { "Ktor RPC server stopped" }
    }
}

@kotlinx.serialization.Serializable
data class CorrectionProgressCallback(
    val meetingId: String,
    val clientId: String,
    val percent: Double,
    val chunksDone: Int,
    val totalChunks: Int,
    val message: String? = null,
    val tokensGenerated: Int = 0,
)

@kotlinx.serialization.Serializable
data class OrchestratorProgressCallback(
    val taskId: String,
    val clientId: String,
    val node: String,
    val message: String,
    val percent: Double = 0.0,
    val goalIndex: Int = 0,
    val totalGoals: Int = 0,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
)

@kotlinx.serialization.Serializable
data class OrchestratorStatusCallback(
    val taskId: String,
    val clientId: String = "",
    val threadId: String,
    val status: String,
    val summary: String? = null,
    val error: String? = null,
    val interruptAction: String? = null,
    val interruptDescription: String? = null,
    val branch: String? = null,
    val artifacts: List<String> = emptyList(),
    val keepEnvironmentRunning: Boolean = false,
)

@kotlinx.serialization.Serializable
data class KbProgressCallback(
    val taskId: String,
    val clientId: String,
    val step: String,
    val message: String,
    val metadata: Map<String, String>? = null,
)

@kotlinx.serialization.Serializable
data class KbCompletionCallback(
    val taskId: String,
    val clientId: String,
    val status: String, // "done" or "error"
    val error: String? = null,
    val result: KbCompletionResult? = null,
)

/**
 * KB ingest completion payload returned by the Python KB service.
 *
 * 2026-04-11 KB-no-qualification refactor:
 *   KB returns ONLY extraction outputs (summary, entities, counts).
 *   The fields below tagged "LEGACY routing hint" remain in the wire
 *   format for backward compatibility with persisted callbacks but
 *   the Kotlin server's qualification flow no longer treats them as
 *   authoritative. The Python /qualify agent (full toolset) is the
 *   single source of truth for routing decisions.
 *
 *   See memory/architecture-kb-no-qualification.md for the rule.
 */
@kotlinx.serialization.Serializable
data class KbCompletionResult(
    val status: String = "success",
    @kotlinx.serialization.SerialName("chunks_count") val chunksCount: Int = 0,
    @kotlinx.serialization.SerialName("nodes_created") val nodesCreated: Int = 0,
    @kotlinx.serialization.SerialName("edges_created") val edgesCreated: Int = 0,
    @kotlinx.serialization.SerialName("attachments_processed") val attachmentsProcessed: Int = 0,
    @kotlinx.serialization.SerialName("attachments_failed") val attachmentsFailed: Int = 0,
    // === Extraction outputs (KB authority) ===
    val summary: String = "",
    val entities: List<String> = emptyList(),
    // === LEGACY routing hints — neutral defaults, do NOT branch on these ===
    val hasActionableContent: Boolean = false,
    val suggestedActions: List<String> = emptyList(),
    val hasFutureDeadline: Boolean = false,
    val suggestedDeadline: String? = null,
    val isAssignedToMe: Boolean = false,
    val urgency: String = "normal",
    @kotlinx.serialization.SerialName("action_type") val actionType: String? = null,
    @kotlinx.serialization.SerialName("estimated_complexity") val estimatedComplexity: String? = null,
    @kotlinx.serialization.SerialName("suggested_agent") val suggestedAgent: String? = null,
    @kotlinx.serialization.SerialName("affected_files") val affectedFiles: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("related_kb_nodes") val relatedKbNodes: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
data class QualificationDoneCallback(
    @kotlinx.serialization.SerialName("task_id") val taskId: String,
    @kotlinx.serialization.SerialName("client_id") val clientId: String,
    /**
     * Phase 3 re-entrant qualifier decisions:
     *   - QUEUED        — ready for orchestrator pickup (default)
     *   - DONE          — terminal, no action needed
     *   - URGENT_ALERT  — push alert + QUEUED
     *   - CONSOLIDATE   — merge with existing topic task, this one becomes DONE
     *   - ESCALATE      — needs user input → state=USER_TASK with question/context
     *   - DECOMPOSE     — break into sub-tasks, parent → BLOCKED until children DONE
     */
    val decision: String = "QUEUED",
    @kotlinx.serialization.SerialName("priority_score") val priorityScore: Int = 5,
    val reason: String = "",
    @kotlinx.serialization.SerialName("alert_message") val alertMessage: String? = null,
    @kotlinx.serialization.SerialName("target_task_id") val targetTaskId: String? = null,
    @kotlinx.serialization.SerialName("context_summary") val contextSummary: String = "",
    @kotlinx.serialization.SerialName("suggested_approach") val suggestedApproach: String = "",
    @kotlinx.serialization.SerialName("action_type") val actionType: String = "",
    @kotlinx.serialization.SerialName("estimated_complexity") val estimatedComplexity: String = "",
    // ESCALATE — populated when decision == "ESCALATE"
    @kotlinx.serialization.SerialName("pending_user_question") val pendingUserQuestion: String? = null,
    @kotlinx.serialization.SerialName("user_question_context") val userQuestionContext: String? = null,
    // DECOMPOSE — populated when decision == "DECOMPOSE"
    @kotlinx.serialization.SerialName("sub_tasks") val subTasks: List<SubTaskRequest> = emptyList(),
)

/**
 * Phase 3: A single sub-task requested by the qualifier when it returns
 * decision=DECOMPOSE. The Kotlin server creates a child TaskDocument for each
 * entry, sets `parentTaskId` to the original task, and populates the parent's
 * `blockedByTaskIds` with the new child IDs. The parent moves to BLOCKED until
 * all children reach DONE, at which point [TaskService.unblockChildrenOfParent]
 * flags it for re-qualification.
 */
@kotlinx.serialization.Serializable
data class SubTaskRequest(
    @kotlinx.serialization.SerialName("task_name") val taskName: String,
    val content: String,
    val phase: String? = null,
    @kotlinx.serialization.SerialName("order_in_phase") val orderInPhase: Int = 0,
)

@kotlinx.serialization.Serializable
data class ThinkingGraphUpdateCallback(
    val taskId: String,
    val taskTitle: String = "",
    val graphId: String = "",
    val status: String,  // "started", "vertex_completed", "completed", "failed"
    val message: String = "",
    val metadata: Map<String, String>? = null,
)

@kotlinx.serialization.Serializable
data class GpgKeyResponse(
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val privateKeyArmored: String,
    val passphrase: String? = null,
)

@kotlinx.serialization.Serializable
data class TrackerCreateIssueRequest(
    val clientId: String,
    val projectId: String? = null,
    val title: String,
    val description: String = "",
    val type: String = "task",
    val parentKey: String? = null,
)

@kotlinx.serialization.Serializable
data class TrackerUpdateIssueRequest(
    val clientId: String,
    val projectId: String? = null,
    val issueKey: String,
    val status: String? = null,
    val comment: String? = null,
)

@kotlinx.serialization.Serializable
data class ScaleRequest(
    val replicas: Int,
)

@kotlinx.serialization.Serializable
data class AgentDispatchedRequest(
    val jobName: String,
    val workspacePath: String? = null,
    val agentType: String? = null,
)

@kotlinx.serialization.Serializable
data class StreamingTokenRequest(
    val taskId: String,
    val clientId: String,
    val projectId: String = "",
    val token: String,
    val messageId: String,
    val isFinal: Boolean = false,
)

// --- Chat internal DTOs (Python → Kotlin) ---

@kotlinx.serialization.Serializable
data class CreateBackgroundTaskRequest(
    val title: String,
    val description: String,
    val clientId: String,
    val projectId: String? = null,
    val priority: String = "medium",
)

@kotlinx.serialization.Serializable
data class DispatchCodingAgentRequest(
    val taskDescription: String,
    val clientId: String,
    val projectId: String,
    val sourceUrn: String? = null,
    val reviewRound: Int? = null,
    val mergeRequestUrl: String? = null,
)

@kotlinx.serialization.Serializable
data class RespondToUserTaskRequest(
    val taskId: String,
    val response: String,
)

@kotlinx.serialization.Serializable
data class DismissUserTasksRequest(
    val taskIds: List<String>,
)

@kotlinx.serialization.Serializable
data class ClassifyMeetingRequest(
    val meetingId: String,
    val clientId: String,
    val projectId: String? = null,
    val title: String? = null,
)


/**
 * Recursively convert Any? to kotlinx.serialization JsonElement.
 * Handles Map, List, String, Number, Boolean, null.
 */
@Suppress("UNCHECKED_CAST")
private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
    null -> kotlinx.serialization.json.JsonNull
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
    is String -> kotlinx.serialization.json.JsonPrimitive(value)
    is Map<*, *> -> kotlinx.serialization.json.JsonObject(
        (value as Map<String, Any?>).mapValues { (_, v) -> toJsonElement(v) }
    )
    is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
}
