package com.jervis.service.sender

import com.jervis.domain.sender.ActionItem
import com.jervis.domain.sender.ConversationThread
import com.jervis.entity.ConversationCategory
import com.jervis.entity.Priority
import com.jervis.entity.ThreadStatus
import com.jervis.mapper.toDomain
import com.jervis.mapper.toEntity
import com.jervis.repository.mongo.ConversationThreadMongoRepository
import com.jervis.service.listener.email.imap.ImapMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class ConversationThreadService(
    private val repository: ConversationThreadMongoRepository,
) {
    suspend fun findById(id: ObjectId): ConversationThread? = repository.findById(id)?.toDomain()

    suspend fun findByThreadId(threadId: String): ConversationThread? = repository.findByThreadId(threadId)?.toDomain()

    fun findBySenderProfileId(
        senderProfileId: ObjectId,
        limit: Int = 10,
    ): Flow<ConversationThread> =
        repository
            .findBySenderProfileIdsContaining(senderProfileId)
            .take(limit)
            .map { it.toDomain() }

    fun findByClientAndProject(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): Flow<ConversationThread> =
        repository
            .findByClientIdAndProjectId(clientId, projectId)
            .map { it.toDomain() }

    suspend fun findOrCreateThread(
        emailHeaders: ImapMessage,
        senderProfileId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ): ConversationThread {
        val threadId = extractThreadId(emailHeaders)

        findByThreadId(threadId)?.let { existing ->
            return addMessageToThread(
                thread = existing,
                messageId = emailHeaders.messageId,
                senderProfileId = senderProfileId,
            )
        }

        logger.info { "Creating new conversation thread: $threadId" }

        val newThread =
            ConversationThread(
                id = ObjectId(),
                threadId = threadId,
                subject = normalizeSubject(emailHeaders.subject),
                channelMappings =
                    listOf(
                        com.jervis.domain.sender.ChannelMapping(
                            channel = com.jervis.entity.MessageChannel.EMAIL,
                            externalId = emailHeaders.messageId,
                            externalThreadId = null,
                            addedAt = emailHeaders.receivedAt,
                        ),
                    ),
                senderProfileIds = listOf(senderProfileId),
                participantSummary = null,
                category = inferCategory(emailHeaders),
                priority = Priority.NORMAL,
                status = ThreadStatus.ACTIVE,
                summary = null,
                keyPoints = emptyList(),
                lastSummaryUpdate = null,
                messageIds = listOf(emailHeaders.messageId),
                messageCount = 1,
                firstMessageAt = emailHeaders.receivedAt,
                lastMessageAt = emailHeaders.receivedAt,
                lastMessageFrom = emailHeaders.from,
                requiresResponse = detectRequiresResponse(emailHeaders),
                responseDeadline = null,
                actionItems = emptyList(),
                ragDocumentIds = emptyList(),
                projectId = projectId,
                clientId = clientId,
                tags = emptyList(),
            )

        val entity = newThread.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun addMessageToThread(
        thread: ConversationThread,
        messageId: String,
        senderProfileId: ObjectId,
    ): ConversationThread {
        if (thread.messageIds.contains(messageId)) {
            return thread
        }

        val updatedSenderIds =
            if (thread.senderProfileIds.contains(senderProfileId)) {
                thread.senderProfileIds
            } else {
                thread.senderProfileIds + senderProfileId
            }

        val updated =
            thread.copy(
                messageIds = thread.messageIds + messageId,
                messageCount = thread.messageCount + 1,
                lastMessageAt = Instant.now(),
                senderProfileIds = updatedSenderIds,
            )

        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun updateSummary(
        threadId: ObjectId,
        summary: String,
        keyPoints: List<String>,
        requiresResponse: Boolean,
    ): ConversationThread? {
        val thread = findById(threadId) ?: return null
        val updated =
            thread.copy(
                summary = summary,
                keyPoints = keyPoints,
                requiresResponse = requiresResponse,
                lastSummaryUpdate = Instant.now(),
            )
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun updateStatus(
        threadId: ObjectId,
        status: ThreadStatus,
    ): ConversationThread? {
        val thread = findById(threadId) ?: return null
        val updated = thread.copy(status = status)
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun addActionItem(
        threadId: ObjectId,
        description: String,
        assignedTo: String?,
        deadline: Instant?,
    ): ConversationThread? {
        val thread = findById(threadId) ?: return null
        val newAction =
            ActionItem(
                description = description,
                assignedTo = assignedTo,
                deadline = deadline,
                completed = false,
                createdAt = Instant.now(),
            )
        val updated = thread.copy(actionItems = thread.actionItems + newAction)
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun addRagDocumentId(
        threadId: ObjectId,
        ragDocumentId: String,
    ): ConversationThread? {
        val thread = findById(threadId) ?: return null
        if (thread.ragDocumentIds.contains(ragDocumentId)) {
            return thread
        }
        val updated = thread.copy(ragDocumentIds = thread.ragDocumentIds + ragDocumentId)
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun getActiveThreadsRequiringResponse(): List<ConversationThread> {
        val now = Instant.now()
        return repository
            .findByRequiresResponseTrueAndResponseDeadlineBefore(now)
            .map { it.toDomain() }
            .toList()
    }

    private fun extractThreadId(email: ImapMessage): String {
        // ImapMessage doesn't have references or inReplyTo fields
        // Use messageId as the threadId for now
        return email.messageId
    }

    private fun normalizeSubject(subject: String): String =
        subject.replace(Regex("^(Re:|Fwd:|Fw:)\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun inferCategory(email: ImapMessage): ConversationCategory =
        when {
            email.subject.contains("failed", ignoreCase = true) -> ConversationCategory.SUPPORT_REQUEST
            email.subject.contains("error", ignoreCase = true) -> ConversationCategory.SUPPORT_REQUEST
            email.subject.contains("urgent", ignoreCase = true) -> ConversationCategory.SUPPORT_REQUEST
            email.subject.contains("task", ignoreCase = true) -> ConversationCategory.TASK_ASSIGNMENT
            email.subject.contains("backup", ignoreCase = true) -> ConversationCategory.SYSTEM_NOTIFICATION
            email.from.contains("noreply", ignoreCase = true) -> ConversationCategory.SYSTEM_NOTIFICATION
            else -> ConversationCategory.DISCUSSION
        }

    private fun detectRequiresResponse(email: ImapMessage): Boolean =
        email.content.contains("?") ||
            email.subject.contains("?") ||
            email.subject.contains("failed", ignoreCase = true) ||
            email.subject.contains("error", ignoreCase = true)
}
