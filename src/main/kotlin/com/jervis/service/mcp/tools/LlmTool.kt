package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
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
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.LLM)

    @Serializable
    data class LlmParams(
        val systemPrompt: String? = null,
        val userPrompt: String = "",
        val contextFromSteps: Int = 0,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): LlmParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.LLM)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.LLM,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = LlmParams(),
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
                return ToolResult.error("Invalid LLM parameters: ${e.message}", "LLM parameter parsing failed")
            }

        if (parsed.userPrompt.isBlank()) {
            return ToolResult.error("User prompt cannot be empty")
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
                    type = PromptTypeEnum.LLM,
                    userPrompt = completeUserPrompt,
                    outputLanguage = "en",
                    quick = context.quick,
                    mappingValue = parsed.systemPrompt?.let { mapOf("systemPrompt" to it) } ?: emptyMap(),
                    exampleInstance = "",
                )
            } catch (e: Exception) {
                return ToolResult.error("LLM call failed: ${e.message}")
            }

        val enhancedOutput = llmResult.trim().ifEmpty { "Empty LLM response" }

        return ToolResult.ok(enhancedOutput)
    }
}
