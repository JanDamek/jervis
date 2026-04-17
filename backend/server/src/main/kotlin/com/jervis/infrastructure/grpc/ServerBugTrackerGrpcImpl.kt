package com.jervis.infrastructure.grpc

import com.jervis.bugtracker.BugTrackerService
import com.jervis.bugtracker.CreateBugTrackerIssueRequest
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.contracts.server.AddIssueCommentRequest
import com.jervis.contracts.server.CommentResponse
import com.jervis.contracts.server.CreateIssueRequest
import com.jervis.contracts.server.IssueListItem
import com.jervis.contracts.server.IssueResponse
import com.jervis.contracts.server.ListIssuesRequest
import com.jervis.contracts.server.ListIssuesResponse
import com.jervis.contracts.server.ServerBugTrackerServiceGrpcKt
import com.jervis.contracts.server.UpdateIssueRequest
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.git.client.GitHubClient
import com.jervis.git.client.GitLabClient
import com.jervis.project.ProjectService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerBugTrackerGrpcImpl(
    private val projectService: ProjectService,
    private val connectionService: ConnectionService,
    private val gitHubClient: GitHubClient,
    private val gitLabClient: GitLabClient,
    private val bugTrackerService: BugTrackerService,
) : ServerBugTrackerServiceGrpcKt.ServerBugTrackerServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun createIssue(request: CreateIssueRequest): IssueResponse {
        val resolved = resolve(request.clientId, request.projectId)
            ?: return IssueResponse.newBuilder().setOk(false)
                .setError("No BUGTRACKER connection found for project ${request.projectId}")
                .build()
        val (connection, resourceId) = resolved
        val description = request.description.takeIf { it.isNotBlank() }
        return when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val parts = resourceId.split("/", limit = 2)
                require(parts.size == 2) { "Invalid GitHub resource identifier: $resourceId" }
                val issue = gitHubClient.createIssue(
                    connection = connection,
                    owner = parts[0],
                    repo = parts[1],
                    title = request.title,
                    body = description,
                    labels = request.labelsList,
                )
                logger.info { "ISSUE_CREATED | github | ${parts[0]}/${parts[1]}#${issue.number}" }
                IssueResponse.newBuilder().setOk(true).setKey("#${issue.number}").setUrl(issue.html_url).build()
            }
            ProviderEnum.GITLAB -> {
                val issue = gitLabClient.createIssue(
                    connection = connection,
                    projectId = resourceId,
                    title = request.title,
                    description = description,
                    labels = request.labelsList,
                )
                logger.info { "ISSUE_CREATED | gitlab | $resourceId#${issue.iid}" }
                IssueResponse.newBuilder().setOk(true).setKey("#${issue.iid}").setUrl(issue.web_url).build()
            }
            ProviderEnum.ATLASSIAN -> {
                val issue = bugTrackerService.createIssue(
                    clientId = ClientId(ObjectId(request.clientId)),
                    request = CreateBugTrackerIssueRequest(
                        projectKey = resourceId,
                        summary = request.title,
                        description = description,
                        issueType = request.issueType.takeIf { it.isNotBlank() } ?: "Task",
                        priority = request.priority.takeIf { it.isNotBlank() },
                        labels = request.labelsList,
                    ),
                )
                logger.info { "ISSUE_CREATED | jira | ${issue.key}" }
                IssueResponse.newBuilder().setOk(true).setKey(issue.key).build()
            }
            else -> IssueResponse.newBuilder().setOk(false)
                .setError("Unsupported provider: ${connection.provider}")
                .build()
        }
    }

    override suspend fun addIssueComment(request: AddIssueCommentRequest): CommentResponse {
        val resolved = resolve(request.clientId, request.projectId)
            ?: return CommentResponse.newBuilder().setOk(false)
                .setError("No BUGTRACKER connection found")
                .build()
        val (connection, resourceId) = resolved
        return when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
                    ?: return CommentResponse.newBuilder().setOk(false)
                        .setError("Invalid issue number: ${request.issueKey}").build()
                val parts = resourceId.split("/", limit = 2)
                require(parts.size == 2) { "Invalid GitHub resource identifier: $resourceId" }
                val comment = gitHubClient.addIssueComment(
                    connection = connection,
                    owner = parts[0],
                    repo = parts[1],
                    issueNumber = issueNumber,
                    body = request.comment,
                )
                logger.info { "ISSUE_COMMENT_ADDED | github | ${parts[0]}/${parts[1]}#$issueNumber" }
                CommentResponse.newBuilder().setOk(true).setUrl(comment.html_url).build()
            }
            ProviderEnum.GITLAB -> {
                val issueIid = request.issueKey.removePrefix("#").toIntOrNull()
                    ?: return CommentResponse.newBuilder().setOk(false)
                        .setError("Invalid issue IID: ${request.issueKey}").build()
                gitLabClient.addIssueNote(
                    connection = connection,
                    projectId = resourceId,
                    issueIid = issueIid,
                    noteBody = request.comment,
                )
                logger.info { "ISSUE_COMMENT_ADDED | gitlab | $resourceId#$issueIid" }
                CommentResponse.newBuilder().setOk(true).build()
            }
            ProviderEnum.ATLASSIAN -> {
                bugTrackerService.addComment(
                    clientId = ClientId(ObjectId(request.clientId)),
                    issueKey = request.issueKey,
                    comment = request.comment,
                )
                logger.info { "ISSUE_COMMENT_ADDED | jira | ${request.issueKey}" }
                CommentResponse.newBuilder().setOk(true).build()
            }
            else -> CommentResponse.newBuilder().setOk(false)
                .setError("Unsupported provider: ${connection.provider}").build()
        }
    }

    override suspend fun updateIssue(request: UpdateIssueRequest): IssueResponse {
        val resolved = resolve(request.clientId, request.projectId)
            ?: return IssueResponse.newBuilder().setOk(false)
                .setError("No BUGTRACKER connection found").build()
        val (connection, resourceId) = resolved
        val title = request.title.takeIf { it.isNotBlank() }
        val description = request.description.takeIf { it.isNotBlank() }
        val state = request.state.takeIf { it.isNotBlank() }
        val labels = if (request.hasLabels) request.labelsList else null
        return when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val issueNumber = request.issueKey.removePrefix("#").toIntOrNull()
                    ?: return IssueResponse.newBuilder().setOk(false)
                        .setError("Invalid issue number: ${request.issueKey}").build()
                val parts = resourceId.split("/", limit = 2)
                require(parts.size == 2) { "Invalid GitHub resource identifier: $resourceId" }
                val issue = gitHubClient.updateIssue(
                    connection = connection,
                    owner = parts[0],
                    repo = parts[1],
                    issueNumber = issueNumber,
                    title = title,
                    body = description,
                    state = state,
                    labels = labels,
                )
                logger.info { "ISSUE_UPDATED | github | ${parts[0]}/${parts[1]}#$issueNumber state=$state" }
                IssueResponse.newBuilder().setOk(true).setKey("#${issue.number}").setUrl(issue.html_url).build()
            }
            ProviderEnum.GITLAB -> {
                val issueIid = request.issueKey.removePrefix("#").toIntOrNull()
                    ?: return IssueResponse.newBuilder().setOk(false)
                        .setError("Invalid issue IID: ${request.issueKey}").build()
                val stateEvent = when (state) {
                    "closed" -> "close"
                    "open" -> "reopen"
                    else -> null
                }
                val issue = gitLabClient.updateIssue(
                    connection = connection,
                    projectId = resourceId,
                    issueIid = issueIid,
                    title = title,
                    description = description,
                    stateEvent = stateEvent,
                    labels = labels,
                )
                logger.info { "ISSUE_UPDATED | gitlab | $resourceId#$issueIid state=$state" }
                IssueResponse.newBuilder().setOk(true).setKey("#${issue.iid}").setUrl(issue.web_url).build()
            }
            else -> IssueResponse.newBuilder().setOk(false)
                .setError("update_issue not supported for provider: ${connection.provider}")
                .build()
        }
    }

    override suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse {
        val resolved = resolve(request.clientId, request.projectId)
            ?: return ListIssuesResponse.newBuilder().setOk(false)
                .setError("No BUGTRACKER connection found").build()
        val (connection, resourceId) = resolved
        val builder = ListIssuesResponse.newBuilder().setOk(true)
        when (connection.provider) {
            ProviderEnum.GITHUB -> {
                val parts = resourceId.split("/", limit = 2)
                require(parts.size == 2) { "Invalid GitHub resource identifier: $resourceId" }
                gitHubClient.listIssues(connection = connection, owner = parts[0], repo = parts[1]).forEach { issue ->
                    builder.addIssues(
                        IssueListItem.newBuilder()
                            .setKey("#${issue.number}")
                            .setTitle(issue.title)
                            .setState(issue.state)
                            .setUrl(issue.html_url)
                            .setCreated(issue.created_at)
                            .setUpdated(issue.updated_at)
                            .build(),
                    )
                }
            }
            ProviderEnum.GITLAB -> {
                gitLabClient.listIssues(connection = connection, projectId = resourceId).forEach { issue ->
                    builder.addIssues(
                        IssueListItem.newBuilder()
                            .setKey("#${issue.iid}")
                            .setTitle(issue.title)
                            .setState(issue.state)
                            .setUrl(issue.web_url)
                            .setCreated(issue.created_at)
                            .setUpdated(issue.updated_at)
                            .build(),
                    )
                }
            }
            ProviderEnum.ATLASSIAN -> {
                bugTrackerService.searchIssues(
                    clientId = ClientId(ObjectId(request.clientId)),
                    query = "ORDER BY updated DESC",
                    project = resourceId,
                    maxResults = 50,
                ).forEach { issue ->
                    builder.addIssues(
                        IssueListItem.newBuilder()
                            .setKey(issue.key)
                            .setTitle(issue.summary)
                            .setState(issue.status)
                            .setUrl("")
                            .setCreated(issue.created)
                            .setUpdated(issue.updated)
                            .build(),
                    )
                }
            }
            else -> {
                return ListIssuesResponse.newBuilder().setOk(false)
                    .setError("Unsupported provider: ${connection.provider}").build()
            }
        }
        return builder.build()
    }

    private suspend fun resolve(clientId: String, projectId: String): Pair<ConnectionDocument, String>? {
        val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(projectId))) ?: return null
        val resource = project.resources.firstOrNull {
            it.capability == ConnectionCapability.BUGTRACKER
        } ?: project.resources.firstOrNull {
            it.capability == ConnectionCapability.REPOSITORY
        } ?: return null
        val connection = connectionService.findById(ConnectionId(resource.connectionId)) ?: return null
        return connection to resource.resourceIdentifier
    }
}
