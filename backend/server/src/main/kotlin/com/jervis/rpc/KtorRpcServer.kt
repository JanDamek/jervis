package com.jervis.rpc

import com.jervis.common.types.ConnectionId
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
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
import com.jervis.rpc.internal.installInternalCacheApi
import com.jervis.rpc.internal.installInternalChatContextApi
import com.jervis.rpc.internal.installInternalEnvironmentApi
import com.jervis.rpc.internal.installInternalFilterRulesApi
import com.jervis.rpc.internal.installInternalGitApi
import com.jervis.rpc.internal.installInternalGuidelinesApi
import com.jervis.rpc.internal.installInternalOpenRouterApi
import com.jervis.rpc.internal.installInternalProjectManagementApi
import com.jervis.rpc.internal.installInternalMeetingApi
import com.jervis.rpc.internal.installInternalMergeRequestApi
import com.jervis.rpc.internal.installInternalTaskApi
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
    private val meetingRpcImpl: MeetingRpcImpl,
    private val transcriptCorrectionRpcImpl: TranscriptCorrectionRpcImpl,
    private val deviceTokenRpcImpl: DeviceTokenRpcImpl,
    private val indexingQueueRpcImpl: IndexingQueueRpcImpl,
    private val projectGroupRpcImpl: ProjectGroupRpcImpl,
    private val environmentRpcImpl: EnvironmentRpcImpl,
    private val environmentResourceRpcImpl: EnvironmentResourceRpcImpl,
    private val gpgCertificateRpcImpl: GpgCertificateRpcImpl,
    private val environmentResourceService: com.jervis.service.environment.EnvironmentResourceService,
    private val environmentService: com.jervis.service.environment.EnvironmentService,
    private val environmentK8sService: com.jervis.service.environment.EnvironmentK8sService,
    private val orchestratorWorkflowTracker: com.jervis.service.agent.coordinator.OrchestratorWorkflowTracker,
    private val orchestratorStatusHandler: com.jervis.service.agent.coordinator.OrchestratorStatusHandler,
    private val oauth2Service: com.jervis.service.oauth2.OAuth2Service,
    private val taskRepository: com.jervis.repository.TaskRepository,
    private val taskService: com.jervis.service.background.TaskService,
    private val kbResultRouter: com.jervis.qualifier.KbResultRouter,
    private val systemConfigRpcImpl: SystemConfigRpcImpl,
    private val userTaskService: com.jervis.service.task.UserTaskService,
    private val backgroundEngine: com.jervis.service.background.BackgroundEngine,
    private val chatRpcImpl: ChatRpcImpl,
    private val guidelinesRpcImpl: GuidelinesRpcImpl,
    private val kbDocumentRpcImpl: KbDocumentRpcImpl,
    private val openRouterSettingsRpcImpl: OpenRouterSettingsRpcImpl,
    private val speakerRpcImpl: SpeakerRpcImpl,
    private val taskGraphRpcImpl: TaskGraphRpcImpl,
    private val jobLogsRpcImpl: JobLogsRpcImpl,
    private val guidelinesService: com.jervis.service.guidelines.GuidelinesService,
    private val filteringRulesService: com.jervis.service.filtering.FilteringRulesService,
    // Dependencies for qualification agent dispatch
    private val agentOrchestratorService: com.jervis.service.agent.coordinator.AgentOrchestratorService,
    private val chatService: com.jervis.service.chat.ChatService,
    private val chatMessageService: com.jervis.service.chat.ChatMessageService,
    // Dependencies for internal routing modules (injected, used by install*Api extensions)
    private val clientService: com.jervis.service.client.ClientService,
    private val projectService: com.jervis.service.project.ProjectService,
    private val connectionService: com.jervis.service.connection.ConnectionService,
    private val gitRepoCreationService: com.jervis.service.git.GitRepositoryCreationService,
    private val projectTemplateService: com.jervis.service.project.ProjectTemplateService,
    private val applicationEventPublisher: org.springframework.context.ApplicationEventPublisher,
    private val gitHubClient: com.jervis.service.github.GitHubClient,
    private val gitLabClient: com.jervis.service.gitlab.GitLabClient,
    private val reviewLanguageResolver: com.jervis.service.ReviewLanguageResolver,
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
                            pingPeriodMillis = 30000  // 30 seconds
                            timeoutMillis = 15000     // 15 seconds
                        }
                        install(ContentNegotiation) {
                            json(Json { ignoreUnknownKeys = true })
                        }

                        routing {
                            // Internal REST API modules (Python orchestrator → Kotlin)
                            installInternalChatContextApi(clientService, projectService, userTaskService, meetingRpcImpl)
                            installInternalTaskApi(taskRepository, taskService, userTaskService)
                            installInternalGuidelinesApi(guidelinesService)
                            installInternalFilterRulesApi(filteringRulesService)
                            installInternalEnvironmentApi(environmentService, environmentK8sService)
                            installInternalOpenRouterApi(openRouterSettingsRpcImpl)
                            installInternalProjectManagementApi(clientService, projectService, connectionService, projectTemplateService)
                            installInternalGitApi(gitRepoCreationService, projectService, applicationEventPublisher)
                            installInternalMergeRequestApi(taskRepository, projectService, connectionService, gitHubClient, gitLabClient, reviewLanguageResolver)
                            installInternalCacheApi(guidelinesService)
                            installInternalMeetingApi(meetingRpcImpl)

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
                                    is com.jervis.service.oauth2.OAuth2CallbackResult.Success -> {
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

                                    is com.jervis.service.oauth2.OAuth2CallbackResult.InvalidState -> {
                                        call.respondText(
                                            "<html><body><h1>Invalid State</h1><p>Authorization state not found or expired.</p></body></html>",
                                            io.ktor.http.ContentType.Text.Html,
                                            HttpStatusCode.BadRequest,
                                        )
                                    }

                                    is com.jervis.service.oauth2.OAuth2CallbackResult.Error -> {
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
                                                com.jervis.entity.OrchestratorStepRecord(
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

                            // Internal endpoint: memory map changed — triggers UI refresh
                            post("/internal/memory-map-changed") {
                                try {
                                    launch {
                                        notificationRpcImpl.emitMemoryMapChanged()
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.debug(e) { "Failed to process memory-map-changed" }
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
                                    val state = com.jervis.dto.TaskStateEnum.valueOf(stateStr)
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
                                                "content" to task.content.take(500),
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
                                        state = com.jervis.dto.TaskStateEnum.PROCESSING,
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
                                        state = com.jervis.dto.TaskStateEnum.CODING,
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
                                            com.jervis.entity.QualificationStepRecord(
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

                                            if (task.state != com.jervis.dto.TaskStateEnum.INDEXING) {
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
                                                    com.jervis.entity.QualificationStepRecord(
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

                                            val kbResult = com.jervis.knowledgebase.model.FullIngestResult(
                                                success = true,
                                                chunksCount = r.chunksCount,
                                                nodesCreated = r.nodesCreated,
                                                edgesCreated = r.edgesCreated,
                                                attachmentsProcessed = r.attachmentsProcessed,
                                                attachmentsFailed = r.attachmentsFailed,
                                                summary = r.summary,
                                                entities = r.entities,
                                                hasActionableContent = r.hasActionableContent,
                                                suggestedActions = r.suggestedActions,
                                                hasFutureDeadline = r.hasFutureDeadline,
                                                suggestedDeadline = r.suggestedDeadline,
                                                isAssignedToMe = r.isAssignedToMe,
                                                urgency = r.urgency,
                                                // EPIC 2: Enhanced qualifier output
                                                actionType = r.actionType,
                                                estimatedComplexity = r.estimatedComplexity,
                                                suggestedAgent = r.suggestedAgent,
                                                affectedFiles = r.affectedFiles,
                                                relatedKbNodes = r.relatedKbNodes,
                                            )

                                            // Emit progress for routing step via callback
                                            val onProgress: suspend (String, Map<String, String>) -> Unit = { message, metadata ->
                                                taskService.appendQualificationStep(
                                                    taskId,
                                                    com.jervis.entity.QualificationStepRecord(
                                                        timestamp = java.time.Instant.now(),
                                                        step = metadata["step"] ?: "unknown",
                                                        message = message,
                                                        metadata = metadata,
                                                    ),
                                                )
                                                notificationRpcImpl.emitQualificationProgress(
                                                    taskId = body.taskId,
                                                    clientId = body.clientId,
                                                    message = message,
                                                    step = metadata["step"] ?: "unknown",
                                                    metadata = metadata,
                                                )
                                            }

                                            val routingDecision = kbResultRouter.routeTask(task, kbResult, onProgress)

                                            when (routingDecision.state) {
                                                com.jervis.dto.TaskStateEnum.QUALIFYING -> {
                                                    // Actionable → dispatch to GPU qualification agent
                                                    val chatTopics = try {
                                                        val session = chatService.getOrCreateActiveSession()
                                                        val messages = chatMessageService.getLastMessages(session.id, 10)
                                                        messages
                                                            .filter { it.role == com.jervis.entity.MessageRole.USER || it.role == com.jervis.entity.MessageRole.ASSISTANT }
                                                            .map { com.jervis.configuration.ChatTopicDto(role = it.role.name.lowercase(), content = it.content.take(200)) }
                                                    } catch (e: Exception) {
                                                        logger.debug(e) { "Failed to get chat topics for qualification" }
                                                        emptyList()
                                                    }

                                                    // Transition INDEXING → QUALIFYING
                                                    taskService.updateState(task, com.jervis.dto.TaskStateEnum.QUALIFYING)

                                                    val dispatched = agentOrchestratorService.dispatchQualification(task, kbResult, chatTopics)
                                                    if (dispatched) {
                                                        logger.info {
                                                            "KB_DONE_CALLBACK: qualification dispatched taskId=${body.taskId} reason=${routingDecision.reason}"
                                                        }
                                                        onProgress(
                                                            "GPU kvalifikační agent analyzuje úkol...",
                                                            mapOf("step" to "qualifying", "agent" to "qualification_agent"),
                                                        )
                                                    } else {
                                                        // GPU unavailable → fall back to QUEUED (skip qualification)
                                                        logger.warn { "KB_DONE_CALLBACK: qualification unavailable, falling back to QUEUED taskId=${body.taskId}" }
                                                        taskService.updateState(task, com.jervis.dto.TaskStateEnum.QUEUED)
                                                    }
                                                }
                                                else -> {
                                                    // DONE or other terminal state
                                                    taskService.updateState(task, routingDecision.state)
                                                }
                                            }

                                            logger.info {
                                                "KB_DONE_CALLBACK: routed taskId=${body.taskId} state=${routingDecision.state} " +
                                                    "reason=${routingDecision.reason} scheduled=${routingDecision.scheduledCopyCreated}"
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
                                                .filter { it.role == com.jervis.entity.MessageRole.USER || it.role == com.jervis.entity.MessageRole.ASSISTANT }
                                                .forEach { msg ->
                                                    add(kotlinx.serialization.json.buildJsonObject {
                                                        put("role", kotlinx.serialization.json.JsonPrimitive(msg.role.name.lowercase()))
                                                        put("content", kotlinx.serialization.json.JsonPrimitive(msg.content.take(200)))
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

                            // Qualification agent callback: Python sends qualification result here
                            post("/internal/qualification-done") {
                                try {
                                    val body = call.receive<QualificationDoneCallback>()
                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} decision=${body.decision}" }

                                    launch {
                                        try {
                                            val taskId = com.jervis.common.types.TaskId(ObjectId(body.taskId))
                                            val task = taskRepository.getById(taskId)

                                            if (task == null) {
                                                logger.error { "QUALIFICATION_DONE: task not found taskId=${body.taskId}" }
                                                return@launch
                                            }

                                            if (task.state != com.jervis.dto.TaskStateEnum.QUALIFYING) {
                                                logger.warn { "QUALIFICATION_DONE: task not in QUALIFYING state taskId=${body.taskId} state=${task.state}" }
                                                return@launch
                                            }

                                            // Emit qualification progress
                                            taskService.appendQualificationStep(
                                                taskId,
                                                com.jervis.entity.QualificationStepRecord(
                                                    timestamp = java.time.Instant.now(),
                                                    step = "qualification_done",
                                                    message = body.reason ?: "Kvalifikace dokončena",
                                                    metadata = mapOf(
                                                        "agent" to "qualification_agent",
                                                        "decision" to body.decision,
                                                        "priority" to (body.priorityScore?.toString() ?: ""),
                                                    ),
                                                ),
                                            )
                                            notificationRpcImpl.emitQualificationProgress(
                                                taskId = body.taskId,
                                                clientId = body.clientId,
                                                message = body.reason ?: "Kvalifikace dokončena",
                                                step = "qualification_done",
                                                metadata = mapOf("agent" to "qualification_agent", "decision" to body.decision),
                                            )

                                            // Save prepared context from GPU qualification agent
                                            val preparedContext = body.contextSummary ?: body.suggestedApproach
                                            if (preparedContext != null || body.contextSummary != null) {
                                                val contextJson = buildString {
                                                    append("{")
                                                    body.contextSummary?.let { append("\"context\":\"${it.replace("\"", "\\\"").replace("\n", "\\n")}\",") }
                                                    body.suggestedApproach?.let { append("\"approach\":\"${it.replace("\"", "\\\"").replace("\n", "\\n")}\",") }
                                                    body.actionType?.let { append("\"actionType\":\"$it\",") }
                                                    body.estimatedComplexity?.let { append("\"complexity\":\"$it\",") }
                                                    append("\"priority\":${body.priorityScore ?: 5}")
                                                    append("}")
                                                }
                                                taskService.saveQualifierContext(taskId, contextJson)
                                            }

                                            when (body.decision) {
                                                "QUEUED" -> {
                                                    // Qualification says: proceed to orchestration (with prepared context)
                                                    val updatedTask = task.copy(
                                                        priorityScore = body.priorityScore ?: task.priorityScore,
                                                        priorityReason = body.reason ?: task.priorityReason,
                                                        actionType = body.actionType ?: task.actionType,
                                                        estimatedComplexity = body.estimatedComplexity ?: task.estimatedComplexity,
                                                    )
                                                    taskRepository.save(updatedTask)
                                                    taskService.updateState(updatedTask, com.jervis.dto.TaskStateEnum.QUEUED)
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → QUEUED priority=${body.priorityScore} actionType=${body.actionType}" }
                                                }
                                                "DONE" -> {
                                                    taskService.updateState(task, com.jervis.dto.TaskStateEnum.DONE)
                                                    logger.info { "QUALIFICATION_DONE: taskId=${body.taskId} → DONE reason=${body.reason}" }
                                                }
                                                else -> {
                                                    // Unknown decision — default to QUEUED
                                                    logger.warn { "QUALIFICATION_DONE: unknown decision=${body.decision}, defaulting to QUEUED" }
                                                    taskService.updateState(task, com.jervis.dto.TaskStateEnum.QUEUED)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            logger.error(e) { "QUALIFICATION_DONE: failed to process result taskId=${body.taskId}" }
                                        }
                                    }

                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process qualification done callback" }
                                    call.respondText(
                                        "{\"ok\":false}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
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
                                        taskType = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                                        content = body.description,
                                        clientId = clientId,
                                        correlationId = correlationId,
                                        sourceUrn = com.jervis.common.types.SourceUrn("chat:background-task"),
                                        projectId = projectId,
                                        state = com.jervis.dto.TaskStateEnum.QUEUED,
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
                                        taskType = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                                        content = body.taskDescription,
                                        clientId = clientId,
                                        correlationId = correlationId,
                                        sourceUrn = com.jervis.common.types.SourceUrn(sourceUrn),
                                        projectId = projectId,
                                        state = com.jervis.dto.TaskStateEnum.QUEUED,
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

                            // List unclassified meetings
                            get("/internal/unclassified-meetings") {
                                try {
                                    val meetings = meetingRpcImpl.listUnclassifiedMeetings()
                                    val meetingsJson = meetings.map { meeting ->
                                        mapOf(
                                            "id" to meeting.id,
                                            "title" to (meeting.title ?: ""),
                                            "startedAt" to meeting.startedAt.toString(),
                                            "durationSeconds" to (meeting.durationSeconds?.toString() ?: "0"),
                                        )
                                    }
                                    call.respondText(
                                        Json.encodeToString<List<Map<String, String>>>(meetingsJson),
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to list unclassified meetings" }
                                    call.respondText("[]", io.ktor.http.ContentType.Application.Json)
                                }
                            }

                            rpc("/rpc") {
                                rpcConfig {
                                    serialization {
                                        cbor()
                                    }
                                }

                                registerService<com.jervis.service.IClientService> { clientRpcImpl }
                                registerService<com.jervis.service.IProjectService> { projectRpcImpl }
                                registerService<com.jervis.service.IUserTaskService> { userTaskRpcImpl }
                                registerService<com.jervis.service.ITaskSchedulingService> { taskSchedulingRpcImpl }
                                registerService<com.jervis.service.IAgentOrchestratorService> { agentOrchestratorRpcImpl }
                                registerService<com.jervis.service.IErrorLogService> { errorLogRpcImpl }
                                registerService<com.jervis.service.IConnectionService> { connectionRpcImpl }
                                registerService<com.jervis.service.IGitConfigurationService> { gitConfigurationRpcImpl }
                                registerService<com.jervis.service.IClientProjectLinkService> { clientProjectLinkRpcImpl }
                                registerService<com.jervis.service.IPendingTaskService> { pendingTaskRpcImpl }
                                registerService<com.jervis.service.INotificationService> { notificationRpcImpl }
                                registerService<com.jervis.service.IGpgCertificateService> { gpgCertificateRpcImpl }
                                registerService<com.jervis.service.IPollingIntervalService> { pollingIntervalRpcImpl }
                                registerService<com.jervis.service.IMeetingService> { meetingRpcImpl }
                                registerService<com.jervis.service.ITranscriptCorrectionService> { transcriptCorrectionRpcImpl }
                                registerService<com.jervis.service.IDeviceTokenService> { deviceTokenRpcImpl }
                                registerService<com.jervis.service.IIndexingQueueService> { indexingQueueRpcImpl }
                                registerService<com.jervis.service.IProjectGroupService> { projectGroupRpcImpl }
                                registerService<com.jervis.service.IEnvironmentService> { environmentRpcImpl }
                                registerService<com.jervis.service.IEnvironmentResourceService> { environmentResourceRpcImpl }
                                registerService<com.jervis.service.ISystemConfigService> { systemConfigRpcImpl }
                                registerService<com.jervis.service.IChatService> { chatRpcImpl }
                                registerService<com.jervis.service.IGuidelinesService> { guidelinesRpcImpl }
                                registerService<com.jervis.service.IKbDocumentService> { kbDocumentRpcImpl }
                                registerService<com.jervis.service.IOpenRouterSettingsService> { openRouterSettingsRpcImpl }
                                registerService<com.jervis.service.ISpeakerService> { speakerRpcImpl }
                                registerService<com.jervis.service.ITaskGraphService> { taskGraphRpcImpl }
                                registerService<com.jervis.service.IJobLogsService> { jobLogsRpcImpl }
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

@kotlinx.serialization.Serializable
data class KbCompletionResult(
    val status: String = "success",
    @kotlinx.serialization.SerialName("chunks_count") val chunksCount: Int = 0,
    @kotlinx.serialization.SerialName("nodes_created") val nodesCreated: Int = 0,
    @kotlinx.serialization.SerialName("edges_created") val edgesCreated: Int = 0,
    @kotlinx.serialization.SerialName("attachments_processed") val attachmentsProcessed: Int = 0,
    @kotlinx.serialization.SerialName("attachments_failed") val attachmentsFailed: Int = 0,
    val summary: String = "",
    val entities: List<String> = emptyList(),
    val hasActionableContent: Boolean = false,
    val suggestedActions: List<String> = emptyList(),
    val hasFutureDeadline: Boolean = false,
    val suggestedDeadline: String? = null,
    val isAssignedToMe: Boolean = false,
    val urgency: String = "normal",
    // EPIC 2: Enhanced qualifier output (optional — inferred if not provided by KB)
    @kotlinx.serialization.SerialName("action_type") val actionType: String? = null,
    @kotlinx.serialization.SerialName("estimated_complexity") val estimatedComplexity: String? = null,
    @kotlinx.serialization.SerialName("suggested_agent") val suggestedAgent: String? = null,
    @kotlinx.serialization.SerialName("affected_files") val affectedFiles: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("related_kb_nodes") val relatedKbNodes: List<String> = emptyList(),
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
data class ClassifyMeetingRequest(
    val meetingId: String,
    val clientId: String,
    val projectId: String? = null,
    val title: String? = null,
)

@kotlinx.serialization.Serializable
data class QualificationDoneCallback(
    @kotlinx.serialization.SerialName("task_id") val taskId: String,
    @kotlinx.serialization.SerialName("client_id") val clientId: String,
    /** "QUEUED" or "DONE" */
    val decision: String,
    /** Priority score 0-100 (higher = more urgent) */
    @kotlinx.serialization.SerialName("priority_score") val priorityScore: Int? = null,
    /** Human-readable reason for the decision */
    val reason: String? = null,
    /** Prepared context from GPU qualification agent (KB search results, related items) */
    @kotlinx.serialization.SerialName("context_summary") val contextSummary: String? = null,
    /** Suggested approach/plan for the orchestrator */
    @kotlinx.serialization.SerialName("suggested_approach") val suggestedApproach: String? = null,
    /** Refined action type from qualification (e.g., CODE_FIX, RESPOND_EMAIL) */
    @kotlinx.serialization.SerialName("action_type") val actionType: String? = null,
    /** Refined complexity estimate from qualification */
    @kotlinx.serialization.SerialName("estimated_complexity") val estimatedComplexity: String? = null,
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
