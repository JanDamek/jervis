package com.jervis.entity.connection

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Stores optional per-connection filter configuration for external tools (e.g., JIRA).
 * Unique per (connectionId, tool).
 *
 * For JIRA you can specify either a custom JQL or a list of project keys.
 * If both are provided, JQL takes precedence.
 */
@Document(collection = "connection_filters")
@CompoundIndexes(
    CompoundIndex(name = "connection_tool_unique_idx", def = "{'connectionId':1,'tool':1}", unique = true),
)
data class ConnectionFilterDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val connectionId: ObjectId,
    val tool: String,
    val jql: String? = null,
    val projectKeys: List<String> = emptyList(),
    val updatedAt: Instant = Instant.now(),
)
