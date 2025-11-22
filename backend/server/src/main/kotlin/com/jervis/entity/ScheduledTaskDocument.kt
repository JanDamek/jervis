package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Scheduled task dispatched to agent 10 minutes before scheduled time.
 *
 * LIFECYCLE:
 * - Non-cron: Dispatched as PendingTask, then DELETED
 * - Cron: Dispatched as PendingTask, scheduledAt updated to next occurrence, stays in DB
 *
 * NO STATUS - tasks are either waiting (in DB) or dispatched (deleted or rescheduled).
 */
@Document(collection = "scheduled_tasks")
data class ScheduledTaskDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    /** Client this task belongs to */
    @Indexed
    val clientId: ObjectId,
    /** Project this task belongs to */
    @Indexed
    val projectId: ObjectId?,
    /** Task content - what agent should do (full context for agent) */
    val content: String,
    /** Task name for display/logging purposes only */
    val taskName: String,
    /** When the task should be dispatched to agent (10min before this time) */
    @Indexed
    val scheduledAt: Instant,
    /** Recurring schedule expression (cron-like). If null, task is one-time. */
    val cronExpression: String? = null,
    /** CorrelationId linking to source (user task, agent decision, etc) */
    val correlationId: String? = null,
)
