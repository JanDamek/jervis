package com.jervis.entity

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatSessionDocument - stores Koog agent checkpoint metadata for conversation continuity.
 *
 * Purpose:
 * - Tracks active chat sessions between client devices and projects
 * - Links to Koog Persistence checkpoints for agent state restoration
 * - Enables continuation of conversation from exact execution point
 *
 * Workflow:
 * 1. First message: Create new session with null checkpointId
 * 2. Agent execution: Save checkpoint, update session with checkpointId
 * 3. Next message: Load checkpoint, agent continues from saved state
 * 4. Auto-cleanup: Remove sessions inactive > 24 hours
 *
 * @property sessionId Unique session identifier (format: "clientId:projectId")
 * @property clientId Client device identifier
 * @property projectId Project identifier
 * @property checkpointId Koog checkpoint ID (null if no checkpoint yet)
 * @property lastCorrelationId Last TaskDocument correlationId processed
 * @property createdAt Session creation timestamp
 * @property lastActivityAt Last message timestamp
 * @property metadata Additional session metadata (extensible)
 */
@Document(collection = "chat_sessions")
@CompoundIndex(name = "client_project_idx", def = "{'clientId': 1, 'projectId': 1}", unique = true)
data class ChatSessionDocument(
    @Id
    val sessionId: String, // Format: "clientId:projectId"
    @Indexed
    val clientId: ClientId,
    val projectId: ProjectId,
    val checkpointId: String? = null, // Koog checkpoint ID
    val lastCorrelationId: String,
    val createdAt: Instant = Instant.now(),
    @Indexed
    val lastActivityAt: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap(),
)
