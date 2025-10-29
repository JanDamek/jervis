package com.jervis.domain.sender

import com.jervis.entity.ConversationCategory
import com.jervis.entity.MessageChannel
import com.jervis.entity.Priority
import com.jervis.entity.ThreadStatus
import org.bson.types.ObjectId
import java.time.Instant

data class ConversationThread(
    val id: ObjectId,
    val threadId: String,
    val subject: String,
    val channelMappings: List<ChannelMapping>,
    val senderProfileIds: List<ObjectId>,
    val participantSummary: String?,
    val category: ConversationCategory,
    val priority: Priority,
    val status: ThreadStatus,
    val summary: String?,
    val keyPoints: List<String>,
    val lastSummaryUpdate: Instant?,
    val messageIds: List<String>,
    val messageCount: Int,
    val firstMessageAt: Instant,
    val lastMessageAt: Instant,
    val lastMessageFrom: String?,
    val requiresResponse: Boolean,
    val responseDeadline: Instant?,
    val actionItems: List<ActionItem>,
    val ragDocumentIds: List<String>,
    val projectId: ObjectId?,
    val clientId: ObjectId,
    val tags: List<String>,
)

data class ActionItem(
    val description: String,
    val assignedTo: String?,
    val deadline: Instant?,
    val completed: Boolean,
    val createdAt: Instant,
)

data class ChannelMapping(
    val channel: MessageChannel,
    val externalId: String,
    val externalThreadId: String?,
    val addedAt: Instant,
)
