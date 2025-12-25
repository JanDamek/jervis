package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.common.client.IAiderClient
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.entity.TaskDocument
import mu.KotlinLogging

/**
 * AiderCodingTool – local surgical code edits using Aider (CLI) on the project workspace.
 *
 * Use for:
 * - Small, targeted changes in existing files (bugfixes, refactors)
 * - Fast iterative edits when you already know affected files
 *
 * Do NOT use for:
 * - Spinning up/running apps, heavy debugging across services, installing deps
 *   → use OpenHandsCodingTool for that (isolated K8s environment)
 */
@LLMDescription(
    "Local surgical code edits via Aider on the project's git workspace. Ideal for small fixes/refactors in specific files.",
)
class AiderCodingTool(
    private val task: TaskDocument,
    private val engine: IAiderClient,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Run Aider with a clear task description and list of target files (relative paths under the project's git directory). " +
            "By default uses fast local Qwen model. For complex/critical tasks, set model='paid' to use more powerful paid API.",
    )
    suspend fun runAiderCoding(
        @LLMDescription("Clear task description for the programmer. Be specific about what to change and why.")
        taskDescription: String,
        @LLMDescription("List of relative file paths to edit (from GraphDB or repository context)")
        targetFiles: List<String>,
        @LLMDescription(
            "Model selection strategy: Leave NULL for standard tasks (uses fast local Qwen model). " +
                "Set to 'paid' ONLY for complex architectural tasks, security audits, or large refactoring where SOTA reasoning is required.",
        )
        model: String? = null,
    ): String {
        logger.info { "AIDER_TOOL_CALL: files=$targetFiles, model=$model" }

        val req =
            CodingExecuteRequest(
                correlationId = task.correlationId,
                clientId = task.clientId.toString(),
                projectId = task.projectId.toString(),
                taskDescription = taskDescription,
                targetFiles = targetFiles,
            )

        val res = engine.execute(req)

        return buildString {
            appendLine("AIDER_RESULT: success=${res.success}")
            appendLine("summary: ${res.summary}")
            res.details?.let { appendLine("details:\n$it") }
        }
    }
}
