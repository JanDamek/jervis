package com.jervis.integration.wiki.internal

import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.wiki.WikiPageRequest
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.wiki.CreatePageRequest
import com.jervis.integration.wiki.UpdatePageRequest
import com.jervis.integration.wiki.WikiPage
import com.jervis.integration.wiki.WikiService
import com.jervis.integration.wiki.WikiSpace
import com.jervis.service.client.ClientService
import com.jervis.service.connection.ConnectionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class WikiServiceImpl(
    private val wikiClient: IWikiClient,
    private val clientService: ClientService,
    private val connectionService: ConnectionService,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) : WikiService {
    private val logger = KotlinLogging.logger {}

    override suspend fun searchPages(
        clientId: ClientId,
        query: String,
        spaceKey: String?,
        maxResults: Int,
    ): List<WikiPage> {
        val connection = findWikiConnection(clientId) ?: return emptyList()

        val response =
            withRpcRetry(
                name = "WikiSearch",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                wikiClient.searchPages(
                    WikiSearchRequest(
                        baseUrl = connection.baseUrl,
                        authType = getAuthType(connection),
                        basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                        spaceKey = spaceKey,
                        query = query,
                        maxResults = maxResults,
                    ),
                )
            }

        return response.pages.map { page ->
            WikiPage(
                id = page.id,
                title = page.title,
                content = "", // Only summary in search results
                spaceKey = page.spaceKey ?: "",
                created = page.created,
                updated = page.updated,
                version = 1,
                parentId = null,
            )
        }
    }

    override suspend fun getPage(
        clientId: ClientId,
        pageId: String,
    ): WikiPage {
        val connection =
            findWikiConnection(clientId)
                ?: throw IllegalStateException("No Wiki connection found for client $clientId")

        val response =
            withRpcRetry(
                name = "WikiGetPage",
                reconnect = { reconnectHandler.reconnectAtlassian() },
            ) {
                wikiClient.getPage(
                    WikiPageRequest(
                        baseUrl = connection.baseUrl,
                        authType = getAuthType(connection),
                        basicUsername = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.username,
                        basicPassword = (connection.credentials as? ConnectionDocument.HttpCredentials.Basic)?.password,
                        bearerToken = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token,
                        pageId = pageId,
                    ),
                )
            }.page

        return WikiPage(
            id = response.id,
            title = response.title,
            content = response.content ?: "",
            spaceKey = response.spaceKey ?: "",
            created = response.created,
            updated = response.updated,
            version = 1,
            parentId = null,
        )
    }

    override suspend fun listSpaces(clientId: ClientId): List<WikiSpace> {
        // NOTE: IAtlassianClient doesn't have listSpaces yet.
        return emptyList()
    }

    override suspend fun getChildren(
        clientId: ClientId,
        pageId: String,
    ): List<WikiPage> {
        // NOTE: IAtlassianClient doesn't have getChildren yet.
        return emptyList()
    }

    override suspend fun createPage(
        clientId: ClientId,
        request: CreatePageRequest,
    ): WikiPage = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun updatePage(
        clientId: ClientId,
        pageId: String,
        request: UpdatePageRequest,
    ): WikiPage = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    private suspend fun findWikiConnection(clientId: ClientId): ConnectionDocument? {
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
