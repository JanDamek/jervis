package com.jervis.entity

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatSessionDocument - stores conversation history and agent state for chat continuity.
 *
 * Purpose:
 * - Tracks active chat sessions between client devices (and optionally projects)
 * - Stores conversation messages for history display
 * - Links to Koog Persistence checkpoints for agent state restoration
 * - Enables continuation of conversation from exact execution point
 *
 * Workflow:
 * 1. First message: Create new session with empty messages
 * 2. Agent execution: Append user/assistant messages
 * 3. Next message: Load last 10 messages for display
 * 4. Auto-cleanup: Remove sessions inactive > 24 hours
 *
 * @property sessionId Unique session identifier (format: "clientId[:projectId]")
 * @property clientId Client device identifier
 * @property projectId Project identifier (optional - null for general chat)
 * @property messages Conversation history (user + assistant messages)
 * @property checkpointId Koog checkpoint ID (null if no checkpoint yet)
 * @property lastCorrelationId Last TaskDocument correlationId processed
 * @property createdAt Session creation timestamp
 * @property lastActivityAt Last message timestamp
 * @property metadata Additional session metadata (extensible)
 */
@Document(collection = "chat_sessions")
@CompoundIndex(name = "client_project_idx", def = "{'clientId': 1, 'projectId': 1}")
data class ChatSessionDocument(
    @Id
    val sessionId: String, // Format: "clientId[:projectId]"
    @Indexed
    val clientId: ClientId,
    val projectId: ProjectId? = null, // Optional - null for general chat
    val messages: List<ChatMessageDocument> = emptyList(),
    val checkpointId: String? = null, // Koog checkpoint ID
    val lastCorrelationId: String? = null,
    val createdAt: Instant = Instant.now(),
    @Indexed
    val lastActivityAt: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * ChatMessageDocument - single message in conversation history.
 *
 * @property role Message role: "user", "assistant", "system"
 * @property content Message text content
 * @property timestamp Message creation time
 * @property correlationId Link to TaskDocument for traceability
 * @property metadata Additional message metadata (e.g., status updates, progress)
 */
data class ChatMessageDocument(
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Instant = Instant.now(),
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
