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
    @Indexed
    val clientId: ObjectId,
    @Indexed
    val projectId: ObjectId?,
    val content: String,
    val taskName: String,
    @Indexed
    val scheduledAt: Instant,
    val cronExpression: String? = null,
    val correlationId: String? = null,
)
