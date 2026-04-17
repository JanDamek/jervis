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
    private val filteringRulesService: com.jervis.filtering.FilteringRulesService,
    private val chatService: com.jervis.chat.ChatService,
    private val chatMessageService: com.jervis.chat.ChatMessageService,
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
    private val pythonOrchestratorClient: com.jervis.agent.PythonOrchestratorClient,
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

                            // GPU idle notification from Ollama Router migrated to gRPC
                            // (jervis.server.ServerGpuIdleService).

                            // Per-namespace K8s ops (resources, logs, deployments, scale, restart,
                            // status) migrated to gRPC (jervis.server.ServerEnvironmentK8sService).

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

                            // tasks/by-state + agent-completed + agent-dispatched migrated to
                            // gRPC (jervis.server.ServerTaskApiService.{TasksByState,
                            // AgentDispatched, AgentCompleted}).
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

                            // Qualification-done migrated to gRPC
                            // (jervis.server.ServerOrchestratorProgressService.QualificationDone).

                            // --- Chat internal endpoints (Python → Kotlin) ---
                            // Foreground GPU reservation (foreground-start/end/chat-on-cloud)
                            // migrated to gRPC (jervis.server.ServerForegroundService).

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

                            // create-background-task + dispatch-coding-agent + user-tasks + respond-to-user-task
                            // + dismiss-user-tasks migrated to gRPC (jervis.server.ServerTaskApiService).

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
data class StreamingTokenRequest(
    val taskId: String,
    val clientId: String,
    val projectId: String = "",
    val token: String,
    val messageId: String,
    val isFinal: Boolean = false,
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
