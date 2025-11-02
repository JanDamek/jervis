package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.service.background.PendingTaskService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Creates a pending task for background processing.
 * NO LLM - purely parametric operation.
 *
 * Always creates AGENT_ANALYSIS task with NORMAL priority.
 * Input is plain text description of what needs analysis.
 */
@Service
class CreatePendingTaskTool(
    private val pendingTaskService: PendingTaskService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.CREATE_PENDING_TASK_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "CREATE_PENDING_TASK: Creating task with description length=${taskDescription.length}" }

        if (taskDescription.isBlank()) {
            return ToolResult.error("Task description cannot be blank")
        }

        val task =
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.AGENT_ANALYSIS,
                content = taskDescription,
                projectId = plan.projectDocument?.id,
                clientId = plan.clientDocument.id,
                needsQualification = true,
            )

        logger.info { "CREATE_PENDING_TASK_SUCCESS: Created task ${task.id}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Created pending task ${task.id}",
            content = "Successfully created background analysis task. Task ID: ${task.id}",
        )
    }
}
