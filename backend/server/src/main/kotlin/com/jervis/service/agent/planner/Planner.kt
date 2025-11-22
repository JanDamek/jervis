package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.SearchRequest
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpToolRegistry
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Simplified iterative Planner that suggests next steps based on the current plan.
 * Phase 1: Planner decides WHAT information is needed (descriptive requirements only).
 * No tool selection - just pure requirements.
 */
@Service
class Planner(
    private val llmGateway: LlmGateway,
    private val mcpToolRegistry: McpToolRegistry,
    private val knowledgeService: KnowledgeService,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class NextStepRequest(
        val description: String = "",
    )

    @Serializable
    data class KnowledgeRequest(
        val query: String,
        val type: String,
    )

    @Serializable
    data class PlannerResponseDto(
        val nextSteps: List<NextStepRequest> = emptyList(),
        val knowledgeRequests: List<KnowledgeRequest> = emptyList(),
    )

    /**
     * Suggests next steps based on current plan progress.
     * Returns descriptive requirements without tool assignments.
     * Phase 1: Planner only decides WHAT information is needed.
     */
    suspend fun suggestNextSteps(plan: Plan): PlannerResponseDto {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        logger.info { "PLANNER_START: planId=${plan.id} currentSteps=$totalSteps completed=$completedSteps" }

        // Load relevant knowledge based on previous iteration's requests
        // First iteration: load based on user request
        // Subsequent iterations: load based on explicit knowledge requests
        val knowledge = loadRelevantKnowledge(plan)

        // Planner returns structured response with nextSteps and knowledgeRequests
        val parsedResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN,
                responseSchema = PlannerResponseDto(),
                correlationId = plan.correlationId,
                mappingValue = buildStepsContext(plan, knowledge),
                backgroundMode = plan.backgroundMode,
            )

        // Store think content if present
        parsedResponse.thinkContent?.let { thinkContent ->
            logger.info { "[PLANNER_THINK] Plan ${plan.id}: think content captured (${thinkContent.length} chars)" }
            plan.thinkingSequence += thinkContent
        }

        val plannerOut: PlannerResponseDto = parsedResponse.result
        logger.info {
            "[PLANNER_RESULT] planId=${plan.id} " +
                "suggestedSteps=${plannerOut.nextSteps.size} " +
                "knowledgeRequests=${plannerOut.knowledgeRequests.size}"
        }

        // Store knowledge requests in plan for next iteration
        if (plannerOut.knowledgeRequests.isNotEmpty()) {
            plan.requestedKnowledge =
                plannerOut.knowledgeRequests.map {
                    Plan.KnowledgeRequest(
                        query = it.query,
                        type = it.type,
                    )
                }
            logger.info {
                "[PLANNER_KNOWLEDGE] Plan ${plan.id} requested knowledge: " +
                    plannerOut.knowledgeRequests.joinToString(", ") { "${it.type}:${it.query}" }
            }
        }

        return plannerOut
    }

    private fun buildPlanContextSummary(plan: Plan): String {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        val failedSteps = plan.steps.count { it.status == StepStatusEnum.FAILED }
        val pendingSteps = plan.steps.count { it.status == StepStatusEnum.PENDING }

        val contextSummary =
            buildString {
                append("PLAN_CONTEXT: id=${plan.id} progress=$completedSteps/$totalSteps")
                if (failedSteps > 0) append(" failed=$failedSteps")
                if (pendingSteps > 0) append(" pending=$pendingSteps")
                appendLine()

                if (completedSteps > 0) {
                    appendLine("\nCOMPLETED_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.DONE }
                        .forEachIndexed { index, step ->
                            appendLine("\n[${index + 1}] ${step.stepToolName}: ${step.stepInstruction}")
                            step.toolResult?.let { result ->
                                appendLine(result.output)
                            }
                        }
                }

                if (failedSteps > 0) {
                    appendLine("\nFAILED_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.FAILED }
                        .forEach { step ->
                            appendLine("- ${step.stepToolName}: ${step.stepInstruction}")
                            step.toolResult?.let { result ->
                                appendLine("  ERROR: ${result.output}")
                            }
                        }
                }

                if (pendingSteps > 0) {
                    appendLine("\nPENDING_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.PENDING }
                        .forEach { step ->
                            appendLine("- ${step.stepToolName}: ${step.stepInstruction}")
                        }
                }
            }

        return contextSummary
    }

    /**
     * Load relevant knowledge for the current plan.
     *
     * First iteration: Load based on user request (automatic fallback)
     * Subsequent iterations: Load based on explicit knowledge requests from previous planning
     */
    private suspend fun loadRelevantKnowledge(plan: Plan): PlanKnowledge {
        logger.info { "Loading knowledge for plan ${plan.id}" }
        val rules = mutableListOf<String>()
        val memories = mutableListOf<String>()
        val requestedKnowledge = mutableListOf<String>()
        if (plan.requestedKnowledge.isNotEmpty()) {
            logger.info { "Using ${plan.requestedKnowledge.size} explicit knowledge requests from previous planning" }

            plan.requestedKnowledge.forEach { request ->
                requestedKnowledge.add(
                    knowledgeService
                        .search(
                            SearchRequest(
                                query = request.query,
                                clientId = plan.clientDocument.id,
                                projectId = plan.projectDocument?.id,
                                maxResults = 50,
                                knowledgeTypes = setOf(),
                            ),
                        ).text,
                )
            }
        }

        rules.add(
            knowledgeService
                .search(
                    SearchRequest(
                        query = plan.englishInstruction,
                        clientId = plan.clientDocument.id,
                        projectId = plan.projectDocument?.id,
                        maxResults = 50,
                        knowledgeTypes = setOf(KnowledgeType.RULE),
                    ),
                ).text,
        )

        memories.add(
            knowledgeService
                .search(
                    SearchRequest(
                        query = plan.englishInstruction,
                        clientId = plan.clientDocument.id,
                        projectId = plan.projectDocument?.id,
                        maxResults = 50,
                        knowledgeTypes = setOf(KnowledgeType.MEMORY),
                    ),
                ).text,
        )

        return PlanKnowledge(rules = rules, memories = memories, knowledge = requestedKnowledge)
    }

    /**
     * Format rules for prompt injection.
     */
    private fun formatForPrompt(rules: List<String>): String = rules.joinToString("\n")

    private fun buildStepsContext(
        plan: Plan,
        knowledge: PlanKnowledge,
    ): Map<String, String> =
        mapOf(
            "userRequest" to plan.englishInstruction,
            "projectDescription" to (plan.projectDocument?.description ?: "Project: ${plan.projectDocument?.name}"),
            "completedSteps" to
                plan.steps.joinToString("\n") { "${it.stepToolName}:${it.toolResult}" },
            "totalSteps" to plan.steps.size.toString(),
            "questionChecklist" to plan.questionChecklist.joinToString(", "),
            "clientDescription" to (plan.clientDocument.description ?: "Client: ${plan.clientDocument.name}"),
            "planContext" to buildPlanContextSummary(plan),
            "initialRagQueries" to plan.initialRagQueries.joinToString(", "),
            "activeRules" to formatForPrompt(knowledge.rules),
            "relevantMemories" to formatForPrompt(knowledge.memories),
            "availableTools" to mcpToolRegistry.getAllToolsPlannerDescriptions(),
        )

    /**
     * Knowledge data for a plan (rules + memories).
     */
    private data class PlanKnowledge(
        val rules: List<String>,
        val memories: List<String>,
        val knowledge: List<String>,
    )
}
