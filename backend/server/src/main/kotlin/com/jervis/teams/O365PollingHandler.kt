package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.client.ClientDocument
import com.jervis.project.ProjectDocument
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.infrastructure.oauth2.OAuth2Service
import com.jervis.infrastructure.polling.PollingResult
import com.jervis.infrastructure.polling.PollingStateService
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.PollingHandler
import com.jervis.infrastructure.polling.handler.ResourceFilter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for Microsoft Teams / Microsoft 365.
 *
 * Triple-mode:
 * - **OAuth2 connections**: call Microsoft Graph API directly with bearer token
 * - **Browser Session (with token)**: call O365 Gateway (which uses browser pool tokens)
 * - **Browser Session (VLM scraping)**: read from o365_scrape_messages collection
 *
 * Messages are stored as TeamsMessageIndexDocument (NEW state).
 * TeamsContinuousIndexer picks them up for KB indexing.
 */
@Component
class O365PollingHandler(
    private val repository: TeamsMessageIndexRepository,
    private val scrapeMessageRepository: O365ScrapeMessageRepository,
    private val pollingStateService: PollingStateService,
    private val httpClient: HttpClient,
    private val oauth2Service: OAuth2Service,
    private val connectionService: ConnectionService,
    private val mongoTemplate: ReactiveMongoTemplate,
    @Value("\${jervis.o365-gateway.url:http://jervis-o365-gateway:8080}")
    private val gatewayUrl: String,
    @Value("\${jervis.o365-browser-pool.url:http://jervis-o365-browser-pool:8090}")
    private val browserPoolUrl: String,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.MICROSOFT_TEAMS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.CHAT_READ
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        // OAuth2 connections -> call Graph API directly with bearer token
        val isOAuth2 = connectionDocument.authType == AuthTypeEnum.OAUTH2 &&
            !connectionDocument.bearerToken.isNullOrBlank()

        // Browser Session connections (authType=NONE) -> read VLM scrape data from MongoDB
        val isBrowserScraping = connectionDocument.authType == AuthTypeEnum.NONE &&
            !connectionDocument.o365ClientId.isNullOrBlank()

        val o365ClientId = connectionDocument.o365ClientId
        if (!isOAuth2 && !isBrowserScraping && o365ClientId.isNullOrBlank()) {
            logger.warn { "O365 connection '${connectionDocument.name}' has no o365ClientId and no OAuth2 token" }
            return PollingResult(errors = 1, authenticationError = true)
        }

        // Browser scraping mode -- read from o365_scrape_messages collection
        if (isBrowserScraping) {
            // Skip polling if connection is invalid or new (no session yet)
            if (connectionDocument.state in listOf(ConnectionStateEnum.INVALID, ConnectionStateEnum.NEW)) {
                logger.debug { "Skipping O365 poll for '${connectionDocument.name}' -- state=${connectionDocument.state}" }
                return PollingResult()
            }

            // Proactive health check: verify browser pool session is alive
            // If session is EXPIRED/ERROR, mark connection INVALID immediately
            if (connectionDocument.state in listOf(ConnectionStateEnum.VALID, ConnectionStateEnum.DISCOVERING)) {
                try {
                    val statusResponse = httpClient.get("$browserPoolUrl/session/$o365ClientId")
                    if (statusResponse.status.isSuccess()) {
                        val statusJson = json.parseToJsonElement(statusResponse.body<String>()).jsonObject
                        val sessionState = statusJson["state"]?.jsonPrimitive?.content
                        if (sessionState in listOf("EXPIRED", "ERROR")) {
                            logger.warn { "Browser pool session $sessionState for '${connectionDocument.name}' -- marking INVALID" }
                            connectionService.save(connectionDocument.copy(state = ConnectionStateEnum.INVALID))
                            return PollingResult()
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "Browser pool unreachable for '${connectionDocument.name}' -- marking INVALID: ${e.message}" }
                    connectionService.save(connectionDocument.copy(state = ConnectionStateEnum.INVALID))
                    return PollingResult()
                }
            }

            // DISCOVERING passed health check (session alive) -- skip polling, wait for capabilities callback
            if (connectionDocument.state == ConnectionStateEnum.DISCOVERING) {
                logger.debug { "Skipping O365 poll for '${connectionDocument.name}' -- still discovering" }
                return PollingResult()
            }

            logger.debug { "O365 Teams polling for '${connectionDocument.name}' (VLM scraping/${o365ClientId})" }
            return try {
                pollFromScrapeMessages(connectionDocument, context)
            } catch (e: Exception) {
                logger.error(e) { "Error polling scrape messages for ${connectionDocument.name}" }
                PollingResult(errors = 1)
            }
        }

        // For OAuth2, refresh token if needed
        val accessToken = if (isOAuth2) {
            refreshOAuth2Token(connectionDocument)
        } else null

        if (isOAuth2 && accessToken == null) {
            logger.warn { "OAuth2 token refresh failed for '${connectionDocument.name}'" }
            return PollingResult(errors = 1, authenticationError = true)
        }

        val mode = if (isOAuth2) "OAuth2/GraphAPI" else "Gateway/$o365ClientId"
        logger.debug { "O365 Teams polling for '${connectionDocument.name}' ($mode)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        // Poll chats (1:1 and group chats)
        try {
            val chatResult = pollChats(connectionDocument, context, o365ClientId, accessToken)
            totalDiscovered += chatResult.itemsDiscovered
            totalCreated += chatResult.itemsCreated
            totalSkipped += chatResult.itemsSkipped
            totalErrors += chatResult.errors
        } catch (e: Exception) {
            logger.error(e) { "Error polling Teams chats for ${connectionDocument.name}" }
            totalErrors++
        }

        // Poll team channels
        try {
            val channelResult = pollChannels(connectionDocument, context, o365ClientId, accessToken)
            totalDiscovered += channelResult.itemsDiscovered
            totalCreated += channelResult.itemsCreated
            totalSkipped += channelResult.itemsSkipped
            totalErrors += channelResult.errors
        } catch (e: Exception) {
            logger.error(e) { "Error polling Teams channels for ${connectionDocument.name}" }
            totalErrors++
        }

        // If all calls failed and nothing was discovered, treat as auth error
        // This stops CentralPoller from retrying indefinitely
        val isAuthError = totalErrors > 0 && totalDiscovered == 0 && totalCreated == 0

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors,
            authenticationError = isAuthError,
        )
    }

    /**
     * Read VLM-scraped messages from o365_scrape_messages collection (state=NEW).
     * Convert to TeamsMessageIndexDocument and mark as PROCESSED.
     *
     * Server-side filtering: messages are matched against assigned resources
     * (project/client level). Unmatched messages are marked SKIPPED for auditability.
     */
    private suspend fun pollFromScrapeMessages(
        connection: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        val o365ClientId = connection.o365ClientId ?: return PollingResult()
        val scrapeMessages = scrapeMessageRepository
            .findByConnectionIdAndState(o365ClientId, "NEW")
            .toList()

        if (scrapeMessages.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (msg in scrapeMessages) {
            discovered++

            val syntheticMessageId = "scrape_${msg.messageHash}"

            if (repository.existsByConnectionIdAndMessageId(connection.id, syntheticMessageId)) {
                markScrapeMessageState(msg.id, "PROCESSED")
                skipped++
                continue
            }

            // Server-side filtering: resolve target client/project by chatName
            val chatName = msg.chatName ?: ""
            val (targetClientId, targetProjectId) = resolveScrapeTarget(connection, context, chatName)

            if (targetClientId == null) {
                // No resource filter matches this chat -- mark SKIPPED for auditability
                markScrapeMessageState(msg.id, "SKIPPED")
                skipped++
                continue
            }

            val doc = TeamsMessageIndexDocument(
                connectionId = connection.id,
                clientId = targetClientId,
                projectId = targetProjectId,
                messageId = syntheticMessageId,
                chatDisplayName = msg.chatName,
                from = msg.sender,
                body = msg.content,
                bodyContentType = "text",
                createdDateTime = parseInstant(msg.timestamp) ?: Instant.now(),
            )
            repository.save(doc)
            markScrapeMessageState(msg.id, "PROCESSED")
            created++
        }

        if (created > 0) {
            logger.info { "Processed $created VLM scrape messages for '${connection.name}'" }
        }

        return PollingResult(
            itemsDiscovered = discovered,
            itemsCreated = created,
            itemsSkipped = skipped,
        )
    }

    /**
     * Resolve target client/project for a VLM-scraped message by chatName.
     * Checks project-level resources first, then client-level.
     * If no filter is configured (IndexAll), routes to first client.
     */
    private fun resolveScrapeTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        chatName: String,
    ): Pair<ClientId?, ProjectId?> {
        // Check projects first -- project-level resources take priority
        for (project in context.projects) {
            val filter = context.getProjectResourceFilter(
                project.id, project.clientId, ConnectionCapability.CHAT_READ,
            )
            if (filter != null && filter.shouldIndex(chatName)) {
                return Pair(project.clientId, project.id)
            }
        }

        // Check client-level filters
        for (client in context.clients) {
            val filter = context.getResourceFilter(client.id, ConnectionCapability.CHAT_READ)
            if (filter != null && filter.shouldIndex(chatName)) {
                return Pair(client.id, null)
            }
        }

        return Pair(null, null)
    }

    /**
     * Mark a scrape message with given state (PROCESSED or SKIPPED).
     */
    private suspend fun markScrapeMessageState(id: org.bson.types.ObjectId, state: String) {
        try {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").`is`(id)),
                Update.update("state", state),
                O365ScrapeMessageDocument::class.java,
            ).block()
        } catch (e: Exception) {
            logger.warn { "Failed to mark scrape message $id as $state: ${e.message}" }
        }
    }

    /**
     * Refresh OAuth2 access token if needed. Returns current valid access token.
     */
    private suspend fun refreshOAuth2Token(connection: ConnectionDocument): String? {
        try {
            oauth2Service.refreshAccessToken(connection)
        } catch (e: Exception) {
            logger.warn { "Token refresh failed for ${connection.name}: ${e.message}" }
        }
        // Re-read connection to get potentially refreshed token
        val refreshed = connectionService.findById(connection.id) ?: connection
        return refreshed.bearerToken?.takeIf { it.isNotBlank() }
    }

    /**
     * Poll 1:1 and group chats -- always at client level (not channel-specific).
     */
    private suspend fun pollChats(
        connection: ConnectionDocument,
        context: PollingContext,
        o365ClientId: String?,
        accessToken: String?,
    ): PollingResult {
        val chats = if (accessToken != null) {
            fetchChatsGraphApi(accessToken)
        } else {
            fetchChats(o365ClientId!!)
        } ?: return PollingResult(errors = 1)
        if (chats.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (chat in chats.take(20)) {
            val chatId = chat.id ?: continue
            val messages = if (accessToken != null) {
                fetchChatMessagesGraphApi(accessToken, chatId)
            } else {
                fetchChatMessages(o365ClientId!!, chatId)
            } ?: continue

            for (msg in messages) {
                val msgId = msg.id ?: continue
                discovered++

                if (repository.existsByConnectionIdAndMessageId(connection.id, msgId)) {
                    skipped++
                    continue
                }

                // Route to client (chats are not channel-specific)
                val clientId = context.clients.firstOrNull()?.id ?: continue

                val doc = TeamsMessageIndexDocument(
                    connectionId = connection.id,
                    clientId = clientId,
                    messageId = msgId,
                    chatId = chatId,
                    chatDisplayName = chat.topic ?: "1:1 Chat",
                    from = msg.from?.user?.displayName ?: msg.from?.application?.displayName,
                    body = msg.body?.content,
                    bodyContentType = msg.body?.contentType,
                    createdDateTime = parseInstant(msg.createdDateTime) ?: Instant.now(),
                    subject = msg.subject,
                )
                repository.save(doc)
                created++
            }
        }

        return PollingResult(itemsDiscovered = discovered, itemsCreated = created, itemsSkipped = skipped)
    }

    /**
     * Poll team channels -- routes to project if project claims channel, else client.
     */
    private suspend fun pollChannels(
        connection: ConnectionDocument,
        context: PollingContext,
        o365ClientId: String?,
        accessToken: String?,
    ): PollingResult {
        val teams = if (accessToken != null) {
            fetchTeamsGraphApi(accessToken)
        } else {
            fetchTeams(o365ClientId!!)
        } ?: return PollingResult(errors = 1)
        if (teams.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (team in teams) {
            val teamId = team.id ?: continue
            val channels = if (accessToken != null) {
                fetchChannelsGraphApi(accessToken, teamId)
            } else {
                fetchChannels(o365ClientId!!, teamId)
            } ?: continue

            for (channel in channels) {
                val channelId = channel.id ?: continue
                val channelKey = "$teamId/$channelId"

                // Determine routing: project (if channel is in project resources) or client
                val (targetClientId, targetProjectId) = resolveTarget(
                    connection, context, channelKey,
                )
                if (targetClientId == null) continue

                val messages = if (accessToken != null) {
                    fetchChannelMessagesGraphApi(accessToken, teamId, channelId)
                } else {
                    fetchChannelMessages(o365ClientId!!, teamId, channelId)
                } ?: continue

                for (msg in messages) {
                    val msgId = msg.id ?: continue
                    discovered++

                    if (repository.existsByConnectionIdAndMessageId(connection.id, msgId)) {
                        skipped++
                        continue
                    }

                    val doc = TeamsMessageIndexDocument(
                        connectionId = connection.id,
                        clientId = targetClientId,
                        projectId = targetProjectId,
                        messageId = msgId,
                        teamId = teamId,
                        channelId = channelId,
                        teamDisplayName = team.displayName,
                        channelDisplayName = channel.displayName,
                        from = msg.from?.user?.displayName ?: msg.from?.application?.displayName,
                        body = msg.body?.content,
                        bodyContentType = msg.body?.contentType,
                        createdDateTime = parseInstant(msg.createdDateTime) ?: Instant.now(),
                        subject = msg.subject,
                    )
                    repository.save(doc)
                    created++
                }
            }
        }

        return PollingResult(itemsDiscovered = discovered, itemsCreated = created, itemsSkipped = skipped)
    }

    /**
     * Resolve target client/project for a channel.
     */
    private fun resolveTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        channelKey: String,
    ): Pair<ClientId?, ProjectId?> {
        // Check projects first -- project-level resources take priority
        for (project in context.projects) {
            val filter = context.getProjectResourceFilter(
                project.id, project.clientId, ConnectionCapability.CHAT_READ,
            )
            if (filter != null && filter.shouldIndex(channelKey)) {
                return Pair(project.clientId, project.id)
            }
        }

        // Fall back to client level
        for (client in context.clients) {
            val filter = context.getResourceFilter(client.id, ConnectionCapability.CHAT_READ)
            if (filter != null && filter.shouldIndex(channelKey)) {
                return Pair(client.id, null)
            }
        }

        return Pair(null, null)
    }

    // -- Graph API direct calls (for OAuth2 connections) -----------------------

    private val graphBaseUrl = "https://graph.microsoft.com/v1.0"

    private suspend fun fetchChatsGraphApi(token: String): List<GatewayChat>? {
        return try {
            val response = httpClient.get("$graphBaseUrl/me/chats?\$top=20") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) {
                logger.warn { "Graph API /me/chats returned ${response.status}" }
                return null
            }
            response.body<GraphListResponse<GatewayChat>>().value
        } catch (e: Exception) {
            logger.error(e) { "Error fetching chats from Graph API" }
            null
        }
    }

    private suspend fun fetchChatMessagesGraphApi(token: String, chatId: String): List<GatewayMessage>? {
        return try {
            val response = httpClient.get("$graphBaseUrl/me/chats/$chatId/messages?\$top=20") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<GraphListResponse<GatewayMessage>>().value
        } catch (e: Exception) {
            logger.error(e) { "Error fetching chat messages from Graph API" }
            null
        }
    }

    private suspend fun fetchTeamsGraphApi(token: String): List<GatewayTeam>? {
        return try {
            val response = httpClient.get("$graphBaseUrl/me/joinedTeams") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<GraphListResponse<GatewayTeam>>().value
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Teams from Graph API" }
            null
        }
    }

    private suspend fun fetchChannelsGraphApi(token: String, teamId: String): List<GatewayChannel>? {
        return try {
            val response = httpClient.get("$graphBaseUrl/teams/$teamId/channels") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<GraphListResponse<GatewayChannel>>().value
        } catch (e: Exception) {
            logger.error(e) { "Error fetching channels from Graph API" }
            null
        }
    }

    private suspend fun fetchChannelMessagesGraphApi(
        token: String,
        teamId: String,
        channelId: String,
    ): List<GatewayMessage>? {
        return try {
            val response = httpClient.get(
                "$graphBaseUrl/teams/$teamId/channels/$channelId/messages?\$top=20",
            ) {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<GraphListResponse<GatewayMessage>>().value
        } catch (e: Exception) {
            logger.error(e) { "Error fetching channel messages from Graph API" }
            null
        }
    }

    // -- Gateway REST API calls (for Browser Session connections) ---------------

    private suspend fun fetchChats(clientId: String): List<GatewayChat>? {
        return try {
            val response = httpClient.get("$gatewayUrl/api/o365/chats/$clientId?top=20")
            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch chats: ${response.status}" }
                return null
            }
            response.body<List<GatewayChat>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Teams chats from gateway" }
            null
        }
    }

    private suspend fun fetchChatMessages(clientId: String, chatId: String): List<GatewayMessage>? {
        return try {
            val response = httpClient.get("$gatewayUrl/api/o365/chats/$clientId/$chatId/messages?top=20")
            if (!response.status.isSuccess()) return null
            response.body<List<GatewayMessage>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching chat messages" }
            null
        }
    }

    private suspend fun fetchTeams(clientId: String): List<GatewayTeam>? {
        return try {
            val response = httpClient.get("$gatewayUrl/api/o365/teams/$clientId")
            if (!response.status.isSuccess()) return null
            response.body<List<GatewayTeam>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Teams list" }
            null
        }
    }

    private suspend fun fetchChannels(clientId: String, teamId: String): List<GatewayChannel>? {
        return try {
            val response = httpClient.get("$gatewayUrl/api/o365/teams/$clientId/$teamId/channels")
            if (!response.status.isSuccess()) return null
            response.body<List<GatewayChannel>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching channels for team $teamId" }
            null
        }
    }

    private suspend fun fetchChannelMessages(
        clientId: String,
        teamId: String,
        channelId: String,
    ): List<GatewayMessage>? {
        return try {
            val response = httpClient.get(
                "$gatewayUrl/api/o365/teams/$clientId/$teamId/channels/$channelId/messages?top=20",
            )
            if (!response.status.isSuccess()) return null
            response.body<List<GatewayMessage>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching channel messages" }
            null
        }
    }

    private fun parseInstant(dateTime: String?): Instant? {
        if (dateTime == null) return null
        return try {
            Instant.parse(dateTime)
        } catch (_: Exception) {
            null
        }
    }

    // -- Response models (shared between Graph API and Gateway) ----------------

    @Serializable
    private data class GraphListResponse<T>(
        val value: List<T> = emptyList(),
        @kotlinx.serialization.SerialName("@odata.nextLink")
        val nextLink: String? = null,
    )

    @Serializable
    data class GatewayChat(
        val id: String? = null,
        val topic: String? = null,
        val chatType: String? = null,
    )

    @Serializable
    data class GatewayTeam(
        val id: String? = null,
        val displayName: String? = null,
    )

    @Serializable
    data class GatewayChannel(
        val id: String? = null,
        val displayName: String? = null,
    )

    @Serializable
    data class GatewayMessage(
        val id: String? = null,
        val createdDateTime: String? = null,
        val subject: String? = null,
        val from: GatewayMessageFrom? = null,
        val body: GatewayMessageBody? = null,
    )

    @Serializable
    data class GatewayMessageFrom(
        val user: GatewayUser? = null,
        val application: GatewayUser? = null,
    )

    @Serializable
    data class GatewayUser(
        val displayName: String? = null,
        val id: String? = null,
    )

    @Serializable
    data class GatewayMessageBody(
        val contentType: String? = null,
        val content: String? = null,
    )
}
