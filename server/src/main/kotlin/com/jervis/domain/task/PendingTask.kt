package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a pending background task awaiting processing.
 *
 * @property context Dynamic key-value pairs for prompt template substitution.
 *                   Used by qualifier and orchestrator to fill {placeholder} variables in YAML prompts.
 *                   Common keys: from, to, subject, body, date, etc.
 */
data class PendingTask(
    val id: ObjectId = ObjectId(),
    val taskType: PendingTaskTypeEnum,
    val content: String? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId? = null,
    val createdAt: Instant = Instant.now(),
    val needsQualification: Boolean = false,
    val context: Map<String, String> = emptyMap(),
)
