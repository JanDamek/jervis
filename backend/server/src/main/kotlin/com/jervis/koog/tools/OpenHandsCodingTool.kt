package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.common.client.ICodingEngineClient
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.entity.PendingTaskDocument
import mu.KotlinLogging

/**
 * OpenHandsCodingTool – heavy-weight autonomous coding in isolated K8s sandbox (Server 3).
 *
 * Use for:
 * - New projects or large-scale refactors
 * - Running and debugging applications, installing dependencies
 * - Multi-step workflows requiring long runtime and tools
 *
 * Not ideal for:
 * - Small, surgical edits in a couple of files → use AiderCodingTool instead
 */
@LLMDescription(
    "Delegate complex coding tasks to OpenHands in an isolated K8s environment. Ideal for running apps, heavy debugging, or large changes.",
)
class OpenHandsCodingTool(
    private val task: PendingTaskDocument,
    private val engine: ICodingEngineClient,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Submit a complex job to OpenHands. Provide a detailed task spec. Optionally include repoUrl to clone. " +
            "By default uses fast local Qwen model. For complex/critical tasks, set model='paid' to use more powerful paid API.",
    )
    suspend fun delegateToOpenHands(
        @LLMDescription("Detailed task specification for OpenHands (what to build/run/debug and expected outcome)")
        taskSpec: String,
        @LLMDescription(
            "Model selection strategy: Leave NULL for standard tasks (uses fast local Qwen model). " +
                "Set to 'paid' ONLY for complex architectural tasks, security audits, or large refactoring where SOTA reasoning is required.",
        )
        model: String? = null,
    ): String {
        logger.info { "OPENHANDS_TOOL_SUBMIT: correlationId=${task.correlationId}, model=$model" }

        val req =
            CodingExecuteRequest(
                correlationId = task.correlationId,
                clientId = task.clientId.toString(),
                projectId = task.projectId.toString(),
                taskDescription = taskSpec,
            )

        val res = engine.execute(req)

        return buildString {
            appendLine("OPENHANDS_SUBMIT: success=${res.success}")
            appendLine("summary: ${res.summary}")
            res.details?.let { appendLine("details:\n$it") }
        }
    }
}
