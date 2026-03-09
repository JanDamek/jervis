package com.jervis.rpc.internal

import com.jervis.common.types.ConnectionId
import com.jervis.common.types.TaskId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.repository.TaskRepository
import com.jervis.service.connection.ConnectionService
import com.jervis.service.github.GitHubClient
import com.jervis.service.gitlab.GitLabClient
import com.jervis.service.project.ProjectService
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

/**
 * Internal REST endpoints for merge request / pull request operations.
 *
 * Used by Python orchestrator (agent_task_watcher) to create MR/PR after coding agent completes,
 * and to post code review comments.
 */
fun Routing.installInternalMergeRequestApi(
    taskRepository: TaskRepository,
    projectService: ProjectService,
    connectionService: ConnectionService,
    gitHubClient: GitHubClient,
    gitLabClient: GitLabClient,
) {
    // Create MR/PR for a coding task — called by AgentTaskWatcher after successful coding job
    post("/internal/tasks/{taskId}/create-merge-request") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val body = call.receive<CreateMergeRequestRequest>()
            val taskId = TaskId(ObjectId(taskIdStr))

            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val projectId = task.projectId
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task has no projectId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val project = projectService.getProjectByIdOrNull(projectId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Project not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            // Find REPOSITORY resource on the project
            val repoResource = project.resources.firstOrNull {
                it.capability == ConnectionCapability.REPOSITORY
            } ?: return@post call.respondText(
                """{"ok":false,"error":"No REPOSITORY resource on project"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )

            val connection = connectionService.findById(ConnectionId(repoResource.connectionId))
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Connection not found: ${repoResource.connectionId}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val targetBranch = body.targetBranch ?: "main"

            // Create MR/PR based on provider
            val mrUrl: String = when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val parts = repoResource.resourceIdentifier.split("/", limit = 2)
                    if (parts.size != 2) error("Invalid GitHub resource identifier: ${repoResource.resourceIdentifier}")
                    val pr = gitHubClient.createPullRequest(
                        connection = connection,
                        owner = parts[0],
                        repo = parts[1],
                        title = body.title,
                        body = body.description,
                        head = body.branch,
                        base = targetBranch,
                    )
                    pr.html_url
                }

                ProviderEnum.GITLAB -> {
                    val mr = gitLabClient.createMergeRequest(
                        connection = connection,
                        projectId = repoResource.resourceIdentifier,
                        sourceBranch = body.branch,
                        targetBranch = targetBranch,
                        title = body.title,
                        description = body.description,
                    )
                    mr.web_url
                }

                else -> return@post call.respondText(
                    """{"ok":false,"error":"Unsupported provider: ${connection.provider}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            // Save MR URL on task document
            taskRepository.save(task.copy(mergeRequestUrl = mrUrl))

            logger.info { "MR_CREATED | task=$taskIdStr | provider=${connection.provider} | url=$mrUrl" }

            call.respondText(
                Json.encodeToString(mapOf("ok" to "true", "url" to mrUrl)),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=create-merge-request | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")?.take(500)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Post a comment on an existing MR/PR — called by code review pipeline
    post("/internal/tasks/{taskId}/post-mr-comment") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val body = call.receive<PostMrCommentRequest>()
            val taskId = TaskId(ObjectId(taskIdStr))

            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val mrUrl = body.mergeRequestUrl ?: task.mergeRequestUrl
                ?: return@post call.respondText(
                    """{"ok":false,"error":"No MR URL on task or in request"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val projectId = task.projectId
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task has no projectId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val project = projectService.getProjectByIdOrNull(projectId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Project not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val repoResource = project.resources.firstOrNull {
                it.capability == ConnectionCapability.REPOSITORY
            } ?: return@post call.respondText(
                """{"ok":false,"error":"No REPOSITORY resource on project"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )

            val connection = connectionService.findById(ConnectionId(repoResource.connectionId))
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Connection not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    // Parse GitHub PR URL: https://github.com/owner/repo/pull/123
                    val match = Regex("""/([^/]+)/([^/]+)/pull/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitHub PR URL: $mrUrl")
                    val (owner, repo, prNumber) = match.destructured
                    gitHubClient.commentOnPullRequest(
                        connection = connection,
                        owner = owner,
                        repo = repo,
                        prNumber = prNumber.toInt(),
                        body = body.comment,
                    )
                }

                ProviderEnum.GITLAB -> {
                    // Parse GitLab MR URL: https://gitlab.com/owner/repo/-/merge_requests/123
                    val match = Regex("""/merge_requests/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitLab MR URL: $mrUrl")
                    val mrIid = match.groupValues[1].toInt()
                    gitLabClient.addMergeRequestNote(
                        connection = connection,
                        projectId = repoResource.resourceIdentifier,
                        mrIid = mrIid,
                        noteBody = body.comment,
                    )
                }

                else -> error("Unsupported provider: ${connection.provider}")
            }

            logger.info { "MR_COMMENTED | task=$taskIdStr | url=$mrUrl" }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=post-mr-comment | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")?.take(500)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
    // Get MR/PR diff — used by code review pipeline to review without workspace
    get("/internal/tasks/{taskId}/merge-request-diff") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val taskId = TaskId(ObjectId(taskIdStr))

            val task = taskRepository.getById(taskId)
                ?: return@get call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val mrUrl = task.mergeRequestUrl
                ?: return@get call.respondText(
                    """{"ok":false,"error":"No MR URL on task"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val projectId = task.projectId
                ?: return@get call.respondText(
                    """{"ok":false,"error":"Task has no projectId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val project = projectService.getProjectByIdOrNull(projectId)
                ?: return@get call.respondText(
                    """{"ok":false,"error":"Project not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val repoResource = project.resources.firstOrNull {
                it.capability == ConnectionCapability.REPOSITORY
            } ?: return@get call.respondText(
                """{"ok":false,"error":"No REPOSITORY resource on project"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )

            val connection = connectionService.findById(ConnectionId(repoResource.connectionId))
                ?: return@get call.respondText(
                    """{"ok":false,"error":"Connection not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val diffs: List<DiffEntry> = when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val match = Regex("""/([^/]+)/([^/]+)/pull/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitHub PR URL: $mrUrl")
                    val (owner, repo, prNumber) = match.destructured
                    val files = gitHubClient.getPullRequestFiles(
                        connection = connection,
                        owner = owner,
                        repo = repo,
                        prNumber = prNumber.toInt(),
                    )
                    files.map { f ->
                        DiffEntry(
                            oldPath = f.filename,
                            newPath = f.filename,
                            newFile = f.status == "added",
                            deletedFile = f.status == "removed",
                            renamedFile = f.status == "renamed",
                            diff = f.patch ?: "",
                        )
                    }
                }

                ProviderEnum.GITLAB -> {
                    val match = Regex("""/merge_requests/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitLab MR URL: $mrUrl")
                    val mrIid = match.groupValues[1].toInt()
                    gitLabClient.getMergeRequestDiffs(
                        connection = connection,
                        projectId = repoResource.resourceIdentifier,
                        mrIid = mrIid,
                    ).map { d ->
                        DiffEntry(
                            oldPath = d.old_path,
                            newPath = d.new_path,
                            newFile = d.new_file,
                            deletedFile = d.deleted_file,
                            renamedFile = d.renamed_file,
                            diff = d.diff,
                        )
                    }
                }

                else -> error("Unsupported provider: ${connection.provider}")
            }

            val diffJson = Json.encodeToString(diffs)

            logger.info { "MR_DIFF | task=$taskIdStr | provider=${connection.provider}" }
            call.respondText(
                """{"ok":true,"diffs":$diffJson}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=merge-request-diff | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")?.take(500)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Post inline review comments on MR/PR — file:line level comments
    post("/internal/tasks/{taskId}/post-mr-inline-comments") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val body = call.receive<PostInlineCommentsRequest>()
            val taskId = TaskId(ObjectId(taskIdStr))

            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val mrUrl = body.mergeRequestUrl ?: task.mergeRequestUrl
                ?: return@post call.respondText(
                    """{"ok":false,"error":"No MR URL"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val projectId = task.projectId
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task has no projectId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )

            val project = projectService.getProjectByIdOrNull(projectId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Project not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val repoResource = project.resources.firstOrNull {
                it.capability == ConnectionCapability.REPOSITORY
            } ?: return@post call.respondText(
                """{"ok":false,"error":"No REPOSITORY resource"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )

            val connection = connectionService.findById(ConnectionId(repoResource.connectionId))
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Connection not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            when (connection.provider) {
                ProviderEnum.GITHUB -> {
                    val match = Regex("""/([^/]+)/([^/]+)/pull/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitHub PR URL: $mrUrl")
                    val (owner, repo, prNumber) = match.destructured

                    val reviewComments = body.comments.mapNotNull { c ->
                        if (c.file.isNotBlank() && c.line != null && c.line > 0) {
                            com.jervis.service.github.GitHubReviewComment(
                                path = c.file,
                                line = c.line,
                                body = c.body,
                            )
                        } else null
                    }

                    val event = when (body.verdict) {
                        "APPROVE" -> "APPROVE"
                        "REQUEST_CHANGES" -> "REQUEST_CHANGES"
                        else -> "COMMENT"
                    }

                    gitHubClient.createPullRequestReview(
                        connection = connection,
                        owner = owner,
                        repo = repo,
                        prNumber = prNumber.toInt(),
                        body = body.summary,
                        event = event,
                        comments = reviewComments,
                    )
                }

                ProviderEnum.GITLAB -> {
                    val match = Regex("""/merge_requests/(\d+)""").find(mrUrl)
                        ?: error("Cannot parse GitLab MR URL: $mrUrl")
                    val mrIid = match.groupValues[1].toInt()

                    // Get MR versions for SHA references (needed for inline comments)
                    val versions = gitLabClient.getMergeRequestVersions(
                        connection = connection,
                        projectId = repoResource.resourceIdentifier,
                        mrIid = mrIid,
                    )
                    val latestVersion = versions.firstOrNull()

                    // Post summary as regular note
                    if (body.summary.isNotBlank()) {
                        gitLabClient.addMergeRequestNote(
                            connection = connection,
                            projectId = repoResource.resourceIdentifier,
                            mrIid = mrIid,
                            noteBody = body.summary,
                        )
                    }

                    // Post inline comments as discussions (with position)
                    for (c in body.comments) {
                        if (c.file.isBlank() || c.line == null || c.line <= 0) continue
                        try {
                            gitLabClient.createMergeRequestDiscussion(
                                connection = connection,
                                projectId = repoResource.resourceIdentifier,
                                mrIid = mrIid,
                                body = c.body,
                                newPath = c.file,
                                newLine = c.line,
                                baseSha = latestVersion?.base_commit_sha,
                                headSha = latestVersion?.head_commit_sha,
                                startSha = latestVersion?.start_commit_sha,
                            )
                        } catch (e: Exception) {
                            logger.warn { "Failed to post inline comment on ${c.file}:${c.line}: ${e.message}" }
                            // Fallback: post as regular note with file reference
                            gitLabClient.addMergeRequestNote(
                                connection = connection,
                                projectId = repoResource.resourceIdentifier,
                                mrIid = mrIid,
                                noteBody = "**${c.file}:${c.line}** — ${c.body}",
                            )
                        }
                    }
                }

                else -> error("Unsupported provider: ${connection.provider}")
            }

            logger.info { "MR_INLINE_COMMENTS | task=$taskIdStr | comments=${body.comments.size}" }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=post-mr-inline-comments | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")?.take(500)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

// --- DTOs ---

@Serializable
data class CreateMergeRequestRequest(
    val branch: String,
    val targetBranch: String? = null,
    val title: String,
    val description: String? = null,
)

@Serializable
data class PostMrCommentRequest(
    val comment: String,
    val mergeRequestUrl: String? = null,
)

@Serializable
data class PostInlineCommentsRequest(
    val summary: String = "",
    val verdict: String = "COMMENT",
    val mergeRequestUrl: String? = null,
    val comments: List<InlineComment> = emptyList(),
)

@Serializable
data class InlineComment(
    val file: String,
    val line: Int? = null,
    val body: String,
)

@Serializable
data class DiffEntry(
    val oldPath: String,
    val newPath: String,
    val newFile: Boolean = false,
    val deletedFile: Boolean = false,
    val renamedFile: Boolean = false,
    val diff: String = "",
)
