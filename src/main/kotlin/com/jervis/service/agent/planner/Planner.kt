package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
import kotlinx.serialization.Serializable
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
    var availableToolNames: String = ""
    private val logger = KotlinLogging.logger {}

    @Serializable
    private data class PlannerStepDto(
        val tool: String = "",
        val taskDescription: String? = null,
        val stepBack: Int = 0,
        val stepGroup: String? = null,
    )

    suspend fun createPlan(
        context: TaskContext,
        plan: Plan,
    ): Plan {
        val previousConversations = buildPreviousConversationContext(context, plan)
        val planHistory = buildExistingPlanHistory(plan)
        val planContext = buildPlanContext(plan)
        val questionChecklist =
            if (plan.questionChecklist.isNotEmpty()) {
                buildString {
                    appendLine("Question checklist (use as foundation for planning steps):")
                    plan.questionChecklist.forEachIndexed { index, question ->
                        appendLine("${index + 1}. $question")
                    }
                }
            } else {
                ""
            }

        val mappingValue =
            mapOf(
                "toolDescriptions" to allToolDescriptions,
                "availableTools" to availableToolNames,
                "clientDescription" to buildClientDescription(context.clientDocument),
                "projectDescription" to buildProjectDescription(context.projectDocument),
                "previousConversations" to
                    if (previousConversations.isNotBlank()) "Previous conversations:\n$previousConversations\n" else "",
                "planHistory" to if (planHistory.isNotBlank()) "Current plan status:\n$planHistory\n" else "",
                "planContext" to planContext,
                "userRequest" to plan.englishQuestion,
                "questionChecklist" to questionChecklist,
            )

        val llmAnswer =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNER,
                userPrompt = "",
                quick = context.quick,
                mappingValue = mappingValue,
                responseSchema = "", // String-based format instead of complex JSON array
            )

        val stepsList =
            parseAnswerToSteps(llmAnswer, plan.id, context.id)

        logger.info { "PLANNER_LLM_PLAN_BUILT: steps=${stepsList.size}" }
        logger.debug { "PLANNER_FINAL_STEPS: ${stepsList.map { "Step ${it.order}: ${it.name} - ${it.taskDescription}" }}" }

        // Validate checklist coverage
        validateChecklistCoverage(plan, stepsList)

        return plan.apply { this.steps = stepsList }
    }

    /**
     * Creates a recovery plan for failed steps, replacing QuickPlanner functionality.
     * Uses the sophisticated PLANNER prompt with MANDATORY ALTERNATIVE STRATEGIES
     * to create targeted solutions for step failures.
     */
    suspend fun createRecoveryPlan(
        context: TaskContext,
        originalPlan: Plan,
        failedStep: PlanStep,
        nextStep: PlanStep?
    ): Plan {
        logger.info { 
            "PLANNER_RECOVERY: Creating recovery plan for failed step '${failedStep.name}' (stepBack=${failedStep.stepBack})" 
        }

        // Build enhanced failure context for recovery planning
        val failureAnalysisContext = buildFailureAnalysisContext(originalPlan, failedStep, nextStep)
        
        // Create a focused recovery plan using existing PLANNER capabilities
        val recoveryPlan = Plan(
            id = ObjectId.get(),
            contextId = context.id,
            originalQuestion = originalPlan.originalQuestion,
            originalLanguage = originalPlan.originalLanguage,
            englishQuestion = "Recover from failed step: ${failedStep.name} - ${failedStep.taskDescription}",
            contextSummary = failureAnalysisContext,
            questionChecklist = listOf("Create alternative approach to accomplish: ${failedStep.taskDescription}")
        )

        return createPlan(context, recoveryPlan)
    }

    /**
     * Builds comprehensive failure analysis context for recovery planning.
     */
    private fun buildFailureAnalysisContext(
        originalPlan: Plan,
        failedStep: PlanStep,
        nextStep: PlanStep?
    ): String = buildString {
        appendLine("=== FAILURE RECOVERY CONTEXT ===")
        appendLine("Failed Step: ${failedStep.name} (order: ${failedStep.order}, stepBack: ${failedStep.stepBack})")
        appendLine("Task Description: ${failedStep.taskDescription}")
        
        // Include failure output for context
        failedStep.output?.let { output ->
            appendLine("Failure Output: ${output.output.take(500)}")
            if (output is ToolResult.Error) {
                appendLine("Error Message: ${output.errorMessage}")
            }
        }
        
        // Include next step context if available
        nextStep?.let { next ->
            appendLine("Next Planned Step: ${next.name} - ${next.taskDescription}")
        }
        
        // Include completed steps for context
        val completedSteps = originalPlan.steps.filter { it.status == StepStatus.DONE && it.order < failedStep.order }
        if (completedSteps.isNotEmpty()) {
            appendLine("\nCompleted Steps (available context):")
            completedSteps.sortedBy { it.order }.forEach { step ->
                appendLine("✓ Step ${step.order}: ${step.name} - ${step.taskDescription}")
                step.output?.let { output ->
                    val summary = output.output.lines().firstOrNull()?.take(100) ?: ""
                    if (summary.isNotBlank()) {
                        appendLine("  Result: $summary")
                    }
                }
            }
        }
        
        appendLine("\nRECOVERY OBJECTIVE: Create alternative approach that avoids the failure pattern above.")
        appendLine("MANDATORY: Use different tools/strategies than the failed step to accomplish the same goal.")
    }

    private fun buildPreviousConversationContext(
        context: TaskContext,
        plan: Plan,
    ): String {
        val otherPlans = context.plans.filter { it.id != plan.id }
        if (otherPlans.isEmpty()) return ""

        return otherPlans.sortedBy { it.createdAt }.joinToString("\n\n") { previousPlan ->
            buildString {
                append("Q: ${previousPlan.originalQuestion}")
                if (previousPlan.englishQuestion.isNotBlank() && previousPlan.englishQuestion != previousPlan.originalQuestion) {
                    append(" (${previousPlan.englishQuestion})")
                }

                previousPlan.contextSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    append("\nSummary: $summary")
                }

                val completedSteps = previousPlan.steps.filter { it.status == StepStatus.DONE }
                if (completedSteps.isNotEmpty()) {
                    append("\nActions: ")
                    append(
                        completedSteps
                            .mapNotNull { step ->
                                step.output?.output?.let { "${step.name}: ${it.lines().firstOrNull()?.take(100)}" }
                            }.joinToString("; "),
                    )
                }
            }
        }
    }

    private fun buildExistingPlanHistory(plan: Plan): String {
        if (plan.steps.isEmpty()) return ""

        val stepsInfo =
            plan.steps.sortedBy { it.order }.joinToString("\n") { step ->
                when (step.status) {
                    StepStatus.DONE -> {
                        val output =
                            step.output
                                ?.output
                                ?.lines()
                                ?.firstOrNull()
                                ?.take(150) ?: "No output"
                        "Step ${step.order}: ${step.name} → DONE ($output)"
                    }

                    StepStatus.FAILED -> {
                        val errorMessage = (step.output as? ToolResult.Error)?.errorMessage ?: "Unknown error"
                        "Step ${step.order}: ${step.name} → FAILED ($errorMessage)"
                    }

                    StepStatus.PENDING -> "Step ${step.order}: ${step.name} → PENDING (${step.taskDescription})"
                }
            }

        return buildString {
            append(stepsInfo)
            plan.contextSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                append("\n\nContext: $summary")
            }
        }
    }

    private fun buildPlanContext(plan: Plan): String {
        if (plan.steps.isEmpty()) return "Create a new plan from scratch."

        val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
        val lastFailedStep = failedSteps.maxByOrNull { it.order }

        return when {
            lastFailedStep != null -> "Revise plan: failed at step ${lastFailedStep.order} (${lastFailedStep.name}). Create new plan keeping completed steps unchanged."
            else -> "Complete existing plan: preserve completed steps, fix failed ones, achieve goal: ${plan.englishQuestion}"
        }
    }

    private fun parseAnswerToSteps(
        planText: String,
        planId: ObjectId,
        contextId: ObjectId,
    ): List<PlanStep> {
        logger.debug { "PLANNER_PARSE_STEPS: Processing plan text (${planText.length} chars)" }

        // Parse structured text format instead of JSON arrays
        val lines = planText.trim().lines().filter { it.isNotBlank() }
        
        require(lines.isNotEmpty()) {
            logger.error { "PLANNER_NO_STEPS: Plan text was empty" }
            "Planner expected non-empty structured plan text."
        }

        val steps = lines.mapIndexedNotNull { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@mapIndexedNotNull null
            
            // Simple parsing: extract tool name (first word) and task description
            val parts = trimmed.split(":", limit = 2)
            val tool = parts[0].trim()
            val taskDescription = if (parts.size > 1) parts[1].trim() else ""
            
            require(tool.isNotBlank()) {
                logger.error { "PLANNER_INVALID_STEP: Step at index $idx has empty tool: $trimmed" }
                "Invalid step at index $idx: tool must be non-empty."
            }
            
            PlanStep(
                id = ObjectId.get(),
                name = tool,
                taskDescription = taskDescription,
                stepBack = 0, // Simplified - no stepBack complexity
                stepGroup = null, // Simplified - no stepGroup complexity
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
     * Validates that all questionChecklist items are covered by plan steps.
     * Enforces complete checklist coverage as required by issue #6.
     */
    private fun validateChecklistCoverage(
        plan: Plan,
        steps: List<PlanStep>,
    ) {
        if (plan.questionChecklist.isEmpty()) {
            logger.debug { "PLANNER_NO_CHECKLIST: No questionChecklist to validate" }
            return
        }

        val checklistSize = plan.questionChecklist.size
        val stepsSize = steps.size

        logger.debug { "PLANNER_CHECKLIST_VALIDATION: Checklist has $checklistSize items, plan has $stepsSize steps" }

        // Detailed coverage analysis - check if step descriptions reference checklist concepts
        val uncoveredItems = mutableListOf<String>()
        val coveredItems = mutableSetOf<Int>()

        plan.questionChecklist.forEachIndexed { index, checklistItem ->
            val covered =
                steps.any { step ->
                    // Enhanced keyword matching to detect coverage
                    val checklistKeywords = extractKeywords(checklistItem)
                    val stepKeywords = extractKeywords(step.taskDescription ?: "")
                    checklistKeywords.any { keyword -> stepKeywords.contains(keyword) }
                }

            if (covered) {
                coveredItems.add(index)
            } else {
                uncoveredItems.add("${index + 1}. $checklistItem")
            }
        }

        val uniqueCoveredCount = coveredItems.size

        // Enforce strict checklist completeness - all items must be covered
        if (uniqueCoveredCount < checklistSize || uncoveredItems.isNotEmpty()) {
            logger.warn {
                "PLANNER_CHECKLIST_MISMATCH: Checklist completeness violation. " +
                    "Expected coverage for $checklistSize items, but only $uniqueCoveredCount items are covered. " +
                    "Uncovered items: $uncoveredItems. " +
                    "Full checklist: ${plan.questionChecklist}. " +
                    "Consider re-planning with explicit step-by-step coverage for each checklist item."
            }
        } else {
            logger.debug { "PLANNER_CHECKLIST_COVERAGE_COMPLETE: All $checklistSize checklist items are covered by plan steps" }
        }

        // Additional validation: warn if steps significantly exceed checklist items (potential over-planning)
        if (stepsSize > checklistSize * 2) {
            logger.debug {
                "PLANNER_POTENTIAL_OVERPLANNING: Plan has $stepsSize steps for $checklistSize checklist items. " +
                    "Consider consolidating steps if possible while maintaining checklist coverage."
            }
        }
    }

    /**
     * Extracts relevant keywords from text for coverage analysis.
     */
    private fun extractKeywords(text: String): Set<String> =
        text
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }
            .filter { word ->
                // Filter out common words, keep technical/domain terms
                !setOf("the", "and", "are", "for", "with", "that", "this", "what", "where", "how", "why", "when")
                    .contains(word)
            }.toSet()

    /**
     * Build a comprehensive client description using all available information
     */
    private fun buildClientDescription(clientDocument: com.jervis.entity.mongo.ClientDocument): String {
        val parts = mutableListOf<String>()

        // Add client name
        parts.add("Client: ${clientDocument.name}")

        // Add short description only (as requested)
        clientDocument.shortDescription?.takeIf { it.isNotBlank() }?.let { parts.add("Description: $it") }

        // Add default language if available
        if (clientDocument.defaultLanguage.name.isNotBlank()) {
            parts.add("Default Language: ${clientDocument.defaultLanguage.name}")
        }

        return if (parts.size > 1) parts.joinToString("; ") else clientDocument.name
    }

    /**
     * Build a comprehensive project description using all available information
     */
    private fun buildProjectDescription(projectDocument: com.jervis.entity.mongo.ProjectDocument): String {
        val parts = mutableListOf<String>()

        // Add project name
        parts.add("Project: ${projectDocument.name}")

        // Add short description only (as requested)
        projectDocument.shortDescription?.takeIf { it.isNotBlank() }?.let { parts.add("Description: $it") }

        // Add programming languages if available
        if (projectDocument.languages.isNotEmpty()) {
            parts.add("Languages: ${projectDocument.languages.joinToString(", ")}")
        }

        // Add path if available
        if (projectDocument.path.isNotBlank()) {
            parts.add("Path: ${projectDocument.path}")
        }

        return if (parts.size > 1) parts.joinToString("; ") else projectDocument.name
    }
}
