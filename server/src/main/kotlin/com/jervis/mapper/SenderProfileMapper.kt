package com.jervis.mapper

import com.jervis.domain.sender.CommunicationStats
import com.jervis.domain.sender.SenderAlias
import com.jervis.domain.sender.SenderProfile
import com.jervis.entity.CommunicationStatsEmbedded
import com.jervis.entity.SenderAliasEmbedded
import com.jervis.entity.SenderProfileDocument

/**
 * Extension mappers for SenderProfile Entity ↔ Domain.
 * Kotlin idiomatic approach - no mapper objects.
 */

// ENTITY → DOMAIN
fun SenderProfileDocument.toDomain(): SenderProfile =
    SenderProfile(
        id = id,
        primaryIdentifier = primaryIdentifier,
        displayName = displayName,
        aliases = aliases.map { it.toDomain() },
        relationship = relationship,
        organization = organization,
        role = role,
        conversationSummary = conversationSummary,
        lastSummaryUpdate = lastSummaryUpdate,
        topics = topics,
        communicationStats = communicationStats?.toDomain(),
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        lastInteractionAt = lastInteractionAt,
        totalMessagesReceived = totalMessagesReceived,
        totalMessagesSent = totalMessagesSent,
    )

// DOMAIN → ENTITY
fun SenderProfile.toEntity(): SenderProfileDocument =
    SenderProfileDocument(
        id = id,
        primaryIdentifier = primaryIdentifier,
        displayName = displayName,
        aliases = aliases.map { it.toEntity() },
        relationship = relationship,
        organization = organization,
        role = role,
        conversationSummary = conversationSummary,
        lastSummaryUpdate = lastSummaryUpdate,
        topics = topics,
        communicationStats = communicationStats?.toEntity(),
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        lastInteractionAt = lastInteractionAt,
        totalMessagesReceived = totalMessagesReceived,
        totalMessagesSent = totalMessagesSent,
    )

// NESTED TYPES - SenderAlias
fun SenderAliasEmbedded.toDomain(): SenderAlias =
    SenderAlias(
        type = type,
        value = value,
        displayName = displayName,
        verified = verified,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )

fun SenderAlias.toEntity(): SenderAliasEmbedded =
    SenderAliasEmbedded(
        type = type,
        value = value,
        displayName = displayName,
        verified = verified,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )

// NESTED TYPES - CommunicationStats
fun CommunicationStatsEmbedded.toDomain(): CommunicationStats =
    CommunicationStats(
        averageResponseTimeMs = averageResponseTimeMs,
        preferredChannel = preferredChannel,
        typicalResponseDay = typicalResponseDay,
        timezone = timezone,
    )

fun CommunicationStats.toEntity(): CommunicationStatsEmbedded =
    CommunicationStatsEmbedded(
        averageResponseTimeMs = averageResponseTimeMs,
        preferredChannel = preferredChannel,
        typicalResponseDay = typicalResponseDay,
        timezone = timezone,
    )
