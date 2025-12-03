package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.service.dialog.UserDialogCoordinator
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Task management and user communication tools.
 * Native Koog implementation - no MCP dependencies.
 */
@LLMDescription("Task management: schedule tasks, create user tasks, interactive dialogs")
class TaskTools(
    private val plan: Plan,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val userDialogCoordinator: UserDialogCoordinator,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    @Tool
    @LLMDescription("""Schedule or cancel a system task.
Supports natural language times: 'now', 'today', 'tomorrow', 'in 2 hours'
Also supports exact datetime: 'yyyy-MM-dd HH:mm' or ISO-8601
Can set CRON expression for recurring tasks.""")
    fun scheduleTask(
        @LLMDescription("Task content or command to execute")
        content: String,

        @LLMDescription("When to run: 'tomorrow', 'in 3 days', 'yyyy-MM-dd HH:mm'")
        scheduledDateTime: String? = null,

        @LLMDescription("Human-friendly task name (optional)")
        taskName: String? = null,

        @LLMDescription("CRON expression for recurring tasks (optional)")
        cronExpression: String? = null,

        @LLMDescription("Action: 'schedule' or 'cancel'")
        action: String = "schedule",

        @LLMDescription("Task ID for cancellation (required if action=cancel)")
        taskId: String? = null,
    ): String = runBlocking {
        when (action) {
            "cancel" -> handleCancelTask(taskId)
            "schedule" -> handleScheduleTask(content, scheduledDateTime, taskName, cronExpression)
            else -> throw IllegalArgumentException("Unknown action: $action")
        }
    }

    private suspend fun handleCancelTask(taskId: String?): String {
        if (taskId == null) {
            throw IllegalArgumentException("Task ID is required for cancellation")
        }

        val objectId = try {
            ObjectId(taskId)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid task ID format: $taskId")
        }

        val cancelled = taskManagementService.cancelTask(objectId)
        return if (cancelled) {
            "Successfully cancelled task with ID: $taskId"
        } else {
            throw IllegalStateException("Failed to cancel task with ID: $taskId (task not found or not cancellable)")
        }
    }

    private suspend fun handleScheduleTask(
        content: String,
        scheduledDateTime: String?,
        taskName: String?,
        cronExpression: String?,
    ): String {
        val projectId = plan.projectDocument?.id
        val scheduledTime = parseScheduledTime(scheduledDateTime)
        val effectiveTaskName = taskName ?: generateTaskName(content, plan.projectDocument?.name)

        val scheduledTask = taskManagementService.scheduleTask(
            clientId = plan.clientDocument.id,
            projectId = projectId,
            content = content,
            taskName = effectiveTaskName,
            scheduledAt = scheduledTime,
            cronExpression = cronExpression,
            correlationId = plan.correlationId,
        )

        return buildString {
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
    }

    private fun parseScheduledTime(scheduledDateTime: String?): Instant {
        if (scheduledDateTime.isNullOrBlank()) {
            throw IllegalArgumentException("Scheduled date time cannot be null or blank")
        }

        val timeStr = scheduledDateTime.lowercase().trim()

        return when {
            timeStr == "now" -> Instant.now()

            timeStr == "today" -> {
                LocalDateTime.now()
                    .withHour(9)
                    .withMinute(0)
                    .withSecond(0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }

            timeStr == "tomorrow" -> {
                LocalDateTime.now()
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

                val amount = parts[0].toLongOrNull()
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
                if (timeStr.contains("t") && timeStr.contains("z")) {
                    try {
                        Instant.parse(timeStr.uppercase())
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException("Invalid ISO datetime format: '$scheduledDateTime'", e)
                    }
                } else {
                    try {
                        LocalDateTime.parse(timeStr, dateTimeFormatter)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException(
                            "Invalid datetime format: '$scheduledDateTime'. Expected format: yyyy-MM-dd HH:mm",
                            e,
                        )
                    }
                }
            }

            timeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                try {
                    LocalDateTime.parse("$timeStr 09:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid date format: '$scheduledDateTime'. Expected format: yyyy-MM-dd", e)
                }
            }

            else -> throw IllegalArgumentException(
                "Unsupported time format: '$scheduledDateTime'. Supported formats: 'now', 'today', 'tomorrow', 'in X time_unit', 'yyyy-MM-dd HH:mm', 'yyyy-MM-dd'",
            )
        }
    }

    private fun generateTaskName(content: String, projectName: String?): String {
        val baseContent = content.take(60).trim()
        return if (projectName != null) {
            "$baseContent for $projectName".take(100)
        } else {
            baseContent.take(100)
        }
    }

    @Tool
    @LLMDescription("""Create a user-facing task requiring user action.
Use when user must: reply email, review document, make decision, attend meeting.
For EMAIL sourceType, provide sourceUri as 'email://accountId/messageId' and threadId in metadata.""")
    fun createUserTask(
        @LLMDescription("Short actionable title")
        title: String,

        @LLMDescription("Detailed instructions or context")
        description: String? = null,

        @LLMDescription("Priority: LOW, MEDIUM, HIGH, URGENT")
        priority: String? = null,

        @LLMDescription("Due date in ISO-8601: 2025-12-31T17:00:00Z")
        dueDate: String? = null,

        @LLMDescription("Source type: EMAIL, AGENT_SUGGESTION, MANUAL, MEETING")
        sourceType: String = "AGENT_SUGGESTION",

        @LLMDescription("Source URI, e.g., email://accountId/messageId")
        sourceUri: String? = null,

        @LLMDescription("Additional metadata as key=value lines")
        metadata: String? = null,
    ): String = runBlocking {
        val priorityEnum = priority?.let {
            runCatching { TaskPriorityEnum.valueOf(it.uppercase()) }.getOrNull()
        } ?: TaskPriorityEnum.MEDIUM

        val sourceTypeEnum = runCatching { TaskSourceType.valueOf(sourceType.uppercase()) }
            .getOrElse { TaskSourceType.AGENT_SUGGESTION }

        val dueDateParsed = dueDate?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }

        val enrichedMetadata = metadata
            ?.lines()
            ?.mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            ?.toMap()
            ?.toMutableMap()
            ?: mutableMapOf()

        if (sourceTypeEnum == TaskSourceType.EMAIL) {
            val uri = sourceUri
            require(!uri.isNullOrBlank()) { "For sourceType=EMAIL, sourceUri must be provided as email://accountId/messageId" }
            require(uri.startsWith("email://")) { "For sourceType=EMAIL, sourceUri must start with 'email://'" }
            val parts = uri.removePrefix("email://").split("/")
            require(parts.size >= 2) { "Invalid email sourceUri. Expected format: email://accountId/messageId" }
            val accountIdHex = parts[0]
            val messageId = parts[1]
            enrichedMetadata.putIfAbsent("emailAccountId", accountIdHex)
            enrichedMetadata.putIfAbsent("emailMessageId", messageId)
            require(enrichedMetadata.containsKey("threadId")) {
                "metadata.threadId is required for EMAIL tasks so the system can auto-resolve when the conversation is answered"
            }
        }

        val combinedDescription = buildString {
            appendLine("Action: $title")
            description?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("Source: $sourceTypeEnum")
            sourceUri?.let { appendLine("Source URI: $it") }

            if (enrichedMetadata.isNotEmpty()) {
                appendLine()
                appendLine("Metadata:")
                for (entry in enrichedMetadata.entries.sortedBy { it.key }) {
                    appendLine("- ${entry.key}=${entry.value}")
                }
            }
        }

        var effectiveProjectId: ObjectId? = plan.projectId
        if (sourceTypeEnum == TaskSourceType.EMAIL) {
            val projectScopedFlag = enrichedMetadata["projectScoped"] ?: enrichedMetadata["email.projectScoped"] ?: "false"
            val projectIdHex = enrichedMetadata["projectId"] ?: enrichedMetadata["emailProjectId"] ?: enrichedMetadata["email.projectId"]

            if (projectScopedFlag.equals("true", ignoreCase = true) && !projectIdHex.isNullOrBlank()) {
                try {
                    effectiveProjectId = ObjectId(projectIdHex)
                } catch (e: Exception) {
                    logger.warn(e) { "Invalid projectId in metadata: $projectIdHex - falling back to plan.projectId" }
                }
            }
        }

        val task = userTaskService.createTask(
            title = title,
            description = combinedDescription,
            priority = priorityEnum,
            dueDate = dueDateParsed,
            projectId = effectiveProjectId,
            clientId = plan.clientId,
            sourceType = sourceTypeEnum,
            correlationId = sourceUri ?: plan.correlationId,
        )

        buildString {
            appendLine("TASK_ID: ${task.id.toHexString()}")
            appendLine("TITLE: ${task.title}")
            task.description?.let { appendLine("DESCRIPTION: $it") }
            appendLine("PRIORITY: ${task.priority}")
            task.dueDate?.let { appendLine("DUE: $it") }
            appendLine("SOURCE: ${task.sourceType}")
            appendLine("CORRELATION_ID: ${task.correlationId}")
        }
    }

    @Tool
    @LLMDescription("""Ask user a question and wait for interactive input via WebSocket.
Not available in background mode - use createUserTask instead.
Emits ASK payload to UI, waits for response from any connected device.""")
    fun userDialog(
        @LLMDescription("Question to ask the user")
        question: String,

        @LLMDescription("Proposed default answer")
        proposedAnswer: String,
    ): String = runBlocking {
        if (plan.backgroundMode) {
            throw IllegalStateException(
                "USER_DIALOG_NOT_ALLOWED_IN_BACKGROUND: Use createUserTask with correlationId=${plan.correlationId} " +
                    "to collect user input asynchronously.",
            )
        }

        logger.info { "USER_DIALOG: Requesting interactive input via WebSocket (correlationId=${plan.correlationId})" }

        val result = userDialogCoordinator.requestDialog(
            clientId = plan.clientDocument.id,
            projectId = plan.projectDocument?.id,
            correlationId = plan.correlationId,
            question = question,
            proposedAnswer = proposedAnswer,
        )

        if (result.accepted && !result.answer.isNullOrBlank()) {
            buildString {
                appendLine("Answer:")
                appendLine(result.answer)
            }
        } else {
            throw IllegalStateException("User canceled the dialog or no answer provided")
        }
    }
}
