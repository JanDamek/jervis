package com.jervis.integration.wiki.internal

import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.wiki.WikiCreatePageRpcRequest
import com.jervis.common.dto.wiki.WikiPageRequest
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.dto.wiki.WikiUpdatePageRpcRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.ProviderEnum
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
    private val providerRegistry: ProviderRegistry,
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
                reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
            ) {
                wikiClient.searchPages(
                    WikiSearchRequest(
                        baseUrl = connection.baseUrl,
                        authType = com.jervis.common.dto.AuthType.valueOf(connection.authType.name),
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
                        cloudId = connection.cloudId,
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
                reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
            ) {
                wikiClient.getPage(
                    WikiPageRequest(
                        baseUrl = connection.baseUrl,
                        authType = com.jervis.common.dto.AuthType.valueOf(connection.authType.name),
                        basicUsername = connection.username,
                        basicPassword = connection.password,
                        bearerToken = connection.bearerToken,
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
    ): WikiPage {
        val connection = findWikiConnection(clientId)
            ?: throw IllegalStateException("No Wiki connection found for client $clientId")

        val response = withRpcRetry(
            name = "WikiCreatePage",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            wikiClient.createPage(
                WikiCreatePageRpcRequest(
                    baseUrl = connection.baseUrl,
                    authType = com.jervis.common.dto.AuthType.valueOf(connection.authType.name),
                    basicUsername = connection.username,
                    basicPassword = connection.password,
                    bearerToken = connection.bearerToken,
                    cloudId = connection.cloudId,
                    spaceKey = request.spaceKey,
                    title = request.title,
                    content = request.content,
                    parentPageId = request.parentId,
                ),
            )
        }.page

        return WikiPage(
            id = response.id,
            title = response.title,
            content = response.content ?: request.content,
            spaceKey = response.spaceKey ?: request.spaceKey,
            created = response.created,
            updated = response.updated,
            version = 1,
            parentId = response.parentId ?: request.parentId,
        )
    }

    override suspend fun updatePage(
        clientId: ClientId,
        pageId: String,
        request: UpdatePageRequest,
    ): WikiPage {
        val connection = findWikiConnection(clientId)
            ?: throw IllegalStateException("No Wiki connection found for client $clientId")

        // We need the current page to get title if not provided
        val currentTitle = request.title ?: getPage(clientId, pageId).title
        val currentContent = request.content ?: getPage(clientId, pageId).content

        val response = withRpcRetry(
            name = "WikiUpdatePage",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            wikiClient.updatePage(
                WikiUpdatePageRpcRequest(
                    baseUrl = connection.baseUrl,
                    authType = com.jervis.common.dto.AuthType.valueOf(connection.authType.name),
                    basicUsername = connection.username,
                    basicPassword = connection.password,
                    bearerToken = connection.bearerToken,
                    cloudId = connection.cloudId,
                    pageId = pageId,
                    title = currentTitle,
                    content = currentContent,
                    version = request.version,
                ),
            )
        }.page

        return WikiPage(
            id = response.id,
            title = response.title,
            content = response.content ?: currentContent,
            spaceKey = response.spaceKey ?: "",
            created = response.created,
            updated = response.updated,
            version = request.version,
            parentId = response.parentId,
        )
    }

    private suspend fun findWikiConnection(clientId: ClientId): ConnectionDocument? {
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
