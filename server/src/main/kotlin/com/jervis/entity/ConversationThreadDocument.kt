package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "conversation_threads")
@CompoundIndexes(
    CompoundIndex(name = "thread_client_project", def = "{'threadId': 1, 'clientId': 1, 'projectId': 1}"),
    CompoundIndex(name = "sender_profiles_last_message", def = "{'senderProfileIds': 1, 'lastMessageAt': -1}"),
)
data class ConversationThreadDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed(unique = true)
    val threadId: String,
    val subject: String,
    val channelMappings: List<ChannelMappingEmbedded> = emptyList(),
    val senderProfileIds: List<ObjectId> = emptyList(),
    val participantSummary: String? = null,
    val category: ConversationCategory = ConversationCategory.DISCUSSION,
    val priority: Priority = Priority.NORMAL,
    val status: ThreadStatus = ThreadStatus.ACTIVE,
    val summary: String? = null,
    val keyPoints: List<String> = emptyList(),
    val lastSummaryUpdate: Instant? = null,
    val messageIds: List<String> = emptyList(),
    val messageCount: Int = 0,
    val firstMessageAt: Instant = Instant.now(),
    val lastMessageAt: Instant = Instant.now(),
    val lastMessageFrom: String? = null,
    val requiresResponse: Boolean = false,
    val responseDeadline: Instant? = null,
    val actionItems: List<ActionItemEmbedded> = emptyList(),
    val ragDocumentIds: List<String> = emptyList(),
    @Indexed
    val projectId: ObjectId? = null,
    @Indexed
    val clientId: ObjectId,
    val tags: List<String> = emptyList(),
)

enum class ConversationCategory {
    DISCUSSION,
    SUPPORT_REQUEST,
    TASK_ASSIGNMENT,
    DECISION_MAKING,
    INFORMATION_SHARING,
    SYSTEM_NOTIFICATION,
}

enum class ThreadStatus {
    ACTIVE,
    WAITING_RESPONSE,
    RESOLVED,
    ARCHIVED,
}

enum class Priority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

data class ActionItemEmbedded(
    val description: String,
    val assignedTo: String? = null,
    val deadline: Instant? = null,
    val completed: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

data class ChannelMappingEmbedded(
    val channel: MessageChannel,
    val externalId: String,
    val externalThreadId: String? = null,
    val addedAt: Instant = Instant.now(),
)
