package com.jervis.whatsapp

import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.polling.PollingStatusEnum
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
 * Continuous indexer for WhatsApp messages.
 *
 * Same architecture as SlackContinuousIndexer / TeamsContinuousIndexer:
 * - WhatsAppPollingHandler reads VLM-scraped messages → saves as NEW in MongoDB
 * - This indexer reads NEW documents (no external API calls)
 * - Creates WHATSAPP_PROCESSING tasks for Qualifier
 * - Converts to INDEXED (minimal tracking record)
 */
@Service
@Order(15)
class WhatsAppContinuousIndexer(
    private val repository: WhatsAppMessageIndexRepository,
    private val taskService: TaskService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting WhatsAppContinuousIndexer (MongoDB → RAG)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "WhatsApp continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        continuousNewMessages().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) return@collect
            try {
                indexMessage(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index WhatsApp message ${doc.messageId}" }
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

    private suspend fun indexMessage(doc: WhatsAppMessageIndexDocument) {
        val content = buildMessageContent(doc)

        val chatLabel = doc.chatName ?: "unknown"
        val taskName = "WhatsApp: $chatLabel - ${doc.from ?: "unknown"}".take(120)

        val topicId = "whatsapp-chat:$chatLabel"

        val task = taskService.createTask(
            taskType = TaskTypeEnum.SYSTEM,
            content = content,
            clientId = doc.clientId,
            correlationId = "whatsapp:${doc.messageId}",
            sourceUrn = SourceUrn.whatsapp(
                connectionId = doc.connectionId,
                messageId = doc.messageId,
                chatName = doc.chatName,
            ),
            projectId = doc.projectId,
            taskName = taskName,
        )

        taskService.setTopicId(task.id, topicId)

        markAsIndexed(doc)
        logger.debug { "Indexed WhatsApp message: $taskName" }
    }

    private fun buildMessageContent(doc: WhatsAppMessageIndexDocument): String = buildString {
        appendLine("# WhatsApp Message")
        appendLine("**Chat:** ${doc.chatName ?: "unknown"}")
        if (doc.isGroup) appendLine("**Type:** Group chat")
        appendLine("**From:** ${doc.from ?: "unknown"}")
        appendLine("**Date:** ${doc.createdDateTime}")
        appendLine()

        val body = doc.body
        if (!body.isNullOrBlank()) {
            append(body.trim())
            appendLine()
        }

        if (doc.attachmentType != null) {
            appendLine()
            appendLine("**Attachment (${doc.attachmentType}):** ${doc.attachmentDescription ?: "no description"}")
        }

        appendLine()
        appendLine("---")
        appendLine("## Metadata")
        appendLine("- **Message ID:** ${doc.messageId}")
        appendLine("- **Connection ID:** ${doc.connectionId}")
        appendLine("- **Client ID:** ${doc.clientId}")
        if (doc.projectId != null) appendLine("- **Project ID:** ${doc.projectId}")
        appendLine("- **Chat:** ${doc.chatName ?: "N/A"}")
    }

    private suspend fun markAsIndexed(doc: WhatsAppMessageIndexDocument) {
        repository.save(
            doc.copy(state = PollingStatusEnum.INDEXED),
        )
    }

    private suspend fun markAsFailed(doc: WhatsAppMessageIndexDocument, error: String) {
        repository.save(doc.copy(state = PollingStatusEnum.FAILED, indexingError = error))
    }
}
