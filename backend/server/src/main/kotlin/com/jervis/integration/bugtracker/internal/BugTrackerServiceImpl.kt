package com.jervis.integration.bugtracker.internal

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerIssueRequest
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.ProviderEnum
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
    private val providerRegistry: ProviderRegistry,
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
                reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
            ) {
                bugTrackerClient.searchIssues(
                    BugTrackerSearchRequest(
                        baseUrl = connection.baseUrl,
                        authType = AuthType.valueOf(connection.authType.name),
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
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
                issueType = "Task",
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
                reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
            ) {
                bugTrackerClient.getIssue(
                    BugTrackerIssueRequest(
                        baseUrl = connection.baseUrl,
                        authType = AuthType.valueOf(connection.authType.name),
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
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
        return emptyList()
    }

    override suspend fun getComments(
        clientId: ClientId,
        issueKey: String,
    ): List<BugTrackerComment> {
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
                conn.protocol == com.jervis.dto.connection.ProtocolEnum.HTTP &&
                conn.baseUrl.contains("atlassian.net", ignoreCase = true)
            ) {
                return conn
            }
        }
        return null
    }
}
