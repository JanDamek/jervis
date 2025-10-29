package com.jervis.domain.sender

import com.jervis.entity.AliasType
import com.jervis.entity.RelationshipType
import org.bson.types.ObjectId
import java.time.Instant

data class SenderProfile(
    val id: ObjectId,
    val primaryIdentifier: String,
    val displayName: String?,
    val aliases: List<SenderAlias>,
    val relationship: RelationshipType,
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

data class SenderAlias(
    val type: AliasType,
    val value: String,
    val displayName: String?,
    val verified: Boolean,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)

data class CommunicationStats(
    val averageResponseTimeMs: Long?,
    val preferredChannel: String?,
    val typicalResponseDay: String?,
    val timezone: String?,
)
