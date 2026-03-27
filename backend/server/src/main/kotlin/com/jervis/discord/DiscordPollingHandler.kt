package com.jervis.discord

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.connection.ConnectionDocument
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for Discord via Discord REST API.
 *
 * Architecture:
 * - Uses Bot Token stored as bearerToken on the connection
 * - Calls Discord REST API: /guilds, /channels, /messages
 * - Messages are stored as DiscordMessageIndexDocument (NEW state)
 * - DiscordContinuousIndexer picks them up for KB indexing
 *
 * Routing (same hierarchy as Teams/Slack/email):
 * - Resource key: "guildId/channelId"
 * - Project claims specific channels -> those index to project
 * - Everything else -> client level
 */
@Component
class DiscordPollingHandler(
    private val repository: DiscordMessageIndexRepository,
    private val pollingStateService: PollingStateService,
    private val httpClient: HttpClient,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.DISCORD

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    companion object {
        private const val DISCORD_API = "https://discord.com/api/v10"
    }

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.CHAT_READ
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        val token = connectionDocument.bearerToken
        if (token.isNullOrBlank()) {
            logger.warn { "Discord connection '${connectionDocument.name}' has no bot token configured" }
            return PollingResult(errors = 1)
        }

        logger.debug { "Discord polling for connection '${connectionDocument.name}'" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        try {
            val guilds = fetchGuilds(token) ?: return PollingResult(errors = 1)

            for (guild in guilds) {
                val guildId = guild.id ?: continue
                val guildName = guild.name

                val channels = fetchChannels(token, guildId) ?: continue

                // Only text channels (type 0)
                val textChannels = channels.filter { it.type == 0 }

                for (channel in textChannels) {
                    val channelId = channel.id ?: continue
                    val channelName = channel.name
                    val channelKey = "$guildId/$channelId"

                    val (targetClientId, targetProjectId) = resolveTarget(
                        connectionDocument, context, channelKey,
                    )
                    if (targetClientId == null) continue

                    val messages = fetchMessages(token, channelId) ?: continue

                    for (msg in messages) {
                        val msgId = msg.id ?: continue
                        totalDiscovered++

                        if (repository.existsByConnectionIdAndMessageId(connectionDocument.id, msgId)) {
                            totalSkipped++
                            continue
                        }

                        val doc = DiscordMessageIndexDocument(
                            connectionId = connectionDocument.id,
                            clientId = targetClientId,
                            projectId = targetProjectId,
                            messageId = msgId,
                            guildId = guildId,
                            channelId = channelId,
                            guildName = guildName,
                            channelName = channelName,
                            from = msg.author?.username,
                            body = msg.content,
                            createdDateTime = parseDiscordTimestamp(msg.timestamp) ?: Instant.now(),
                        )
                        repository.save(doc)
                        totalCreated++
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error polling Discord for ${connectionDocument.name}" }
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
     * Resolve target client/project for a channel.
     * channelKey = "guildId/channelId"
     */
    private fun resolveTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        channelKey: String,
    ): Pair<ClientId?, ProjectId?> {
        for (project in context.projects) {
            val filter = context.getProjectResourceFilter(
                project.id, project.clientId, ConnectionCapability.CHAT_READ,
            )
            if (filter != null && filter.shouldIndex(channelKey)) {
                return Pair(project.clientId, project.id)
            }
        }

        for (client in context.clients) {
            val filter = context.getResourceFilter(client.id, ConnectionCapability.CHAT_READ)
            if (filter != null && filter.shouldIndex(channelKey)) {
                return Pair(client.id, null)
            }
        }

        return Pair(null, null)
    }

    // -- Discord REST API calls ------------------------------------------------

    private suspend fun fetchGuilds(token: String): List<DiscordGuild>? {
        return try {
            val response = httpClient.get("$DISCORD_API/users/@me/guilds") {
                header("Authorization", "Bot $token")
            }
            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch Discord guilds: ${response.status}" }
                return null
            }
            response.body<List<DiscordGuild>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Discord guilds" }
            null
        }
    }

    private suspend fun fetchChannels(token: String, guildId: String): List<DiscordChannel>? {
        return try {
            val response = httpClient.get("$DISCORD_API/guilds/$guildId/channels") {
                header("Authorization", "Bot $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<List<DiscordChannel>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Discord channels for guild $guildId" }
            null
        }
    }

    private suspend fun fetchMessages(token: String, channelId: String): List<DiscordMessage>? {
        return try {
            val response = httpClient.get("$DISCORD_API/channels/$channelId/messages?limit=20") {
                header("Authorization", "Bot $token")
            }
            if (!response.status.isSuccess()) return null
            response.body<List<DiscordMessage>>()
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Discord messages for channel $channelId" }
            null
        }
    }

    private fun parseDiscordTimestamp(timestamp: String?): Instant? {
        if (timestamp == null) return null
        return try {
            Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp))
        } catch (_: Exception) {
            null
        }
    }

    // -- Discord response models -----------------------------------------------

    @Serializable
    data class DiscordGuild(
        val id: String? = null,
        val name: String? = null,
    )

    @Serializable
    data class DiscordChannel(
        val id: String? = null,
        val name: String? = null,
        val type: Int = 0,
    )

    @Serializable
    data class DiscordMessage(
        val id: String? = null,
        val content: String? = null,
        val author: DiscordAuthor? = null,
        val timestamp: String? = null,
    )

    @Serializable
    data class DiscordAuthor(
        val id: String? = null,
        val username: String? = null,
    )
}
