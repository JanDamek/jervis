package com.jervis.domain.task

import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.TaskStatusEnum
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
    val priority: TaskPriorityEnum = TaskPriorityEnum.MEDIUM,
    val status: TaskStatusEnum = TaskStatusEnum.TODO,
    val dueDate: Instant? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val sourceType: TaskSourceType = TaskSourceType.AGENT_SUGGESTION,
    val sourceUri: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val correlationId: String? = null,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
)
