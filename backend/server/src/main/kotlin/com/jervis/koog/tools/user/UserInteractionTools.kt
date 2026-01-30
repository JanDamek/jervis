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
    private val jiraService: com.jervis.service.jira.JiraService? = null,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        """Request approval for cloud model spend. 
        Use this when ProjectCostPolicy requires approval for cloud models.""",
    )
    suspend fun requestCloudSpendApproval(
        @LLMDescription("Estimated cost of the operation in USD")
        estimatedCost: Double,
        @LLMDescription("Model being requested (e.g., 'claude-3-5-sonnet')")
        modelId: String,
        @LLMDescription("Why the cloud model is needed instead of local Qwen3")
        reason: String,
    ): UserTaskResult {
        val title = "Cloud Spend Approval: $modelId"
        val description = """
            Agent is requesting approval to use a cloud model.
            
            Model: $modelId
            Estimated Cost: $${String.format("%.4f", estimatedCost)}
            Reason: $reason
            
            By approving this task, you allow the agent to proceed with this model.
        """.trimIndent()

        return createUserTask(
            title = title,
            description = description,
            priority = "HIGH"
        )
    }

    @Tool
    @LLMDescription(
        """Ask a question or request information/decision from the user.
        This will pause the current agent execution and wait for user input.
        The current TaskDocument will be transitioned to USER_TASK state.""",
    )
    suspend fun askUser(
        @LLMDescription("Specific question or request for information/decision")
        question: String,
        @LLMDescription("Optional context why this information is needed")
        reason: String? = null,
    ): UserTaskResult {
        logger.info { "AGENT_ASK_USER: question=$question" }
        return UserTaskResult(
            success = true,
            taskId = task.id.toString(),
            title = "Waiting for user input",
        )
    }

    @Tool
    @LLMDescription(
        """Create a task for a human user to complete (non-blocking).
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
            // Validate that due date is in the future if provided
            val dueDateParsed =
                dueDate?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                }

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

            // Místo vytváření nového tasku převedeme aktuální task na USER_TASK.
            // Invariant: jeden objekt pravdy (TaskDocument).
            userTaskService.failAndEscalateToUserTask(task, "User action required: $title")

            UserTaskResult(
                success = true,
                taskId = task.id.toString(),
                title = title,
                dueDate = dueDateParsed?.toString(),
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to transition to user task: $title" }
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
