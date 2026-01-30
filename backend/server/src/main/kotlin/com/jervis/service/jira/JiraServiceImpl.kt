package com.jervis.service.jira

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ClientId
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class JiraServiceImpl(
    private val atlassianClient: IAtlassianClient,
    private val connectionService: ConnectionService,
    private val clientService: com.jervis.service.client.ClientService,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) : JiraService {
    private val logger = KotlinLogging.logger {}

    override suspend fun searchIssues(
        clientId: ClientId,
        query: String,
        project: String?,
        maxResults: Int,
    ): List<JiraIssue> {
        val connection = findJiraConnection(clientId) ?: return emptyList()
        val finalQuery = if (project != null) "project = \"$project\" AND ($query)" else query

        val response = withRpcRetry(
            name = "JiraSearch",
            reconnect = { reconnectHandler.reconnectAtlassian() }
        ) {
            atlassianClient.searchJiraIssues(
                JiraSearchRequest(
                    baseUrl = connection.baseUrl ?: "",
                    authType = getAuthType(connection),
                    basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                    basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                    bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                    jql = finalQuery,
                    maxResults = maxResults,
                ),
            )
        }

        return response.issues.map { issue ->
            JiraIssue(
                key = issue.key,
                summary = issue.fields.summary ?: "",
                description = null, // Summary only for search results
                status = issue.fields.status?.name ?: "Unknown",
                assignee = issue.fields.assignee?.displayName,
                reporter = issue.fields.reporter?.displayName ?: "Unknown",
                created = issue.fields.created ?: "",
                updated = issue.fields.updated ?: "",
                issueType = issue.fields.issueType?.name ?: "Unknown",
                priority = issue.fields.priority?.name,
                labels = issue.fields.labels ?: emptyList(),
            )
        }
    }

    override suspend fun getIssue(
        clientId: ClientId,
        issueKey: String,
    ): JiraIssue {
        val connection =
            findJiraConnection(clientId) ?: throw IllegalStateException("No Jira connection found for client $clientId")

        val response = withRpcRetry(
            name = "JiraGetIssue",
            reconnect = { reconnectHandler.reconnectAtlassian() }
        ) {
            atlassianClient.getJiraIssue(
                JiraIssueRequest(
                    baseUrl = connection.baseUrl ?: "",
                    authType = getAuthType(connection),
                    basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                    basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                    bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                    issueKey = issueKey,
                ),
            )
        }

        val fields = response.fields
        return JiraIssue(
            key = response.key,
            summary = fields.summary ?: "",
            description = response.renderedDescription ?: fields.description?.toString(),
            status = fields.status?.name ?: "Unknown",
            assignee = fields.assignee?.displayName,
            reporter = fields.reporter?.displayName ?: "Unknown",
            created = fields.created ?: "",
            updated = fields.updated ?: "",
            issueType = fields.issueType?.name ?: "Unknown",
            priority = fields.priority?.name,
            labels = fields.labels ?: emptyList(),
        )
    }

    override suspend fun listProjects(clientId: ClientId): List<JiraProject> {
        // NOTE: IAtlassianClient doesn't have listProjects yet.
        // For now, return empty or implement via search if possible.
        // Actually, AtlassianApiClient might have it. Let's check.
        return emptyList()
    }

    override suspend fun getComments(
        clientId: ClientId,
        issueKey: String,
    ): List<JiraComment> {
        val connection = findJiraConnection(clientId) ?: return emptyList()

        val response = withRpcRetry(
            name = "JiraGetComments",
            reconnect = { reconnectHandler.reconnectAtlassian() }
        ) {
            atlassianClient.getJiraIssue(
                JiraIssueRequest(
                    baseUrl = connection.baseUrl ?: "",
                    authType = getAuthType(connection),
                    basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                    basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                    bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                    issueKey = issueKey,
                ),
            )
        }

        return response.comments?.map { comment ->
            JiraComment(
                id = comment.id,
                author = comment.author?.displayName ?: "Unknown",
                body = comment.body?.toString() ?: "",
                created = comment.created ?: "",
            )
        } ?: emptyList()
    }

    override suspend fun createIssue(
        clientId: ClientId,
        request: CreateJiraIssueRequest,
    ): JiraIssue = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun updateIssue(
        clientId: ClientId,
        issueKey: String,
        request: UpdateJiraIssueRequest,
    ): JiraIssue = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun addComment(
        clientId: ClientId,
        issueKey: String,
        comment: String,
    ): JiraComment = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    private suspend fun findJiraConnection(clientId: ClientId): ConnectionDocument? {
        val client = clientService.getClientById(clientId) ?: return null
        val connectionIds = client.connectionIds.map { com.jervis.types.ConnectionId(it) }

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
