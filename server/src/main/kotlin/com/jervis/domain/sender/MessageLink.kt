package com.jervis.domain.sender

import com.jervis.entity.MessageChannel
import org.bson.types.ObjectId
import java.time.Instant

data class MessageLink(
    val id: ObjectId,
    val messageId: String,
    val channel: MessageChannel,
    val threadId: ObjectId,
    val senderProfileId: ObjectId,
    val subject: String?,
    val snippet: String?,
    val timestamp: Instant,
    val hasAttachments: Boolean,
    val ragDocumentId: String?,
)
