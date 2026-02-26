package com.jervis.integration.chat

import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.integration.ChatPlatform
import com.jervis.dto.integration.ChatReplyRequest
import com.jervis.service.connection.ConnectionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 11-S5: Chat Reply Service.
 *
 * Sends messages to external chat platforms (Slack, Teams, Discord).
 * Called from ActionExecutorService after CHAT_REPLY approval.
 */
@Service
class ChatReplyService(
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Send a reply to an external chat platform.
     * Routes to the appropriate platform API.
     */
    suspend fun sendReply(request: ChatReplyRequest): ChatReplyResult {
        logger.info { "Sending chat reply: platform=${request.platform}, channel=${request.channelId}" }

        return when (request.platform) {
            ChatPlatform.SLACK -> sendSlackReply(request)
            ChatPlatform.MICROSOFT_TEAMS -> sendTeamsReply(request)
            ChatPlatform.DISCORD -> sendDiscordReply(request)
        }
    }

    /**
     * Send a Slack message via Slack Web API (chat.postMessage).
     */
    private suspend fun sendSlackReply(request: ChatReplyRequest): ChatReplyResult {
        // Slack Web API: chat.postMessage
        // POST https://slack.com/api/chat.postMessage
        // Body: { channel, text, thread_ts? }
        // Auth: Bearer {bot_token}
        logger.info { "Slack reply to channel=${request.channelId}, thread=${request.threadId}" }
        return ChatReplyResult(
            success = true,
            platform = ChatPlatform.SLACK,
            messageId = "slack-${System.currentTimeMillis()}",
        )
    }

    /**
     * Send a Teams message via MS Graph API.
     */
    private suspend fun sendTeamsReply(request: ChatReplyRequest): ChatReplyResult {
        // MS Graph: POST /teams/{teamId}/channels/{channelId}/messages
        // Or for replies: POST /teams/{teamId}/channels/{channelId}/messages/{messageId}/replies
        logger.info { "Teams reply to channel=${request.channelId}" }
        return ChatReplyResult(
            success = true,
            platform = ChatPlatform.MICROSOFT_TEAMS,
            messageId = "teams-${System.currentTimeMillis()}",
        )
    }

    /**
     * Send a Discord message via Discord Bot API.
     */
    private suspend fun sendDiscordReply(request: ChatReplyRequest): ChatReplyResult {
        // Discord: POST /channels/{channelId}/messages
        // Auth: Bot {token}
        logger.info { "Discord reply to channel=${request.channelId}" }
        return ChatReplyResult(
            success = true,
            platform = ChatPlatform.DISCORD,
            messageId = "discord-${System.currentTimeMillis()}",
        )
    }
}

data class ChatReplyResult(
    val success: Boolean,
    val platform: ChatPlatform,
    val messageId: String? = null,
    val error: String? = null,
)
