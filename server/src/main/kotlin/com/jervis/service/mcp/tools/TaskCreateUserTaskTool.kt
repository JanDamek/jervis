package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.task.TaskPriority
import com.jervis.domain.task.TaskSourceType
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Serializable
data class CreateUserTaskRequest(
    val title: String,
    val description: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
    val sourceType: String,
    val sourceUri: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Service
class TaskCreateUserTaskTool(
    private val userTaskService: UserTaskService,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.TASK_CREATE_USER_TASK_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        try {
            logger.info { "Creating user task from: $taskDescription" }

            val request = Json.decodeFromString<CreateUserTaskRequest>(taskDescription)

            val priority =
                request.priority?.let {
                    runCatching { TaskPriority.valueOf(it.uppercase()) }.getOrNull()
                } ?: TaskPriority.MEDIUM

            val sourceType =
                runCatching { TaskSourceType.valueOf(request.sourceType.uppercase()) }
                    .getOrElse { TaskSourceType.AGENT_SUGGESTION }

            val dueDate =
                request.dueDate?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                }

            val task =
                userTaskService.createTask(
                    title = request.title,
                    description = request.description,
                    priority = priority,
                    dueDate = dueDate,
                    projectId = plan.projectId,
                    clientId = plan.clientId,
                    sourceType = sourceType,
                    sourceUri = request.sourceUri,
                    metadata = request.metadata,
                )

            ToolResult.success(
                toolName = name.name,
                summary = "Created user task: ${task.title} (priority: ${task.priority})",
                content =
                    buildString {
                        appendLine("TASK_ID: ${task.id.toHexString()}")
                        appendLine("TITLE: ${task.title}")
                        task.description?.let { appendLine("DESCRIPTION: $it") }
                        appendLine("PRIORITY: ${task.priority}")
                        task.dueDate?.let { appendLine("DUE: $it") }
                        appendLine("SOURCE: ${task.sourceType}")
                        task.sourceUri?.let { appendLine("SOURCE_URI: $it") }
                    },
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create user task" }
            ToolResult.error(
                output = "Failed to create user task: ${e.message}",
                message = e.message,
            )
        }
}
