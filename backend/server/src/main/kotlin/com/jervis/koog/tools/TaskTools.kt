package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.task.TaskRoutingDecision
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.service.background.PendingTaskService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.TaskPriorityEnum
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.UserTaskService
import com.jervis.types.SourceUrn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Unified task management tools for routing, scheduling, and user interaction.
 */
@LLMDescription("A unified toolset for all task-related operations: routing, scheduling, delegation, and user interaction.")
class TaskTools(
    private val task: PendingTaskDocument,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val pendingTaskService: PendingTaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val coroutineScope: CoroutineScope,
    private val onRoutingCaptured: ((String) -> Unit)? = null,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    @Tool
    @LLMDescription(
        """
        Finalizes the qualification process by routing the task. This should be one of the last calls.

        **Routing Options:**
        - **`DONE`**: Use when all information has been indexed, no further actions are needed, and the task is complete.
        - **`LIFT_UP`**: Use when the task requires complex analysis, coding, or a user response that the main GPU agent should handle.
        """,
    )
    suspend fun routeTask(
        @LLMDescription("The final routing decision for the task: `DONE` or `LIFT_UP`.")
        routing: String,
    ): String {
        val routingUpper = routing.uppercase().trim()

        if (routingUpper !in setOf("DONE", "LIFT_UP")) {
            throw IllegalArgumentException("Invalid routing: $routing. Use DONE or LIFT_UP")
        }

        logger.info { "TASK_ROUTING: decision=$routingUpper for task ${task.id}" }

        // Capture routing if callback provided (for Phase 1)
        onRoutingCaptured?.invoke(routingUpper)

        val decision =
            when (routingUpper) {
                "DONE" -> TaskRoutingDecision.DONE
                "LIFT_UP" -> TaskRoutingDecision.READY_FOR_GPU
                else -> throw IllegalArgumentException("Invalid routing: $routing")
            }

        if (decision == TaskRoutingDecision.READY_FOR_GPU) {
            pendingTaskService.updateState(
                task.id,
                PendingTaskStateEnum.QUALIFYING,
                PendingTaskStateEnum.DISPATCHED_GPU,
            )
        } else {
            pendingTaskService.deleteTask(task.id)
        }

        return "✓ Task routing decision made: $routingUpper. The task is now finalized."
    }

    @Tool
    @LLMDescription(
        """
        **HIGHLY RESTRICTED AND DANGEROUS TOOL.**
        Asynchronously downloads content from a URL and creates a new task for its analysis.

        **MANDATORY SAFETY RULES:**
        1.  **ONLY USE FOR SAFE, CONTENT-RICH LINKS:** Use ONLY for links that point to static content like documentation, articles, knowledge bases, or code repositories.
        2.  **NEVER USE FOR ACTION LINKS:** NEVER call on links that perform actions (e.g., 'unsubscribe', 'confirm participation', 'delete account', 'login'). Clicking these can cause irreversible real-world effects.
        3.  **NEVER USE FOR TRACKING/MONITORING LINKS:** Avoid all links that look like tracking pixels or monitoring endpoints.
        4.  **WHEN IN DOUBT, DO NOT USE:** If you are not 100% certain the link is safe and contains valuable content for indexing, DO NOT use this tool. Instead, add the link to the knowledge graph using `storeKnowledge` with a `-[REFERENCES_URL]->` relationship.

        This tool starts the download in the background and immediately returns a confirmation. The model does not wait for the download to complete.
        """,
    )
    fun delegateLinkProcessing(
        @LLMDescription("A safe URL pointing to static, content-rich text to be analyzed and indexed.")
        url: String,
    ): String {
        val lowerUrl = url.lowercase()
        val forbiddenKeywords =
            listOf("unsubscribe", "confirm", "accept", "decline", "login", "logout", "delete", "remove")
        if (forbiddenKeywords.any { lowerUrl.contains(it) }) {
            val errorMsg =
                "SAFETY VIOLATION: URL contains a forbidden keyword. This tool cannot be used for action links."
            logger.warn { errorMsg }
            return errorMsg
        }

        coroutineScope.launch {
            try {
                // Check if link is already indexed for this client
                if (indexedLinkService.isLinkIndexed(url, task.clientId)) {
                    val existingLink = indexedLinkService.getIndexedLink(url, task.clientId)
                    logger.info { "Link $url already indexed at ${existingLink?.lastIndexedAt}, skipping download." }
                    return@launch
                }

                // Detect if this is a known service (Confluence, Jira, GitHub)
                val knownService = indexedLinkService.detectKnownService(url)

                val content = linkContentService.fetchPlainText(url)
                if (content.success) {
                    val correlationId = "link:${ObjectId()}"

                    // Enrich content with known service metadata
                    val enrichedContent = if (knownService != null) {
                        buildString {
                            appendLine("--- KNOWN SERVICE DETECTED ---")
                            appendLine("Service Type: ${knownService.serviceType}")
                            appendLine("Identifier: ${knownService.identifier}")
                            appendLine("Domain: ${knownService.domain}")
                            appendLine("URL: $url")
                            appendLine("--- ORIGINAL CONTENT ---")
                            appendLine()
                            append(content.plainText)
                        }
                    } else {
                        content.plainText
                    }

                    val newTask =
                        pendingTaskService.createTask(
                            taskType = PendingTaskTypeEnum.LINK_PROCESSING,
                            content = enrichedContent,
                            clientId = task.clientId,
                            projectId = task.projectId,
                            sourceUrn = SourceUrn.link(url),
                            correlationId = correlationId,
                        )

                    // Record link as indexed
                    indexedLinkService.recordIndexedLink(
                        url = url,
                        clientId = task.clientId,
                        correlationId = correlationId,
                        pendingTaskId = newTask.id,
                    )

                    logger.info {
                        if (knownService != null) {
                            "Successfully created new task from downloaded link (${knownService.serviceType}): $url"
                        } else {
                            "Successfully created new task from downloaded link: $url"
                        }
                    }
                } else {
                    logger.warn { "Downloaded content from $url is blank, skipping task creation." }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to download or process link $url" }
            }
        }

        return "✓ Link processing has been delegated to a background task for URL: $url. The download has started."
    }

    @Tool
    @LLMDescription(
        """Schedules a future system task, like a meeting reminder.
Use this when the input specifies a future event (e.g., 'Meeting tomorrow at 10am').
This is for future actions, not for immediate processing.
Supports natural language times ('tomorrow at 10am') and exact datetimes.""",
    )
    suspend fun scheduleTask(
        @LLMDescription("Task content or command to execute (e.g., 'Remind about project sync').")
        content: String,
        @LLMDescription("When to run: 'tomorrow at 10am', 'in 3 days', '2025-12-25 09:00'.")
        scheduledDateTime: String,
        @LLMDescription("Human-friendly task name (e.g., 'Project Sync Reminder').")
        taskName: String,
        @LLMDescription("CRON expression for recurring tasks (optional).")
        cronExpression: String? = null,
    ): String {
        val projectId = task.projectId
        val scheduledTime = parseScheduledTime(scheduledDateTime)

        // Validate that scheduled time is in the future
        if (scheduledTime.isBefore(Instant.now())) {
            return "✗ Cannot schedule task in the past. Scheduled time: $scheduledDateTime (${scheduledTime}) is before current time. Please schedule only future tasks."
        }

        val scheduledTask =
            taskManagementService.scheduleTask(
                clientId = task.clientId,
                projectId = projectId,
                content = content,
                taskName = taskName,
                scheduledAt = scheduledTime,
                cronExpression = cronExpression,
                correlationId = task.correlationId,
            )

        return "✓ Future task scheduled successfully. ID: ${scheduledTask.id}, Name: $taskName, Time: $scheduledDateTime"
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
                if (parts.size <
                    2
                ) {
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
                if (timeStr.contains("t") && timeStr.contains("z")) {
                    try {
                        Instant.parse(timeStr.uppercase())
                    } catch (e: DateTimeParseException) {
                        throw IllegalArgumentException("Invalid ISO datetime format: '$scheduledDateTime'", e)
                    }
                } else {
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
                throw IllegalArgumentException("Unsupported time format: '$scheduledDateTime'.")
            }
        }
    }

    @Tool
    @LLMDescription(
        """Creates a task for a human user to complete.
Use when a decision or action is required from a person (e.g., 'review this document', 'approve this request').""",
    )
    suspend fun createUserTask(
        @LLMDescription("Short, actionable title for the user.")
        title: String,
        @LLMDescription("Detailed instructions or context for the user.")
        description: String? = null,
        @LLMDescription("Priority: LOW, MEDIUM, HIGH, URGENT.")
        priority: String? = null,
        @LLMDescription("Due date in ISO-8601 format: 2025-12-31T17:00:00Z.")
        dueDate: String? = null,
    ): String {
        val priorityEnum =
            priority?.let { runCatching { TaskPriorityEnum.valueOf(it.uppercase()) }.getOrNull() }
                ?: TaskPriorityEnum.MEDIUM
        val dueDateParsed = dueDate?.let { runCatching { Instant.parse(it) }.getOrNull() }

        // Validate that due date is in the future if provided
        if (dueDateParsed != null && dueDateParsed.isBefore(Instant.now())) {
            return "✗ Cannot create user task with due date in the past. Due date: $dueDate (${dueDateParsed}) is before current time. Tasks should have future due dates or no due date."
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
                priority = priorityEnum,
                dueDate = dueDateParsed,
                projectId = task.projectId,
                clientId = task.clientId,
                sourceType = TaskSourceType.AGENT_SUGGESTION,
                correlationId = task.correlationId,
            )

        return "✓ User task created successfully. ID: ${createdTask.id}, Title: $title"
    }

}
