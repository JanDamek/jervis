package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.service.coding.CodingRequest
import com.jervis.service.coding.ModelSelectionService
import com.jervis.service.coding.OpenHandsCodingEngine
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
    private val engine: OpenHandsCodingEngine,
    private val modelSelectionService: ModelSelectionService,
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
        @LLMDescription("Optional Git repository URL to clone into the workspace")
        repoUrl: String? = null,
        @LLMDescription(
            "Model selection strategy: Leave NULL for standard tasks (uses fast local Qwen model). " +
                "Set to 'paid' ONLY for complex architectural tasks, security audits, or large refactoring where SOTA reasoning is required."
        )
        model: String? = null,
    ): String {
        logger.info { "OPENHANDS_TOOL_SUBMIT: correlationId=${task.correlationId}, model=$model" }

        val selectedModel = when (model) {
            "paid" -> {
                val paidModel = modelSelectionService.getOpenHandsPaidModel()
                logger.info { "Using paid model: $paidModel" }
                paidModel
            }
            null -> {
                val defaultModel = modelSelectionService.getOpenHandsDefaultModel()
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
                taskDescription = taskSpec,
                extra = buildMap {
                    repoUrl?.let { put("repoUrl", it) }
                    put("model", selectedModel)
                },
            )

        val res = engine.execute(req)

        return buildString {
            appendLine("OPENHANDS_SUBMIT: success=${res.success}")
            appendLine("summary: ${res.summary}")
            if (res.metadata.containsKey("jobId")) {
                appendLine("jobId: ${res.metadata["jobId"]}")
            }
            res.details?.let { appendLine("details:\n" + it.take(5000)) }
        }
    }

    @Tool
    @LLMDescription("Check status/result of an OpenHands job by jobId returned from delegateToOpenHands.")
    suspend fun checkOpenHandsStatus(
        @LLMDescription("The jobId returned from delegateToOpenHands")
        jobId: String,
    ): String {
        logger.info { "OPENHANDS_TOOL_STATUS: jobId=$jobId" }
        val status = engine.checkStatus(jobId)
        return buildString {
            appendLine("OPENHANDS_STATUS: ${status.status}")
            appendLine("jobId: ${status.jobId}")
            status.result?.let { appendLine("result:\n" + it.take(8000)) }
            status.error?.let { appendLine("error:\n" + it.take(8000)) }
        }
    }
}
