package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.appendNewStep
import com.jervis.domain.plan.prependNewStep
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Two-phase planner implementation as specified in requirements:
 * Phase 1: Pre-planner - Information gathering (scope.resolve, rag-query, user.await)
 * Phase 2: Final planner - Complete task execution (joern, llm, code.write, terminal, etc.)
 */
@Service
class TwoPhasePlanner(
    private val planner: Planner,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates a plan using two-phase approach:
     * 1. Detect if we have enough information for complete planning
     * 2. If not, create pre-planner steps for information gathering
     * 3. If yes, create final planner steps for complete task execution
     */
    suspend fun createPlan(
        context: TaskContext,
        originalPlan: Plan,
    ): Plan {
        logger.info("TWO_PHASE_PLANNER: Starting two-phase planning for plan ${originalPlan.id}")

        // Analyze if we have sufficient information for complete planning
        val planningAnalysis = analyzePlanningRequirements(context, originalPlan)

        return when (planningAnalysis.phase) {
            PlanningPhase.PRE_PLANNER -> {
                logger.debug("TWO_PHASE_PLANNER: Phase 1 - Creating pre-planner steps for information gathering")
                createPrePlannerSteps(context, originalPlan, planningAnalysis)
            }

            PlanningPhase.FINAL_PLANNER -> {
                logger.debug("TWO_PHASE_PLANNER: Phase 2 - Creating final planner steps for task execution")
                createFinalPlannerSteps(context, originalPlan)
            }
        }
    }

    /**
     * Analyzes the current context and plan to determine which phase is needed
     */
    private fun analyzePlanningRequirements(
        context: TaskContext,
        plan: Plan,
    ): PlanningAnalysis {
        val englishQuestion = plan.englishQuestion.lowercase()
        val contextSummary = plan.contextSummary ?: ""

        // Check if question is too vague (less than 7 words as mentioned in requirements)
        val questionWordCount = englishQuestion.split("\\s+".toRegex()).size
        if (questionWordCount < 7) {
            return PlanningAnalysis(
                phase = PlanningPhase.PRE_PLANNER,
                reason = "Question is too vague ($questionWordCount words)",
                missingInformation = listOf("user clarification needed"),
            )
        }

        // Check for missing scope information
        val needsScopeResolution =
            englishQuestion.contains("class") ||
                englishQuestion.contains("method") ||
                englishQuestion.contains("file") ||
                englishQuestion.contains("modify") ||
                englishQuestion.contains("refactor")

        if (needsScopeResolution && !contextSummary.contains("scope")) {
            return PlanningAnalysis(
                phase = PlanningPhase.PRE_PLANNER,
                reason = "Scope resolution needed for code-related task",
                missingInformation = listOf("scope resolution", "code context"),
            )
        }

        // Check if we need more context from RAG
        val needsRagQuery =
            englishQuestion.contains("how") ||
                englishQuestion.contains("what") ||
                englishQuestion.contains("why") ||
                englishQuestion.contains("documentation") ||
                englishQuestion.contains("example")

        if (needsRagQuery && !contextSummary.contains("rag-query")) {
            return PlanningAnalysis(
                phase = PlanningPhase.PRE_PLANNER,
                reason = "RAG query needed for information gathering",
                missingInformation = listOf("documentation search", "code examples"),
            )
        }

        // If we have sufficient information, proceed to final planning
        return PlanningAnalysis(
            phase = PlanningPhase.FINAL_PLANNER,
            reason = "Sufficient information available for task execution",
            missingInformation = emptyList(),
        )
    }

    /**
     * Creates pre-planner steps for information gathering
     */
    private suspend fun createPrePlannerSteps(
        context: TaskContext,
        plan: Plan,
        analysis: PlanningAnalysis,
    ): Plan {
        var updatedPlan = plan

        // Add user clarification if question is too vague
        if (analysis.reason.contains("vague")) {
            updatedPlan =
                updatedPlan.prependNewStep(
                    name = "user.await",
                    taskDescription =
                        "Ask the user to clarify what specifically should be modified or implemented. " +
                            "Request more details about the target class, method, or functionality.",
                )
        }

        // Add scope resolution if needed
        if (analysis.missingInformation.contains("scope resolution")) {
            updatedPlan =
                updatedPlan.appendNewStep(
                    name = "scope.resolve",
                    taskDescription =
                        "Resolve the scope of the code modification task. " +
                            "Identify target classes, methods, or files that need to be modified.",
                )
        }

        // Add RAG query if needed
        if (analysis.missingInformation.contains("documentation search")) {
            updatedPlan =
                updatedPlan.appendNewStep(
                    name = "rag-query",
                    taskDescription =
                        "Search for relevant documentation, examples, or existing implementations " +
                            "related to the task requirements.",
                )
        }

        // Add a final step to transition to final planning
        updatedPlan =
            updatedPlan.appendNewStep(
                name = "planner",
                taskDescription =
                    "Re-plan the task with complete information gathered from previous steps. " +
                        "Create detailed execution steps for the actual implementation.",
            )

        logger.debug("TWO_PHASE_PLANNER: Created ${updatedPlan.steps.size} pre-planner steps")
        return updatedPlan
    }

    /**
     * Creates final planner steps for complete task execution
     */
    private suspend fun createFinalPlannerSteps(
        context: TaskContext,
        plan: Plan,
    ): Plan {
        // Delegate to the existing planner for complete task execution
        // The existing planner has all the logic for creating execution steps
        val finalPlan = planner.createPlan(context, plan)

        logger.debug("TWO_PHASE_PLANNER: Created ${finalPlan.steps.size} final planner steps")
        return finalPlan
    }

    /**
     * Data class representing the analysis of planning requirements
     */
    private data class PlanningAnalysis(
        val phase: PlanningPhase,
        val reason: String,
        val missingInformation: List<String>,
    )

    /**
     * Enum representing the two phases of planning
     */
    private enum class PlanningPhase {
        PRE_PLANNER, // Information gathering phase
        FINAL_PLANNER, // Complete task execution phase
    }
}
