package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Planner creates a plan for a given task context.
 * Prefers LLM-driven plan, falls back to deterministic default when needed.
 */
@Service
class Planner(
    private val llmGateway: LlmGateway,
) {
    var allToolDescriptions: String = ""
    private val logger = KotlinLogging.logger {}

    @Serializable
    private data class PlannerStepDto(
        val name: String,
        val taskDescription: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPlan(
        context: TaskContext,
        plan: Plan,
    ): Plan {
        val systemPrompt = buildSystemPrompt(allToolDescriptions)
        val userPrompt = buildUserPrompt(context, plan)

        val llmAnswer =
            runCatching {
                llmGateway
                    .callLlm(
                        type = ModelType.PLANNER,
                        userPrompt = userPrompt,
                        systemPrompt = systemPrompt,
                        quick = context.quick,
                    )
            }.onFailure {
                logger.error(it) { "PLANNER_LLM_ERROR: falling back to deterministic plan" }
            }.getOrThrow()

        val stepsList = parseAnswerToSteps(llmAnswer.answer, plan.id, context.id)
        logger.info { "PLANNER_LLM_PLAN_BUILT: model=${llmAnswer.model}" }
        return plan.apply { this.steps = stepsList }
    }

    private fun buildSystemPrompt(toolDescriptions: String): String =
        """
You are an autonomous high-level task planner.
Your job is to break down the user’s intent into a sequence of tool-based steps, using **only the tools listed in the Tool List**. Each step must describe a **focused subtask** that helps resolve the user’s request. You do **not** execute any tools — you just plan.
────────────────────────────
PRINCIPLES:
1. NO FABRICATION:
   You must never invent or assume facts, data, or interpretations. If a step requires information you do not have access to, you must:
   • Add a step to explicitly retrieve that information using available tools (RAG, document search, source scan, etc.)
   • Or ask the user for clarification using the `ask-user` tool.
2. AGENT-READY THINKING:
   You are planning a multi-step autonomous workflow for an LLM agent. Design steps so that each can be executed independently.
   • If the task is ambiguous, you must insert a clarification step using the `ask-user` tool.
   • If more knowledge is required, plan a step using `rag-query` or any tool that can retrieve from history, transcripts, or documentation.
3. NO RAW COMMANDS OR PARAMETERS:
   All task descriptions must be written in natural, human-readable language — never include raw JSON, CLI commands, or code. The agent that executes your plan will handle low-level details.
4. MINIMALISM & FOCUS:
   Each step must do only **one logical subtask** (e.g., "Extract all method names from the file", "Find references to input validation", "Summarize the document for security issues").
5. TOOL-BOUND ONLY:
   You are limited to the tools listed in the Tool List. Do not invent new tools — if the task requires capabilities not present, you must add a final step using the `tool-request` tool to suggest what tool should be implemented.
────────────────────────────
OUTPUT FORMAT:
Always return a JSON array, where each item has the following shape:
{
  "name": "<exact tool name from the Tool List>",
  "taskDescription": "<natural language description of what this tool should do in this step>"
}
Never wrap the result in code fences or markdown. Just output the plain JSON array. 
Primary target is get all needed information to resolve user question.
────────────────────────────
FAILURE MODES TO AVOID:
NEVER return JSON with missing fields or raw code
NEVER guess if something is unclear — insert `ask-user` or `rag-query`
NEVER describe tasks for tools that are not in the tool list
NEVER summarize or comment — return only the JSON array
────────────────────────────
TOOL LIST:
$toolDescriptions
        """.trimIndent()

    private fun buildUserPrompt(
        context: TaskContext,
        plan: Plan,
    ): String =
        buildString {
            appendLine("PRIMARY USER GOAL:")
            appendLine("→ ${plan.englishQuestion}")
            appendLine()
            appendLine("CLIENT & PROJECT CONTEXT:")
            appendLine("Client description: ${context.clientDocument.description}")
            appendLine("Project description: ${context.projectDocument.description}")

            if (plan.steps.isNotEmpty()) {
                appendLine()
                appendLine("EXISTING PLAN HISTORY:")
                appendLine("Steps so far:")

                plan.steps.sortedBy { it.order }.forEach { step ->
                    when (step.status) {
                        StepStatus.DONE -> {
                            val output = step.output?.output ?: "No output"
                            appendLine("- Step ${step.order}: ${step.name} → DONE")
                            appendLine("  Output: ${output.lines().firstOrNull()?.take(200) ?: "Empty"}")
                        }

                        StepStatus.FAILED -> {
                            val errorOutput = step.output as? ToolResult.Error
                            val errorMessage = errorOutput?.errorMessage ?: "Unknown error"
                            val fullOutput = step.output?.output ?: "No output"
                            appendLine("- Step ${step.order}: ${step.name} → FAILED")
                            appendLine("  Error: $errorMessage")
                            appendLine("  Output: ${fullOutput.lines().firstOrNull()?.take(200) ?: "Empty"}")
                        }

                        StepStatus.PENDING -> {
                            appendLine("- Step ${step.order}: ${step.name} → PENDING")
                            appendLine("  Task: ${step.taskDescription}")
                        }
                    }
                }

                if (plan.contextSummary?.isNotBlank() == true) {
                    appendLine()
                    appendLine("CONTEXT SUMMARY (from previous steps):")
                    appendLine(plan.contextSummary)
                }

                appendLine()
                val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
                val lastFailedStep = failedSteps.maxByOrNull { it.order }

                appendLine("PLANNER INSTRUCTION:")
                if (lastFailedStep != null) {
                    appendLine("The previous plan failed at Step ${lastFailedStep.order} (${lastFailedStep.name}).")
                    appendLine("You MUST create a NEW plan that:")
                    appendLine("1. Keeps all completed steps unchanged.")
                    appendLine("2. Replaces the failed step ${lastFailedStep.order} and ALL following steps.")
                    appendLine("3. Uses different strategies/tools from that point onward.")
                } else {
                    appendLine("The current plan is incomplete or partially failed.")
                    appendLine("Please create a NEW full plan that:")
                    appendLine("1. Preserves all completed steps.")
                    appendLine("2. Fixes or replaces failed steps using alternative strategies.")
                    appendLine("3. Completes the original user goal: '${plan.englishQuestion}'")
                }
            } else {
                appendLine()
                appendLine("PLANNER INSTRUCTION:")
                appendLine("Please create a NEW plan from scratch to fulfill the user’s goal above.")
                appendLine("Split the task into a minimal sequence of steps using available tools.")
            }
        }

    private fun parseAnswerToSteps(
        inputJson: String,
        planId: ObjectId,
        contextId: ObjectId,
    ): List<PlanStep> {
        require(inputJson.isNotBlank()) {
            "Planner expected a pure JSON array of steps, but got empty output. Adjust the prompt."
        }

        val cleanedJson = extractJsonFromResponse(inputJson)
        val dtos = parseJsonToStepDtos(cleanedJson)
        require(dtos.isNotEmpty()) { "Planner expected a non-empty JSON array of steps." }

        return dtos.mapIndexed { idx, dto ->
            require(dto.name.isNotBlank()) {
                "Invalid step at index $idx: 'name' must be non-empty."
            }
            PlanStep(
                id = ObjectId.get(),
                name = dto.name.trim(),
                taskDescription = dto.taskDescription ?: "",
                order = idx + 1,
                planId = planId,
                contextId = contextId,
                status = StepStatus.PENDING,
                output = null,
            )
        }
    }

    /**
     * Parses cleaned JSON to list of PlannerStepDto.
     * Handles both array format and single object format (wraps single object in array).
     */
    private fun parseJsonToStepDtos(cleanedJson: String): List<PlannerStepDto> {
        // Try to parse as array first (expected format)
        val arrayResult =
            runCatching {
                json.decodeFromString<List<PlannerStepDto>>(cleanedJson)
            }

        if (arrayResult.isSuccess) {
            return arrayResult.getOrThrow()
        }

        val arrayError = arrayResult.exceptionOrNull()
        logger.debug { "PLANNER_ARRAY_PARSE_FAILED: $arrayError, trying single object" }

        // Try to parse as single object and wrap in array
        val singleObjectResult =
            runCatching {
                val singleDto = json.decodeFromString<PlannerStepDto>(cleanedJson)
                listOf(singleDto)
            }

        if (singleObjectResult.isSuccess) {
            return singleObjectResult.getOrThrow()
        }

        throw IllegalArgumentException(
            "Parse error: ${arrayError?.message}\nJSON input: $cleanedJson",
            arrayError,
        )
    }

    /**
     * Extracts JSON content from various response formats including:
     * - Plain JSON arrays
     * - JSON wrapped in code fences (```json ... ```)
     * - JSON with markdown formatting
     * - Mixed content with JSON embedded
     */
    private fun extractJsonFromResponse(response: String): String {
        val trimmedResponse = response.trim()

        // If it already starts with '[' or '{', assume it's clean JSON
        if (trimmedResponse.startsWith('[') || trimmedResponse.startsWith('{')) {
            return trimmedResponse
        }

        // Try to extract from code fences (```json ... ```)
        val codeFencePattern = Regex("```(?:json)?\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        codeFencePattern.find(trimmedResponse)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Try to find JSON array or object in the text
        val jsonArrayPattern = Regex("(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
        jsonArrayPattern.find(trimmedResponse)?.let { match ->
            return match.groupValues[1]
        }

        val jsonObjectPattern = Regex("(\\{.*?\\})", RegexOption.DOT_MATCHES_ALL)
        jsonObjectPattern.find(trimmedResponse)?.let { match ->
            return match.groupValues[1]
        }
        return trimmedResponse
    }
}
