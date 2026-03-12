package com.jervis.service.teams

import com.jervis.common.types.SourceUrn
import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.teams.TeamsMessageIndexDocument
import com.jervis.repository.TeamsMessageIndexRepository
import com.jervis.service.background.TaskService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Teams messages.
 *
 * Same architecture as EmailContinuousIndexer:
 * - O365PollingHandler fetches messages via Gateway → saves as NEW in MongoDB
 * - This indexer reads NEW documents (no external API calls)
 * - Creates CHAT_PROCESSING tasks for Qualifier
 * - Converts to INDEXED (minimal tracking record)
 */
@Service
@Order(12)
class TeamsContinuousIndexer(
    private val repository: TeamsMessageIndexRepository,
    private val taskService: TaskService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting TeamsContinuousIndexer (MongoDB → RAG)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Teams continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        continuousNewMessages().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) return@collect
            try {
                indexMessage(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index Teams message ${doc.messageId}" }
                markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private fun continuousNewMessages() = flow {
        while (true) {
            val messages = repository.findByStateOrderByCreatedDateTimeAsc(PollingStatusEnum.NEW)
            var emittedAny = false
            messages.collect { msg ->
                emit(msg)
                emittedAny = true
            }
            if (!emittedAny) {
                delay(POLL_DELAY_MS)
            }
        }
    }

    private suspend fun indexMessage(doc: TeamsMessageIndexDocument) {
        val content = buildMessageContent(doc)

        val taskName = when {
            doc.channelDisplayName != null -> "Teams: ${doc.channelDisplayName} - ${doc.from ?: "unknown"}"
            doc.chatDisplayName != null -> "Teams chat: ${doc.chatDisplayName}"
            else -> "Teams message from ${doc.from ?: "unknown"}"
        }.take(120)

        val topicId = when {
            doc.channelId != null -> "teams-channel:${doc.teamId}/${doc.channelId}"
            doc.chatId != null -> "teams-chat:${doc.chatId}"
            else -> null
        }

        val task = taskService.createTask(
            taskType = TaskTypeEnum.CHAT_PROCESSING,
            content = content,
            clientId = doc.clientId,
            correlationId = "teams:${doc.messageId}",
            sourceUrn = SourceUrn.teams(
                connectionId = doc.connectionId,
                messageId = doc.messageId,
                channelId = doc.channelId,
                chatId = doc.chatId,
            ),
            projectId = doc.projectId,
            taskName = taskName,
        )

        if (topicId != null) {
            taskService.setTopicId(task.id, topicId)
        }

        markAsIndexed(doc)
        logger.debug { "Indexed Teams message: $taskName" }
    }

    private fun buildMessageContent(doc: TeamsMessageIndexDocument): String = buildString {
        val context = when {
            doc.channelDisplayName != null ->
                "Team: ${doc.teamDisplayName ?: "?"} / Channel: ${doc.channelDisplayName}"
            doc.chatDisplayName != null ->
                "Chat: ${doc.chatDisplayName}"
            else -> "Teams message"
        }

        appendLine("# Teams Message")
        appendLine("**Context:** $context")
        appendLine("**From:** ${doc.from ?: "unknown"}")
        appendLine("**Date:** ${doc.createdDateTime}")
        if (doc.subject != null) appendLine("**Subject:** ${doc.subject}")
        appendLine()

        val body = doc.body
        if (!body.isNullOrBlank()) {
            if (doc.bodyContentType == "html") {
                // Strip basic HTML tags for plain text indexing
                append(body.replace(Regex("<[^>]+>"), "").trim())
            } else {
                append(body.trim())
            }
            appendLine()
        }

        appendLine()
        appendLine("---")
        appendLine("## Metadata")
        appendLine("- **Message ID:** ${doc.messageId}")
        appendLine("- **Connection ID:** ${doc.connectionId}")
        appendLine("- **Client ID:** ${doc.clientId}")
        if (doc.projectId != null) appendLine("- **Project ID:** ${doc.projectId}")
        if (doc.teamId != null) appendLine("- **Team ID:** ${doc.teamId}")
        if (doc.channelId != null) appendLine("- **Channel ID:** ${doc.channelId}")
        if (doc.chatId != null) appendLine("- **Chat ID:** ${doc.chatId}")
    }

    private suspend fun markAsIndexed(doc: TeamsMessageIndexDocument) {
        val updated = TeamsMessageIndexDocument(
            id = doc.id,
            clientId = doc.clientId,
            projectId = doc.projectId,
            connectionId = doc.connectionId,
            messageId = doc.messageId,
            createdDateTime = doc.createdDateTime,
            state = PollingStatusEnum.INDEXED,
        )
        repository.save(updated)
    }

    private suspend fun markAsFailed(doc: TeamsMessageIndexDocument, error: String) {
        repository.save(doc.copy(state = PollingStatusEnum.FAILED, indexingError = error))
    }
}
