package com.jervis.entity

import com.jervis.domain.email.CommunicationStatsEmbedded
import com.jervis.domain.email.RelationshipTypeEnum
import com.jervis.domain.email.SenderAliasEmbedded
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "sender_profiles")
data class SenderProfileDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed(unique = true)
    val primaryIdentifier: String,
    val displayName: String? = null,
    val aliases: List<SenderAliasEmbedded> = emptyList(),
    val relationship: RelationshipTypeEnum = RelationshipTypeEnum.UNKNOWN,
    val organization: String? = null,
    val role: String? = null,
    val conversationSummary: String? = null,
    val lastSummaryUpdate: Instant? = null,
    val topics: List<String> = emptyList(),
    val communicationStats: CommunicationStatsEmbedded? = null,
    val firstSeenAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
    val lastInteractionAt: Instant? = null,
    val totalMessagesReceived: Int = 0,
    val totalMessagesSent: Int = 0,
)
