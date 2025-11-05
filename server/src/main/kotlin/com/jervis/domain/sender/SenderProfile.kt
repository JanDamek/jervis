package com.jervis.domain.sender

import com.jervis.domain.email.RelationshipTypeEnum
import org.bson.types.ObjectId
import java.time.Instant

data class SenderProfile(
    val id: ObjectId,
    val primaryIdentifier: String,
    val displayName: String?,
    val aliases: List<SenderAlias>,
    val relationship: RelationshipTypeEnum,
    val organization: String?,
    val role: String?,
    val conversationSummary: String?,
    val lastSummaryUpdate: Instant?,
    val topics: List<String>,
    val communicationStats: CommunicationStats?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val lastInteractionAt: Instant?,
    val totalMessagesReceived: Int,
    val totalMessagesSent: Int,
)
