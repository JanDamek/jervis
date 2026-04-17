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
import com.jervis.rpc.internal.installInternalMeetingVideoApi
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
    private val chatService: com.jervis.chat.ChatService,
    // Dependencies for internal routing modules (injected, used by install*Api extensions)
    private val projectService: com.jervis.project.ProjectService,
    private val connectionService: com.jervis.connection.ConnectionService,
    private val gitRepoCreationService: com.jervis.git.GitRepositoryCreationService,
    private val applicationEventPublisher: org.springframework.context.ApplicationEventPublisher,
    private val gitHubClient: com.jervis.git.client.GitHubClient,
    private val gitLabClient: com.jervis.git.client.GitLabClient,
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
                            // Finance surface migrated to gRPC (jervis.server.ServerFinanceService).
                            // Time tracking + proactive triggers migrated to gRPC
                            // (jervis.server.Server{TimeTracking,Proactive}Service).
                            installInternalAttachmentApi(emailMessageIndexRepository, directoryStructureService)

                            // Internal REST API modules (Python orchestrator → Kotlin)
                            // Chat context migrated to gRPC (jervis.server.ServerChatContextService).
                            // Task CRUD + queue + push-notification + background-result migrated
                            // to gRPC (jervis.server.ServerTaskApiService).
                            // Guidelines + filter-rules + urgency migrated to gRPC
                            // (jervis.server.Server{Guidelines,FilterRules,Urgency}Service).
                            // Environment CRUD + provisioning migrated to gRPC
                            // (jervis.server.ServerEnvironmentService).
                            // OpenRouter settings + model-stats persistence migrated to gRPC
                            // (jervis.server.ServerOpenRouterSettingsService).
                            // Clients/projects/connections + project-advisor recommendations
                            // migrated to gRPC (jervis.server.ServerProjectManagementService).
                            // Git repo + workspace ops migrated to gRPC (jervis.server.ServerGitService).
                            // Merge request / PR surface + review language migrated to gRPC
                            // (jervis.server.ServerMergeRequestService).
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
                            // Bug-tracker issues (GitHub/GitLab/Jira) migrated to gRPC
                            // (jervis.server.ServerBugTrackerService).
                            // O365 session-event / capabilities-discovered / notify callbacks
                            // migrated to gRPC (jervis.server.ServerO365SessionService).
                            // O365 discovered resources + user-activity migrated to gRPC
                            // (jervis.server.Server{O365DiscoveredResources,UserActivity}Service).
                            // WhatsApp session events + capabilities migrated to gRPC
                            // (jervis.server.ServerWhatsappSessionService).
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

                            // Orchestrator push-back callbacks (correction-progress,
                            // orchestrator-progress, orchestrator-status, memory-graph-changed,
                            // thinking-graph-update) migrated to gRPC
                            // (jervis.server.ServerOrchestratorProgressService).

                            // GPG key lookup migrated to gRPC (jervis.server.ServerGitService.GetGpgKey).

                            // GPU idle notification from Ollama Router migrated to gRPC
                            // (jervis.server.ServerGpuIdleService).

                            // Per-namespace K8s ops (resources, logs, deployments, scale, restart,
                            // status) migrated to gRPC (jervis.server.ServerEnvironmentK8sService).

                            // /internal/tracker/* placeholder endpoints removed — orchestrator
                            // now calls ServerBugTrackerService directly (GitHub/GitLab/Jira
                            // dispatch lives in that service).

                            // tasks/by-state + agent-completed + agent-dispatched migrated to
                            // gRPC (jervis.server.ServerTaskApiService.{TasksByState,
                            // AgentDispatched, AgentCompleted}).
                            // /internal/streaming-token (legacy no-op) removed — the orchestrator's
                            // token streaming path goes through the chat pushback stream directly.

                            // KB progress + KB done reverse callbacks migrated to gRPC
                            // (jervis.server.ServerKbCallbacksService).

                            // Qualification-done migrated to gRPC
                            // (jervis.server.ServerOrchestratorProgressService.QualificationDone).

                            // --- Chat internal endpoints (Python → Kotlin) ---
                            // Foreground GPU reservation (foreground-start/end/chat-on-cloud)
                            // migrated to gRPC (jervis.server.ServerForegroundService).

                            // Active chat topics migrated to gRPC
                            // (jervis.server.ServerChatContextService.GetActiveChatTopics).

                            // create-background-task + dispatch-coding-agent + user-tasks + respond-to-user-task
                            // + dismiss-user-tasks migrated to gRPC (jervis.server.ServerTaskApiService).

                            // Classify meeting migrated to gRPC
                            // (jervis.server.ServerMeetingsService.ClassifyMeeting).

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

/**
 * Phase 3: A single sub-task requested by the qualifier when it returns
 * decision=DECOMPOSE. The Kotlin server creates a child TaskDocument for each
 * entry, sets `parentTaskId` to the original task, and populates the parent's
 * `blockedByTaskIds` with the new child IDs. The parent moves to BLOCKED until
 * all children reach DONE, at which point [TaskService.unblockChildrenOfParent]
 * flags it for re-qualification.
 *
 * Kept in the `com.jervis.rpc` package because TaskService imports it by
 * that FQN; the wire-format SerialNames ("task_name", "order_in_phase") are
 * retained so any persisted Mongo payloads stay readable.
 */
@kotlinx.serialization.Serializable
data class SubTaskRequest(
    @kotlinx.serialization.SerialName("task_name") val taskName: String,
    val content: String,
    val phase: String? = null,
    @kotlinx.serialization.SerialName("order_in_phase") val orderInPhase: Int = 0,
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
