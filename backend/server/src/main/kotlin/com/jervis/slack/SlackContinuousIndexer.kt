package com.jervis.slack

import com.jervis.common.types.SourceUrn
import com.jervis.dto.urgency.Presence
import com.jervis.infrastructure.polling.PollingStatusEnum
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import com.jervis.urgency.InboundMessageType
import com.jervis.urgency.InboundSignal
import com.jervis.urgency.PresenceCache
import com.jervis.urgency.StructuralUrgencyDetector
import com.jervis.urgency.UrgencyConfigDocument
import com.jervis.urgency.UrgencyConfigRepository
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
 * Continuous indexer for Slack messages.
 *
 * Same architecture as TeamsContinuousIndexer / EmailContinuousIndexer:
 * - SlackPollingHandler fetches messages via Slack API -> saves as NEW in MongoDB
 * - This indexer reads NEW documents (no external API calls)
 * - Creates SLACK_PROCESSING tasks for Qualifier
 * - Converts to INDEXED (minimal tracking record)
 */
@Service
@Order(13)
class SlackContinuousIndexer(
    private val repository: SlackMessageIndexRepository,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val urgencyDetector: StructuralUrgencyDetector,
    private val urgencyConfigRepository: UrgencyConfigRepository,
    private val presenceCache: PresenceCache,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting SlackContinuousIndexer (MongoDB -> RAG)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Slack continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        continuousNewMessages().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) return@collect
            try {
                indexMessage(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index Slack message ${doc.messageId}" }
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

    private suspend fun indexMessage(doc: SlackMessageIndexDocument) {
        val content = buildMessageContent(doc)

        val taskName = "Slack: #${doc.channelName ?: doc.channelId} - ${doc.from ?: "unknown"}".take(120)

        // Use thread-aware topicId: messages in the same thread share a topic
        val topicId = if (doc.threadTs != null) {
            "slack-thread:${doc.channelId}:${doc.threadTs}"
        } else {
            "slack-channel:${doc.channelId}"
        }

        // TopicId merge: append to existing active task instead of creating new
        val activeStates = listOf(
            TaskStateEnum.NEW, TaskStateEnum.INDEXING, TaskStateEnum.QUEUED,
            TaskStateEnum.PROCESSING, TaskStateEnum.USER_TASK, TaskStateEnum.BLOCKED,
        )
        val existing = taskRepository.findFirstByTopicIdAndStateIn(topicId, activeStates)

        if (existing != null) {
            val now = java.time.Instant.now()
            val updated = existing.copy(
                content = "${existing.content}\n\n---\n\n$content",
                lastActivityAt = now,
                needsQualification = true,
                taskName = taskName,
            )
            taskRepository.save(updated)
            logger.debug { "Slack message appended to existing task ${existing.id} (topic=$topicId)" }
        } else {
            // Derive deadline + presence from structural urgency signal (DM detection etc.).
            // Slack DM channel IDs start with 'D'; public/private channels start with 'C'/'G'.
            val isDm = doc.channelId.startsWith("D")
            val signal = InboundSignal(
                platform = "slack",
                senderId = doc.from ?: "unknown",
                messageType = if (isDm) InboundMessageType.DIRECT else InboundMessageType.CHANNEL_MESSAGE,
                isDirectMessage = isDm,
                mentionsMe = false,        // TODO: parse body for bot user mention (next iter)
                replyToMyThread = false,   // TODO: thread root lookup (next iter)
                threadActive = false,
            )
            val cfg = urgencyConfigRepository.findByClientId(doc.clientId)
                ?: UrgencyConfigDocument.default(doc.clientId)
            val presence = doc.from?.let { presenceCache.get(it, "slack")?.presence } ?: Presence.UNKNOWN
            val decision = urgencyDetector.decide(signal, cfg, presence = presence)

            val task = taskService.createTask(
                taskType = TaskTypeEnum.SYSTEM,
                content = content,
                clientId = doc.clientId,
                correlationId = "slack:${doc.messageId}",
                sourceUrn = SourceUrn.slack(
                    connectionId = doc.connectionId,
                    messageId = doc.messageId,
                    channelId = doc.channelId,
                ),
                projectId = doc.projectId,
                taskName = taskName,
                deadline = decision.deadline,
                userPresence = decision.presence.name,
            )
            taskService.setTopicId(task.id, topicId)
            logger.debug {
                "Indexed new Slack message: $taskName (topic=$topicId, deadline=${decision.deadline}, presence=${decision.presence})"
            }
        }

        markAsIndexed(doc)
    }

    private fun buildMessageContent(doc: SlackMessageIndexDocument): String = buildString {
        appendLine("# Slack Message")
        appendLine("**Channel:** #${doc.channelName ?: doc.channelId}")
        if (doc.workspaceName != null) appendLine("**Workspace:** ${doc.workspaceName}")
        appendLine("**From:** ${doc.from ?: "unknown"}")
        appendLine("**Date:** ${doc.createdDateTime}")
        if (doc.threadTs != null) appendLine("**Thread:** ${doc.threadTs}")
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
        appendLine("- **Channel ID:** ${doc.channelId}")
    }

    private suspend fun markAsIndexed(doc: SlackMessageIndexDocument) {
        val updated = SlackMessageIndexDocument(
            id = doc.id,
            clientId = doc.clientId,
            projectId = doc.projectId,
            connectionId = doc.connectionId,
            messageId = doc.messageId,
            channelId = doc.channelId,
            createdDateTime = doc.createdDateTime,
            state = PollingStatusEnum.INDEXED,
        )
        repository.save(updated)
    }

    private suspend fun markAsFailed(doc: SlackMessageIndexDocument, error: String) {
        repository.save(doc.copy(state = PollingStatusEnum.FAILED, indexingError = error))
    }
}
