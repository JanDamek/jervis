package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a pending background task awaiting processing.
 *
 * ## Context Keys
 *
 * ### Standard Keys (task metadata)
 * - `from`, `to`, `subject`, `body`, `date` - Email-related metadata
 * - `senderProfileId`, `threadId` - Conversation tracking
 * - `sourceUri` - Original content location
 * - `projectId`, `filePath`, `commitHash`, `branch` - Git-related metadata
 * - `fileContent` - Source file content for analysis
 *
 * ### Dynamic Goal System (per-task specialization)
 * - `dynamicGoal` - Optional per-task goal that specializes the base YAML goal
 *   - Example: "Focus on concurrency implications of cache layer"
 *   - Appended to GPU worker prompt after YAML goal
 *   - Never set by qualifier (only by task creators or manual API)
 *   - Keep under ~1KB (fail fast if blank)
 *
 * - `qualificationNotes` - Audit trail from qualifier decision
 *   - Example: "DELEGATED: Actionable content" or "DISCARDED: Spam/noise detected"
 *   - Set by TaskQualificationService after DISCARD/DELEGATE decision
 *   - Used for observability and debugging
 *
 * ## Architectural Rules
 * - YAML goals remain source of truth (background-task-goals.yaml)
 * - Dynamic goals are strictly additive (append-only)
 * - Qualifier never assigns goals (only observations/notes)
 * - Context is persisted in MongoDB via PendingTaskDocument
 *
 * @property context Dynamic key-value pairs - see above for standard and dynamic keys
 */
data class PendingTask(
    val id: ObjectId = ObjectId(),
    val taskType: PendingTaskTypeEnum,
    val content: String? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val createdAt: Instant = Instant.now(),
    val needsQualification: Boolean = false,
    val context: Map<String, String> = emptyMap(),
)
