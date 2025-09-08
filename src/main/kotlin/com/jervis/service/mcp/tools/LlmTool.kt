package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpJson
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Service
class LlmTool(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) : McpTool {
    override val name: String = "llm"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.LLM)

    @Serializable
    data class LlmParams(
        val systemPrompt: String? = null,
        val userPrompt: String,
        val modelType: String = "INTERNAL",
        val contextFromSteps: Int = 0,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): LlmParams {
        val systemPrompt = promptRepository.getMcpToolSystemPrompt(McpToolType.LLM)

        val llmResponse =
            llmGateway.callLlm(
                type = ModelType.INTERNAL,
                systemPrompt = systemPrompt,
                userPrompt = taskDescription,
                outputLanguage = "en",
                quick = context.quick,
            )

        return McpJson.decode<LlmParams>(llmResponse.answer).getOrElse {
            throw IllegalArgumentException("Failed to parse LLM parameters: ${it.message}")
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
                return ToolResult.error("Invalid LLM parameters: ${e.message}", "LLM parameter parsing failed")
        }

        if (parsed.userPrompt.isBlank()) {
            return ToolResult.error("User prompt cannot be empty")
        }

        // Validate and map model type
        val modelType =
            try {
                ModelType.valueOf(parsed.modelType.uppercase())
            } catch (e: IllegalArgumentException) {
                return ToolResult.error(
                    "Invalid model type: ${parsed.modelType}. Available types: INTERNAL, TRANSLATION, PLANNER, CHAT_INTERNAL, CHAT_EXTERNAL, JOERN",
                )
            }

        // Collect context from previous steps if requested
        val stepContext =
            if (parsed.contextFromSteps > 0) {
                val recentSteps = plan.steps.takeLast(parsed.contextFromSteps)
                buildString {
                    recentSteps.forEachIndexed { index, step ->
                        append("${step.name}(${step.status})")
                        step.output?.let { append(": ${it.render()}") }
                        if (index < recentSteps.size - 1) append(" | ")
                    }
                    if (recentSteps.isNotEmpty()) appendLine()
                }
            } else {
                ""
            }

        // Build the complete user prompt with context
        val completeUserPrompt =
            buildString {
                if (stepContext.isNotEmpty()) {
                    append(stepContext)
                }
                append(parsed.userPrompt)
            }

        // Execute the main LLM call
        val llmResult =
            try {
                llmGateway.callLlm(
                    type = modelType,
                    systemPrompt =
                        parsed.systemPrompt
                            ?: "You are a helpful AI assistant. Provide clear, accurate, and actionable responses.",
                    userPrompt = completeUserPrompt,
                    outputLanguage = "en",
                    quick = context.quick,
                )
            } catch (e: Exception) {
                return ToolResult.error("LLM call failed: ${e.message}")
            }

        val enhancedOutput = llmResult.answer.trim().ifEmpty { "Empty LLM response" }

        return ToolResult.ok(enhancedOutput)
    }
}
