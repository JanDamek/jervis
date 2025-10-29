package com.jervis.domain.sender

import com.jervis.entity.RelationshipType
import com.jervis.entity.ThreadStatus
import org.bson.types.ObjectId

data class EnrichedContext(
    val sender: SenderContext,
    val thread: ThreadContext,
    val ragContext: RagContext,
)

data class SenderContext(
    val profileId: ObjectId,
    val name: String,
    val relationship: RelationshipType,
    val organization: String?,
    val conversationSummary: String?,
    val recentTopics: List<String>,
)

data class ThreadContext(
    val threadId: ObjectId,
    val subject: String,
    val messageCount: Int,
    val summary: String?,
    val requiresResponse: Boolean,
    val previousMessages: List<String>,
    val status: ThreadStatus,
)

data class RagContext(
    val relevantPastMessages: Int,
    val keyExcerpts: List<String>,
)
