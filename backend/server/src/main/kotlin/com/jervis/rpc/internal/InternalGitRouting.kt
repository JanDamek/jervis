package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.git.GitRepositoryCreationService
import com.jervis.git.service.GitRepositoryService
import com.jervis.project.ProjectService
import com.jervis.project.ProjectWorkspaceInitEvent
import com.jervis.project.WorkspaceStatus
import com.jervis.task.BackgroundEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher

private val logger = KotlinLogging.logger {}

private val gitJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Internal REST endpoints for git repository operations.
 *
 * Called by MCP tools and Python orchestrator for:
 * - Creating git repositories (GitHub/GitLab API)
 * - Initializing project workspaces (clone)
 *
 * Endpoints:
 * - POST /internal/git/repos          — create a new git repository
 * - POST /internal/git/init-workspace — trigger workspace clone for a project
 */
fun Routing.installInternalGitApi(
    gitRepoCreationService: GitRepositoryCreationService,
    projectService: ProjectService,
    eventPublisher: ApplicationEventPublisher,
    backgroundEngine: BackgroundEngine,
    gitRepositoryService: GitRepositoryService,
) {
    // --- Create git repository ---
    post("/internal/git/repos") {
        try {
            val req = call.receive<CreateGitRepoRequest>()
            val result = gitRepoCreationService.createRepository(
                clientId = ClientId(ObjectId(req.clientId)),
                connectionId = req.connectionId,
                name = req.name,
                description = req.description,
                isPrivate = req.isPrivate,
            )
            call.respondText(
                gitJson.encodeToString(
                    com.jervis.git.GitRepoCreationResult.serializer(),
                    result,
                ),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        } catch (e: IllegalArgumentException) {
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/git/repos" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Initialize workspace (BLOCKING git clone for a project) ---
    //
    // Blocks until the clone completes (or fails), then returns the current
    // status + workspacePath. Callers (Python orchestrator, tools) can rely on
    // an immediately usable workspace on success.
    //
    // If the workspace is already READY, returns the cached path without re-cloning.
    // If it's currently CLONING in another request, returns the in-progress status.
    post("/internal/git/init-workspace") {
        try {
            val req = call.receive<InitWorkspaceRequest>()
            val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(req.projectId)))
                ?: return@post call.respondText(
                    """{"error":"Project ${req.projectId} not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            // Fast path: already READY → return workspace path via gitRepositoryService
            if (project.workspaceStatus == WorkspaceStatus.READY) {
                val gitRes = project.resources.firstOrNull {
                    it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                }
                val path = gitRes?.let {
                    gitRepositoryService.getAgentRepoDir(project, it).toString()
                } ?: ""
                call.respondText(
                    """{"ok":true,"projectId":"${req.projectId}","status":"READY","workspacePath":"$path"}""",
                    ContentType.Application.Json,
                )
                return@post
            }

            // Blocking clone — initializeProjectWorkspace updates project.workspaceStatus
            backgroundEngine.initializeProjectWorkspace(project)
            val refreshed = projectService.getProjectByIdOrNull(ProjectId(ObjectId(req.projectId)))
            val status = refreshed?.workspaceStatus?.name ?: "UNKNOWN"
            val gitRes = refreshed?.resources?.firstOrNull {
                it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
            }
            val path = if (refreshed?.workspaceStatus == WorkspaceStatus.READY && gitRes != null) {
                gitRepositoryService.getAgentRepoDir(refreshed, gitRes).toString()
            } else {
                ""
            }
            val err = refreshed?.lastWorkspaceError?.replace("\"", "\\\"") ?: ""

            call.respondText(
                """{"ok":${refreshed?.workspaceStatus == WorkspaceStatus.READY},""" +
                    """"projectId":"${req.projectId}","status":"$status",""" +
                    """"workspacePath":"$path","error":"$err"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=POST /internal/git/init-workspace" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // --- Get workspace status (non-blocking poll) ---
    get("/internal/git/workspace-status") {
        try {
            val projectIdParam = call.request.queryParameters["projectId"]
                ?: return@get call.respondText(
                    """{"error":"projectId query parameter required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(projectIdParam)))
                ?: return@get call.respondText(
                    """{"error":"Project $projectIdParam not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            val status = project.workspaceStatus?.name ?: "NONE"
            val gitRes = project.resources.firstOrNull {
                it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
            }
            val path = if (project.workspaceStatus == WorkspaceStatus.READY && gitRes != null) {
                gitRepositoryService.getAgentRepoDir(project, gitRes).toString()
            } else {
                ""
            }
            val err = project.lastWorkspaceError?.replace("\"", "\\\"") ?: ""
            call.respondText(
                """{"projectId":"$projectIdParam","status":"$status","workspacePath":"$path","error":"$err"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=GET /internal/git/workspace-status" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class CreateGitRepoRequest(
    val clientId: String,
    val connectionId: String? = null,
    val name: String,
    val description: String? = null,
    val isPrivate: Boolean = true,
)

@Serializable
data class InitWorkspaceRequest(
    val projectId: String,
)
