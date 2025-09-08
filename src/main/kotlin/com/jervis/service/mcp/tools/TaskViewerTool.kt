package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.entity.mongo.ScheduledTaskStatus
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.scheduling.TaskQueryService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override val name: String = "task_viewer"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.TASK_VIEWER)

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
    ): ToolResult {
        return try {
            logger.info { "Executing scheduler browsing with description: $taskDescription" }

            val params = parseTaskDescription(taskDescription, context)
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
            ToolResult.ok(output)
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

            ToolResult.ok(output)
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
    ): BrowseParams {
        val systemPrompt = promptRepository.getMcpToolSystemPrompt(McpToolType.TASK_VIEWER)

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = ModelType.INTERNAL,
                    systemPrompt = systemPrompt,
                    userPrompt = taskDescription,
                    outputLanguage = "en",
                    quick = context.quick,
                )

            val cleanedResponse =
                llmResponse.answer
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            Json.decodeFromString<BrowseParams>(cleanedResponse)
        } catch (e: Exception) {
            logger.warn(e) { "LLM parsing failed for task description: $taskDescription, falling back to manual parsing" }

            // Enhanced fallback logic based on keywords
            val description = taskDescription.lowercase()

            // Parse status
            val status =
                when {
                    description.contains("pending") -> "PENDING"
                    description.contains("running") -> "RUNNING"
                    description.contains("completed") -> "COMPLETED"
                    description.contains("failed") -> "FAILED"
                    description.contains("cancelled") -> "CANCELLED"
                    else -> null
                }

            // Parse project ID - use current project if mentioned
            val projectId =
                when {
                    description.contains("current project") || description.contains("this project") ->
                        context.projectDocument.id.toString()

                    else -> null
                }

            // Parse task type (deprecated field, always null as per prompts)
            val taskType: String? = null

            // Parse limit
            val limit =
                Regex("(last|latest|first)\\s+(\\d+)")
                    .find(description)
                    ?.groupValues
                    ?.get(2)
                    ?.toIntOrNull() ?: 50

            // Check for statistics request
            val showStatistics =
                description.contains("statistics") || description.contains("stats") || description.contains("summary")

            BrowseParams(
                status = status,
                projectId = projectId,
                taskType = taskType,
                limit = limit,
                showStatistics = showStatistics,
            )
        }
    }
}
