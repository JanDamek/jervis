package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpJson
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class TeamsTool(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}

    override val name: String = "teams"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.TEAMS)

    @Serializable
    data class TeamsParams(
        val action: String,
        val target_type: String,
        val target: String,
        val message: String,
        val thread_id: String? = null,
        val mentions: List<String> = emptyList(),
        val priority: String = "normal",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): TeamsParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.TEAMS)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.TEAMS,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = TeamsParams("", "", "", "", null, emptyList(), "normal"),
            )

        return llmResponse
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
                return ToolResult.error("Invalid Teams parameters: ${e.message}", "Teams parameter parsing failed")
            }

        if (parsed.action.isBlank()) {
            return ToolResult.error("Teams action cannot be empty")
        }

        if (parsed.target_type.isBlank()) {
            return ToolResult.error("Teams target type cannot be empty")
        }

        if (parsed.target.isBlank()) {
            return ToolResult.error("Teams target cannot be empty")
        }

        if (parsed.message.isBlank()) {
            return ToolResult.error("Teams message cannot be empty")
        }

        // Validate target_type
        if (parsed.target_type !in listOf("channel", "user", "chat")) {
            return ToolResult.error("Invalid target type. Must be: channel, user, or chat")
        }

        val result =
            withContext(Dispatchers.IO) {
                try {
                    executeTeamsOperation(parsed)
                } catch (e: Exception) {
                    logger.error(e) { "Teams operation failed: ${parsed.action}" }
                    ToolResult.error("Teams operation failed: ${e.message}")
                }
            }

        return result
    }

    private suspend fun executeTeamsOperation(params: TeamsParams): ToolResult {
        // Note: This is a mock implementation. In production, you would integrate with Microsoft Graph API
        // for actual Teams messaging functionality.

        val output =
            buildString {
                appendLine("🔷 Microsoft Teams Operation: ${params.action}")
                appendLine()
                appendLine("Target Details:")
                appendLine("  Type: ${params.target_type}")
                appendLine("  Target: ${params.target}")
                if (params.thread_id != null) {
                    appendLine("  Thread ID: ${params.thread_id}")
                }
                appendLine("  Priority: ${params.priority}")
                appendLine()
                if (params.mentions.isNotEmpty()) {
                    appendLine("Mentions: ${params.mentions.joinToString(", ")}")
                    appendLine()
                }
                appendLine("Message Content:")
                appendLine("---")
                appendLine(params.message)
                appendLine("---")
                appendLine()

                when (params.target_type) {
                    "channel" -> {
                        appendLine("✅ Message prepared for Teams channel: ${params.target}")
                        appendLine("📢 Channel members will receive notifications based on their settings")
                    }

                    "user" -> {
                        appendLine("✅ Direct message prepared for user: ${params.target}")
                        appendLine("👤 User will receive a personal notification")
                    }

                    "chat" -> {
                        appendLine("✅ Message prepared for Teams chat: ${params.target}")
                        appendLine("💬 Chat participants will receive notifications")
                    }
                }

                appendLine()
                appendLine("⚠️ Note: This is a mock implementation. To enable actual Teams messaging, integrate with:")
                appendLine("  - Microsoft Graph API")
                appendLine("  - Teams Bot Framework")
                appendLine("  - Azure AD authentication")
                appendLine("  - Proper Teams app registration")
                appendLine("  - Webhook connectors for channel messaging")
            }

        return ToolResult.ok(output)
    }
}
