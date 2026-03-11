package com.jervis.service.email

import com.jervis.dto.TaskStateEnum
import com.jervis.entity.TaskDocument
import com.jervis.entity.email.EmailDirection
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.repository.TaskRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

data class ThreadContext(
    val threadId: String,
    val topicId: String,
    val messageCount: Int,
    val existingTask: TaskDocument?,
    val userReplied: Boolean,
    val threadSummary: String,
)

@Service
class EmailThreadService(
    private val emailRepository: EmailMessageIndexRepository,
    private val taskRepository: TaskRepository,
) {
    /**
     * Analyze email thread context for a new message.
     *
     * Returns null if this is a standalone message (no thread).
     * Otherwise returns thread context with existing task, user reply status, and summary.
     */
    suspend fun analyzeThread(doc: EmailMessageIndexDocument): ThreadContext? {
        val threadId = doc.threadId ?: return null

        val threadMessages = emailRepository.findByThreadIdOrderBySentDateAsc(threadId).toList()
        if (threadMessages.size <= 1) return null // First or only message in thread

        // Check if user has sent a reply in this thread
        val userReplied = threadMessages.any {
            it.direction == EmailDirection.SENT &&
                it.id != doc.id // Not the current message itself
        }

        // Find existing active task for this thread
        val activeStates = listOf(
            TaskStateEnum.INDEXING, TaskStateEnum.QUALIFYING, TaskStateEnum.QUEUED,
            TaskStateEnum.PROCESSING, TaskStateEnum.USER_TASK, TaskStateEnum.BLOCKED,
        )
        val topicId = "email-thread:$threadId"
        val existingTask = taskRepository.findFirstByTopicIdAndStateIn(topicId, activeStates)

        return ThreadContext(
            threadId = threadId,
            topicId = topicId,
            messageCount = threadMessages.size,
            existingTask = existingTask,
            userReplied = userReplied,
            threadSummary = buildThreadSummary(threadMessages),
        )
    }

    private fun buildThreadSummary(messages: List<EmailMessageIndexDocument>): String = buildString {
        appendLine("## Konverzace (${messages.size} zpráv)")
        for (msg in messages.takeLast(5)) {
            val direction = if (msg.direction == EmailDirection.SENT) "→ Odesláno" else "← Přijato"
            appendLine("$direction (${msg.sentDate}): ${msg.from}")
            appendLine(msg.textBody?.take(300) ?: "")
            appendLine("---")
        }
    }
}
