package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.domain.rag.KnowledgeSeverity
import com.jervis.domain.rag.KnowledgeType
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.knowledge.KnowledgeFragment
import com.jervis.service.knowledge.KnowledgeManagementService
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
    private val knowledgeManagementService: KnowledgeManagementService,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class NextStepRequest(
        val description: String = "",
    )

    @Serializable
    data class KnowledgeRequest(
        val query: String = "", // Search query for finding relevant knowledge
        val type: String = "ANY", // "RULE", "MEMORY", "ANY"
        val reason: String = "", // Why this knowledge is needed
    )

    @Serializable
    data class PlannerResponseDto(
        val nextSteps: List<NextStepRequest> = emptyList(),
        val knowledgeRequests: List<KnowledgeRequest> = emptyList(), // What knowledge to load for next iteration
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
                type = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL,
                responseSchema = PlannerResponseDto(),
                correlationId = plan.correlationId,
                quick = plan.quick,
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
            plan.requestedKnowledge = plannerOut.knowledgeRequests.map {
                Plan.KnowledgeRequest(
                    query = it.query,
                    type = it.type,
                    reason = it.reason,
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

        // Check if we have explicit knowledge requests from previous iteration
        val explicitRequests = plan.requestedKnowledge

        if (explicitRequests.isNotEmpty()) {
            logger.info { "Using ${explicitRequests.size} explicit knowledge requests from previous planning" }

            val allRules = mutableListOf<KnowledgeFragment>()
            val allMemories = mutableListOf<KnowledgeFragment>()

            explicitRequests.forEach { request ->
                val requestedType =
                    when (request.type.uppercase()) {
                        "RULE" -> KnowledgeType.RULE
                        "MEMORY" -> KnowledgeType.MEMORY
                        else -> null // ANY - search both
                    }

                if (requestedType == null) {
                    // Search both types
                    val results =
                        knowledgeManagementService
                            .searchKnowledge(
                                query = request.query,
                                type = null,
                                clientId = plan.clientDocument.id,
                                projectId = plan.projectDocument?.id,
                                limit = 5,
                            ).getOrNull() ?: emptyList()

                    allRules.addAll(results.filter { it.type == KnowledgeType.RULE })
                    allMemories.addAll(results.filter { it.type == KnowledgeType.MEMORY })
                } else {
                    // Search specific type
                    val results =
                        knowledgeManagementService
                            .searchKnowledge(
                                query = request.query,
                                type = requestedType,
                                clientId = plan.clientDocument.id,
                                projectId = plan.projectDocument?.id,
                                limit = 5,
                            ).getOrNull() ?: emptyList()

                    if (requestedType == KnowledgeType.RULE) {
                        allRules.addAll(results)
                    } else {
                        allMemories.addAll(results)
                    }
                }
            }

            // Deduplicate by knowledgeId
            val uniqueRules = allRules.distinctBy { it.knowledgeId }
            val uniqueMemories = allMemories.distinctBy { it.knowledgeId }

            logger.info {
                "Loaded knowledge from explicit requests: " +
                    "${uniqueRules.size} rules, ${uniqueMemories.size} memories"
            }

            return PlanKnowledge(rules = uniqueRules, memories = uniqueMemories)
        } else {
            // First iteration: automatic fallback search based on user request
            logger.info { "No explicit knowledge requests - using automatic fallback search" }

            val rules =
                knowledgeManagementService
                    .searchKnowledge(
                        query = plan.englishInstruction,
                        type = KnowledgeType.RULE,
                        clientId = plan.clientDocument.id,
                        projectId = plan.projectDocument?.id,
                        limit = 10,
                    ).getOrNull() ?: emptyList()

            val memories =
                knowledgeManagementService
                    .searchKnowledge(
                        query = plan.englishInstruction,
                        type = KnowledgeType.MEMORY,
                        clientId = plan.clientDocument.id,
                        projectId = plan.projectDocument?.id,
                        limit = 5,
                    ).getOrNull() ?: emptyList()

            logger.info { "Loaded knowledge (fallback): ${rules.size} rules, ${memories.size} memories" }

            return PlanKnowledge(rules = rules, memories = memories)
        }
    }

    /**
     * Format rules for prompt injection.
     */
    private fun formatRulesForPrompt(rules: List<KnowledgeFragment>): String {
        if (rules.isEmpty()) return "No active rules."

        return buildString {
            val bySeverity = rules.groupBy { it.severity }

            bySeverity[KnowledgeSeverity.MUST]?.let { mustRules ->
                appendLine("🔴 CRITICAL REQUIREMENTS (MUST):")
                mustRules.forEach { rule ->
                    appendLine("  - ${rule.text}")
                    if (rule.tags.isNotEmpty()) {
                        appendLine("    Tags: ${rule.tags.joinToString(", ")}")
                    }
                }
                appendLine()
            }

            bySeverity[KnowledgeSeverity.SHOULD]?.let { shouldRules ->
                appendLine("🟡 STRONG RECOMMENDATIONS (SHOULD):")
                shouldRules.forEach { rule ->
                    appendLine("  - ${rule.text}")
                    if (rule.tags.isNotEmpty()) {
                        appendLine("    Tags: ${rule.tags.joinToString(", ")}")
                    }
                }
                appendLine()
            }

            bySeverity[KnowledgeSeverity.INFO]?.let { infoRules ->
                appendLine("🔵 GUIDELINES (INFO):")
                infoRules.forEach { rule ->
                    appendLine("  - ${rule.text}")
                    if (rule.tags.isNotEmpty()) {
                        appendLine("    Tags: ${rule.tags.joinToString(", ")}")
                    }
                }
            }
        }
    }

    /**
     * Format memories for prompt injection.
     */
    private fun formatMemoriesForPrompt(memories: List<KnowledgeFragment>): String {
        if (memories.isEmpty()) return "No relevant context."

        return buildString {
            memories.forEach { memory ->
                appendLine("• ${memory.text}")
                if (memory.tags.isNotEmpty()) {
                    appendLine("  Tags: ${memory.tags.joinToString(", ")}")
                }
            }
        }
    }

    private fun buildStepsContext(
        plan: Plan,
        knowledge: PlanKnowledge,
    ): Map<String, String> =
        mapOf(
            "userRequest" to plan.englishInstruction,
            "projectDescription" to (plan.projectDocument?.description ?: "Project: ${plan.projectDocument?.name}"),
            "completedSteps" to
                plan.steps
                    .filter { it.status == StepStatusEnum.DONE }
                    .joinToString("\n") { "${it.stepToolName}:${it.toolResult}" },
            "totalSteps" to plan.steps.size.toString(),
            "questionChecklist" to plan.questionChecklist.joinToString(", "),
            // Add missing placeholders for PLANNING_CREATE_PLAN userPrompt
            "clientDescription" to (
                plan.clientDocument.description
                    ?: "Client: ${plan.clientDocument.name}"
            ),
            "previousConversations" to "", // Empty for now - could be enhanced later
            "planHistory" to "", // Empty for now - could be enhanced later
            "planContext" to buildPlanContextSummary(plan),
            "initialRagQueries" to plan.initialRagQueries.joinToString(", "),
            "knowledgeSearchToolName" to PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL.aliases.first(),
            "analysisReasoningToolName" to PromptTypeEnum.ANALYSIS_REASONING_TOOL.aliases.first(),
            // Knowledge Engine integration
            "activeRules" to formatRulesForPrompt(knowledge.rules),
            "relevantMemories" to formatMemoriesForPrompt(knowledge.memories),
        )

    /**
     * Knowledge data for a plan (rules + memories).
     */
    private data class PlanKnowledge(
        val rules: List<KnowledgeFragment>,
        val memories: List<KnowledgeFragment>,
    )
}
