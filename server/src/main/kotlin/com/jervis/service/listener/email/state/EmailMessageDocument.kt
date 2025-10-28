package com.jervis.service.listener.email.state

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class EmailMessageState {
    NEW,
    INDEXED,
}

@Document(collection = "email_messages")
@CompoundIndexes(
    CompoundIndex(name = "account_messageId_idx", def = "{'accountId':1,'messageId':1}", unique = true),
    CompoundIndex(name = "account_state_receivedAt_idx", def = "{'accountId':1,'state':1,'receivedAt':1}"),
)
data class EmailMessageDocument(
    @Id val id: ObjectId = ObjectId(),
    val accountId: ObjectId,
    val messageId: String,
    val state: EmailMessageState,
    val subject: String? = null,
    val from: String? = null,
    val receivedAt: Instant? = null,
)
