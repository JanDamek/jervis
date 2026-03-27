package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.bugtracker.BugTrackerService
import com.jervis.bugtracker.CreateBugTrackerIssueRequest
import com.jervis.connection.ConnectionService
import com.jervis.git.client.GitHubClient
import com.jervis.git.client.GitLabClient
import com.jervis.project.ProjectService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Internal REST endpoints for bug tracker (issue) operations.
 *
 * Supports three providers:
 * - **GitHub** Issues (via GitHubClient)
 * - **GitLab** Issues (via GitLabClient)
 * - **Atlassian/Jira** (via BugTrackerService — existing RPC integration)
 *
 * The provider is determined by the project's BUGTRACKER resource connection.
 * Falls back to REPOSITORY resource if no BUGTRACKER is configured.
 *
 * Typical setup: GitLab for code + Jira for issues + Confluence for wiki —
 * each capability points to a different connection.
 */
fun Routing.installInternalBugTrackerApi(
    projectService: ProjectService,
    connectionService: ConnectionService,
    gitHubClient: GitHubClient,
    gitLabClient: GitLabClient,
    bugTrackerService: BugTrackerService,
) {
    // Create an issue on the project's bug tracker
    post("/internal/issues/create") {
        try {
            val body = call.receive<CreateIssueRequest>()

            val resolved = resolveBugTrackerConnection(
                body.clientId, body.projectId, projectService, connectionService,
            )
            if (resolved == null) {
                return@post call.respondText(
                    """{"ok":false,"error":"No BUGTRACKER connection found for project ${body.projectId}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val (connection, resourceId) = resolved

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val parts = resourceId.split("/", limit = 2)
                    if (parts.size != 2) error("Invalid GitHub resource identifier: $resourceId")
                    val issue = gitHubClient.createIssue(
                        connection = connection,
                        owner = parts[0],
                        repo = parts[1],
                        title = body.title,
                        body = body.description,
                        labels = body.labels,
                    )
                    logger.info { "ISSUE_CREATED | github | ${parts[0]}/${parts[1]}#${issue.number} | ${body.title}" }
                    call.respondText(
                        json.encodeToString(IssueResponse(ok = true, key = "#${issue.number}", url = issue.html_url)),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                ProviderEnum.GITLAB -> {
                    val issue = gitLabClient.createIssue(
                        connection = connection,
                        projectId = resourceId,
                        title = body.title,
                        description = body.description,
                        labels = body.labels,
                    )
                    logger.info { "ISSUE_CREATED | gitlab | $resourceId#${issue.iid} | ${body.title}" }
                    call.respondText(
                        json.encodeToString(IssueResponse(ok = true, key = "#${issue.iid}", url = issue.web_url)),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                ProviderEnum.ATLASSIAN -> {
                    // Jira — resourceIdentifier is the project key (e.g. "PROJ")
                    val issue = bugTrackerService.createIssue(
                        clientId = ClientId(ObjectId(body.clientId)),
                        request = CreateBugTrackerIssueRequest(
                            projectKey = resourceId,
                            summary = body.title,
                            description = body.description,
                            issueType = body.issueType ?: "Task",
                            priority = body.priority,
                            labels = body.labels,
                        ),
                    )
                    logger.info { "ISSUE_CREATED | jira | ${issue.key} | ${body.title}" }
                    call.respondText(
                        json.encodeToString(IssueResponse(ok = true, key = issue.key)),
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                else -> {
                    call.respondText(
                        """{"ok":false,"error":"Unsupported provider: ${connection.provider}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ISSUE_CREATE_FAILED | ${e.message}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Add comment to an existing issue
    post("/internal/issues/comment") {
        try {
            val body = call.receive<AddIssueCommentRequest>()

            val resolved = resolveBugTrackerConnection(
                body.clientId, body.projectId, projectService, connectionService,
            )
            if (resolved == null) {
                return@post call.respondText(
                    """{"ok":false,"error":"No BUGTRACKER connection found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val (connection, resourceId) = resolved

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val issueNumber = body.issueKey.removePrefix("#").toIntOrNull()
                        ?: return@post call.respondText(
                            """{"ok":false,"error":"Invalid issue number: ${body.issueKey}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val parts = resourceId.split("/", limit = 2)
                    if (parts.size != 2) error("Invalid GitHub resource identifier: $resourceId")
                    val comment = gitHubClient.addIssueComment(
                        connection = connection,
                        owner = parts[0],
                        repo = parts[1],
                        issueNumber = issueNumber,
                        body = body.comment,
                    )
                    logger.info { "ISSUE_COMMENT_ADDED | github | ${parts[0]}/${parts[1]}#$issueNumber" }
                    call.respondText(
                        json.encodeToString(CommentResponse(ok = true, url = comment.html_url)),
                        ContentType.Application.Json,
                    )
                }
                ProviderEnum.GITLAB -> {
                    val issueIid = body.issueKey.removePrefix("#").toIntOrNull()
                        ?: return@post call.respondText(
                            """{"ok":false,"error":"Invalid issue IID: ${body.issueKey}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val note = gitLabClient.addIssueNote(
                        connection = connection,
                        projectId = resourceId,
                        issueIid = issueIid,
                        noteBody = body.comment,
                    )
                    logger.info { "ISSUE_COMMENT_ADDED | gitlab | $resourceId#$issueIid" }
                    call.respondText(
                        json.encodeToString(CommentResponse(ok = true)),
                        ContentType.Application.Json,
                    )
                }
                ProviderEnum.ATLASSIAN -> {
                    // Jira — issueKey is like "PROJ-123"
                    val result = bugTrackerService.addComment(
                        clientId = ClientId(ObjectId(body.clientId)),
                        issueKey = body.issueKey,
                        comment = body.comment,
                    )
                    logger.info { "ISSUE_COMMENT_ADDED | jira | ${body.issueKey}" }
                    call.respondText(
                        json.encodeToString(CommentResponse(ok = true)),
                        ContentType.Application.Json,
                    )
                }
                else -> {
                    call.respondText(
                        """{"ok":false,"error":"Unsupported provider: ${connection.provider}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ISSUE_COMMENT_FAILED | ${e.message}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Update an existing issue (title, body, state, labels)
    post("/internal/issues/update") {
        try {
            val body = call.receive<UpdateIssueRequest>()

            val resolved = resolveBugTrackerConnection(
                body.clientId, body.projectId, projectService, connectionService,
            )
            if (resolved == null) {
                return@post call.respondText(
                    """{"ok":false,"error":"No BUGTRACKER connection found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val (connection, resourceId) = resolved

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val issueNumber = body.issueKey.removePrefix("#").toIntOrNull()
                        ?: return@post call.respondText(
                            """{"ok":false,"error":"Invalid issue number: ${body.issueKey}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val parts = resourceId.split("/", limit = 2)
                    if (parts.size != 2) error("Invalid GitHub resource identifier: $resourceId")
                    val issue = gitHubClient.updateIssue(
                        connection = connection,
                        owner = parts[0],
                        repo = parts[1],
                        issueNumber = issueNumber,
                        title = body.title,
                        body = body.description,
                        state = body.state,
                        labels = body.labels,
                    )
                    logger.info { "ISSUE_UPDATED | github | ${parts[0]}/${parts[1]}#$issueNumber | state=${body.state} labels=${body.labels}" }
                    call.respondText(
                        json.encodeToString(IssueResponse(ok = true, key = "#${issue.number}", url = issue.html_url)),
                        ContentType.Application.Json,
                    )
                }
                ProviderEnum.GITLAB -> {
                    val issueIid = body.issueKey.removePrefix("#").toIntOrNull()
                        ?: return@post call.respondText(
                            """{"ok":false,"error":"Invalid issue IID: ${body.issueKey}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                    val stateEvent = when (body.state) {
                        "closed" -> "close"
                        "open" -> "reopen"
                        else -> null
                    }
                    val issue = gitLabClient.updateIssue(
                        connection = connection,
                        projectId = resourceId,
                        issueIid = issueIid,
                        title = body.title,
                        description = body.description,
                        stateEvent = stateEvent,
                        labels = body.labels,
                    )
                    logger.info { "ISSUE_UPDATED | gitlab | $resourceId#$issueIid | state=${body.state} labels=${body.labels}" }
                    call.respondText(
                        json.encodeToString(IssueResponse(ok = true, key = "#${issue.iid}", url = issue.web_url)),
                        ContentType.Application.Json,
                    )
                }
                else -> {
                    call.respondText(
                        """{"ok":false,"error":"update_issue not supported for provider: ${connection.provider}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ISSUE_UPDATE_FAILED | ${e.message}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // List issues from the project's bug tracker
    get("/internal/issues/list") {
        try {
            val clientId = call.parameters["clientId"]
                ?: return@get call.respondText(
                    """{"ok":false,"error":"clientId required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val projectId = call.parameters["projectId"]
                ?: return@get call.respondText(
                    """{"ok":false,"error":"projectId required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val resolved = resolveBugTrackerConnection(
                clientId, projectId, projectService, connectionService,
            )
            if (resolved == null) {
                return@get call.respondText(
                    """{"ok":false,"error":"No BUGTRACKER connection found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            val (connection, resourceId) = resolved

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val parts = resourceId.split("/", limit = 2)
                    if (parts.size != 2) error("Invalid GitHub resource identifier: $resourceId")
                    val issues = gitHubClient.listIssues(
                        connection = connection,
                        owner = parts[0],
                        repo = parts[1],
                    )
                    val result = issues.map { issue ->
                        IssueListItem(
                            key = "#${issue.number}",
                            title = issue.title,
                            state = issue.state,
                            url = issue.html_url,
                            created = issue.created_at,
                            updated = issue.updated_at,
                        )
                    }
                    call.respondText(
                        json.encodeToString(IssueListResponse(ok = true, issues = result)),
                        ContentType.Application.Json,
                    )
                }
                ProviderEnum.GITLAB -> {
                    val issues = gitLabClient.listIssues(
                        connection = connection,
                        projectId = resourceId,
                    )
                    val result = issues.map { issue ->
                        IssueListItem(
                            key = "#${issue.iid}",
                            title = issue.title,
                            state = issue.state,
                            url = issue.web_url,
                            created = issue.created_at,
                            updated = issue.updated_at,
                        )
                    }
                    call.respondText(
                        json.encodeToString(IssueListResponse(ok = true, issues = result)),
                        ContentType.Application.Json,
                    )
                }
                ProviderEnum.ATLASSIAN -> {
                    // Jira — search for all issues in the project
                    val issues = bugTrackerService.searchIssues(
                        clientId = ClientId(ObjectId(clientId)),
                        query = "ORDER BY updated DESC",
                        project = resourceId,
                        maxResults = 50,
                    )
                    val result = issues.map { issue ->
                        IssueListItem(
                            key = issue.key,
                            title = issue.summary,
                            state = issue.status,
                            url = "",
                            created = issue.created,
                            updated = issue.updated,
                        )
                    }
                    call.respondText(
                        json.encodeToString(IssueListResponse(ok = true, issues = result)),
                        ContentType.Application.Json,
                    )
                }
                else -> {
                    call.respondText(
                        """{"ok":false,"error":"Unsupported provider: ${connection.provider}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "ISSUE_LIST_FAILED | ${e.message}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

/**
 * Resolve the BUGTRACKER connection for a project. Falls back to REPOSITORY if no BUGTRACKER.
 */
private suspend fun resolveBugTrackerConnection(
    clientId: String,
    projectId: String,
    projectService: ProjectService,
    connectionService: ConnectionService,
): Pair<com.jervis.connection.ConnectionDocument, String>? {
    val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(projectId))) ?: return null

    // Try BUGTRACKER resource first, then fall back to REPOSITORY
    val resource = project.resources.firstOrNull {
        it.capability == ConnectionCapability.BUGTRACKER
    } ?: project.resources.firstOrNull {
        it.capability == ConnectionCapability.REPOSITORY
    } ?: return null

    val connection = connectionService.findById(ConnectionId(resource.connectionId)) ?: return null
    return connection to resource.resourceIdentifier
}

// ── Request/Response DTOs ──────────────────────────────────

@Serializable
private data class CreateIssueRequest(
    val clientId: String,
    val projectId: String,
    val title: String,
    val description: String? = null,
    val labels: List<String> = emptyList(),
    val issueType: String? = null,
    val priority: String? = null,
)

@Serializable
private data class AddIssueCommentRequest(
    val clientId: String,
    val projectId: String,
    val issueKey: String,
    val comment: String,
)

@Serializable
private data class IssueResponse(
    val ok: Boolean,
    val key: String? = null,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
private data class CommentResponse(
    val ok: Boolean,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
private data class IssueListItem(
    val key: String,
    val title: String,
    val state: String,
    val url: String,
    val created: String,
    val updated: String,
)

@Serializable
private data class UpdateIssueRequest(
    val clientId: String,
    val projectId: String,
    val issueKey: String,
    val title: String? = null,
    val description: String? = null,
    val state: String? = null, // "open" or "closed"
    val labels: List<String>? = null,
)

@Serializable
private data class IssueListResponse(
    val ok: Boolean,
    val issues: List<IssueListItem> = emptyList(),
    val error: String? = null,
)
