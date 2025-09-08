package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import com.jervis.service.mcp.util.McpJson
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class EmailTool(
    private val llmGateway: LlmGateway,
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
    private val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: String = "email"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.EMAIL)

    @Serializable
    data class EmailParams(
        val action: String,
        val to: List<String>,
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String,
        val body: String,
        val priority: String = "normal",
        val finalPrompt: String? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): EmailParams {
        val systemPrompt = promptRepository.getMcpToolSystemPrompt(McpToolType.EMAIL)
        val llmResponse =
            llmGateway.callLlm(
                type = ModelType.INTERNAL,
                systemPrompt = systemPrompt,
                userPrompt = taskDescription,
                outputLanguage = "en",
                quick = context.quick,
            )

        return McpJson.decode<EmailParams>(llmResponse.answer).getOrElse {
            throw IllegalArgumentException("Failed to parse Email parameters: ${it.message}")
        }
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val parsed =
            try {
                parseTaskDescription(taskDescription, context)
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

        val result =
            withContext(Dispatchers.IO) {
                try {
                    executeEmailOperation(parsed)
                } catch (e: Exception) {
                    logger.error(e) { "Email operation failed: ${parsed.action}" }
                    ToolResult.error("Email operation failed: ${e.message}")
                }
            }

        // Process through LLM if finalPrompt is provided and result is successful
        return mcpFinalPromptProcessor.processFinalPrompt(
            finalPrompt = parsed.finalPrompt,
            systemPrompt =
                promptRepository.getMcpToolFinalProcessingSystemPrompt(McpToolType.EMAIL)
                    ?: "You are an email communication expert. Analyze the email operation results and provide actionable insights.",
            originalResult = result,
            context = context,
        )
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
