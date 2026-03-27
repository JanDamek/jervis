package com.jervis.slack

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for Slack via Slack Web API.
 *
 * Architecture:
 * - Uses Bot Token (xoxb-...) or User Token (xoxp-... via OAuth2) stored as bearerToken
 * - Calls Slack Web API: conversations.list, conversations.history
 * - Messages are stored as SlackMessageIndexDocument (NEW state)
 * - SlackContinuousIndexer picks them up for KB indexing
 *
 * Routing (same hierarchy as Teams/email):
 * - Project claims specific channels -> those index to project
 * - Everything else -> client level
 */
@Component
class SlackPollingHandler(
    private val repository: SlackMessageIndexRepository,
    private val pollingStateService: PollingStateService,
    private val httpClient: HttpClient,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.SLACK

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    companion object {
        private const val SLACK_API = "https://slack.com/api"
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
            logger.warn { "Slack connection '${connectionDocument.name}' has no token configured (bot or user)" }
            return PollingResult(errors = 1)
        }

        logger.debug { "Slack polling for connection '${connectionDocument.name}'" }

        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        try {
            val channels = fetchChannels(token) ?: return PollingResult(errors = 1)

            for (channel in channels) {
                val channelId = channel.id ?: continue
                val channelName = channel.name ?: "unknown"

                // Determine routing
                val (targetClientId, targetProjectId) = resolveTarget(
                    connectionDocument, context, channelId,
                )
                if (targetClientId == null) continue

                val messages = fetchMessages(token, channelId) ?: continue

                for (msg in messages) {
                    val msgTs = msg.ts ?: continue
                    totalDiscovered++

                    if (repository.existsByConnectionIdAndMessageId(connectionDocument.id, msgTs)) {
                        totalSkipped++
                        continue
                    }

                    val doc = SlackMessageIndexDocument(
                        connectionId = connectionDocument.id,
                        clientId = targetClientId,
                        projectId = targetProjectId,
                        messageId = msgTs,
                        channelId = channelId,
                        channelName = channelName,
                        from = msg.user,
                        body = msg.text,
                        createdDateTime = parseSlackTs(msgTs) ?: Instant.now(),
                        threadTs = msg.threadTs,
                    )
                    repository.save(doc)
                    totalCreated++
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error polling Slack for ${connectionDocument.name}" }
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
     * Same hierarchy as Teams: project claims channel -> project, else -> client.
     */
    private fun resolveTarget(
        connection: ConnectionDocument,
        context: PollingContext,
        channelId: String,
    ): Pair<ClientId?, ProjectId?> {
        for (project in context.projects) {
            val filter = context.getProjectResourceFilter(
                project.id, project.clientId, ConnectionCapability.CHAT_READ,
            )
            if (filter != null && filter.shouldIndex(channelId)) {
                return Pair(project.clientId, project.id)
            }
        }

        for (client in context.clients) {
            val filter = context.getResourceFilter(client.id, ConnectionCapability.CHAT_READ)
            if (filter != null && filter.shouldIndex(channelId)) {
                return Pair(client.id, null)
            }
        }

        return Pair(null, null)
    }

    // -- Slack Web API calls ---------------------------------------------------

    private suspend fun fetchChannels(token: String): List<SlackChannel>? {
        return try {
            val response = httpClient.get("$SLACK_API/conversations.list?types=public_channel,private_channel&limit=200&exclude_archived=true") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) {
                logger.warn { "Failed to fetch Slack channels: ${response.status}" }
                return null
            }
            val body = response.body<SlackChannelsResponse>()
            if (!body.ok) {
                logger.warn { "Slack API error: ${body.error}" }
                return null
            }
            body.channels
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Slack channels" }
            null
        }
    }

    private suspend fun fetchMessages(token: String, channelId: String): List<SlackMessage>? {
        return try {
            val response = httpClient.get("$SLACK_API/conversations.history?channel=$channelId&limit=20") {
                header("Authorization", "Bearer $token")
            }
            if (!response.status.isSuccess()) return null
            val body = response.body<SlackHistoryResponse>()
            if (!body.ok) {
                logger.warn { "Slack conversations.history error: ${body.error}" }
                return null
            }
            body.messages
        } catch (e: Exception) {
            logger.error(e) { "Error fetching Slack messages for channel $channelId" }
            null
        }
    }

    /**
     * Parse Slack message timestamp (e.g. "1234567890.123456") to Instant.
     */
    private fun parseSlackTs(ts: String): Instant? {
        return try {
            val epochSeconds = ts.substringBefore(".").toLong()
            Instant.ofEpochSecond(epochSeconds)
        } catch (_: Exception) {
            null
        }
    }

    // -- Slack response models -------------------------------------------------

    @Serializable
    data class SlackChannelsResponse(
        val ok: Boolean = false,
        val channels: List<SlackChannel> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    data class SlackChannel(
        val id: String? = null,
        val name: String? = null,
        @SerialName("is_private") val isPrivate: Boolean = false,
    )

    @Serializable
    data class SlackHistoryResponse(
        val ok: Boolean = false,
        val messages: List<SlackMessage> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    data class SlackMessage(
        val ts: String? = null,
        val user: String? = null,
        val text: String? = null,
        @SerialName("thread_ts") val threadTs: String? = null,
        val type: String? = null,
    )
}
