package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.service.git.GitRepositoryCreationService
import com.jervis.service.project.ProjectService
import com.jervis.service.project.ProjectWorkspaceInitEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
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
                    com.jervis.service.git.GitRepoCreationResult.serializer(),
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

    // --- Initialize workspace (trigger git clone for a project) ---
    post("/internal/git/init-workspace") {
        try {
            val req = call.receive<InitWorkspaceRequest>()
            val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(req.projectId)))
                ?: return@post call.respondText(
                    """{"error":"Project ${req.projectId} not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            // Trigger workspace initialization event (async clone)
            eventPublisher.publishEvent(ProjectWorkspaceInitEvent(project))

            call.respondText(
                """{"ok":true,"projectId":"${req.projectId}","status":"WORKSPACE_INIT_TRIGGERED"}""",
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
