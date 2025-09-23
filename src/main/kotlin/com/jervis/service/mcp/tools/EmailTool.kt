package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class EmailTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: PromptTypeEnum = PromptTypeEnum.EMAIL

    @Serializable
    data class EmailParams(
        val action: String = "",
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String = "",
        val body: String = "",
        val priority: String = "normal",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): EmailParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.EMAIL,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = EmailParams(),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed =
            try {
                parseTaskDescription(taskDescription, context, stepContext)
            } catch (e: Exception) {
                return ToolResult.error("Invalid Email parameters: ${e.message}", "Email parameter parsing failed")
            }

        if (parsed.action.isBlank()) {
            return ToolResult.error("Email action cannot be empty")
        }

        if (parsed.to.isEmpty()) {
            return ToolResult.error("Email recipients (to) cannot be empty")
        }

        if (parsed.subject.isBlank()) {
            return ToolResult.error("Email subject cannot be empty")
        }

        return withContext(Dispatchers.IO) {
            try {
                executeEmailOperation(parsed)
            } catch (e: Exception) {
                logger.error(e) { "Email operation failed: ${parsed.action}" }
                ToolResult.error("Email operation failed: ${e.message}")
            }
        }
    }

    private suspend fun executeEmailOperation(params: EmailParams): ToolResult {
        // Note: This is a mock implementation. In production, you would integrate with an actual email service
        // such as SendGrid, Amazon SES, SMTP server, etc.

        val output =
            buildString {
                appendLine("📧 Email Operation: ${params.action}")
                appendLine()
                appendLine("Recipients:")
                appendLine("  To: ${params.to.joinToString(", ")}")
                if (params.cc.isNotEmpty()) {
                    appendLine("  CC: ${params.cc.joinToString(", ")}")
                }
                if (params.bcc.isNotEmpty()) {
                    appendLine("  BCC: ${params.bcc.joinToString(", ")}")
                }
                appendLine()
                appendLine("Subject: ${params.subject}")
                appendLine("Priority: ${params.priority}")
                appendLine()
                appendLine("Message Body:")
                appendLine("---")
                appendLine(params.body)
                appendLine("---")
                appendLine()
                appendLine("✅ Email prepared successfully")
                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual email sending, integrate with:")
                appendLine("  - SMTP server configuration")
                appendLine("  - SendGrid API")
                appendLine("  - Amazon SES")
                appendLine("  - Microsoft Graph API")
                appendLine("  - Other email service providers")
            }

        return ToolResult.ok(output)
    }
}
