package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import org.springframework.stereotype.Service

@Service
class AnalysisReasoningTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.ANALYSIS_REASONING_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val llmResult =
            llmGateway.callLlm(
                type = PromptTypeEnum.ANALYSIS_REASONING_TOOL,
                responseSchema = LlmResponseWrapper(),
                correlationId = plan.correlationId,
                quick = plan.quick,
                mappingValue =
                    mapOf(
                        "taskParams" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                backgroundMode = plan.backgroundMode,
            )

        val enhancedOutput =
            llmResult.result.response
                .trim()
                .ifEmpty { "Empty LLM response" }
        return ToolResult.success(
            "LLM",
            enhancedOutput,
            enhancedOutput,
        )
    }
}
