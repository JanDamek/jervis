package com.jervis.entity

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
    val relationship: RelationshipType = RelationshipType.UNKNOWN,
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

data class SenderAliasEmbedded(
    val type: AliasType,
    val value: String,
    val displayName: String? = null,
    val verified: Boolean = false,
    val firstSeenAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
)

enum class AliasType {
    EMAIL_WORK,
    EMAIL_PERSONAL,
    PHONE_MOBILE,
    PHONE_WORK,
    SLACK_USER,
    TEAMS_USER,
    DISCORD_USER,
    JIRA_USER,
    GIT_AUTHOR,
}

enum class RelationshipType {
    UNKNOWN,
    COLLEAGUE,
    CLIENT,
    VENDOR,
    FRIEND,
    FAMILY,
    SYSTEM,
    SUPPORT,
}

data class CommunicationStatsEmbedded(
    val averageResponseTimeMs: Long? = null,
    val preferredChannel: String? = null,
    val typicalResponseDay: String? = null,
    val timezone: String? = null,
)
