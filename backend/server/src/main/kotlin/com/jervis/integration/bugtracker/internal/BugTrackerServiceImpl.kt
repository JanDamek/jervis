package com.jervis.integration.bugtracker.internal

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.bugtracker.BugTrackerIssueRequest
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.RpcReconnectHandler
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.BugTrackerComment
import com.jervis.integration.bugtracker.BugTrackerIssue
import com.jervis.integration.bugtracker.BugTrackerProject
import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.integration.bugtracker.CreateBugTrackerIssueRequest
import com.jervis.integration.bugtracker.UpdateBugTrackerIssueRequest
import com.jervis.service.client.ClientService
import com.jervis.service.connection.ConnectionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class BugTrackerServiceImpl(
    private val bugTrackerClient: IBugTrackerClient,
    private val connectionService: ConnectionService,
    private val clientService: ClientService,
    private val reconnectHandler: RpcReconnectHandler,
) : BugTrackerService {
    private val logger = KotlinLogging.logger {}

    override suspend fun searchIssues(
        clientId: ClientId,
        query: String,
        project: String?,
        maxResults: Int,
    ): List<BugTrackerIssue> {
        val connection = findBugTrackerConnection(clientId) ?: return emptyList()
        val finalQuery = if (project != null) "project = \"$project\" AND ($query)" else query

        val response =
            withRpcRetry(
                name = "BugTrackerSearch",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                bugTrackerClient.searchIssues(
                    BugTrackerSearchRequest(
                        baseUrl = connection.baseUrl,
                        authType = getAuthType(connection),
                        basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                        query = finalQuery,
                        maxResults = maxResults,
                    ),
                )
            }

        return response.issues.map { issue ->
            BugTrackerIssue(
                key = issue.key,
                summary = issue.title,
                description = issue.description,
                status = issue.status,
                assignee = issue.assignee,
                reporter = issue.reporter ?: "Unknown",
                created = issue.created,
                updated = issue.updated,
                issueType = "Task", // Defaulting as not available in generic DTO
                priority = issue.priority,
                labels = emptyList(),
            )
        }
    }

    override suspend fun getIssue(
        clientId: ClientId,
        issueKey: String,
    ): BugTrackerIssue {
        val connection =
            findBugTrackerConnection(clientId)
                ?: throw IllegalStateException("No BugTracker connection found for client $clientId")

        val response =
            withRpcRetry(
                name = "BugTrackerGetIssue",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                bugTrackerClient.getIssue(
                    BugTrackerIssueRequest(
                        baseUrl = connection.baseUrl,
                        authType = getAuthType(connection),
                        basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                        issueKey = issueKey,
                    ),
                )
            }.issue

        return BugTrackerIssue(
            key = response.key,
            summary = response.title,
            description = response.description,
            status = response.status,
            assignee = response.assignee,
            reporter = response.reporter ?: "Unknown",
            created = response.created,
            updated = response.updated,
            issueType = "Task",
            priority = response.priority,
            labels = emptyList(),
        )
    }

    override suspend fun listProjects(clientId: ClientId): List<BugTrackerProject> {
        // NOTE: IAtlassianClient doesn't have listProjects yet.
        // For now, return empty or implement via search if possible.
        // Actually, AtlassianApiClient might have it. Let's check.
        return emptyList()
    }

    override suspend fun getComments(
        clientId: ClientId,
        issueKey: String,
    ): List<BugTrackerComment> {
        // Jira comments are currently not supported by generic BugTrackerClient.
        // Needs addition to IBugTrackerClient if required.
        return emptyList()
    }

    override suspend fun createIssue(
        clientId: ClientId,
        request: CreateBugTrackerIssueRequest,
    ): BugTrackerIssue = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun updateIssue(
        clientId: ClientId,
        issueKey: String,
        request: UpdateBugTrackerIssueRequest,
    ): BugTrackerIssue = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun addComment(
        clientId: ClientId,
        issueKey: String,
        comment: String,
    ): BugTrackerComment = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    private suspend fun findBugTrackerConnection(clientId: ClientId): ConnectionDocument? {
        val client = clientService.getClientById(clientId)
        val connectionIds = client.connectionIds.map { ConnectionId(it) }

        for (id in connectionIds) {
            val conn = connectionService.findById(id) ?: continue
            if (conn.state == com.jervis.dto.connection.ConnectionStateEnum.VALID &&
                conn.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP &&
                conn.baseUrl.contains("atlassian.net", ignoreCase = true)
            ) {
                return conn
            }
        }
        return null
    }

    private fun getAuthType(connection: ConnectionDocument): String =
        when (connection.credentials) {
            is ConnectionDocument.HttpCredentials.Basic -> "BASIC"
            is ConnectionDocument.HttpCredentials.Bearer -> "BEARER"
            else -> "NONE"
        }
}
