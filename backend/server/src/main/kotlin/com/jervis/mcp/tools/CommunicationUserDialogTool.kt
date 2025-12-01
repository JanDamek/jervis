package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.dialog.UserDialogCoordinator
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * CommunicationUserDialogTool integrates the agent with the UI to handle interactive questions and answers.
 *
 * Pattern: typed JSON input prepared by planner. This tool DOES NOT call LLM.
 * It simply emits an ASK payload for the UI with the question and optional proposed answer.
 */
@Service
class CommunicationUserDialogTool(
    override val promptRepository: PromptRepository,
    private val userDialogCoordinator: UserDialogCoordinator,
) : McpTool<CommunicationUserDialogTool.UserDialogParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.COMMUNICATION_USER_DIALOG_TOOL

    @Serializable
    data class UserDialogParams(
        val question: String,
        val proposedAnswer: String,
    )

    override val descriptionObject =
        UserDialogParams(
            question = "Ask user to confirm deployment to production",
            proposedAnswer = "Yes, please deploy now.",
        )

    override suspend fun execute(
        plan: Plan,
        request: UserDialogParams,
    ): ToolResult {
        if (plan.backgroundMode) {
            // In background mode, the interactive dialog is forbidden. Planner must create a user task instead.
            val msg =
                "USER_DIALOG_NOT_ALLOWED_IN_BACKGROUND: Use TASK_CREATE_USER_TASK_TOOL with correlationId=${plan.correlationId} " +
                    "to collect user input asynchronously."
            return ToolResult.error(msg)
        }

        logger.info { "USER_DIALOG: Requesting interactive input via WebSocket (correlationId=${plan.correlationId})" }

        val result =
            userDialogCoordinator.requestDialog(
                clientId = plan.clientDocument.id,
                projectId = plan.projectDocument?.id,
                correlationId = plan.correlationId,
                question = request.question,
                proposedAnswer = request.proposedAnswer,
            )

        return if (result.accepted && !result.answer.isNullOrBlank()) {
            ToolResult.success(
                toolName = name.name,
                summary = "User provided input",
                content =
                    buildString {
                        appendLine("Answer:")
                        appendLine(result.answer)
                    },
            )
        } else {
            ToolResult.error("User canceled the dialog or no answer provided")
        }
    }
}
