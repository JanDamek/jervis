package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.background.PendingTaskService
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
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
) : McpTool<CreatePendingTaskTool.PendingTaskParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.CREATE_PENDING_TASK_TOOL

    @Serializable
    data class PendingTaskParams(
        val description: String,
    )

    override val descriptionObject = PendingTaskParams(description = "Analyze newly found docs for client onboarding")

    override suspend fun execute(
        plan: Plan,
        request: PendingTaskParams,
    ): ToolResult {
        logger.info { "CREATE_PENDING_TASK: Creating task with description length=${request.description.length}" }

        if (request.description.isBlank()) {
            return ToolResult.error("Task description cannot be blank")
        }

        val task =
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.AGENT_ANALYSIS,
                content = request.description,
                projectId = plan.projectDocument?.id,
                clientId = plan.clientDocument.id,
                correlationId = plan.correlationId, // Preserve correlationId from plan - same work chain
            )

        logger.info { "CREATE_PENDING_TASK_SUCCESS: Created task ${task.id}" }

        return ToolResult.success(
            toolName = name.name,
            summary = "Created pending task ${task.id}",
            content = "Successfully created background analysis task. Task ID: ${task.id}",
        )
    }
}
