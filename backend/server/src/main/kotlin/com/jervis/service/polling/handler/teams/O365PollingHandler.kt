package com.jervis.service.polling.handler.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.teams.TeamsMessageIndexDocument
import com.jervis.repository.TeamsMessageIndexRepository
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.PollingStateService
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.service.polling.handler.ResourceFilter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for Microsoft Teams via O365 Gateway.
 *
 * Architecture:
 * - O365 Gateway relays requests to Microsoft Graph API using browser-pool tokens
 * - This handler calls Gateway REST endpoints (not Graph API directly)
 * - Messages are stored as TeamsMessageIndexDocument (NEW state)
 * - TeamsContinuousIndexer picks them up for KB indexing
 *
 * Routing (same as email):
 * - Client has "all" → everything goes to client level
 * - Project has specific channels → those index to project, rest to client
 */
@Component
class O365PollingHandler(
    private val repository: TeamsMessageIndexRepository,
    private val pollingStateService: PollingStateService,
    private val httpClient: HttpClient,
    @Value("\${jervis.o365-gateway.url:http://jervis-o365-gateway:8080}")
    private val gatewayUrl: String,
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
        val o365ClientId = connectionDocument.o365ClientId
        if (o365ClientId.isNullOrBlank()) {
            logger.warn { "O365 connection '${connectionDocument.name}' has no o365ClientId configured" }
            return PollingResult(errors = 1)
        }

        logger.debug { "O365 Teams polling for connection '${connectionDocument.name}' (clientId=$o365ClientId)" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        // Poll chats (1:1 and group chats)
        try {
            val chatResult = pollChats(connectionDocument, context, o365ClientId)
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
            val channelResult = pollChannels(connectionDocument, context, o365ClientId)
            totalDiscovered += channelResult.itemsDiscovered
            totalCreated += channelResult.itemsCreated
            totalSkipped += channelResult.itemsSkipped
            totalErrors += channelResult.errors
        } catch (e: Exception) {
            logger.error(e) { "Error polling Teams channels for ${connectionDocument.name}" }
            totalErrors++
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors,
        )
    }

    /**
     * Poll 1:1 and group chats — always at client level (not channel-specific).
     */
    private suspend fun pollChats(
        connection: ConnectionDocument,
        context: PollingContext,
        o365ClientId: String,
    ): PollingResult {
        val chats = fetchChats(o365ClientId) ?: return PollingResult(errors = 1)
        if (chats.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (chat in chats.take(20)) {
            val chatId = chat.id ?: continue
            val messages = fetchChatMessages(o365ClientId, chatId) ?: continue

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
     * Poll team channels — routes to project if project claims channel, else client.
     */
    private suspend fun pollChannels(
        connection: ConnectionDocument,
        context: PollingContext,
        o365ClientId: String,
    ): PollingResult {
        val teams = fetchTeams(o365ClientId) ?: return PollingResult(errors = 1)
        if (teams.isEmpty()) return PollingResult()

        var discovered = 0
        var created = 0
        var skipped = 0

        for (team in teams) {
            val teamId = team.id ?: continue
            val channels = fetchChannels(o365ClientId, teamId) ?: continue

            for (channel in channels) {
                val channelId = channel.id ?: continue
                val channelKey = "$teamId/$channelId"

                // Determine routing: project (if channel is in project resources) or client
                val (targetClientId, targetProjectId) = resolveTarget(
                    connection, context, channelKey,
                )
                if (targetClientId == null) continue

                val messages = fetchChannelMessages(o365ClientId, teamId, channelId) ?: continue

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
     *
     * Rules (same hierarchy as email/git):
     * - If project claims this channel in its resources → index to project
     * - Otherwise → index to client (if client has CHAT_READ enabled or indexAllResources)
     */
    private fun resolveTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        channelKey: String,
    ): Pair<ClientId?, ProjectId?> {
        // Check projects first — project-level resources take priority
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

    // -- Gateway REST API calls -----------------------------------------------

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

    // -- Gateway response models (minimal, ignoreUnknownKeys) -----------------

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
