package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Service
class TaskQueryUserTasksTool(
    private val userTaskService: UserTaskService,
    override val promptRepository: PromptRepository,
) : McpTool<TaskQueryUserTasksTool.QueryUserTasksRequest> {
    override val name = ToolTypeEnum.TASK_QUERY_USER_TASKS_TOOL

    override val descriptionObject =
        QueryUserTasksRequest(
            scope = "active", // active | today | range
            daysAhead = 7,
            startDate = null,
            endDate = null,
        )

    override suspend fun execute(
        plan: Plan,
        request: QueryUserTasksRequest,
    ): ToolResult {
        return try {
            logger.info { "Querying user tasks: $request" }

            val tasks =
                when (request.scope.lowercase()) {
                    "today" -> {
                        userTaskService.findTasksForToday(plan.clientId).toList()
                    }

                    "active" -> {
                        userTaskService.findActiveTasksByClient(plan.clientId).toList()
                    }

                    "range" -> {
                        val start = request.startDate?.let { Instant.parse(it) } ?: Instant.now()
                        val end =
                            request.endDate?.let { Instant.parse(it) }
                                ?: start.plus(request.daysAhead?.toLong() ?: 7, ChronoUnit.DAYS)
                        userTaskService.findTasksByDateRange(plan.clientId, start, end).toList()
                    }

                    else -> {
                        userTaskService.findActiveTasksByClient(plan.clientId).toList()
                    }
                }

            if (tasks.isEmpty()) {
                return ToolResult.success(
                    toolName = name.name,
                    summary = "No tasks found for scope: ${request.scope}",
                    content = "No tasks match the query criteria.",
                )
            }

            // Group by date
            val tasksByDate =
                tasks.groupBy { task ->
                    task.dueDate?.let {
                        LocalDate.ofInstant(it, ZoneId.systemDefault())
                    } ?: LocalDate.MAX
                }

            val content =
                buildString {
                    appendLine("FOUND ${tasks.size} TASK(S):\n")

                    tasksByDate.entries.sortedBy { it.key }.forEach { (date, dateTasks) ->
                        val today = LocalDate.now()
                        val dateLabel =
                            when {
                                date.isEqual(LocalDate.MAX) -> "NO DUE DATE"
                                date.isEqual(today) -> "TODAY"
                                date.isEqual(today.plusDays(1)) -> "TOMORROW"
                                date.isBefore(today) -> "OVERDUE - $date"
                                else -> date.toString()
                            }

                        appendLine("=== $dateLabel ===")

                        dateTasks.sortedBy { it.priority.ordinal }.forEach { task ->
                            appendLine()
                            appendLine("• [${task.priority}] ${task.title}")
                            task.description?.let { appendLine("  $it") }
                            appendLine("  Status: ${task.status}")
                            appendLine("  Correlation ID: ${task.correlationId}")
                        }
                        appendLine()
                    }
                }

            ToolResult.success(
                toolName = name.name,
                summary = "Found ${tasks.size} task(s) for scope: ${request.scope}",
                content = content,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to query user tasks" }
            ToolResult.error(
                output = "Failed to query user tasks: ${e.message}",
                message = e.message,
            )
        }
    }

    @Serializable
    data class QueryUserTasksRequest(
        val scope: String = "active",
        val daysAhead: Int? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )
}
