package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
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
class ScheduleTaskTool(
    private val taskManagementService: TaskManagementService,
    override val promptRepository: PromptRepository,
) : McpTool<ScheduleTaskTool.ScheduleParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override val name = ToolTypeEnum.SYSTEM_SCHEDULE_TASK_TOOL
    override val descriptionObject =
        ScheduleParams(
            content = "Perform data backup",
            projectId = null,
            scheduledDateTime = "tomorrow",
            taskName = "Daily Data Backup",
            cronExpression = null,
            action = "schedule",
            taskId = null,
            correlationId = null,
        )

    @Serializable
    data class ScheduleParams(
        val content: String = "",
        val projectId: String? = null,
        val scheduledDateTime: String? = null,
        val taskName: String? = null,
        val cronExpression: String? = null,
        val action: String = "schedule", // schedule, cancel
        val taskId: String? = null,
        val correlationId: String? = null,
    )

    override suspend fun execute(
        plan: Plan,
        request: ScheduleParams,
    ): ToolResult =
        try {
            logger.info { "Executing scheduler management with description: $request" }

            when (request.action) {
                "cancel" -> handleCancelTask(request)
                "schedule" -> handleScheduleTask(request, plan)
                else -> ToolResult.error("Unknown action: ${request.action}")
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
        } catch (_: IllegalArgumentException) {
            ToolResult.error("Invalid task ID format: $taskId")
        }
    }

    private suspend fun handleScheduleTask(
        params: ScheduleParams,
        plan: Plan,
    ): ToolResult {
        try {
            val projectId =
                if (params.projectId != null) {
                    try {
                        ObjectId(params.projectId)
                    } catch (_: IllegalArgumentException) {
                        return ToolResult.error("Invalid project ID format: ${params.projectId}")
                    }
                } else {
                    plan.projectDocument?.id
                }

            val scheduledTime = parseScheduledTime(params.scheduledDateTime)
            val taskName = params.taskName ?: generateTaskName(params.content, plan.projectDocument?.name)

            val scheduledTask =
                taskManagementService.scheduleTask(
                    clientId = plan.clientDocument.id,
                    projectId = projectId,
                    content = params.content,
                    taskName = taskName,
                    scheduledAt = scheduledTime,
                    cronExpression = params.cronExpression,
                    correlationId = params.correlationId,
                )

            val output =
                buildString {
                    appendLine("=== TASK SCHEDULED SUCCESSFULLY ===")
                    appendLine()
                    appendLine("Task ID: ${scheduledTask.id}")
                    appendLine("Task Name: ${scheduledTask.taskName}")
                    appendLine("Content: ${scheduledTask.content}")
                    appendLine("Client ID: ${scheduledTask.clientId}")
                    appendLine("Project ID: ${scheduledTask.projectId ?: "N/A"}")
                    appendLine(
                        "Scheduled Time: ${
                            scheduledTask.scheduledAt.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        }",
                    )
                    if (scheduledTask.cronExpression != null) {
                        appendLine("Cron Expression: ${scheduledTask.cronExpression}")
                    }
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
            timeStr == "now" -> {
                Instant.now()
            }

            timeStr == "today" -> {
                LocalDateTime
                    .now()
                    .withHour(9)
                    .withMinute(0)
                    .withSecond(0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }

            timeStr == "tomorrow" -> {
                LocalDateTime
                    .now()
                    .plusDays(1)
                    .withHour(9)
                    .withMinute(0)
                    .withSecond(0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }

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
                // Try ISO 8601 format first
                if (timeStr.contains("t") && timeStr.contains("z")) {
                    try {
                        Instant.parse(timeStr.uppercase())
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException(
                            "Invalid ISO datetime format: '$scheduledDateTime'",
                            e,
                        )
                    }
                } else {
                    // Try a full datetime format
                    try {
                        LocalDateTime.parse(timeStr, dateTimeFormatter).atZone(ZoneId.systemDefault()).toInstant()
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException(
                            "Invalid datetime format: '$scheduledDateTime'. Expected format: yyyy-MM-dd HH:mm",
                            e,
                        )
                    }
                }
            }

            timeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                // Date-only format - default to 9 AM
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

            else -> {
                throw IllegalArgumentException(
                    "Unsupported time format: '$scheduledDateTime'. Supported formats: 'now', 'today', 'tomorrow', 'in X time_unit', 'yyyy-MM-dd HH:mm', 'yyyy-MM-dd'",
                )
            }
        }
    }

    private fun generateTaskName(
        content: String,
        projectName: String?,
    ): String {
        // Generate a concise task name from the content, limited to 100 characters
        val baseContent = content.take(60).trim()
        return if (projectName != null) {
            "$baseContent for $projectName".take(100)
        } else {
            baseContent.take(100)
        }
    }
}
