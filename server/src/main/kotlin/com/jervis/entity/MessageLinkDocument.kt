package com.jervis.entity

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
)
data class MessageLinkDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed(unique = true)
    val messageId: String,
    val channel: MessageChannel,
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

enum class MessageChannel {
    EMAIL,
    SLACK,
    TEAMS,
    DISCORD,
    JIRA,
    GIT_COMMIT,
}
