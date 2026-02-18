package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Persisted record of a completed orchestrator task.
 * Stored when a task finishes (done, error, interrupted) for history display.
 */
@Document(collection = "task_history")
data class TaskHistoryDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val taskId: String,
    val taskPreview: String,
    val projectName: String?,
    val clientName: String?,
    val startedAt: Instant?,
    @Indexed val completedAt: Instant = Instant.now(),
    val status: String, // "done", "error", "interrupted"
    val processingMode: String, // "FOREGROUND", "BACKGROUND"
    /** JSON-serialized workflow steps (same format as OrchestratorWorkflowTracker) */
    val workflowStepsJson: String? = null,
    /** JSON-serialized orchestrator steps with durations [{node, label, durationMs}] */
    val orchestratorStepsJson: String? = null,
)
