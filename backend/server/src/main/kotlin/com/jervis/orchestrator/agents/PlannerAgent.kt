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
 * PlannerAgent - Internal agent for task decomposition and management.
 *
 * Role:
 * - Break user query into ordered list of atomic steps
 * - Manage plan lifecycle: creation, execution tracking, and adaptive refinement
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
        existingPlan: OrderedPlan? = null,
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
            strategy<String, OrderedPlan>("Task Planning") {
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
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
                projectId = task.projectId
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("planner-agent") {
                        system(
                            """
                            You are Planner Agent. Break user query into ordered execution steps or refine an existing plan.

                            Your main objective is to satisfy the NormalizedRequest goal.

                            PLAN MANAGEMENT:
                            1. INITIAL PLANNING: If no plan exists, create a complete sequence of steps to satisfy the goal.
                            2. ADAPTIVE REFINEMENT: If a plan already exists but evidence shows it needs changes, output a REFINED plan.
                            3. COMPLETION TRACKING: Ensure all steps lead to the final goal. Mark progress in reasoning.

                            CRITICAL REQUEST TYPE RULES:
                            ❌ If request type is ADVICE → NEVER create 'coding' actions (use search/analysis only)
                            ❌ If request type is CODE_ANALYSIS → NEVER create 'coding' actions (read-only operations)
                            ❌ If request type is MESSAGE_DRAFT → NEVER create 'coding' actions
                            ✅ Only create 'coding' actions for CODE_CHANGE or EPIC request types

                            SEARCH STRATEGY HIERARCHY (for information gathering):
                            1. FIRST: Search in indexed knowledge base (emails, documents, Confluence, code) - FREE
                            2. SECOND: Explore code structure via graph relationships - FREE
                            3. THIRD: Deep static code analysis - FREE
                            4. LAST RESORT: Paid coding/API services - only if free options insufficient

                            For CODE_ANALYSIS: Plan free search/analysis steps BEFORE considering paid services
                            For ADVICE (simple questions): ALWAYS start with knowledge base search!

                            SEARCH QUERY GUIDELINES:
                            - Make step descriptions BROAD and COMPREHENSIVE to find all relevant information in ONE step
                            - Include synonyms, full terms, related concepts in description
                            - Example: "najdi NTB na Alze" → action step description: "Search knowledge base for NTB/notebook/laptop purchases from Alza in emails and documents"
                            - DON'T create multiple search steps for the same topic - ONE comprehensive search is better
                            - The knowledge base search covers all sources simultaneously

                            Rules:
                            1. Each step is atomic (single clear action)
                            2. Order matters - no parallel dependencies
                            3. WORKFLOW COMPLIANCE:
                               - Before planning any 'coding' action, you MUST ensure the work item is in a 'Ready' state.
                               - If an item is NOT ready (e.g. in DRAFT), you MUST NOT plan coding.
                               - Instead, plan to add a comment to the tracker explaining why you can't proceed.
                            4. Use executor hints:
                               - "aider": Fast surgical edits in known files
                               - "openhands": Complex debugging, build/test, unknown files
                               - "internal": RAG, GraphDB, JIRA, Confluence, Email, Slack
                               - "research": Gather more evidence before proceeding
                            5. SEARCH FIRST: If the user asks about existing data, logic, status, purchases, emails, documents, ALWAYS start with knowledge base search steps.
                            6. CROSS-REFERENCE: If a task involves multiple sources, plan steps to gather evidence from all sources.
                            7. If context shows missingInfo, create knowledge search steps first
                            8. Always include "verify" step after "coding" steps
                            9. PAUSE POLICY: If you reach a point where user input or approval is MANDATORY, the orchestrator will handle the pause.

                            Action type categories (high-level descriptions for plan steps):
                            - search: Search indexed data (documents, emails, Confluence, code)
                            - codeAnalysis: Analyze code structure and relationships
                            - staticAnalysis: Deep static code analysis
                            - coding: Code modification (PAID - check budget!)
                            - verify: Run tests/builds
                            - ingest: Index new information
                            - issueTracking: Work item operations (read, update, search)
                            - documentation: Documentation operations (read, update, search)
                            - userInteraction: Request user input/confirmation

                            These are PLAN ACTION CATEGORIES, not specific tools.
                            Executor will select appropriate tools from registry based on these action types.

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
                maxAgentIterations = 100, // Allow more refinement for complex tasks
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
     * @param existingPlan Current plan if refining
     * @return OrderedPlan with steps to execute
     */
    suspend fun run(
        task: TaskDocument,
        userQuery: String,
        context: ContextPack,
        evidence: EvidencePack? = null,
        existingPlan: OrderedPlan? = null,
    ): OrderedPlan {
        logger.info { "PLANNER_AGENT_START | correlationId=${task.correlationId} | hasEvidence=${evidence != null} | refiningPlan=${existingPlan != null}" }

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

                if (existingPlan != null) {
                    appendLine()
                    appendLine("CURRENT_PLAN:")
                    existingPlan.steps.forEachIndexed { idx, step ->
                        appendLine("  ${idx + 1}. [${step.action}/${step.executor}] ${step.description}")
                    }
                }

                if (evidence != null && evidence.isNotEmpty()) {
                    appendLine()
                    appendLine("EVIDENCE (from previous iteration):")
                    appendLine(evidence.summary.ifBlank { evidence.combinedSummary() })
                }

                appendLine()
                if (existingPlan == null) {
                    appendLine("Create OrderedPlan:")
                } else {
                    appendLine("Evaluate and refine OrderedPlan based on evidence:")
                }
            }

        val agent = create(task, context, evidence, existingPlan)
        val result = agent.run(promptInput)

        logger.debug { "PLANNER_AGENT_COMPLETE | steps=${result.steps.size}" }

        return result
    }
}
