package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
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
    private val promptRepository: PromptRepository,
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
        val systemPrompt =
            promptRepository
                .getSystemPrompt(McpToolType.PLANNER)
                .replace("\$toolDescriptions", allToolDescriptions)
        val userPrompt = buildUserPrompt(context, plan)
        val modelParams = promptRepository.getEffectiveModelParams(McpToolType.PLANNER)

        val llmAnswer =
            runCatching {
                llmGateway
                    .callLlm(
                        type = ModelType.PLANNER,
                        userPrompt = userPrompt,
                        systemPrompt = systemPrompt,
                        outputLanguage = "en",
                        quick = context.quick,
                        modelParams = modelParams,
                    )
            }.onFailure {
                logger.error(it) { "PLANNER_LLM_ERROR: falling back to deterministic plan" }
            }.getOrThrow()

        val stepsList =
            try {
                parseAnswerToSteps(llmAnswer.answer, plan.id, context.id)
            } catch (e: Exception) {
                logger.error(e) { "PLANNER_STEP_PARSING_FAILED: Failed to parse steps from LLM response: '${llmAnswer.answer}'" }
                // Return empty list to ensure plan has no steps, which will be logged in executor
                emptyList()
            }

        logger.info { "PLANNER_LLM_PLAN_BUILT: model=${llmAnswer.model}, steps=${stepsList.size}" }
        logger.debug { "PLANNER_FINAL_STEPS: ${stepsList.map { "Step ${it.order}: ${it.name} - ${it.taskDescription}" }}" }

        return plan.apply { this.steps = stepsList }
    }

    private fun buildSystemPrompt(toolDescriptions: String): String =
        """
You are an autonomous high-level task planner.
Your job is to break down the user's intent into a sequence of tool-based steps, using **ONLY the tools listed in the Tool List below**. Each step must describe a **focused subtask** that helps resolve the user's request. You do **not** execute any tools — you just plan.
Through these tools, you have access to everything needed to complete any task: code analysis, file operations, user interaction, documentation search, and more.

**RAG SEARCH CAPABILITIES** (via `rag-query` tool):
You have access to comprehensive project knowledge through RAG search including:
• **CODE**: Source code files with code embeddings - find implementations, patterns, examples
• **TEXT**: Documentation, README files, configs with text embeddings - API docs, explanations, guides  
• **MEETING**: Meeting transcripts from Whisper + manual notes - decisions, context, requirements
• **GIT_HISTORY**: Commit history and changes - code evolution, development timeline, change context
• **DEPENDENCY**: Raw dependency data from Joern - library usage, versions, compatibility info
• **DEPENDENCY_DESCRIPTION**: LLM-enhanced dependency explanations - what libraries do, how they work
• **CLASS_SUMMARY**: LLM-generated class/method summaries - architectural insights, code understanding
• **JOERN_ANALYSIS**: Static analysis results - security vulnerabilities, code quality, structural insights
• **NOTE**: General notes and observations - manual insights, tips, reminders
Use RAG search for any information retrieval about the project, its code, documentation, history, or decisions.
────────────────────────────
CORE PRINCIPLES:
1. **STRICT TOOL USAGE**:
   • Use ONLY tools from the Tool List below - never invent or assume tools exist
   • Each tool name must match EXACTLY as listed (case-sensitive)
   • If you need capabilities not available, plan cannot proceed
2. **NO FABRICATION**:
   • Never invent or assume facts, data, or interpretations
   • If information is needed, explicitly retrieve it using available tools
   • For user clarification, use the `user.await` tool
3. **AGENT-READY WORKFLOW**:
   • Design each step to be executed independently
   • If task is ambiguous, insert clarification step using `user.await`
   • For knowledge retrieval, use `rag-query` or appropriate search tools
4. **FOCUSED SUBTASKS**:
   • Each step does ONE logical subtask only
   • Use natural, human-readable task descriptions
   • Never include raw JSON, CLI commands, or code in descriptions
────────────────────────────
OUTPUT FORMAT:
Return a JSON array with this exact structure:
[
  {
    "name": "<exact tool name from Tool List>",
    "taskDescription": "<natural language description of what this tool should accomplish>"
  }
]
• Never wrap in code fences or markdown
• Output ONLY the JSON array
• Primary goal: gather all information needed to resolve the user's question
────────────────────────────
CRITICAL REQUIREMENTS:
✓ Use EXACT tool names from the Tool List
✓ Never guess - use `user.await` for clarification
✓ Never create tasks for non-existent tools  
✓ Return pure JSON array only
────────────────────────────
AVAILABLE TOOL LIST:
$toolDescriptions
        """.trimIndent()

    private fun buildUserPrompt(
        context: TaskContext,
        plan: Plan,
    ): String =
        buildString {
            appendLine("PRIMARY USER GOAL:")
            appendLine(plan.englishQuestion)
            appendLine()
            appendLine("CLIENT & PROJECT CONTEXT:")
            appendLine("Client description: ${context.clientDocument.description}")
            appendLine("Project description: ${context.projectDocument.description}")

            // Include previous conversation context if there are other plans
            val otherPlans = context.plans.filter { it.id != plan.id }
            if (otherPlans.isNotEmpty()) {
                appendLine()
                appendLine("PREVIOUS CONVERSATION CONTEXT:")
                appendLine("This context has previous conversations that provide important background:")

                otherPlans.sortedBy { it.createdAt }.forEach { previousPlan ->
                    appendLine()
                    appendLine("Previous Question: ${previousPlan.originalQuestion}")
                    if (previousPlan.englishQuestion.isNotBlank() && previousPlan.englishQuestion != previousPlan.originalQuestion) {
                        appendLine("English Translation: ${previousPlan.englishQuestion}")
                    }

                    if (previousPlan.contextSummary?.isNotBlank() == true) {
                        appendLine("Plan Summary: ${previousPlan.contextSummary}")
                    }

                    // Show key completed steps from previous plan
                    val completedSteps = previousPlan.steps.filter { it.status == StepStatus.DONE }
                    if (completedSteps.isNotEmpty()) {
                        appendLine("Key completed actions:")
                        completedSteps.take(3).forEach { step ->
                            // Show only first 3 to avoid overwhelming
                            val output = step.output?.output?.take(150) ?: "No output"
                            appendLine("  - ${step.name}: $output")
                        }
                        if (completedSteps.size > 3) {
                            appendLine("  ... and ${completedSteps.size - 3} more completed actions")
                        }
                    }
                }

                appendLine()
                appendLine("Use this previous context to inform your planning for the current question.")
            }

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
        logger.debug { "PLANNER_PARSE_STEPS: Processing LLM response: '$inputJson'" }

        require(inputJson.isNotBlank()) {
            logger.error { "PLANNER_EMPTY_INPUT: LLM response was empty or blank" }
            "Planner expected a pure JSON array of steps, but got empty output. Adjust the prompt."
        }

        val cleanedJson = extractJsonFromResponse(inputJson)
        logger.debug { "PLANNER_CLEANED_JSON: Extracted JSON: '$cleanedJson'" }

        val dtos = parseJsonToStepDtos(cleanedJson)
        logger.debug { "PLANNER_PARSED_DTOS: Parsed ${dtos.size} DTOs: $dtos" }

        require(dtos.isNotEmpty()) {
            logger.error { "PLANNER_NO_STEPS: Parsed DTO list was empty" }
            "Planner expected a non-empty JSON array of steps."
        }

        val steps =
            dtos.mapIndexed { idx, dto ->
                require(dto.name.isNotBlank()) {
                    logger.error { "PLANNER_INVALID_STEP: Step at index $idx has empty name: $dto" }
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

        logger.debug { "PLANNER_CREATED_STEPS: Created ${steps.size} PlanSteps with PENDING status" }
        return steps
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
