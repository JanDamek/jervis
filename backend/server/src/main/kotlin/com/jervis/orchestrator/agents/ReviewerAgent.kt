package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.*
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ReviewerAgent - Internal agent for completeness checking.
 *
 * Role:
 * - Check if executed steps cover all parts of original user query
 * - Verify security constraints (no git push, context included, etc.)
 * - Ensure verify step present after coding steps
 * - If incomplete, create extraSteps for next iteration
 *
 * Used by Orchestrator after execution phase.
 * Determines if workflow should iterate or finish.
 *
 * Implementation: Pure Koog agent with structured output (ReviewResult).
 */
@Component
class ReviewerAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create reviewer agent instance.
     * Returns ReviewResult directly - Koog serializes automatically.
     */
    suspend fun create(
        task: TaskDocument,
        currentIteration: Int,
        maxIterations: Int,
    ): AIAgent<String, ReviewResult> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy("Completeness Review") {
                val nodeLLMRequest by nodeLLMRequest()

                // Node that builds ReviewResult from LLM response
                val nodeBuildReview by node<String, ReviewResult> { llmResponse ->
                    parseReviewFromText(llmResponse, currentIteration, maxIterations)
                }

                edge(nodeStart forwardTo nodeLLMRequest)
                edge((nodeLLMRequest forwardTo nodeBuildReview).onAssistantMessage { true })
                edge(nodeBuildReview forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("reviewer-agent") {
                        system(
                            """
                            You are Reviewer Agent. Check if answer is complete and secure.

                            Your checklist:
                            1. ALL PARTS COVERED?
                               - Does evidence address every part of original query?
                               - Any user question left unanswered?

                            2. MANDATORY STEPS PRESENT?
                               - If coding step executed, is there verify step after it?
                               - If data ingested, is there confirmation?
                               - If JIRA/Confluence updated, is there ticket/page ID?

                            3. SECURITY CONSTRAINTS MET?
                               - No "git push" or "git commit" in executed steps
                               - All coding delegated to Aider/OpenHands (not direct edits)
                               - Context (clientId, projectId) included in outputs

                            4. QUALITY CHECKS?
                               - Evidence confidence high enough?
                               - No contradictory information in evidence?
                               - Execution errors properly handled?

                            Output your review clearly:

                            If INCOMPLETE or VIOLATIONS found:
                            - State "INCOMPLETE"
                            - List missing parts
                            - Suggest additional steps needed
                            - Explain violations found

                            If COMPLETE:
                            - State "COMPLETE"
                            - Summarize what was verified
                            - Confirm no violations

                            Max 2 iterations allowed. If already iterated twice, mark complete even if gaps remain.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 1, // Single review pass
            )

        val toolRegistry = ToolRegistry {
            // No tools needed - reviewer just analyzes and outputs structured result
        }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run reviewer agent.
     *
     * @param task TaskDocument
     * @param originalQuery Original user query
     * @param executedSteps Steps that were executed
     * @param evidence Evidence collected during execution
     * @param currentIteration Current iteration number (0-based)
     * @param maxIterations Maximum allowed iterations
     * @return ReviewResult determining next action
     */
    suspend fun run(
        task: TaskDocument,
        originalQuery: String,
        executedSteps: List<PlanStep>,
        evidence: EvidencePack,
        currentIteration: Int,
        maxIterations: Int,
    ): ReviewResult {
        logger.info {
            "REVIEWER_AGENT_START | correlationId=${task.correlationId} | " +
                "steps=${executedSteps.size} | iteration=$currentIteration/$maxIterations"
        }

        val agent = create(task, currentIteration, maxIterations)

        val promptInput =
            buildString {
                appendLine("ORIGINAL_QUERY: $originalQuery")
                appendLine()
                appendLine("EXECUTED_STEPS:")
                executedSteps.forEachIndexed { idx, step ->
                    appendLine("  ${idx + 1}. [${step.action}/${step.executor}] ${step.description}")
                }
                appendLine()
                appendLine("EVIDENCE_SUMMARY:")
                appendLine(evidence.summary.ifBlank { evidence.combinedSummary() })
                appendLine()
                appendLine("ITERATION: $currentIteration / $maxIterations")
                if (currentIteration >= maxIterations - 1) {
                    appendLine("⚠️ FINAL ITERATION - Must complete or explicitly state gaps")
                }
                appendLine()
                appendLine("Review completeness and security.")
            }

        val result = agent.run(promptInput)

        logger.debug { "REVIEWER_AGENT_COMPLETE | complete=${result.complete} | violations=${result.violations.size}" }

        return result
    }

    /**
     * Parse agent's text output into ReviewResult.
     * Agent analyzes completeness, we structure the decision.
     */
    private fun parseReviewFromText(
        text: String,
        currentIteration: Int,
        maxIterations: Int,
    ): ReviewResult {
        val lower = text.lowercase()

        // Check if agent says "complete" or "incomplete"
        val complete =
            when {
                currentIteration >= maxIterations -> true // Force complete at max iterations
                lower.contains("complete") && !lower.contains("incomplete") -> true
                lower.contains("incomplete") -> false
                else -> true // Default to complete if unclear
            }

        // Extract violations (simple heuristic)
        val violations = mutableListOf<String>()
        if (lower.contains("git push") || lower.contains("git commit")) {
            violations.add("Git write operations detected")
        }
        if (lower.contains("no verify") || lower.contains("missing verify")) {
            violations.add("Verify step missing after coding")
        }

        // Extract missing parts (look for bullet points or "missing:")
        val missingParts = mutableListOf<String>()
        val missingSection = text.substringAfter("missing", "").take(300)
        missingSection.lines().forEach { line ->
            if (line.trim().startsWith("-") || line.trim().startsWith("*")) {
                missingParts.add(line.trim().removePrefix("-").removePrefix("*").trim())
            }
        }

        return ReviewResult(
            complete = complete,
            missingParts = missingParts,
            violations = violations,
            reasoning = text.take(500), // First 500 chars as reasoning
            extraSteps = emptyList(), // Simplification: Planner creates new steps if needed
        )
    }
}
