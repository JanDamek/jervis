package com.jervis.mapper

import com.jervis.domain.sender.MessageLink
import com.jervis.entity.MessageLinkDocument

/**
 * Extension mappers for MessageLink Entity ↔ Domain.
 * Kotlin idiomatic approach - no mapper objects.
 */

// ENTITY → DOMAIN
fun MessageLinkDocument.toDomain(): MessageLink =
    MessageLink(
        id = id,
        messageId = messageId,
        channel = channel,
        threadId = threadId,
        senderProfileId = senderProfileId,
        subject = subject,
        snippet = snippet,
        timestamp = timestamp,
        hasAttachments = hasAttachments,
        ragDocumentId = ragDocumentId,
    )

// DOMAIN → ENTITY
fun MessageLink.toEntity(): MessageLinkDocument =
    MessageLinkDocument(
        id = id,
        messageId = messageId,
        channel = channel,
        threadId = threadId,
        senderProfileId = senderProfileId,
        subject = subject,
        snippet = snippet,
        timestamp = timestamp,
        hasAttachments = hasAttachments,
        ragDocumentId = ragDocumentId,
    )
