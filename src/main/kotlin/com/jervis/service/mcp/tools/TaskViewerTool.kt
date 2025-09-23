package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.entity.mongo.ScheduledTaskStatus
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.scheduling.TaskQueryService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter

/**
 * MCP Tool for viewing and browsing scheduled tasks.
 * Allows filtering by project, status, task type, and date ranges.
 */
@Service
class TaskViewerTool(
    private val taskQueryService: TaskQueryService,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override val name: PromptTypeEnum = PromptTypeEnum.TASK_VIEWER

    @Serializable
    data class BrowseParams(
        val status: String? = null,
        val projectId: String? = null,
        val taskType: String? = null,
        val limit: Int = 50,
        val showStatistics: Boolean = false,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        return try {
            logger.info { "Executing scheduler browsing with description: $taskDescription" }

            val params = parseTaskDescription(taskDescription, context, stepContext)
            logger.debug { "Parsed browse parameters: $params" }

            if (params.showStatistics) {
                return showTaskStatistics()
            }

            val tasks =
                when {
                    params.status != null && params.projectId != null -> {
                        val status = ScheduledTaskStatus.valueOf(params.status.uppercase())
                        val projectId = org.bson.types.ObjectId(params.projectId)
                        taskQueryService.getTasksForProject(projectId).filter { it.status == status }
                    }

                    params.status != null -> {
                        val status = ScheduledTaskStatus.valueOf(params.status.uppercase())
                        taskQueryService.getTasksByStatus(status)
                    }

                    params.projectId != null -> {
                        val projectId = org.bson.types.ObjectId(params.projectId)
                        taskQueryService.getTasksForProject(projectId)
                    }

                    else -> {
                        // Get all pending and running tasks by default
                        val pending = taskQueryService.getTasksByStatus(ScheduledTaskStatus.PENDING)
                        val running = taskQueryService.getTasksByStatus(ScheduledTaskStatus.RUNNING)
                        pending + running
                    }
                }

            val filteredTasks =
                tasks
                    .sortedWith(
                        compareBy<com.jervis.entity.mongo.ScheduledTaskDocument> { it.status }
                            .thenBy { it.scheduledAt },
                    ).take(params.limit)

            val output = buildTaskListOutput(filteredTasks, params)
            ToolResult.listingResult(
                toolName = "TASK_VIEWER",
                itemCount = filteredTasks.size,
                itemType = "tasks",
                listing = output
            )
        } catch (e: Exception) {
            logger.error(e) { "Error browsing scheduled tasks" }
            ToolResult.error("Failed to browse scheduled tasks: ${e.message}")
        }
    }

    private suspend fun showTaskStatistics(): ToolResult =
        try {
            val statistics = taskQueryService.getTaskStatistics()

            val output =
                buildString {
                    appendLine("=== SCHEDULED TASKS STATISTICS ===")
                    appendLine()
                    appendLine("Task Status Distribution:")
                    statistics.forEach { (status: String, count: Long) ->
                        appendLine("  ${status.uppercase()}: $count")
                    }
                    appendLine()
                    appendLine("Total Tasks: ${statistics.values.sum()}")
                }

            ToolResult.success(
                toolName = "TASK_VIEWER",
                summary = "Task statistics retrieved",
                content = output
            )
        } catch (e: Exception) {
            logger.error(e) { "Error getting task statistics" }
            ToolResult.error("Failed to get task statistics: ${e.message}")
        }

    private fun buildTaskListOutput(
        tasks: List<com.jervis.entity.mongo.ScheduledTaskDocument>,
        params: BrowseParams,
    ): String {
        return buildString {
            appendLine("=== SCHEDULED TASKS ===")
            appendLine()

            if (tasks.isEmpty()) {
                appendLine("No tasks found matching the specified criteria.")
                return@buildString
            }

            appendLine("Found ${tasks.size} task(s):")
            appendLine()

            tasks.forEachIndexed { index, task ->
                appendLine("${index + 1}. Task: ${task.taskName}")
                appendLine("   ID: ${task.id}")
                appendLine("   Project: ${task.projectId}")
                appendLine("   Instruction: ${task.taskInstruction}")
                appendLine("   Status: ${task.status}")
                appendLine(
                    "   Scheduled: ${
                        task.scheduledAt.atZone(java.time.ZoneId.systemDefault()).format(dateFormatter)
                    }",
                )

                task.startedAt?.let {
                    appendLine("   Started: ${it.atZone(java.time.ZoneId.systemDefault()).format(dateFormatter)}")
                }

                task.completedAt?.let {
                    appendLine("   Completed: ${it.atZone(java.time.ZoneId.systemDefault()).format(dateFormatter)}")
                }

                if (task.retryCount > 0) {
                    appendLine("   Retries: ${task.retryCount}/${task.maxRetries}")
                }

                task.errorMessage?.let {
                    appendLine("   Error: $it")
                }

                if (task.taskParameters.isNotEmpty()) {
                    appendLine("   Parameters:")
                    task.taskParameters.forEach { (key, value) ->
                        appendLine("     $key: $value")
                    }
                }

                task.cronExpression?.let {
                    appendLine("   Cron: $it")
                }

                appendLine("   Priority: ${task.priority}")
                appendLine(
                    "   Created: ${
                        task.createdAt.atZone(java.time.ZoneId.systemDefault()).format(dateFormatter)
                    } by ${task.createdBy}",
                )
                appendLine()
            }
        }
    }

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): BrowseParams =
        llmGateway.callLlm(
            type = PromptTypeEnum.TASK_VIEWER,
            userPrompt = taskDescription,
            quick = context.quick,
            BrowseParams(),
            stepContext = stepContext,
        )
}
