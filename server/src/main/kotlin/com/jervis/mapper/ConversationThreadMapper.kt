package com.jervis.mapper

import com.jervis.domain.sender.ActionItem
import com.jervis.domain.sender.ChannelMapping
import com.jervis.domain.sender.ConversationThread
import com.jervis.entity.ActionItemEmbedded
import com.jervis.entity.ChannelMappingEmbedded
import com.jervis.entity.ConversationThreadDocument

/**
 * Extension mappers for ConversationThread Entity ↔ Domain.
 * Kotlin idiomatic approach - no mapper objects.
 */

fun ConversationThreadDocument.toDomain(): ConversationThread =
    ConversationThread(
        id = id,
        threadId = threadId,
        subject = subject,
        channelMappings = channelMappings.map { it.toDomain() },
        senderProfileIds = senderProfileIds,
        participantSummary = participantSummary,
        category = category,
        priority = priority,
        status = status,
        summary = summary,
        keyPoints = keyPoints,
        lastSummaryUpdate = lastSummaryUpdate,
        messageIds = messageIds,
        messageCount = messageCount,
        firstMessageAt = firstMessageAt,
        lastMessageAt = lastMessageAt,
        lastMessageFrom = lastMessageFrom,
        requiresResponse = requiresResponse,
        responseDeadline = responseDeadline,
        actionItems = actionItems.map { it.toDomain() },
        ragDocumentIds = ragDocumentIds,
        projectId = projectId,
        clientId = clientId,
        tags = tags,
    )

// DOMAIN → ENTITY
fun ConversationThread.toEntity(): ConversationThreadDocument =
    ConversationThreadDocument(
        id = id,
        threadId = threadId,
        subject = subject,
        channelMappings = channelMappings.map { it.toEntity() },
        senderProfileIds = senderProfileIds,
        participantSummary = participantSummary,
        category = category,
        priority = priority,
        status = status,
        summary = summary,
        keyPoints = keyPoints,
        lastSummaryUpdate = lastSummaryUpdate,
        messageIds = messageIds,
        messageCount = messageCount,
        firstMessageAt = firstMessageAt,
        lastMessageAt = lastMessageAt,
        lastMessageFrom = lastMessageFrom,
        requiresResponse = requiresResponse,
        responseDeadline = responseDeadline,
        actionItems = actionItems.map { it.toEntity() },
        ragDocumentIds = ragDocumentIds,
        projectId = projectId,
        clientId = clientId,
        tags = tags,
    )

// NESTED TYPES - ActionItem
fun ActionItemEmbedded.toDomain(): ActionItem =
    ActionItem(
        description = description,
        assignedTo = assignedTo,
        deadline = deadline,
        completed = completed,
        createdAt = createdAt,
    )

fun ActionItem.toEntity(): ActionItemEmbedded =
    ActionItemEmbedded(
        description = description,
        assignedTo = assignedTo,
        deadline = deadline,
        completed = completed,
        createdAt = createdAt,
    )

// NESTED TYPES - ChannelMapping
fun ChannelMappingEmbedded.toDomain(): ChannelMapping =
    ChannelMapping(
        channel = channel,
        externalId = externalId,
        externalThreadId = externalThreadId,
        addedAt = addedAt,
    )

fun ChannelMapping.toEntity(): ChannelMappingEmbedded =
    ChannelMappingEmbedded(
        channel = channel,
        externalId = externalId,
        externalThreadId = externalThreadId,
        addedAt = addedAt,
    )
