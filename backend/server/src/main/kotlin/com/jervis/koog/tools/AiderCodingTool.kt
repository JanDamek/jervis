package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import mu.KotlinLogging

/**
 * AiderCodingTool – local surgical code edits using Aider (CLI) on the project workspace.
 *
 * Uses Koog A2A Client to communicate with service-aider.
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
    private val aiderBaseUrl: String = "http://localhost:8081",
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
    ): String {
        logger.info { "AIDER_TOOL_CALL: files=$targetFiles" }

        return try {
            val client = A2AClientHelper.getOrCreate(aiderBaseUrl)
            val result =
                A2AClientHelper.sendCodingRequest(
                    client = client,
                    task = task,
                    taskDescription = taskDescription,
                    targetFiles = targetFiles,
                    codingInstruction = "Modify the specified files according to the task description. Focus on surgical, targeted changes.",
                    codingRules = CodingRules.NO_GIT_WRITES_RULES,
                )

            buildString {
                appendLine("AIDER_RESULT:")
                appendLine(result)
            }
        } catch (e: Exception) {
            logger.error(e) { "AIDER_A2A_ERROR: ${e.message}" }
            "ERROR: Aider A2A call failed: ${e.message}"
        }
    }
}
