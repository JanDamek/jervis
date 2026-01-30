package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.MessageRole
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.repository.ChatMessageRepository
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * ChatHistoryTools - tools for agent to search and retrieve conversation history.
 *
 * Purpose:
 * - Agent can search through chat message history across conversation
 * - Enables agent to recall context from earlier messages
 * - Supports filtering by role (user/assistant/system)
 * - Pagination for large conversations
 *
 * Used when:
 * - Agent checkpoint is compressed and old details lost
 * - User references something from earlier in conversation
 * - Agent needs to verify what it said previously
 */
class ChatHistoryTools(
    private val task: TaskDocument,
    private val chatMessageRepository: ChatMessageRepository,
    private val json: Json,
) : ToolSet {

    @Tool
    @LLMDescription(
        """Get recent chat messages from current conversation.

        Returns last N messages (user and assistant) from the conversation history.
        Useful when you need recent context that might not be in your checkpoint.

        Example: getRecentMessages(limit=20) to get last 20 messages
        """
    )
    suspend fun getRecentMessages(
        @LLMDescription("Number of recent messages to retrieve (default: 10)")
        limit: Int = 10,
    ): String {
        logger.info {
            "📜 GET_RECENT_MESSAGES | taskId=${task.id} | limit=$limit | correlationId=${task.correlationId}"
        }

        try {
            val taskId = TaskId.fromString(task.id)
            val messages = chatMessageRepository.findByTaskIdOrderBySequenceAsc(taskId)
                .take(limit)
                .toList()

            if (messages.isEmpty()) {
                return """{"messages": [], "count": 0, "note": "No messages found"}"""
            }

            val messageList = messages.map { msg ->
                """
                {
                    "role": "${msg.role.name.lowercase()}",
                    "content": "${msg.content.replace("\"", "\\\"")}",
                    "timestamp": "${msg.timestamp}",
                    "sequence": ${msg.sequence}
                }
                """.trimIndent()
            }.joinToString(",\n")

            return """
            {
                "messages": [$messageList],
                "count": ${messages.size},
                "oldestSequence": ${messages.first().sequence},
                "newestSequence": ${messages.last().sequence}
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ GET_RECENT_MESSAGES_FAILED | taskId=${task.id}" }
            return """{"error": "Failed to retrieve messages: ${e.message}"}"""
        }
    }

    @Tool
    @LLMDescription(
        """Search through conversation history for specific text.

        Searches all messages (user and assistant) for text containing the query.
        Case-insensitive search. Returns matching messages with context.

        Example: searchHistory(query="budget analysis") to find when budget was discussed
        """
    )
    suspend fun searchHistory(
        @LLMDescription("Search query text (case-insensitive)")
        query: String,
        @LLMDescription("Maximum results to return (default: 20)")
        limit: Int = 20,
    ): String {
        logger.info {
            "🔍 SEARCH_HISTORY | taskId=${task.id} | query=\"$query\" | limit=$limit | correlationId=${task.correlationId}"
        }

        if (query.isBlank()) {
            return """{"error": "Query cannot be blank"}"""
        }

        try {
            val taskId = TaskId.fromString(task.id)
            val messages = chatMessageRepository.findByTaskIdAndContentRegex(
                taskId = taskId,
                searchText = query
            ).take(limit).toList()

            if (messages.isEmpty()) {
                return """{"matches": [], "count": 0, "query": "$query", "note": "No matches found"}"""
            }

            val matchList = messages.map { msg ->
                """
                {
                    "role": "${msg.role.name.lowercase()}",
                    "content": "${msg.content.replace("\"", "\\\"")}",
                    "timestamp": "${msg.timestamp}",
                    "sequence": ${msg.sequence}
                }
                """.trimIndent()
            }.joinToString(",\n")

            logger.info {
                "✅ SEARCH_COMPLETE | taskId=${task.id} | query=\"$query\" | found=${messages.size}"
            }

            return """
            {
                "matches": [$matchList],
                "count": ${messages.size},
                "query": "$query"
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ SEARCH_HISTORY_FAILED | taskId=${task.id} | query=\"$query\"" }
            return """{"error": "Search failed: ${e.message}"}"""
        }
    }

    @Tool
    @LLMDescription(
        """Get all user messages from conversation.

        Returns only USER messages (questions, requests) from the conversation.
        Useful to review what user asked for throughout the conversation.

        Example: getUserMessages(limit=30) to see last 30 user questions
        """
    )
    suspend fun getUserMessages(
        @LLMDescription("Maximum messages to return (default: 20)")
        limit: Int = 20,
    ): String {
        logger.info {
            "👤 GET_USER_MESSAGES | taskId=${task.id} | limit=$limit | correlationId=${task.correlationId}"
        }

        try {
            val taskId = TaskId.fromString(task.id)
            val messages = chatMessageRepository.findByTaskIdAndRole(
                taskId = taskId,
                role = MessageRole.USER
            ).take(limit).toList()

            if (messages.isEmpty()) {
                return """{"userMessages": [], "count": 0, "note": "No user messages found"}"""
            }

            val messageList = messages.map { msg ->
                """
                {
                    "content": "${msg.content.replace("\"", "\\\"")}",
                    "timestamp": "${msg.timestamp}",
                    "sequence": ${msg.sequence}
                }
                """.trimIndent()
            }.joinToString(",\n")

            return """
            {
                "userMessages": [$messageList],
                "count": ${messages.size}
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ GET_USER_MESSAGES_FAILED | taskId=${task.id}" }
            return """{"error": "Failed to retrieve user messages: ${e.message}"}"""
        }
    }

    @Tool
    @LLMDescription(
        """Get your own previous responses from conversation.

        Returns only ASSISTANT messages (your own responses) from the conversation.
        Useful to check what you said previously or verify your past answers.

        Example: getMyResponses(limit=15) to review your last 15 responses
        """
    )
    suspend fun getMyResponses(
        @LLMDescription("Maximum responses to return (default: 20)")
        limit: Int = 20,
    ): String {
        logger.info {
            "🤖 GET_MY_RESPONSES | taskId=${task.id} | limit=$limit | correlationId=${task.correlationId}"
        }

        try {
            val taskId = TaskId.fromString(task.id)
            val messages = chatMessageRepository.findByTaskIdAndRole(
                taskId = taskId,
                role = MessageRole.ASSISTANT
            ).take(limit).toList()

            if (messages.isEmpty()) {
                return """{"assistantMessages": [], "count": 0, "note": "No assistant messages found"}"""
            }

            val messageList = messages.map { msg ->
                """
                {
                    "content": "${msg.content.replace("\"", "\\\"")}",
                    "timestamp": "${msg.timestamp}",
                    "sequence": ${msg.sequence}
                }
                """.trimIndent()
            }.joinToString(",\n")

            return """
            {
                "assistantMessages": [$messageList],
                "count": ${messages.size}
            }
            """.trimIndent()
        } catch (e: Exception) {
            logger.error(e) { "❌ GET_MY_RESPONSES_FAILED | taskId=${task.id}" }
            return """{"error": "Failed to retrieve assistant messages: ${e.message}"}"""
        }
    }
}
