package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.service.coding.AiderCodingEngine
import com.jervis.service.coding.CodingRequest
import com.jervis.service.coding.ModelSelectionService
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
    private val task: PendingTaskDocument,
    private val engine: AiderCodingEngine,
    private val modelSelectionService: ModelSelectionService,
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
                "Set to 'paid' ONLY for complex architectural tasks, security audits, or large refactoring where SOTA reasoning is required."
        )
        model: String? = null,
    ): String {
        logger.info { "AIDER_TOOL_CALL: files=$targetFiles, model=$model" }

        val selectedModel = when (model) {
            "paid" -> {
                val paidModel = modelSelectionService.getAiderPaidModel()
                logger.info { "Using paid model: $paidModel" }
                paidModel
            }
            null -> {
                val defaultModel = modelSelectionService.getAiderDefaultModel()
                logger.info { "Using default model: $defaultModel" }
                defaultModel
            }
            else -> {
                logger.info { "Using explicit model: $model" }
                model
            }
        }

        val req =
            CodingRequest(
                correlationId = task.correlationId,
                clientId = task.clientId,
                projectId = task.projectId,
                taskDescription = taskDescription,
                targetFiles = targetFiles,
                extra = mapOf("model" to selectedModel),
            )

        val res = engine.execute(req)

        return buildString {
            appendLine("AIDER_RESULT: success=${res.success}")
            appendLine("summary: ${res.summary}")
            if (res.metadata.containsKey("jobId")) {
                appendLine("jobId: ${res.metadata["jobId"]}")
                res.metadata["status"]?.let { appendLine("status: $it") }
            }
            res.details?.let { appendLine("details:\n" + it.take(5000)) }
        }
    }

    @Tool
    @LLMDescription("Check status/result of an Aider job by jobId returned from runAiderCoding (if the service runs async).")
    suspend fun checkAiderStatus(
        @LLMDescription("The jobId returned from runAiderCoding")
        jobId: String,
    ): String {
        logger.info { "AIDER_TOOL_STATUS: jobId=$jobId" }
        val status = engine.checkStatus(jobId)
        return buildString {
            appendLine("AIDER_STATUS: ${status.status}")
            appendLine("jobId: ${status.jobId}")
            status.result?.let { appendLine("result:\n" + it.take(8000)) }
            status.error?.let { appendLine("error:\n" + it.take(8000)) }
        }
    }
}
