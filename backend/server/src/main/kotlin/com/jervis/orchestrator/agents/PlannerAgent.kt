package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.OrderedPlan
import com.jervis.orchestrator.model.PlanStep
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * PlannerAgent - Internal agent for task decomposition.
 *
 * Role:
 * - Break user query into ordered list of atomic steps
 * - No task IDs, no dependencies - order IS the execution order
 * - Choose executor hint for each step (aider, openhands, internal, research)
 * - If missing info, create "research" step first
 *
 * Used by Orchestrator after ContextAgent.
 *
 * Implementation: Pure Koog agent with structured output (OrderedPlan).
 */
@Component
class PlannerAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create planner agent instance.
     * Returns OrderedPlan directly via structured output - Koog serializes automatically.
     */
    suspend fun create(
        task: TaskDocument,
        context: ContextPack,
        evidence: EvidencePack? = null,
    ): AIAgent<String, OrderedPlan> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val examplePlan =
            OrderedPlan(
                steps =
                    listOf(
                        PlanStep(
                            action = "coding",
                            executor = "aider",
                            description = "Fix bug in UserService.kt line 42",
                        ),
                        PlanStep(
                            action = "verify",
                            executor = "openhands",
                            description = "Run tests to verify the fix",
                        ),
                    ),
                reasoning = "Small fix with verification",
            )

        val agentStrategy =
            strategy("Task Planning") {
                val nodePlan by
                    nodeLLMRequestStructured<OrderedPlan>(
                        examples = listOf(examplePlan),
                    ).transform { result ->
                        result
                            .getOrElse { e ->
                                throw IllegalStateException("PlannerAgent: structured output parsing failed", e)
                            }.data
                    }

                edge(nodeStart forwardTo nodePlan)
                edge(nodePlan forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("planner-agent") {
                        system(
                            """
                            You are Planner Agent. Break user query into ordered execution steps.

                            Rules:
                            1. Each step is atomic (single clear action)
                            2. Order matters - no parallel dependencies
                            3. Use executor hints:
                               - "aider": Fast surgical edits in known files
                               - "openhands": Complex debugging, build/test, unknown files
                               - "internal": RAG, GraphDB, JIRA, Confluence, Email, Slack
                               - "research": Gather more evidence before proceeding
                            4. If context shows missingInfo, create "research" step first
                            5. Always include "verify" step after "coding" steps

                            Action types:
                            - coding: Code changes
                            - verify: Run build/test
                            - rag_ingest: Ingest information into RAG and GraphDB via ingestKnowledge()
                            - rag_lookup: Search RAG via searchKnowledge()
                            - graph_query: Query GraphDB
                            - graph_update: Update GraphDB nodes/edges
                            - jira_read: Read from Jira via searchIssues(), getIssue(), getComments()
                            - jira_update: Create/update JIRA ticket
                            - confluence_read: Read from Confluence via searchPages(), getPage(), getChildren()
                            - confluence_update: Update Confluence page
                            - email_read: Read from Email via searchEmails(), getEmail(), getThread()
                            - email_send: Send email
                            - slack_post: Post to Slack
                            - research: Gather evidence via tool calls

                            Output an OrderedPlan with list of steps.
                            Each step has:
                            - action: type of action
                            - executor: who executes it
                            - description: what to do

                            Be concise and specific.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 2, // Allow minor refinement
            )

        val toolRegistry =
            ToolRegistry {
                // No tools needed - planner just thinks and outputs structured plan
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run planner agent.
     *
     * @param task TaskDocument
     * @param userQuery Original user query
     * @param context Mandatory context from ContextAgent
     * @param evidence Evidence from previous iteration (null on first run)
     * @return OrderedPlan with steps to execute
     */
    suspend fun run(
        task: TaskDocument,
        userQuery: String,
        context: ContextPack,
        evidence: EvidencePack? = null,
    ): OrderedPlan {
        logger.info { "PLANNER_AGENT_START | correlationId=${task.correlationId} | hasEvidence=${evidence != null}" }

        val promptInput =
            buildString {
                appendLine("USER_QUERY: ${task.content}")
                appendLine()
                appendLine("CONTEXT:")
                if (context.projectName != null) {
                    appendLine("  project: ${context.projectName}")
                }
                appendLine("  projectPath: ${context.projectPath}")
                appendLine("  buildCommands: ${context.buildCommands.joinToString(", ")}")
                appendLine("  testCommands: ${context.testCommands.joinToString(", ")}")
                appendLine("  environmentHints: ${context.environmentHints}")
                if (context.knownFacts.isNotEmpty()) {
                    appendLine("  knownFacts:")
                    context.knownFacts.forEach { appendLine("    - $it") }
                }
                if (context.missingInfo.isNotEmpty()) {
                    appendLine("  missingInfo:")
                    context.missingInfo.forEach { appendLine("    - $it") }
                }

                if (evidence != null && evidence.isNotEmpty()) {
                    appendLine()
                    appendLine("EVIDENCE (from previous iteration):")
                    appendLine(evidence.summary.ifBlank { evidence.combinedSummary() })
                }

                appendLine()
                appendLine("Create OrderedPlan:")
            }

        val agent = create(task, context, evidence)
        val result = agent.run(promptInput)

        logger.debug { "PLANNER_AGENT_COMPLETE | steps=${result.steps.size}" }

        return result
    }
}
