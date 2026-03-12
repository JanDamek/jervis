package com.jervis.integration.chat

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.integration.ChatPlatform
import com.jervis.dto.integration.ExternalChatMessage
import com.jervis.service.connection.ConnectionService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 11-S4: Chat Continuous Indexer (DEPRECATED).
 *
 * Superseded by per-platform indexers:
 * - [com.jervis.service.teams.TeamsContinuousIndexer]
 * - [com.jervis.service.slack.SlackContinuousIndexer]
 * - [com.jervis.service.discord.DiscordContinuousIndexer]
 *
 * The per-platform indexers follow the same NEW→INDEXED state machine
 * as EmailContinuousIndexer and read from MongoDB index documents
 * populated by their respective PollingHandlers. This generic indexer
 * is kept for reference but is no longer active.
 */
@Deprecated("Replaced by per-platform continuous indexers (Teams/Slack/Discord)")
@Service
class ChatContinuousIndexer(
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Polling interval for chat messages. */
    private val pollingIntervalMs = 30_000L // 30 seconds

    // @PostConstruct — disabled, replaced by per-platform indexers
    fun start() {
        scope.launch {
            logger.info { "ChatContinuousIndexer started" }
            indexContinuously()
        }
    }

    private suspend fun indexContinuously() {
        while (true) {
            try {
                val chatConnections = connectionService.findAllValid()
                    .filter { conn ->
                        conn.availableCapabilities.contains(ConnectionCapability.CHAT_READ)
                    }
                    .toList()

                for (conn in chatConnections) {
                    try {
                        val platform = providerToPlatform(conn.provider) ?: continue
                        val messages = fetchNewMessages(conn.id.toString(), platform)

                        for (msg in messages) {
                            indexMessage(msg)
                        }

                        if (messages.isNotEmpty()) {
                            logger.debug { "Indexed ${messages.size} chat messages from ${conn.name} ($platform)" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index chat from connection ${conn.name}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in chat indexing loop" }
            }

            delay(pollingIntervalMs)
        }
    }

    /**
     * Fetch new messages from a chat platform since last poll.
     * Routes to platform-specific API based on provider.
     */
    private suspend fun fetchNewMessages(
        connectionId: String,
        platform: ChatPlatform,
    ): List<ExternalChatMessage> {
        return when (platform) {
            ChatPlatform.SLACK -> fetchSlackMessages(connectionId)
            ChatPlatform.MICROSOFT_TEAMS -> fetchTeamsMessages(connectionId)
            ChatPlatform.DISCORD -> fetchDiscordMessages(connectionId)
        }
    }

    /**
     * E11-S2: Fetch Slack messages via Slack Web API.
     * Uses conversations.history + conversations.replies for threading.
     */
    private suspend fun fetchSlackMessages(connectionId: String): List<ExternalChatMessage> {
        // Slack Web API: conversations.history, conversations.replies
        // Auth: Bearer {bot_token} (from OAuth2 flow)
        // Rate limit: Tier 3 (50+/min)
        logger.debug { "Slack polling for connection $connectionId (placeholder)" }
        return emptyList()
    }

    /**
     * E11-S3: Fetch Teams messages via MS Graph API.
     * Uses /teams/{teamId}/channels/{channelId}/messages.
     */
    private suspend fun fetchTeamsMessages(connectionId: String): List<ExternalChatMessage> {
        // MS Graph: GET /teams/{teamId}/channels/{channelId}/messages
        // Auth: Bearer {access_token} (from app registration)
        logger.debug { "Teams polling for connection $connectionId (placeholder)" }
        return emptyList()
    }

    /**
     * Fetch Discord messages via Discord Bot API.
     */
    private suspend fun fetchDiscordMessages(connectionId: String): List<ExternalChatMessage> {
        // Discord: GET /channels/{channelId}/messages
        // Auth: Bot {token}
        logger.debug { "Discord polling for connection $connectionId (placeholder)" }
        return emptyList()
    }

    /**
     * Index a single chat message into the knowledge base.
     * Creates a KB node with threading and reaction metadata.
     */
    private suspend fun indexMessage(message: ExternalChatMessage) {
        // Build source URN: "chat::{platform}::{channelId}::{messageId}"
        val sourceUrn = "chat::${message.platform.name.lowercase()}::${message.channelId}::${message.id}"

        // Priority boosting:
        // - @channel/@here/@everyone mentions → URGENT
        // - Direct messages → HIGH
        // - Regular channel messages → NORMAL
        val priority = when {
            message.mentions.any { it in listOf("@channel", "@here", "@everyone") } -> "URGENT"
            message.isDirectMessage -> "HIGH"
            else -> "NORMAL"
        }

        // Reaction sentiment:
        // 👍/✅/🎉 → positive
        // 👎/❌/😡 → negative
        // 🔥/⚡/🚨 → urgent
        val sentiment = analyzeSentiment(message.reactions.map { it.emoji })

        logger.trace {
            "Chat message indexed: $sourceUrn (priority=$priority, sentiment=$sentiment)"
        }

        // In production: POST to KB ingest API with:
        // - nodeKey: sourceUrn
        // - type: "chat_message"
        // - content: message.content
        // - metadata: { platform, channelId, threadId, authorName, priority, sentiment }
        // - edges: thread relationship (if threadId != null)
    }

    private fun analyzeSentiment(emojis: List<String>): String {
        val positive = setOf("👍", "✅", "🎉", "❤️", "😊", "+1", "thumbsup", "white_check_mark")
        val negative = setOf("👎", "❌", "😡", "😞", "-1", "thumbsdown", "x")
        val urgent = setOf("🔥", "⚡", "🚨", "rotating_light", "fire", "zap")

        return when {
            emojis.any { it in urgent } -> "urgent"
            emojis.any { it in negative } -> "negative"
            emojis.any { it in positive } -> "positive"
            else -> "neutral"
        }
    }

    private fun providerToPlatform(provider: ProviderEnum): ChatPlatform? = when (provider) {
        ProviderEnum.SLACK -> ChatPlatform.SLACK
        ProviderEnum.MICROSOFT_TEAMS -> ChatPlatform.MICROSOFT_TEAMS
        ProviderEnum.DISCORD -> ChatPlatform.DISCORD
        else -> null
    }
}
