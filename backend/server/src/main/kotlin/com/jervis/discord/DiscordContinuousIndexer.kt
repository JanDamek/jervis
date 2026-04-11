package com.jervis.discord

import com.jervis.common.types.SourceUrn
import com.jervis.infrastructure.polling.PollingStatusEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskService
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
 * Continuous indexer for Discord messages.
 *
 * Same architecture as SlackContinuousIndexer / TeamsContinuousIndexer:
 * - DiscordPollingHandler fetches messages via Discord API -> saves as NEW in MongoDB
 * - This indexer reads NEW documents (no external API calls)
 * - Creates DISCORD_PROCESSING tasks for Qualifier
 * - Converts to INDEXED (minimal tracking record)
 */
@Service
@Order(14)
class DiscordContinuousIndexer(
    private val repository: DiscordMessageIndexRepository,
    private val taskService: TaskService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting DiscordContinuousIndexer (MongoDB -> RAG)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Discord continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        continuousNewMessages().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) return@collect
            try {
                indexMessage(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index Discord message ${doc.messageId}" }
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

    private suspend fun indexMessage(doc: DiscordMessageIndexDocument) {
        val content = buildMessageContent(doc)

        val taskName = "Discord: ${doc.guildName ?: doc.guildId} / #${doc.channelName ?: doc.channelId} - ${doc.from ?: "unknown"}".take(120)

        val topicId = "discord-channel:${doc.guildId}/${doc.channelId}"

        val task = taskService.createTask(
            taskType = TaskTypeEnum.SYSTEM,
            content = content,
            clientId = doc.clientId,
            correlationId = "discord:${doc.messageId}",
            sourceUrn = SourceUrn.discord(
                connectionId = doc.connectionId,
                messageId = doc.messageId,
                channelId = doc.channelId,
                guildId = doc.guildId,
            ),
            projectId = doc.projectId,
            taskName = taskName,
        )

        taskService.setTopicId(task.id, topicId)

        markAsIndexed(doc)
        logger.debug { "Indexed Discord message: $taskName" }
    }

    private fun buildMessageContent(doc: DiscordMessageIndexDocument): String = buildString {
        appendLine("# Discord Message")
        appendLine("**Server:** ${doc.guildName ?: doc.guildId}")
        appendLine("**Channel:** #${doc.channelName ?: doc.channelId}")
        appendLine("**From:** ${doc.from ?: "unknown"}")
        appendLine("**Date:** ${doc.createdDateTime}")
        appendLine()

        val body = doc.body
        if (!body.isNullOrBlank()) {
            append(body.trim())
            appendLine()
        }

        appendLine()
        appendLine("---")
        appendLine("## Metadata")
        appendLine("- **Message ID:** ${doc.messageId}")
        appendLine("- **Connection ID:** ${doc.connectionId}")
        appendLine("- **Client ID:** ${doc.clientId}")
        if (doc.projectId != null) appendLine("- **Project ID:** ${doc.projectId}")
        appendLine("- **Guild ID:** ${doc.guildId}")
        appendLine("- **Channel ID:** ${doc.channelId}")
    }

    private suspend fun markAsIndexed(doc: DiscordMessageIndexDocument) {
        val updated = DiscordMessageIndexDocument(
            id = doc.id,
            clientId = doc.clientId,
            projectId = doc.projectId,
            connectionId = doc.connectionId,
            messageId = doc.messageId,
            guildId = doc.guildId,
            channelId = doc.channelId,
            createdDateTime = doc.createdDateTime,
            state = PollingStatusEnum.INDEXED,
        )
        repository.save(updated)
    }

    private suspend fun markAsFailed(doc: DiscordMessageIndexDocument, error: String) {
        repository.save(doc.copy(state = PollingStatusEnum.FAILED, indexingError = error))
    }
}
