package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.scheduling.TaskQueryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter

/**
 * MCP Tool for viewing and browsing scheduled tasks.
 * Allows filtering by project, status, task type, and date ranges.
 */
@Service
class SystemViewTasksTool(
    private val taskQueryService: TaskQueryService,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    override val name: PromptTypeEnum = PromptTypeEnum.SYSTEM_VIEW_TASKS_TOOL

    @Serializable
    data class BrowseParams(
        val status: String? = null,
        val projectId: String? = null,
        val taskType: String? = null,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        try {
            logger.info { "Executing scheduler browsing with description: $taskDescription" }

            val params = parseTaskDescription(taskDescription, context, stepContext)
            logger.debug { "Parsed browse parameters: $params" }

            val filteredTasks =
                flow {
                    when {
                        params.status != null && params.projectId != null -> {
                            val status = ScheduledTaskDocument.ScheduledTaskStatus.valueOf(params.status.uppercase())
                            val projectId = org.bson.types.ObjectId(params.projectId)
                            emitAll(taskQueryService.getTasksForProject(projectId).filter { it.status == status })
                        }

                        params.status != null -> {
                            val status = ScheduledTaskDocument.ScheduledTaskStatus.valueOf(params.status.uppercase())
                            emitAll(taskQueryService.getTasksByStatus(status))
                        }

                        params.projectId != null -> {
                            val projectId = org.bson.types.ObjectId(params.projectId)
                            emitAll(taskQueryService.getTasksForProject(projectId))
                        }

                        else -> {
                            // Get all pending and running tasks by default
                            emitAll(
                                taskQueryService.getTasksByStatus(ScheduledTaskDocument.ScheduledTaskStatus.PENDING),
                            )
                            emitAll(
                                taskQueryService.getTasksByStatus(ScheduledTaskDocument.ScheduledTaskStatus.RUNNING),
                            )
                        }
                    }
                }

            val output = buildTaskListOutput(filteredTasks)
            ToolResult.listingResult(
                toolName = "TASK_VIEWER",
                itemType = "tasks",
                listing = output,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error browsing scheduled tasks" }
            ToolResult.error("Failed to browse scheduled tasks: ${e.message}")
        }

    private suspend fun buildTaskListOutput(tasks: Flow<ScheduledTaskDocument>): String =
        buildString {
            appendLine("=== SCHEDULED TASKS ===")
            appendLine()

            tasks.collect { task ->
                appendLine("Task: ${task.taskName}")
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

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): BrowseParams =
        llmGateway
            .callLlm(
                type = PromptTypeEnum.SYSTEM_VIEW_TASKS_TOOL,
                responseSchema = BrowseParams(),
                quick = context.quick,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
            ).result
}
