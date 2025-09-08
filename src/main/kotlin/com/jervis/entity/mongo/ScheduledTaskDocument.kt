package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Status of scheduled tasks
 */
enum class ScheduledTaskStatus {
    /** Task is pending execution */
    PENDING,

    /** Task is currently running */
    RUNNING,

    /** Task completed successfully */
    COMPLETED,

    /** Task failed */
    FAILED,

    /** Task was cancelled */
    CANCELLED,
}

/**
 * MongoDB document for managing scheduled tasks executed through planner.
 * All tasks are defined as text instructions that are executed by agents through MCP tools.
 */
@Document(collection = "scheduled_tasks")
@CompoundIndexes(
    CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}"),
    CompoundIndex(name = "status_scheduled", def = "{'status': 1, 'scheduledAt': 1}"),
)
data class ScheduledTaskDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val projectId: ObjectId,
    /** Task instruction to be executed through planner */
    @Indexed
    val taskInstruction: String,
    /** Current task status */
    @Indexed
    val status: ScheduledTaskStatus = ScheduledTaskStatus.PENDING,
    /** Task name/description */
    val taskName: String,
    /** Task parameters as JSON string */
    val taskParameters: Map<String, String> = emptyMap(),
    /** When the task is scheduled to run */
    @Indexed
    val scheduledAt: Instant,
    /** When the task actually started */
    val startedAt: Instant? = null,
    /** When the task completed */
    val completedAt: Instant? = null,
    /** Error message if task failed */
    val errorMessage: String? = null,
    /** Number of retry attempts */
    val retryCount: Int = 0,
    /** Maximum number of retries allowed */
    val maxRetries: Int = 3,
    /** Priority of the task (higher number = higher priority) */
    val priority: Int = 0,
    /** Recurring schedule expression (cron-like) if applicable */
    val cronExpression: String? = null,
    /** When this task was created */
    val createdAt: Instant = Instant.now(),
    /** When this task was last updated */
    val lastUpdatedAt: Instant = Instant.now(),
    /** User or system that created this task */
    val createdBy: String = "system",
)
