package com.jervis.entity

import com.jervis.domain.MessageChannelEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "message_links")
@CompoundIndexes(
    CompoundIndex(name = "thread_timestamp", def = "{'threadId': 1, 'timestamp': -1}"),
    CompoundIndex(name = "sender_timestamp", def = "{'senderProfileId': 1, 'timestamp': -1}"),
    // Ensure uniqueness per thread to avoid cross-client/global collisions on messageId
    CompoundIndex(name = "message_thread_unique", def = "{'messageId': 1, 'threadId': 1}", unique = true),
)
data class MessageLinkDocument(
    @Id val id: ObjectId = ObjectId(),
    val messageId: String,
    val channel: MessageChannelEnum,
    @Indexed
    val threadId: ObjectId,
    @Indexed
    val senderProfileId: ObjectId,
    val subject: String? = null,
    val snippet: String? = null,
    val timestamp: Instant = Instant.now(),
    val hasAttachments: Boolean = false,
    val ragDocumentId: String? = null,
)
