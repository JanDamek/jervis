package com.jervis.koog.tools.scheduler

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.scheduling.TaskManagementService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Scheduler tools for creating future tasks and reminders.
 * Used by KoogQualifierAgent to schedule meetings, deadlines, and follow-up reminders from emails.
 */
@LLMDescription("Schedule future tasks and reminders from emails, meetings, deadlines")
class SchedulerTools(
    private val task: TaskDocument,
    private val taskManagementService: TaskManagementService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    @Tool
    @LLMDescription(
        """Schedule a future system task, like a meeting reminder or deadline notification.
Use this when the input specifies a future event (e.g., 'Meeting tomorrow at 10am', 'Deadline in 3 days').
This is for future actions, not immediate processing.
Supports natural language times ('tomorrow at 10am', 'in 3 days') and exact datetimes ('2025-12-25 09:00').""",
    )
    suspend fun scheduleTask(
        @LLMDescription("Task content or command to execute (e.g., 'Remind about project sync meeting')")
        content: String,
        @LLMDescription("When to run: 'tomorrow at 10am', 'in 3 days', '2025-12-25 09:00', '2025-12-31T17:00:00Z'")
        scheduledDateTime: String,
        @LLMDescription("Human-friendly task name (e.g., 'Project Sync Reminder')")
        taskName: String,
        @LLMDescription("CRON expression for recurring tasks (optional)")
        cronExpression: String? = null,
    ): ScheduleResult {
        return try {
            val scheduledTime = parseScheduledTime(scheduledDateTime)

            // Validate that scheduled time is in the future
            if (scheduledTime.isBefore(Instant.now())) {
                return ScheduleResult(
                    success = false,
                    taskId = null,
                    taskName = taskName,
                    scheduledTime = scheduledTime.toString(),
                    error = "Cannot schedule task in the past. Scheduled time: $scheduledDateTime is before current time.",
                )
            }

            val scheduledTask =
                taskManagementService.scheduleTask(
                    clientId = task.clientId,
                    projectId = task.projectId,
                    content = content,
                    taskName = taskName,
                    scheduledAt = scheduledTime,
                    cronExpression = cronExpression,
                    correlationId = task.correlationId,
                )

            logger.info { "SCHEDULED_TASK: taskId=${scheduledTask.id}, name=$taskName, time=$scheduledDateTime" }

            ScheduleResult(
                success = true,
                taskId = scheduledTask.id.toString(),
                taskName = taskName,
                scheduledTime = scheduledTime.toString(),
                cronExpression = cronExpression,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to schedule task: $taskName" }
            ScheduleResult(
                success = false,
                taskId = null,
                taskName = taskName,
                scheduledTime = null,
                error = e.message ?: "Unknown error",
            )
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
                        "Invalid relative time format: '$scheduledDateTime'. Expected: 'in X minutes/hours/days/weeks'",
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
                        "Invalid time unit: '$unit'. Supported: minute(s), hour(s), day(s), week(s)",
                    )
                }
            }

            timeStr.contains(":") -> {
                if (timeStr.contains("t") && timeStr.contains("z")) {
                    try {
                        Instant.parse(timeStr.uppercase())
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException("Invalid ISO datetime format: '$scheduledDateTime'", e)
                    }
                } else {
                    try {
                        LocalDateTime
                            .parse(timeStr, dateTimeFormatter)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException(
                            "Invalid datetime format: '$scheduledDateTime'. Expected: yyyy-MM-dd HH:mm",
                            e,
                        )
                    }
                }
            }

            timeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                try {
                    LocalDateTime
                        .parse("$timeStr 09:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Invalid date format: '$scheduledDateTime'. Expected: yyyy-MM-dd",
                        e,
                    )
                }
            }

            else -> {
                throw IllegalArgumentException("Unsupported time format: '$scheduledDateTime'")
            }
        }
    }

    @Serializable
    data class ScheduleResult(
        val success: Boolean,
        val taskId: String?,
        val taskName: String,
        val scheduledTime: String?,
        val cronExpression: String? = null,
        val error: String? = null,
    )
}
