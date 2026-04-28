package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.client.ClientDocument
import com.jervis.project.ProjectDocument
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionService
import com.jervis.infrastructure.polling.PollingResult
import com.jervis.infrastructure.polling.PollingStateService
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.PollingHandler
import com.jervis.infrastructure.polling.handler.ResourceFilter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * The server **never** talks to Microsoft Graph directly. Two data paths,
 * both flowing through the O365 browser-pool pod:
 * - **VLM-scraped messages**: read from `o365_scrape_messages` Mongo
 *   collection (the browser pod scrapes Teams Cloud Fluent UI 9 and writes
 *   the rows there).
 * - **Live gateway RPC**: call `o365GatewayGrpc.listChats / readChat /
 *   listTeams / listChannels / readChannel` for chats/channels not yet in
 *   the scrape collection. The gateway pod proxies to Graph using the
 *   browser-pool token internally.
 *
 * Messages are stored as TeamsMessageIndexDocument (NEW state).
 * TeamsContinuousIndexer picks them up for KB indexing.
 */
@Component
class O365PollingHandler(
    private val repository: TeamsMessageIndexRepository,
    private val scrapeMessageRepository: O365ScrapeMessageRepository,
    private val pollingStateService: PollingStateService,
    private val connectionService: ConnectionService,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val o365CalendarPoller: O365CalendarPoller,
    private val browserPodManager: com.jervis.connection.BrowserPodManager,
    private val o365BrowserPoolGrpc: com.jervis.infrastructure.grpc.O365BrowserPoolGrpcClient,
    private val o365GatewayGrpc: com.jervis.infrastructure.grpc.O365GatewayGrpcClient,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.MICROSOFT_TEAMS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.CHAT_READ || it == ConnectionCapability.CALENDAR_READ
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        // Server side never talks to Microsoft Graph directly. Every Microsoft
        // data path goes through the O365 browser-pool pod (`o365GatewayGrpc`
        // for live RPCs, `o365BrowserPoolGrpc` for session control) which owns
        // the user's browser session. The poller just needs the per-connection
        // `o365ClientId` handle.
        val o365ClientId = connectionDocument.o365ClientId
        if (o365ClientId.isNullOrBlank()) {
            logger.warn { "O365 connection '${connectionDocument.name}' has no o365ClientId" }
            return PollingResult(errors = 1, authenticationError = true)
        }

        // Skip polling if connection is invalid or new (no session yet)
        if (connectionDocument.state in listOf(ConnectionStateEnum.INVALID, ConnectionStateEnum.NEW)) {
            logger.debug { "Skipping O365 poll for '${connectionDocument.name}' -- state=${connectionDocument.state}" }
            return PollingResult()
        }

        // Proactive health check: verify browser pool session is alive.
        // If session is EXPIRED/ERROR, mark connection INVALID immediately.
        if (connectionDocument.state in listOf(ConnectionStateEnum.VALID, ConnectionStateEnum.DISCOVERING)) {
            try {
                val status = o365BrowserPoolGrpc.getSession(connectionDocument.id, o365ClientId)
                if (status.state in listOf("EXPIRED", "ERROR")) {
                    logger.warn { "Browser pool session ${status.state} for '${connectionDocument.name}' -- marking INVALID" }
                    connectionService.save(connectionDocument.copy(state = ConnectionStateEnum.INVALID))
                    return PollingResult()
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

        logger.debug { "O365 Teams polling for '${connectionDocument.name}' (browser pool/${o365ClientId})" }
        val hasChatRead = connectionDocument.availableCapabilities.contains(ConnectionCapability.CHAT_READ)
        val hasCalendarRead = connectionDocument.availableCapabilities.contains(ConnectionCapability.CALENDAR_READ)

        var combined = PollingResult()

        // VLM-scraped chat/channel messages from Mongo (`o365_scrape_messages`).
        try {
            val scrapeResult = pollFromScrapeMessages(connectionDocument, context)
            combined = mergeResults(combined, scrapeResult)
        } catch (e: Exception) {
            logger.error(e) { "Error polling scrape messages for ${connectionDocument.name}" }
            combined = mergeResults(combined, PollingResult(errors = 1))
        }

        // Live Teams chats / channels via gateway gRPC (browser-pool tokens).
        if (hasChatRead) {
            try {
                val chatResult = pollChats(connectionDocument, context, o365ClientId)
                combined = mergeResults(combined, chatResult)
            } catch (e: Exception) {
                logger.error(e) { "Error polling Teams chats for ${connectionDocument.name}" }
                combined = mergeResults(combined, PollingResult(errors = 1))
            }
            try {
                val channelResult = pollChannels(connectionDocument, context, o365ClientId)
                combined = mergeResults(combined, channelResult)
            } catch (e: Exception) {
                logger.error(e) { "Error polling Teams channels for ${connectionDocument.name}" }
                combined = mergeResults(combined, PollingResult(errors = 1))
            }
        }

        // Calendar via gateway (browser pool tokens, gateway proxies to Graph).
        if (hasCalendarRead) {
            try {
                val calResult = o365CalendarPoller.poll(
                    connection = connectionDocument,
                    context = context,
                    o365ClientId = o365ClientId,
                )
                combined = mergeResults(combined, calResult)
            } catch (e: Exception) {
                logger.error(e) { "Error polling O365 calendar (gateway) for ${connectionDocument.name}" }
                combined = mergeResults(combined, PollingResult(errors = 1))
            }
        }

        return combined
    }

    private fun mergeResults(a: PollingResult, b: PollingResult) = PollingResult(
        itemsDiscovered = a.itemsDiscovered + b.itemsDiscovered,
        itemsCreated = a.itemsCreated + b.itemsCreated,
        itemsSkipped = a.itemsSkipped + b.itemsSkipped,
        errors = a.errors + b.errors,
        authenticationError = a.authenticationError || b.authenticationError,
    )

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
     * Poll 1:1 and group chats -- always at client level (not channel-specific).
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
     * Poll team channels -- routes to project if project claims channel, else client.
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

    // -- O365 Gateway gRPC calls (browser session pool — only path) ------------

    private suspend fun fetchChats(clientId: String): List<GatewayChat>? =
        runCatching { o365GatewayGrpc.listChats(clientId, top = 20) }
            .onFailure { logger.error(it) { "Error fetching Teams chats from gateway" } }
            .getOrNull()
            ?.map { GatewayChat(id = it.id, topic = it.topic, chatType = it.chatType) }

    private suspend fun fetchChatMessages(clientId: String, chatId: String): List<GatewayMessage>? =
        runCatching { o365GatewayGrpc.readChat(clientId, chatId, top = 20) }
            .onFailure { logger.error(it) { "Error fetching chat messages" } }
            .getOrNull()
            ?.map { it.toGateway() }

    private suspend fun fetchTeams(clientId: String): List<GatewayTeam>? =
        runCatching { o365GatewayGrpc.listTeams(clientId) }
            .onFailure { logger.error(it) { "Error fetching Teams list" } }
            .getOrNull()
            ?.map { GatewayTeam(id = it.id, displayName = it.displayName) }

    private suspend fun fetchChannels(clientId: String, teamId: String): List<GatewayChannel>? =
        runCatching { o365GatewayGrpc.listChannels(clientId, teamId) }
            .onFailure { logger.error(it) { "Error fetching channels for team $teamId" } }
            .getOrNull()
            ?.map { GatewayChannel(id = it.id, displayName = it.displayName) }

    private suspend fun fetchChannelMessages(
        clientId: String,
        teamId: String,
        channelId: String,
    ): List<GatewayMessage>? =
        runCatching { o365GatewayGrpc.readChannel(clientId, teamId, channelId, top = 20) }
            .onFailure { logger.error(it) { "Error fetching channel messages" } }
            .getOrNull()
            ?.map { it.toGateway() }

    // Proto ChatMessage -> internal GatewayMessage (extracted so both chat + channel
    // paths share the mapping and the compiler enforces field coverage).
    private fun com.jervis.contracts.o365_gateway.ChatMessage.toGateway(): GatewayMessage =
        GatewayMessage(
            id = id.takeIf { it.isNotBlank() },
            createdDateTime = createdDateTime.takeIf { it.isNotBlank() },
            subject = null,
            from = if (hasSender()) {
                GatewayMessageFrom(
                    user = if (sender.hasUser()) GatewayUser(
                        displayName = sender.user.displayName.takeIf { it.isNotBlank() },
                        id = sender.user.id.takeIf { it.isNotBlank() },
                    ) else null,
                    application = if (sender.hasApplication()) GatewayUser(
                        displayName = sender.application.displayName.takeIf { it.isNotBlank() },
                        id = sender.application.id.takeIf { it.isNotBlank() },
                    ) else null,
                )
            } else null,
            body = if (hasBody()) GatewayMessageBody(
                contentType = body.contentType.takeIf { it.isNotBlank() },
                content = body.content.takeIf { it.isNotBlank() },
            ) else null,
        )

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
