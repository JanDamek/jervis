package com.jervis.domain.sender

import com.jervis.domain.confluence.ConversationCategoryEnum
import com.jervis.domain.confluence.PriorityEnum
import com.jervis.domain.confluence.ThreadStatusEnum
import org.bson.types.ObjectId
import java.time.Instant

data class ConversationThread(
    val id: ObjectId,
    val threadId: String,
    val subject: String,
    val channelMappings: List<ChannelMapping>,
    val senderProfileIds: List<ObjectId>,
    val participantSummary: String?,
    val category: ConversationCategoryEnum,
    val priorityEnum: PriorityEnum,
    val status: ThreadStatusEnum,
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
