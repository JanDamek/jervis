package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a task for the user to handle (distinct from background PendingTask).
 * These tasks are presented to the user when they ask "what do I need to do today?"
 */
data class UserTask(
    val id: ObjectId = ObjectId(),
    val title: String,
    val description: String? = null,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val status: TaskStatus = TaskStatus.TODO,
    val dueDate: Instant? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val sourceType: TaskSourceType,
    val sourceUri: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
)

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
}

enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
}
