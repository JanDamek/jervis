package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import mu.KotlinLogging

/**
 * OpenHandsCodingTool – heavy-weight autonomous coding in isolated K8s sandbox (Server 3).
 *
 * Uses Koog A2A Client to communicate with service-coding-engine.
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
    private val task: TaskDocument,
    private val openHandsBaseUrl: String = "http://localhost:8082",
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Submit a complex job to OpenHands. Provide a detailed task spec. Optionally include repoUrl to clone. " +
            "By default uses fast local Qwen model. For complex/critical tasks.",
    )
    suspend fun delegateToOpenHands(
        @LLMDescription("Detailed task specification for OpenHands (what to build/run/debug and expected outcome)")
        taskSpec: String,
    ): String {
        logger.info { "OPENHANDS_TOOL_SUBMIT: correlationId=${task.correlationId}" }

        return try {
            val client = A2AClientHelper.getOrCreate(openHandsBaseUrl)
            val result =
                A2AClientHelper.sendCodingRequest(
                    client = client,
                    task = task,
                    taskDescription = taskSpec,
                    targetFiles = emptyList(),
                    codingInstruction =
                        "Autonomously solve the task. You can explore the codebase, run commands, install dependencies, " +
                            "debug issues, and make necessary code changes. Provide a summary of changes made.",
                    codingRules = CodingRules.NO_GIT_WRITES_RULES,
                )

            buildString {
                appendLine("OPENHANDS_RESULT:")
                appendLine(result)
            }
        } catch (e: Exception) {
            logger.error(e) { "OPENHANDS_A2A_ERROR: ${e.message}" }
            "ERROR: OpenHands A2A call failed: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Execute verification commands (build/test) and return results. " +
            "Does NOT edit code. Used after coding changes to verify correctness.",
    )
    suspend fun runVerificationWithOpenHands(
        @LLMDescription("Build/test commands to execute (e.g., 'gradle build' or 'npm test')")
        verificationSpec: String,
    ): String {
        logger.info { "OPENHANDS_VERIFY: correlationId=${task.correlationId}" }

        return try {
            val client = A2AClientHelper.getOrCreate(openHandsBaseUrl)
            val result =
                A2AClientHelper.sendCodingRequest(
                    client = client,
                    task = task,
                    taskDescription = "VERIFY: $verificationSpec",
                    targetFiles = emptyList(),
                    codingInstruction = "Execute the specified commands and report results. Do not edit code unless explicitly asked.",
                    codingRules = CodingRules.VERIFY_RULES,
                )

            buildString {
                appendLine("OPENHANDS_VERIFY_RESULT:")
                appendLine(result)
            }
        } catch (e: Exception) {
            logger.error(e) { "OPENHANDS_VERIFY_ERROR: ${e.message}" }
            "ERROR: Verification failed: ${e.message}"
        }
    }
}
