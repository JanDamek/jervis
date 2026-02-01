package com.jervis.orchestrator

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.model.Artifact
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidenceItem
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.GoalResult
import com.jervis.orchestrator.model.GoalSpec
import com.jervis.orchestrator.model.OrderedPlan
import com.jervis.orchestrator.model.RequestType
import com.jervis.orchestrator.model.ReviewResult
import com.jervis.orchestrator.prompts.NoGuessingDirectives
import com.jervis.types.ClientId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import mu.KotlinLogging
import org.springframework.stereotype.Service
import com.jervis.entity.TaskDocument as EntityTaskDocument
import com.jervis.orchestrator.model.TaskDocument as ModelTaskDocument
import com.jervis.types.ProjectId as TypeProjectId
import java.time.Instant as JavaInstant

/**
 * GoalExecutor - Executes single goal through complete lifecycle.
 * Handles: Context → Planning → Execution → Review → Result
 */
@Service
class GoalExecutor(
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val reviewerAgent: ReviewerAgent,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        private fun toEntity(model: ModelTaskDocument): EntityTaskDocument =
            EntityTaskDocument(
                id = TaskId.fromString(model.id),
                clientId = ClientId.fromString(model.clientId),
                projectId = model.projectId?.let { TypeProjectId.fromString(it) },
                type = TaskTypeEnum.valueOf(model.type),
                content = model.content,
                state = TaskStateEnum.valueOf(model.state),
                correlationId = model.correlationId,
                sourceUrn = SourceUrn(model.sourceUrn),
                createdAt = JavaInstant.parse(model.createdAt),
            )
    }

    /**
     * Execute a single goal completely
     */
    suspend fun executeGoal(
        goal: GoalSpec,
        task: ModelTaskDocument,
        previousResults: Map<String, GoalResult>,
        toolRegistry: ToolRegistry,
    ): GoalResult {
        val entityTask = toEntity(task)
        val startTime = System.currentTimeMillis()

        try {
            logger.info { "🚀 GOAL_START | id=${goal.id} | type=${goal.type} | outcome='${goal.outcome}'" }

            val context = gatherContext(goal, entityTask, previousResults)
            val plan = createPlan(goal, entityTask, context)
            val evidence = executePlan(goal, plan, entityTask, toolRegistry)
            if (shouldReview(goal)) {
                reviewExecution(goal, plan, evidence, entityTask)
            }

            val duration = System.currentTimeMillis() - startTime

            logger.info {
                "✅ GOAL_COMPLETE | id=${goal.id} | type=${goal.type} | " +
                    "success=true | duration=${duration}ms"
            }

            return GoalResult(
                goalId = goal.id,
                goalType = goal.type,
                outcome = goal.outcome,
                success = true,
                evidence = evidence,
                executedSteps = plan.steps,
                artifacts = extractArtifacts(evidence),
                duration = duration,
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ GOAL_FAILED | id=${goal.id} | type=${goal.type} | " +
                    "error=${e.message} | duration=${duration}ms"
            }

            return GoalResult(
                goalId = goal.id,
                goalType = goal.type,
                outcome = goal.outcome,
                success = false,
                evidence = null,
                executedSteps = emptyList(),
                duration = duration,
                errors = listOf(e.message ?: "Unknown error: ${e::class.simpleName}"),
            )
        }
    }

    private suspend fun gatherContext(
        goal: GoalSpec,
        task: EntityTaskDocument,
        previousResults: Map<String, GoalResult>,
    ): ContextPack {
        logger.info { "📦 CONTEXT | goalId=${goal.id}" }

        // Enrich query with context from dependent goals
        val enrichedQuery =
            if (previousResults.isNotEmpty()) {
                buildString {
                    appendLine(goal.outcome)
                    appendLine()
                    appendLine("Context from previous goals:")
                    previousResults.values.forEach { result ->
                        appendLine("- ${result.outcome}: ${result.evidence?.summary?.take(200) ?: "N/A"}")
                    }
                }
            } else {
                goal.outcome
            }

        return contextAgent.run(task, enrichedQuery)
    }

    private suspend fun createPlan(
        goal: GoalSpec,
        task: EntityTaskDocument,
        context: ContextPack,
    ): OrderedPlan {
        logger.info { "📋 PLAN | goalId=${goal.id} | type=${goal.type}" }

        // Standard planning for most goal types
        return plannerAgent.run(
            task = task,
            userQuery = goal.outcome,
            context = context,
            evidence = null,
            existingPlan = null,
        )
    }

    private suspend fun executePlan(
        goal: GoalSpec,
        plan: OrderedPlan,
        entityTask: EntityTaskDocument,
        toolRegistry: ToolRegistry,
    ): EvidencePack {
        logger.info { "⚙️ EXECUTE | goalId=${goal.id} | steps=${plan.steps.size}" }

        // Select model for goal execution
        val model =
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = goal.outcome,
                projectId = entityTask.projectId,
            )

        val prompt =
            Prompt.build("goal-executor-${goal.id}") {
                system(
                    """
You are an expert execution agent performing a specific goal: ${goal.outcome}

Goal type: ${goal.type}
Checklist: ${goal.checklist.joinToString(", ")}

TASK: Execute the plan below using available tools.

Plan steps:
${plan.steps.mapIndexed { i, step -> "${i + 1}. ${step.action}: ${step.description}" }.joinToString("\n")}

═══════════════════════════════════════════════════════════════════════════════
CRITICAL EXECUTION DIRECTIVE:
═══════════════════════════════════════════════════════════════════════════════

❌ NEVER describe what you would do
❌ NEVER say "I need to search..." or "Let me check..." or "I will..."
✅ Your FIRST response MUST be a tool call
✅ Plan steps are INSTRUCTIONS, not descriptions

EXECUTION PATTERN:
1. Read plan step
2. IMMEDIATELY call corresponding tool (no explanation first!)
3. Wait for tool result
4. Proceed to next step or summarize

EXAMPLE CORRECT BEHAVIOR:
Plan step: "Search knowledge base for NTB purchases from Alza"
YOU: [immediately calls searchKnowledgeBase("NTB notebook laptop purchase Alza email")]
TOOL RESULT: [search results]
YOU: "Nalezl jsem 3 NTB zakoupené na Alze: ..."

WRONG BEHAVIOR (NEVER DO THIS):
"I need to search for NTB purchases made on Alza. Let me first check if there are any Jira issues..."
^ This is describing a plan, NOT executing it! FORBIDDEN!

${NoGuessingDirectives.CRITICAL_RULES}

${NoGuessingDirectives.LANGUAGE_RULES}

SEARCH STRATEGY:
When searching for information:
- Use the indexed knowledge base (contains emails, documents, code, Confluence)
- Create BROAD comprehensive search queries with all relevant terms and synonyms
- One comprehensive search is better than several narrow queries
- Example good query: "NTB notebook laptop nákup purchase objednávka order Alza email 2025 2026"
- Don't use direct access to live emails if data is already indexed

ANALYSIS STRATEGY:
When analyzing code:
- First search documentation and structure
- Then use deeper static analysis if needed
- Dependency graphs can reveal connections

OUTPUT FORMAT:
After completing all steps, summarize findings in user's language:
- Be specific: names, dates, amounts, model numbers
- If nothing found, clearly state it and suggest missing sources
- If partial matches found, list them with details

ADAPTIVE WORKFLOW:
After completing your goal, VALIDATE your results:
1. Did you answer the question completely?
2. Is your information verified (not guessed)?
3. Are there gaps that need user clarification?

If validation fails OR you're blocked:
- Use askUser() tool to request clarification
- Store what you learned so far in ExecutionMemory
- The orchestrator will resume after user responds

If you discover a useful pattern or fact during execution:
- Use storeLearning() to save it for future tasks
- This helps improve performance over time

COST OPTIMIZATION:
- Knowledge base search, graph database, static analysis = FREE (prefer these!)
- Cloud coding services and APIs = PAID (use only when necessary)
                    """.trimIndent(),
                )
            }

        val executionStrategy =
            strategy<String, String>("Execute Goal ${goal.id}") {
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()

                edge(nodeStart forwardTo nodeLLMRequest)
                edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })
                edge(nodeExecuteTool forwardTo nodeSendToolResult)
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                edge((nodeLLMRequest forwardTo nodeFinish).onAssistantMessage { true })
            }

        val executionAgent =
            AIAgent(
                promptExecutor = promptExecutorFactory.getExecutor("OLLAMA"),
                toolRegistry = toolRegistry,
                strategy = executionStrategy,
                agentConfig =
                    AIAgentConfig(
                        prompt = prompt,
                        model = model,
                        maxAgentIterations = 50,
                    ),
            )

        val result = executionAgent.run(goal.outcome)

        // Convert result to EvidencePack
        return EvidencePack(
            items =
                listOf(
                    EvidenceItem(
                        content = result,
                        source = "goal-execution-${goal.id}",
                    ),
                ),
            summary = result.take(1000),
        )
    }

    private fun shouldReview(goal: GoalSpec): Boolean = goal.estimatedComplexity == "HIGH" || goal.type == RequestType.CODE_CHANGE

    private suspend fun reviewExecution(
        goal: GoalSpec,
        plan: OrderedPlan,
        evidence: EvidencePack,
        task: EntityTaskDocument,
    ): ReviewResult {
        logger.info { "🔍 REVIEW | goalId=${goal.id}" }

        return reviewerAgent.run(
            task = task,
            originalQuery = goal.outcome,
            executedSteps = plan.steps,
            evidence = evidence,
            currentIteration = 0,
            maxIterations = 10,
        )
    }

    private fun extractArtifacts(evidence: EvidencePack?): List<Artifact> {
        if (evidence == null) return emptyList()
        return emptyList()
    }
}
