package com.jervis.koog.tools.user

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant

/**
 * User interaction tools for creating tasks that require human action or decision.
 * Used by LIFT_UP agent (KoogWorkflowAgent) to delegate work to users.
 */
@LLMDescription("Create user tasks and ask questions that require human action or decision")
class UserInteractionTools(
    private val task: TaskDocument,
    private val userTaskService: UserTaskService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Create a task for a human user to complete.
Use when a decision or action is required from a person (e.g., 'review this document', 'approve this request', 'make a decision').
This creates a task in the user's task list that they can see and complete in the UI.""",
    )
    suspend fun createUserTask(
        @LLMDescription("Short, actionable title for the user (e.g., 'Review PR #123')")
        title: String,
        @LLMDescription("Detailed instructions or context for the user")
        description: String? = null,
        @LLMDescription("Priority: LOW, MEDIUM, HIGH, URGENT")
        priority: String? = null,
        @LLMDescription("Due date in ISO-8601 format: 2025-12-31T17:00:00Z (optional)")
        dueDate: String? = null,
    ): UserTaskResult {
        return try {
            val priorityEnum =
                priority?.let {
                    runCatching { TaskPriorityEnum.valueOf(it.uppercase()) }.getOrNull()
                } ?: TaskPriorityEnum.MEDIUM

            val dueDateParsed =
                dueDate?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                }

            // Validate that due date is in the future if provided
            if (dueDateParsed != null && dueDateParsed.isBefore(Instant.now())) {
                return UserTaskResult(
                    success = false,
                    taskId = null,
                    title = title,
                    error = "Cannot create user task with due date in the past. Due date: $dueDate is before current time.",
                )
            }

            val combinedDescription =
                buildString {
                    appendLine("Action: $title")
                    description?.takeIf { it.isNotBlank() }?.let {
                        appendLine()
                        appendLine(it)
                    }
                }

            val createdTask =
                userTaskService.createTask(
                    title = title,
                    description = combinedDescription,
                    projectId = task.projectId,
                    clientId = task.clientId,
                    correlationId = task.correlationId,
                )

            logger.info { "USER_TASK_CREATED: taskId=${createdTask.id}, title=$title, priority=$priorityEnum" }

            UserTaskResult(
                success = true,
                taskId = createdTask.id.toString(),
                title = title,
                priority = priorityEnum.name,
                dueDate = dueDateParsed?.toString(),
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create user task: $title" }
            UserTaskResult(
                success = false,
                taskId = null,
                title = title,
                error = e.message ?: "Unknown error",
            )
        }
    }

    @Serializable
    data class UserTaskResult(
        val success: Boolean,
        val taskId: String?,
        val title: String,
        val priority: String? = null,
        val dueDate: String? = null,
        val error: String? = null,
    )
}
