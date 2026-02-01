package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.external.IssueTrackerTool
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.PlanStep
import com.jervis.orchestrator.model.ReviewResult
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.internal.graphdb.GraphDBService
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
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val jiraService: BugTrackerService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create reviewer agent instance.
     * Returns ReviewResult directly via structured output - Koog serializes automatically.
     */
    suspend fun create(
        task: TaskDocument,
        currentIteration: Int,
        maxIterations: Int,
    ): AIAgent<String, ReviewResult> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val exampleReview =
            ReviewResult(
                complete = true,
                missingParts = emptyList(),
                violations = emptyList(),
                reasoning = "All parts of query addressed, verify step present after coding",
            )

        val agentStrategy =
            strategy("Completeness Review") {
                val nodeReview by
                    nodeLLMRequestStructured<ReviewResult>(
                        examples = listOf(exampleReview),
                    ).transform { result ->
                        val reviewResult =
                            result
                                .getOrElse { e ->
                                    throw IllegalStateException("ReviewerAgent: structured output parsing failed", e)
                                }.data

                        // Force complete at max iterations
                        if (currentIteration >= maxIterations) {
                            reviewResult.copy(complete = true)
                        } else {
                            reviewResult
                        }
                    }

                edge(nodeStart forwardTo nodeReview)
                edge(nodeReview forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
                projectId = task.projectId,
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

                            Output a ReviewResult with:
                            - complete: true if all parts addressed, false otherwise
                            - missingParts: list of what's missing (empty if complete)
                            - violations: list of security/constraint violations found
                            - reasoning: brief explanation of your decision

                            Max 2 iterations allowed. If already iterated twice, mark complete even if gaps remain.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 100, // Allow multiple review/fix cycles if needed
            )

        val toolRegistry =
            ToolRegistry {
                // Reviewer can use tools to verify results in tracker or knowledge base
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))
                tools(
                    IssueTrackerTool(
                        task,
                        jiraService,
                        com.jervis.orchestrator.bugtracker
                            .BugTrackerAdapter(jiraService),
                    ),
                )
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
}
