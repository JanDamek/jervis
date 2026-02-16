package com.jervis.rpc

import com.jervis.common.types.ConnectionId
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.configuration.properties.KtorClientProperties
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
    private val bugTrackerSetupRpcImpl: BugTrackerSetupRpcImpl,
    private val integrationSettingsRpcImpl: IntegrationSettingsRpcImpl,
    private val codingAgentSettingsRpcImpl: CodingAgentSettingsRpcImpl,
    private val whisperSettingsRpcImpl: WhisperSettingsRpcImpl,
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
    private val correctionHeartbeatTracker: com.jervis.service.meeting.CorrectionHeartbeatTracker,
    private val orchestratorHeartbeatTracker: com.jervis.service.agent.coordinator.OrchestratorHeartbeatTracker,
    private val orchestratorWorkflowTracker: com.jervis.service.agent.coordinator.OrchestratorWorkflowTracker,
    private val orchestratorStatusHandler: com.jervis.service.agent.coordinator.OrchestratorStatusHandler,
    private val brainWriteService: com.jervis.service.brain.BrainWriteService,
    private val oauth2Service: com.jervis.service.oauth2.OAuth2Service,
    private val systemConfigRpcImpl: SystemConfigRpcImpl,
    private val properties: KtorClientProperties,
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
                                    correctionHeartbeatTracker.updateHeartbeat(body.meetingId)
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
                                    orchestratorHeartbeatTracker.updateHeartbeat(body.taskId)
                                    // Track workflow step for final message
                                    orchestratorWorkflowTracker.addStep(
                                        taskId = body.taskId,
                                        node = body.node,
                                        tools = emptyList(), // Tools extraction not yet implemented
                                    )
                                    launch {
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

                            // Internal endpoint: orchestrator sends status changes here (done/error/interrupted)
                            // Handles BOTH UI notification (via NotificationRpc) AND task state transition (via StatusHandler)
                            post("/internal/orchestrator-status") {
                                try {
                                    val body = call.receive<OrchestratorStatusCallback>()
                                    orchestratorHeartbeatTracker.clearHeartbeat(body.taskId)
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
                                        // 2. Handle task state transition (PYTHON_ORCHESTRATING → DONE/ERROR/USER_TASK)
                                        orchestratorStatusHandler.handleStatusChange(
                                            taskId = body.taskId,
                                            status = body.status,
                                            summary = body.summary,
                                            error = body.error,
                                            interruptAction = body.interruptAction,
                                            interruptDescription = body.interruptDescription,
                                            branch = body.branch,
                                            artifacts = body.artifacts,
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
                                try {
                                    val keyInfo = gpgCertificateRpcImpl.getActiveKey(clientId)
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

                            // Internal endpoint: orchestrator streams answer tokens here (typewriter effect)
                            post("/internal/orchestrator-streaming-token") {
                                try {
                                    val body = call.receive<OrchestratorStreamingTokenCallback>()
                                    launch {
                                        agentOrchestratorRpcImpl.emitToChatStream(
                                            clientId = body.clientId,
                                            projectId = body.projectId,
                                            response = ChatResponseDto(
                                                message = body.token,
                                                type = ChatResponseType.STREAMING_TOKEN,
                                                metadata = mapOf("taskId" to body.taskId),
                                            ),
                                        )
                                    }
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to process streaming token callback" }
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

                            // ==================== Brain endpoints (orchestrator → brain Jira/Confluence) ====================

                            post("/internal/brain/jira/issue") {
                                try {
                                    val body = call.receive<BrainCreateIssueRequest>()
                                    val issue = brainWriteService.createIssue(
                                        summary = body.summary,
                                        description = body.description,
                                        issueType = body.issueType,
                                        priority = body.priority,
                                        labels = body.labels,
                                        epicKey = body.epicKey,
                                    )
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        com.jervis.integration.bugtracker.BugTrackerIssue.serializer(), issue,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain create issue failed" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/brain/jira/issue/{key}/update") {
                                val issueKey = call.parameters["key"] ?: return@post call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing key\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val body = call.receive<BrainUpdateIssueRequest>()
                                    val issue = brainWriteService.updateIssue(
                                        issueKey = issueKey,
                                        summary = body.summary,
                                        description = body.description,
                                        assignee = body.assignee,
                                        priority = body.priority,
                                        labels = body.labels,
                                    )
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        com.jervis.integration.bugtracker.BugTrackerIssue.serializer(), issue,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain update issue failed: $issueKey" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/brain/jira/issue/{key}/comment") {
                                val issueKey = call.parameters["key"] ?: return@post call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing key\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val body = call.receive<BrainAddCommentRequest>()
                                    val comment = brainWriteService.addComment(issueKey, body.body)
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        com.jervis.integration.bugtracker.BugTrackerComment.serializer(), comment,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain add comment failed: $issueKey" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/brain/jira/issue/{key}/transition") {
                                val issueKey = call.parameters["key"] ?: return@post call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing key\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val body = call.receive<BrainTransitionRequest>()
                                    brainWriteService.transitionIssue(issueKey, body.transitionName)
                                    call.respondText("{\"ok\":true}", io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain transition failed: $issueKey" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/brain/jira/search") {
                                try {
                                    val jql = call.request.queryParameters["jql"] ?: "ORDER BY updated DESC"
                                    val maxResults = call.request.queryParameters["maxResults"]?.toIntOrNull() ?: 20
                                    val issues = brainWriteService.searchIssues(jql, maxResults)
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(
                                            com.jervis.integration.bugtracker.BugTrackerIssue.serializer(),
                                        ),
                                        issues,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain search issues failed" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/brain/confluence/page") {
                                try {
                                    val body = call.receive<BrainCreatePageRequest>()
                                    val page = brainWriteService.createPage(
                                        title = body.title,
                                        content = body.content,
                                        parentPageId = body.parentPageId,
                                    )
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        com.jervis.integration.wiki.WikiPage.serializer(), page,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain create page failed" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            post("/internal/brain/confluence/page/{id}/update") {
                                val pageId = call.parameters["id"] ?: return@post call.respondText(
                                    "{\"ok\":false,\"error\":\"Missing id\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                                )
                                try {
                                    val body = call.receive<BrainUpdatePageRequest>()
                                    val page = brainWriteService.updatePage(
                                        pageId = pageId,
                                        title = body.title,
                                        content = body.content,
                                        version = body.version,
                                    )
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        com.jervis.integration.wiki.WikiPage.serializer(), page,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain update page failed: $pageId" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/brain/confluence/search") {
                                try {
                                    val query = call.request.queryParameters["query"] ?: ""
                                    val maxResults = call.request.queryParameters["maxResults"]?.toIntOrNull() ?: 20
                                    val pages = brainWriteService.searchPages(query, maxResults)
                                    val json = kotlinx.serialization.json.Json.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(
                                            com.jervis.integration.wiki.WikiPage.serializer(),
                                        ),
                                        pages,
                                    )
                                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain search pages failed" }
                                    call.respondText(
                                        "{\"ok\":false,\"error\":\"${e.message?.replace("\"", "\\\"")}\"}",
                                        io.ktor.http.ContentType.Application.Json,
                                        HttpStatusCode.InternalServerError,
                                    )
                                }
                            }

                            get("/internal/brain/config") {
                                try {
                                    val config = systemConfigRpcImpl.getDocument()
                                    call.respondText(
                                        kotlinx.serialization.json.Json.encodeToString(
                                            BrainConfigResponse.serializer(),
                                            BrainConfigResponse(
                                                configured = config.brainBugtrackerConnectionId != null && config.brainBugtrackerProjectKey != null,
                                                projectKey = config.brainBugtrackerProjectKey,
                                                spaceKey = config.brainWikiSpaceKey,
                                            ),
                                        ),
                                        io.ktor.http.ContentType.Application.Json,
                                    )
                                } catch (e: Exception) {
                                    logger.error(e) { "Brain config fetch failed" }
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
                                registerService<com.jervis.service.IBugTrackerSetupService> { bugTrackerSetupRpcImpl }
                                registerService<com.jervis.service.IIntegrationSettingsService> { integrationSettingsRpcImpl }
                                registerService<com.jervis.service.ICodingAgentSettingsService> { codingAgentSettingsRpcImpl }
                                registerService<com.jervis.service.IGpgCertificateService> { gpgCertificateRpcImpl }
                                registerService<com.jervis.service.IWhisperSettingsService> { whisperSettingsRpcImpl }
                                registerService<com.jervis.service.IPollingIntervalService> { pollingIntervalRpcImpl }
                                registerService<com.jervis.service.IMeetingService> { meetingRpcImpl }
                                registerService<com.jervis.service.ITranscriptCorrectionService> { transcriptCorrectionRpcImpl }
                                registerService<com.jervis.service.IDeviceTokenService> { deviceTokenRpcImpl }
                                registerService<com.jervis.service.IIndexingQueueService> { indexingQueueRpcImpl }
                                registerService<com.jervis.service.IProjectGroupService> { projectGroupRpcImpl }
                                registerService<com.jervis.service.IEnvironmentService> { environmentRpcImpl }
                                registerService<com.jervis.service.IEnvironmentResourceService> { environmentResourceRpcImpl }
                                registerService<com.jervis.service.ISystemConfigService> { systemConfigRpcImpl }
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
)

@kotlinx.serialization.Serializable
data class OrchestratorStreamingTokenCallback(
    val taskId: String,
    val clientId: String,
    val projectId: String,
    val token: String,
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

// --- Brain internal DTOs ---

@kotlinx.serialization.Serializable
data class BrainCreateIssueRequest(
    val summary: String,
    val description: String? = null,
    val issueType: String = "Task",
    val priority: String? = null,
    val labels: List<String> = emptyList(),
    val epicKey: String? = null,
)

@kotlinx.serialization.Serializable
data class BrainUpdateIssueRequest(
    val summary: String? = null,
    val description: String? = null,
    val assignee: String? = null,
    val priority: String? = null,
    val labels: List<String>? = null,
)

@kotlinx.serialization.Serializable
data class BrainAddCommentRequest(
    val body: String,
)

@kotlinx.serialization.Serializable
data class BrainTransitionRequest(
    val transitionName: String,
)

@kotlinx.serialization.Serializable
data class BrainCreatePageRequest(
    val title: String,
    val content: String,
    val parentPageId: String? = null,
)

@kotlinx.serialization.Serializable
data class BrainUpdatePageRequest(
    val title: String,
    val content: String,
    val version: Int,
)

@kotlinx.serialization.Serializable
data class BrainConfigResponse(
    val configured: Boolean,
    val projectKey: String? = null,
    val spaceKey: String? = null,
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
