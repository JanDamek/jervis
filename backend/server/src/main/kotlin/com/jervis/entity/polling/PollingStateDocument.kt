package com.jervis.entity.polling

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Stores incremental polling state per connection and tool.
 * Unique per (connectionId, tool).
 */
@Document(collection = "polling_state")
@CompoundIndexes(
    CompoundIndex(name = "connection_tool_unique_idx", def = "{'connectionId':1,'tool':1}", unique = true),
)
data class PollingStateDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val connectionId: ObjectId,
    val tool: String,
    val lastSeenUpdatedAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
)
