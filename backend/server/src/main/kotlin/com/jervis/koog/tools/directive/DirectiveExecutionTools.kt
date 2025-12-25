package com.jervis.koog.tools.directive

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant

/**
 * Directive execution tools - atomic operations for ASK, DEFER, FAIL actions.
 *
 * These tools provide explicit, synchronous operations that return structured results.
 * Each tool maps to one directive action and returns immediately with confirmation.
 *
 * @param task Current pending task being processed
 * @param taskManagementService Service for scheduling deferred tasks
 * @param userTaskService Service for creating user tasks
 */
@LLMDescription("Directive execution tools - ASK, DEFER, FAIL operations")
class DirectiveExecutionTools(
    private val task: TaskDocument,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    /**
     * Defer current task for future processing.
     *
     * Creates a ScheduledTask that will re-process this content at specified time.
     * PendingTask reference is maintained but not re-queued to avoid qualification loop.
     *
     * @param deferMinutes Minutes to defer (must be > 0)
     * @param reason Brief reason for deferral
     * @return Defer result with scheduledTaskId
     */
    @Tool
    @LLMDescription(
        """
Defer task for future processing. Returns immediately with scheduledTaskId.

Use this when:
- Waiting for external event (e.g., "waiting for deployment")
- Time-based processing needed (e.g., "check again in 2 hours")
- Resource not available yet (e.g., "API down, retry later")

IMPORTANT: deferMinutes must be positive integer.

Examples:
- deferPendingTask(120, "Waiting for production deployment")
- deferPendingTask(1440, "Check meeting notes tomorrow")
        """,
    )
    suspend fun deferPendingTask(
        @LLMDescription("Minutes to defer (must be > 0)")
        deferMinutes: Int,
        @LLMDescription("Reason for deferral")
        reason: String,
    ): DeferResult {
        logger.info {
            "⏰ DEFER_TASK | correlationId=${task.correlationId} | " +
                "deferMinutes=$deferMinutes | reason='$reason'"
        }

        if (deferMinutes <= 0) {
            return DeferResult(
                success = false,
                scheduledTaskId = null,
                deferMinutes = deferMinutes,
                nextRunAt = null,
                reason = reason,
                error = "deferMinutes must be positive (got $deferMinutes)",
            )
        }

        return try {
            val nextRunAt = Instant.now().plusSeconds(deferMinutes.toLong() * 60)

            val scheduledTask =
                taskManagementService.scheduleTask(
                    clientId = task.clientId,
                    projectId = task.projectId,
                    content = task.content,
                    taskName = "Deferred: ${task.correlationId}",
                    scheduledAt = nextRunAt,
                    cronExpression = null,
                    correlationId = task.correlationId,
                )

            logger.info {
                "✅ TASK_DEFERRED | correlationId=${task.correlationId} | " +
                    "scheduledTaskId=${scheduledTask.id} | nextRunAt=$nextRunAt"
            }

            DeferResult(
                success = true,
                scheduledTaskId = scheduledTask.id.toString(),
                deferMinutes = deferMinutes,
                nextRunAt = nextRunAt.toString(),
                reason = reason,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "❌ DEFER_FAILED | correlationId=${task.correlationId} | error=${e.message}"
            }
            DeferResult(
                success = false,
                scheduledTaskId = null,
                deferMinutes = deferMinutes,
                nextRunAt = null,
                reason = reason,
                error = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * Fail current task and escalate to user.
     *
     * Creates user task with error details for manual resolution.
     * Original task is marked as failed/errored.
     *
     * @param reason Brief reason for failure
     * @param detailsRef Optional URI reference to detailed error information
     * @return Fail result with userTaskId
     */
    @Tool
    @LLMDescription(
        """
Fail task and escalate to user. Returns immediately with userTaskId.

Use this when:
- Task cannot be processed due to errors
- Required information missing and cannot be obtained
- Business logic prevents processing (e.g., permissions, validation)
- Unrecoverable technical errors

IMPORTANT: Use detailsRef for URN/reference, not full error dumps.

Examples:
- failToUserTask("Missing required credentials", "urn:error:auth-missing")
- failToUserTask("Invalid JIRA key format", null)
        """,
    )
    suspend fun failToUserTask(
        @LLMDescription("Brief reason for failure")
        reason: String,
        @LLMDescription("URI reference to detailed error (optional)")
        detailsRef: String? = null,
    ): FailResult {
        logger.info {
            "❌ FAIL_TO_USER | correlationId=${task.correlationId} | " +
                "reason='$reason' | detailsRef=$detailsRef"
        }

        return try {
            val taskTitle = "Failed Task: ${task.correlationId}"
            val taskDescription =
                buildString {
                    append("Task failed to process.\n\n")
                    append("Reason: $reason\n")
                    if (detailsRef != null) {
                        append("Details: $detailsRef\n")
                    }
                    append("\nOriginal Correlation ID: ${task.correlationId}\n")
                    append("Client ID: ${task.clientId}\n")
                    if (task.projectId != null) {
                        append("Project ID: ${task.projectId}\n")
                    }
                }

            val createdTask =
                userTaskService.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    clientId = task.clientId,
                    projectId = task.projectId,
                    correlationId = task.correlationId,
                )

            logger.info {
                "✅ USER_TASK_CREATED | correlationId=${task.correlationId} | " +
                    "userTaskId=${createdTask.id} | reason='$reason'"
            }

            FailResult(
                success = true,
                userTaskId = createdTask.id.toString(),
                reason = reason,
                detailsRef = detailsRef,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "❌ FAIL_TO_USER_FAILED | correlationId=${task.correlationId} | error=${e.message}"
            }
            FailResult(
                success = false,
                userTaskId = null,
                reason = reason,
                detailsRef = detailsRef,
                error = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * Create dialog task to ask user questions.
     *
     * Creates user task with questions that require user response.
     * System will wait for user input before continuing processing.
     *
     * @param questions List of questions to ask (1-5 recommended)
     * @param context Brief context about why questions are needed
     * @return Ask result with dialogTaskId
     */
    @Tool
    @LLMDescription(
        """
Ask user questions via dialog task. Returns immediately with dialogTaskId.

Use this when:
- Required information missing from input
- Ambiguity needs clarification (e.g., multiple projects match)
- User decision needed (e.g., priority, approach)

IMPORTANT: Keep questions focused (1-5 questions). Provide context.

Examples:
- askUserQuestions(["Which project?", "High priority?"], "Multiple projects match")
        """,
    )
    suspend fun askUserQuestions(
        @LLMDescription("List of questions to ask user (1-5 recommended)")
        questions: List<String>,
        @LLMDescription("Brief context about why asking")
        context: String,
    ): AskResult {
        logger.info {
            "❓ ASK_USER | correlationId=${task.correlationId} | " +
                "questionCount=${questions.size} | context='$context'"
        }

        if (questions.isEmpty()) {
            return AskResult(
                success = false,
                dialogTaskId = null,
                questionCount = 0,
                context = context,
                error = "Questions list cannot be empty",
            )
        }

        if (questions.size > 10) {
            return AskResult(
                success = false,
                dialogTaskId = null,
                questionCount = questions.size,
                context = context,
                error = "Too many questions (${questions.size}). Maximum 10 allowed.",
            )
        }

        return try {
            val taskTitle = "Questions: ${task.correlationId}"
            val taskDescription =
                buildString {
                    append("Context: $context\n\n")
                    append("Questions:\n")
                    questions.forEachIndexed { index, question ->
                        append("${index + 1}. $question\n")
                    }
                    append("\nOriginal Correlation ID: ${task.correlationId}")
                }

            val createdTask =
                userTaskService.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    clientId = task.clientId,
                    projectId = task.projectId,
                    correlationId = task.correlationId,
                )

            logger.info {
                "✅ DIALOG_TASK_CREATED | correlationId=${task.correlationId} | " +
                    "dialogTaskId=${createdTask.id} | questionCount=${questions.size}"
            }

            AskResult(
                success = true,
                dialogTaskId = createdTask.id.toString(),
                questionCount = questions.size,
                context = context,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "❌ ASK_USER_FAILED | correlationId=${task.correlationId} | error=${e.message}"
            }
            AskResult(
                success = false,
                dialogTaskId = null,
                questionCount = questions.size,
                context = context,
                error = e.message ?: "Unknown error",
            )
        }
    }

    @Serializable
    data class DeferResult(
        val success: Boolean,
        val scheduledTaskId: String?,
        val deferMinutes: Int,
        val nextRunAt: String?,
        val reason: String,
        val error: String? = null,
    )

    @Serializable
    data class FailResult(
        val success: Boolean,
        val userTaskId: String?,
        val reason: String,
        val detailsRef: String?,
        val error: String? = null,
    )

    @Serializable
    data class AskResult(
        val success: Boolean,
        val dialogTaskId: String?,
        val questionCount: Int,
        val context: String,
        val error: String? = null,
    )
}
