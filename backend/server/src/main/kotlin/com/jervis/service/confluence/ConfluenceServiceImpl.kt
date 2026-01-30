package com.jervis.service.confluence

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.client.ClientService
import com.jervis.service.connection.ConnectionService
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ConfluenceServiceImpl(
    private val atlassianClient: IAtlassianClient,
    private val clientService: ClientService,
    private val connectionService: ConnectionService,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) : ConfluenceService {
    private val logger = KotlinLogging.logger {}

    override suspend fun searchPages(
        clientId: ClientId,
        query: String,
        spaceKey: String?,
        maxResults: Int,
    ): List<ConfluencePage> {
        val connection = findConfluenceConnection(clientId) ?: return emptyList()

        val response = withRpcRetry(
            name = "ConfluenceSearch",
            reconnect = { reconnectHandler.reconnectAtlassian() }
        ) {
            atlassianClient.searchConfluencePages(
                ConfluenceSearchRequest(
                    baseUrl = connection.baseUrl,
                    authType = getAuthType(connection),
                    basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                    basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                    bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                    spaceKey = spaceKey,
                    cql = query,
                    maxResults = maxResults,
                ),
            )
        }

        return response.pages.map { page ->
            ConfluencePage(
                id = page.id,
                title = page.title,
                content = "", // Only summary in search results
                spaceKey = page.spaceKey ?: "",
                created = page.createdDate ?: "",
                updated = page.lastModified ?: "",
                version = page.version?.number ?: 1,
                parentId = page.parentId,
            )
        }
    }

    override suspend fun getPage(
        clientId: ClientId,
        pageId: String,
    ): ConfluencePage {
        val connection =
            findConfluenceConnection(clientId)
                ?: throw IllegalStateException("No Confluence connection found for client $clientId")

        val response = withRpcRetry(
            name = "ConfluenceGetPage",
            reconnect = { reconnectHandler.reconnectAtlassian() }
        ) {
            atlassianClient.getConfluencePage(
                ConfluencePageRequest(
                    baseUrl = connection.baseUrl,
                    authType = getAuthType(connection),
                    basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                    basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                    bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                    pageId = pageId,
                ),
            )
        }

        return ConfluencePage(
            id = response.id,
            title = response.title,
            content = response.body?.storage?.value ?: response.body?.view?.value ?: "",
            spaceKey = response.spaceKey ?: "",
            created = response.createdDate ?: "",
            updated = response.lastModified ?: "",
            version = response.version?.number ?: 1,
            parentId = response.parentId,
        )
    }

    override suspend fun listSpaces(clientId: ClientId): List<ConfluenceSpace> {
        // NOTE: IAtlassianClient doesn't have listSpaces yet.
        return emptyList()
    }

    override suspend fun getChildren(
        clientId: ClientId,
        pageId: String,
    ): List<ConfluencePage> {
        // NOTE: IAtlassianClient doesn't have getChildren yet.
        return emptyList()
    }

    override suspend fun createPage(
        clientId: ClientId,
        request: CreatePageRequest,
    ): ConfluencePage = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun updatePage(
        clientId: ClientId,
        pageId: String,
        request: UpdatePageRequest,
    ): ConfluencePage = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    private suspend fun findConfluenceConnection(clientId: ClientId): ConnectionDocument? {
        val client = clientService.getClientById(clientId) ?: return null
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
