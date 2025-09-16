package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.scheduling.TaskManagementService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * MCP Tool for scheduling, managing, and canceling scheduled tasks.
 * Allows creating new scheduled tasks and canceling existing ones.
 */
@Service
class TaskSchedulerTool(
    private val taskManagementService: TaskManagementService,
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override val name: String = "task_scheduler"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.TASK_SCHEDULER)

    @Serializable
    data class ScheduleParams(
        val taskInstruction: String = "",
        val projectId: String? = null,
        val scheduledDateTime: String? = null,
        val taskName: String? = null,
        val priority: Int = 0,
        val maxRetries: Int = 3,
        val taskParameters: Map<String, String> = emptyMap(),
        val action: String = "schedule", // schedule, cancel
        val taskId: String? = null,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult =
        try {
            logger.info { "Executing scheduler management with description: $taskDescription" }

            val params = parseTaskDescription(taskDescription, context)
            logger.debug { "Parsed schedule parameters: $params" }

            when (params.action) {
                "cancel" -> handleCancelTask(params)
                "schedule" -> handleScheduleTask(params, context)
                else -> ToolResult.error("Unknown action: ${params.action}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error managing scheduled tasks" }
            ToolResult.error("Failed to manage scheduled tasks: ${e.message}")
        }

    private suspend fun handleCancelTask(params: ScheduleParams): ToolResult {
        val taskId = params.taskId ?: return ToolResult.error("Task ID is required for cancellation")

        return try {
            val objectId = ObjectId(taskId)
            val cancelled = taskManagementService.cancelTask(objectId)

            if (cancelled) {
                ToolResult.ok("Successfully cancelled task with ID: $taskId")
            } else {
                ToolResult.error("Failed to cancel task with ID: $taskId (task not found or not cancellable)")
            }
        } catch (e: IllegalArgumentException) {
            ToolResult.error("Invalid task ID format: $taskId")
        }
    }

    private suspend fun handleScheduleTask(
        params: ScheduleParams,
        context: TaskContext,
    ): ToolResult {
        try {
            val projectId =
                if (params.projectId != null) {
                    try {
                        ObjectId(params.projectId)
                    } catch (e: IllegalArgumentException) {
                        return ToolResult.error("Invalid project ID format: ${params.projectId}")
                    }
                } else {
                    context.projectDocument.id
                }

            val scheduledTime =
                parseScheduledTime(params.scheduledDateTime)
                    ?: return ToolResult.error("Invalid or missing scheduled time: ${params.scheduledDateTime}")

            val taskName = params.taskName ?: generateTaskName(params.taskInstruction, context.projectDocument.name)

            val scheduledTask =
                taskManagementService.scheduleTask(
                    projectId = projectId,
                    taskInstruction = params.taskInstruction,
                    taskName = taskName,
                    scheduledAt = scheduledTime,
                    taskParameters = params.taskParameters,
                    priority = params.priority,
                    maxRetries = params.maxRetries,
                    createdBy = "mcp-tool",
                )

            val output =
                buildString {
                    appendLine("=== TASK SCHEDULED SUCCESSFULLY ===")
                    appendLine()
                    appendLine("Task ID: ${scheduledTask.id}")
                    appendLine("Task Name: ${scheduledTask.taskName}")
                    appendLine("Task Instruction: ${scheduledTask.taskInstruction}")
                    appendLine("Project ID: ${scheduledTask.projectId}")
                    appendLine(
                        "Scheduled Time: ${
                            scheduledTask.scheduledAt.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        }",
                    )
                    appendLine("Priority: ${scheduledTask.priority}")
                    appendLine("Max Retries: ${scheduledTask.maxRetries}")

                    if (scheduledTask.taskParameters.isNotEmpty()) {
                        appendLine("Parameters:")
                        scheduledTask.taskParameters.forEach { (key: String, value: String) ->
                            appendLine("  $key: $value")
                        }
                    }

                    appendLine("Status: ${scheduledTask.status}")
                    appendLine(
                        "Created: ${
                            scheduledTask.createdAt.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        }",
                    )
                }

            return ToolResult.ok(output)
        } catch (e: Exception) {
            logger.error(e) { "Error scheduling task" }
            return ToolResult.error("Failed to schedule task: ${e.message}")
        }
    }

    private fun parseScheduledTime(scheduledDateTime: String?): Instant {
        if (scheduledDateTime.isNullOrBlank()) {
            throw IllegalArgumentException("Scheduled date time cannot be null or blank")
        }

        val timeStr = scheduledDateTime.lowercase().trim()

        return when {
            timeStr == "now" -> Instant.now()
            timeStr == "today" ->
                LocalDateTime
                    .now()
                    .withHour(9)
                    .withMinute(0)
                    .withSecond(0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()

            timeStr == "tomorrow" ->
                LocalDateTime
                    .now()
                    .plusDays(1)
                    .withHour(9)
                    .withMinute(0)
                    .withSecond(0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()

            timeStr.startsWith("in ") -> {
                val parts = timeStr.substringAfter("in ").split(" ")
                if (parts.size < 2) {
                    throw IllegalArgumentException(
                        "Invalid relative time format: '$scheduledDateTime'. Expected format: 'in X minutes/hours/days/weeks'",
                    )
                }

                val amount =
                    parts[0].toLongOrNull()
                        ?: throw IllegalArgumentException("Invalid amount in relative time: '${parts[0]}'")
                val unit = parts[1].lowercase()

                when {
                    unit.startsWith("minute") -> Instant.now().plusSeconds(amount * 60)
                    unit.startsWith("hour") -> Instant.now().plusSeconds(amount * 3600)
                    unit.startsWith("day") -> Instant.now().plusSeconds(amount * 86400)
                    unit.startsWith("week") -> Instant.now().plusSeconds(amount * 604800)
                    else -> throw IllegalArgumentException(
                        "Invalid time unit: '$unit'. Supported units: minute(s), hour(s), day(s), week(s)",
                    )
                }
            }

            timeStr.contains(":") -> {
                // Try full datetime format
                try {
                    LocalDateTime.parse(timeStr, dateTimeFormatter).atZone(ZoneId.systemDefault()).toInstant()
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException(
                        "Invalid datetime format: '$scheduledDateTime'. Expected format: yyyy-MM-dd HH:mm",
                        e,
                    )
                }
            }

            timeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                // Date only format - default to 9 AM
                try {
                    LocalDateTime
                        .parse("$timeStr 09:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Invalid date format: '$scheduledDateTime'. Expected format: yyyy-MM-dd",
                        e,
                    )
                }
            }

            else -> throw IllegalArgumentException(
                "Unsupported time format: '$scheduledDateTime'. Supported formats: 'now', 'today', 'tomorrow', 'in X time_unit', 'yyyy-MM-dd HH:mm', 'yyyy-MM-dd'",
            )
        }
    }

    private fun generateTaskName(
        taskInstruction: String,
        projectName: String,
    ): String {
        // Generate a concise task name from the instruction, limited to 100 characters
        val baseInstruction = taskInstruction.take(60).trim()
        return "$baseInstruction for $projectName".take(100)
    }

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): ScheduleParams =
        llmGateway.callLlm(
            type = PromptTypeEnum.TASK_SCHEDULER,
            userPrompt = taskDescription,
            quick = context.quick,
            ScheduleParams(),
        )
}
