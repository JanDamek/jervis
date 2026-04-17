package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.TaskId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.contracts.server.CreateMergeRequestRequest
import com.jervis.contracts.server.CreateMergeRequestResponse
import com.jervis.contracts.server.DiffEntry
import com.jervis.contracts.server.GetMergeRequestDiffRequest
import com.jervis.contracts.server.GetMergeRequestDiffResponse
import com.jervis.contracts.server.PostMrCommentRequest
import com.jervis.contracts.server.PostMrCommentResponse
import com.jervis.contracts.server.PostMrInlineCommentsRequest
import com.jervis.contracts.server.PostMrInlineCommentsResponse
import com.jervis.contracts.server.ResolveReviewLanguageRequest
import com.jervis.contracts.server.ResolveReviewLanguageResponse
import com.jervis.contracts.server.ServerMergeRequestServiceGrpcKt
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.git.client.GitHubClient
import com.jervis.git.client.GitHubReviewComment
import com.jervis.git.client.GitLabClient
import com.jervis.infrastructure.llm.ReviewLanguageResolver
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectService
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerMergeRequestGrpcImpl(
    private val taskRepository: TaskRepository,
    private val projectService: ProjectService,
    private val connectionService: ConnectionService,
    private val gitHubClient: GitHubClient,
    private val gitLabClient: GitLabClient,
    private val reviewLanguageResolver: ReviewLanguageResolver,
) : ServerMergeRequestServiceGrpcKt.ServerMergeRequestServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun resolveReviewLanguage(
        request: ResolveReviewLanguageRequest,
    ): ResolveReviewLanguageResponse {
        val clientId = request.clientId.takeIf { it.isNotBlank() }?.let { ClientId(ObjectId(it)) }
        val projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) }
        val language = reviewLanguageResolver.resolve(clientId, projectId)
        return ResolveReviewLanguageResponse.newBuilder().setLanguage(language).build()
    }

    override suspend fun createMergeRequest(
        request: CreateMergeRequestRequest,
    ): CreateMergeRequestResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return errorCreate("Task not found")
        val ctx = resolveRepoContext(task)
            ?: return errorCreate("No REPOSITORY resource or connection on project")
        val (connection, repoId) = ctx
        val targetBranch = request.targetBranch.takeIf { it.isNotBlank() } ?: "main"
        val description = request.description.takeIf { it.isNotBlank() }
        val url: String = when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val parts = repoId.split("/", limit = 2)
                require(parts.size == 2) { "Invalid GitHub resource identifier: $repoId" }
                gitHubClient.createPullRequest(
                    connection = connection,
                    owner = parts[0],
                    repo = parts[1],
                    title = request.title,
                    body = description,
                    head = request.branch,
                    base = targetBranch,
                ).html_url
            }
            ProviderEnum.GITLAB -> gitLabClient.createMergeRequest(
                connection = connection,
                projectId = repoId,
                sourceBranch = request.branch,
                targetBranch = targetBranch,
                title = request.title,
                description = description,
            ).web_url
            else -> return errorCreate("Unsupported provider: ${connection.provider}")
        }
        val fresh = taskRepository.getById(taskId) ?: task
        taskRepository.save(fresh.copy(mergeRequestUrl = url))
        logger.info { "MR_CREATED | task=${request.taskId} | provider=${connection.provider} | url=$url" }
        return CreateMergeRequestResponse.newBuilder().setOk(true).setUrl(url).build()
    }

    override suspend fun getMergeRequestDiff(
        request: GetMergeRequestDiffRequest,
    ): GetMergeRequestDiffResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return GetMergeRequestDiffResponse.newBuilder().setOk(false).setError("Task not found").build()
        val mrUrl = task.mergeRequestUrl
            ?: return GetMergeRequestDiffResponse.newBuilder().setOk(false).setError("No MR URL on task").build()
        val ctx = resolveRepoContext(task)
            ?: return GetMergeRequestDiffResponse.newBuilder().setOk(false)
                .setError("No REPOSITORY resource or connection on project").build()
        val (connection, repoId) = ctx
        val builder = GetMergeRequestDiffResponse.newBuilder().setOk(true)
        when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val (owner, repo, prNumber) = parseGitHubPr(mrUrl)
                gitHubClient.getPullRequestFiles(
                    connection = connection,
                    owner = owner,
                    repo = repo,
                    prNumber = prNumber,
                ).forEach { f ->
                    builder.addDiffs(
                        DiffEntry.newBuilder()
                            .setOldPath(f.filename)
                            .setNewPath(f.filename)
                            .setNewFile(f.status == "added")
                            .setDeletedFile(f.status == "removed")
                            .setRenamedFile(f.status == "renamed")
                            .setDiff(f.patch ?: "")
                            .build(),
                    )
                }
            }
            ProviderEnum.GITLAB -> {
                val mrIid = parseGitLabMr(mrUrl)
                gitLabClient.getMergeRequestDiffs(
                    connection = connection,
                    projectId = repoId,
                    mrIid = mrIid,
                ).forEach { d ->
                    builder.addDiffs(
                        DiffEntry.newBuilder()
                            .setOldPath(d.old_path)
                            .setNewPath(d.new_path)
                            .setNewFile(d.new_file)
                            .setDeletedFile(d.deleted_file)
                            .setRenamedFile(d.renamed_file)
                            .setDiff(d.diff)
                            .build(),
                    )
                }
            }
            else -> return GetMergeRequestDiffResponse.newBuilder().setOk(false)
                .setError("Unsupported provider: ${connection.provider}").build()
        }
        logger.info { "MR_DIFF | task=${request.taskId} | provider=${connection.provider}" }
        return builder.build()
    }

    override suspend fun postMrComment(request: PostMrCommentRequest): PostMrCommentResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return PostMrCommentResponse.newBuilder().setOk(false).setError("Task not found").build()
        val mrUrl = request.mergeRequestUrl.takeIf { it.isNotBlank() }
            ?: task.mergeRequestUrl
            ?: return PostMrCommentResponse.newBuilder().setOk(false)
                .setError("No MR URL on task or in request").build()
        val ctx = resolveRepoContext(task)
            ?: return PostMrCommentResponse.newBuilder().setOk(false)
                .setError("No REPOSITORY resource or connection on project").build()
        val (connection, repoId) = ctx
        return when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val (owner, repo, prNumber) = parseGitHubPr(mrUrl)
                gitHubClient.commentOnPullRequest(
                    connection = connection,
                    owner = owner,
                    repo = repo,
                    prNumber = prNumber,
                    body = request.comment,
                )
                logger.info { "MR_COMMENTED | task=${request.taskId} | url=$mrUrl" }
                PostMrCommentResponse.newBuilder().setOk(true).build()
            }
            ProviderEnum.GITLAB -> {
                val mrIid = parseGitLabMr(mrUrl)
                gitLabClient.addMergeRequestNote(
                    connection = connection,
                    projectId = repoId,
                    mrIid = mrIid,
                    noteBody = request.comment,
                )
                logger.info { "MR_COMMENTED | task=${request.taskId} | url=$mrUrl" }
                PostMrCommentResponse.newBuilder().setOk(true).build()
            }
            else -> PostMrCommentResponse.newBuilder().setOk(false)
                .setError("Unsupported provider: ${connection.provider}").build()
        }
    }

    override suspend fun postMrInlineComments(
        request: PostMrInlineCommentsRequest,
    ): PostMrInlineCommentsResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return PostMrInlineCommentsResponse.newBuilder().setOk(false)
                .setError("Task not found").build()
        val mrUrl = request.mergeRequestUrl.takeIf { it.isNotBlank() }
            ?: task.mergeRequestUrl
            ?: return PostMrInlineCommentsResponse.newBuilder().setOk(false)
                .setError("No MR URL").build()
        val ctx = resolveRepoContext(task)
            ?: return PostMrInlineCommentsResponse.newBuilder().setOk(false)
                .setError("No REPOSITORY resource or connection on project").build()
        val (connection, repoId) = ctx
        return when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val (owner, repo, prNumber) = parseGitHubPr(mrUrl)
                val reviewComments = request.commentsList.mapNotNull { c ->
                    if (c.file.isNotBlank() && c.line > 0) {
                        GitHubReviewComment(path = c.file, line = c.line, body = c.body)
                    } else null
                }
                val event = when (request.verdict) {
                    "APPROVE" -> "APPROVE"
                    "REQUEST_CHANGES" -> "REQUEST_CHANGES"
                    else -> "COMMENT"
                }
                gitHubClient.createPullRequestReview(
                    connection = connection,
                    owner = owner,
                    repo = repo,
                    prNumber = prNumber,
                    body = request.summary,
                    event = event,
                    comments = reviewComments,
                )
                logger.info { "MR_INLINE_COMMENTS | task=${request.taskId} | comments=${request.commentsList.size}" }
                PostMrInlineCommentsResponse.newBuilder().setOk(true).build()
            }
            ProviderEnum.GITLAB -> {
                val mrIid = parseGitLabMr(mrUrl)
                val versions = gitLabClient.getMergeRequestVersions(
                    connection = connection,
                    projectId = repoId,
                    mrIid = mrIid,
                )
                val latestVersion = versions.firstOrNull()
                if (request.summary.isNotBlank()) {
                    gitLabClient.addMergeRequestNote(
                        connection = connection,
                        projectId = repoId,
                        mrIid = mrIid,
                        noteBody = request.summary,
                    )
                }
                for (c in request.commentsList) {
                    if (c.file.isBlank() || c.line <= 0) continue
                    try {
                        gitLabClient.createMergeRequestDiscussion(
                            connection = connection,
                            projectId = repoId,
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
                        gitLabClient.addMergeRequestNote(
                            connection = connection,
                            projectId = repoId,
                            mrIid = mrIid,
                            noteBody = "**${c.file}:${c.line}** — ${c.body}",
                        )
                    }
                }
                logger.info { "MR_INLINE_COMMENTS | task=${request.taskId} | comments=${request.commentsList.size}" }
                PostMrInlineCommentsResponse.newBuilder().setOk(true).build()
            }
            else -> PostMrInlineCommentsResponse.newBuilder().setOk(false)
                .setError("Unsupported provider: ${connection.provider}").build()
        }
    }

    // ── Helpers ──

    private suspend fun resolveRepoContext(task: TaskDocument): Pair<ConnectionDocument, String>? {
        val projectId = task.projectId ?: return null
        val project: ProjectDocument = projectService.getProjectByIdOrNull(projectId) ?: return null
        val resource = project.resources.firstOrNull {
            it.capability == ConnectionCapability.REPOSITORY
        } ?: return null
        val connection = connectionService.findById(ConnectionId(resource.connectionId)) ?: return null
        return connection to resource.resourceIdentifier
    }

    private fun errorCreate(msg: String): CreateMergeRequestResponse =
        CreateMergeRequestResponse.newBuilder().setOk(false).setError(msg).build()

    private fun parseGitHubPr(mrUrl: String): Triple<String, String, Int> {
        val match = Regex("""/([^/]+)/([^/]+)/pull/(\d+)""").find(mrUrl)
            ?: error("Cannot parse GitHub PR URL: $mrUrl")
        val (owner, repo, prNumber) = match.destructured
        return Triple(owner, repo, prNumber.toInt())
    }

    private fun parseGitLabMr(mrUrl: String): Int {
        val match = Regex("""/merge_requests/(\d+)""").find(mrUrl)
            ?: error("Cannot parse GitLab MR URL: $mrUrl")
        return match.groupValues[1].toInt()
    }
}
