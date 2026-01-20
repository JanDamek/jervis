package com.jervis.orchestrator

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onIsInstance
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.structure.StructuredResponse
import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.coding.CodingTools
import com.jervis.koog.tools.external.ConfluenceReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.JiraReadTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.tools.scheduler.SchedulerTools
import com.jervis.koog.tools.user.UserInteractionTools
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.IngestAgent
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ResearchAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.agents.SolutionArchitectAgent
import com.jervis.orchestrator.tools.InternalAgentTools
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.background.TaskService
import com.jervis.service.confluence.ConfluenceService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.email.EmailService
import com.jervis.service.jira.JiraService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * OrchestratorAgent - Main user-facing agent using pure Koog 0.6.0 patterns.
 *
 * Architecture:
 * 1. Get mandatory context (ContextAgent)
 * 2. Create execution plan (PlannerAgent)
 * 3. Execute plan steps (tool calls)
 * 4. Review completeness (ReviewerAgent)
 * 5. Iterate if needed or compose final answer
 *
 * Internal agents used as Tools:
 * - ContextAgent: Provide mandatory context
 * - PlannerAgent: Decompose query into ordered steps
 * - ResearchAgent: Gather evidence via tool-loop
 * - ReviewerAgent: Check completeness and create follow-ups
 *
 * All tools available:
 * - Knowledge: RAG, GraphDB
 * - Communication: Email, Slack, JIRA, Confluence
 * - System: Terminal, file operations, scheduling
 * - User interaction: UserTask creation
 *
 * Pure Koog 0.6.0 - no custom state management, only Koog nodes and edges.
 */
@Service
class OrchestratorAgent(
    private val knowledgeService: KnowledgeService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val koogProperties: KoogProperties,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val graphDBService: GraphDBService,
    private val connectionService: ConnectionService,
    private val jiraService: JiraService,
    private val confluenceService: ConfluenceService,
    private val emailService: EmailService,
    private val codingTools: CodingTools,
    // Internal agents
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
    private val ingestAgent: IngestAgent,
    private val solutionArchitectAgent: SolutionArchitectAgent,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val activeAgents = ConcurrentHashMap<String, String>()

    fun isProviderInUse(providerName: String): Boolean = activeAgents.containsValue(providerName)

    /**
     * Create orchestrator agent instance.
     * Simple tool-calling loop - internal agents are tools.
     */
    suspend fun create(task: TaskDocument): AIAgent<String, String> {
        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val decisionExamples =
            listOf(
                OrchestratorDecision(
                    type = DecisionType.FINAL,
                    finalAnswer = "Hotovo. Níže je shrnutí výsledků a další doporučení.",
                ),
            )

        val agentStrategy =
            strategy("JERVIS Orchestrator") {
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()
                val nodeCompressHistory by nodeLLMCompressHistory()

                // Ask Koog Structured Output for the next routing decision (fail-fast)
                val nodeDecideNextAction by nodeLLMRequestStructured<OrchestratorDecision>(
                    name = "decide-next-action",
                    examples = decisionExamples,
                )

                // Convert structured result into a routing output type
                val nodeMapNextAction by node<Result<StructuredResponse<OrchestratorDecision>>, NextAction> { result ->
                    val decision =
                        result
                            .getOrElse {
                                throw IllegalStateException(
                                    "Structured decision failed: ${it.message}",
                                    it,
                                )
                            }.data

                    when (decision.type) {
                        DecisionType.FINAL -> {
                            val answer =
                                decision.finalAnswer?.takeIf { it.isNotBlank() }
                                    ?: throw IllegalStateException("OrchestratorDecision: finalAnswer is required for type=FINAL")
                            NextAction.Final(answer)
                        }
                    }
                }

                edge(nodeStart forwardTo nodeLLMRequest)

                // === LLM output routing ===
                // Any assistant message is followed by a structured routing decision (FINAL)
                val nodeTransformToDecision by node<String, String> { assistantMessage ->
                    buildDecisionPrompt(assistantMessage)
                }
                edge(
                    (nodeLLMRequest forwardTo nodeTransformToDecision)
                        .onAssistantMessage { true },
                )
                edge(nodeTransformToDecision forwardTo nodeDecideNextAction)
                edge(nodeDecideNextAction forwardTo nodeMapNextAction)

                // Tool call -> execute
                edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })

                // === Tool execution routing ===
                edge(
                    (nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 50 } }
                )
                edge(nodeCompressHistory forwardTo nodeSendToolResult)

                edge(
                    (nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } }
                )

                // === After sending tool result, route again ===
                edge(
                    (nodeSendToolResult forwardTo nodeTransformToDecision)
                        .onAssistantMessage { true }
                )

                // Route based on structured decision
                edge(
                    (nodeMapNextAction forwardTo nodeFinish)
                        .onIsInstance(NextAction.Final::class)
                        .transformed { it.answer }
                )

                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
            }


        logger.info {
            "ORCHESTRATOR_CREATE | correlationId=${task.correlationId} | " +
                "model=${model.id} | contextLength=${model.contextLength}"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("orchestrator-agent") {
                        system(
                            """
                            You are JERVIS Orchestrator Agent - universal task coordinator.

                            YOUR WORKFLOW (strictly follow this order):

                            1. PLANNING PHASE:
                               - Call getContext(query) to get mandatory context
                               - Call createPlan(query, contextJson) to get OrderedPlan
                               - If plan has "research" steps, execute them via gatherEvidence()

                            2. EXECUTION PHASE:
                               - Execute plan steps in order using appropriate tools:
                                 * action=rag_ingest → ingestKnowledge()
                                 * action=rag_lookup → searchKnowledge()
                                 * action=graph_update → upsertNode()
                                 * action=jira_update → (JIRA tools)
                                 * action=confluence_update → (Confluence tools)
                                 * action=email_send → (Email tools)
                                 * action=slack_post → (Slack tools)
                                 * action=jira_read → (Jira tools)
                                 * action=confluence_read → (Confluence tools)
                                 * action=email_read → (Email tools)
                                 * action=research → gatherEvidence()
                               - Collect all results as evidence

                            3. REVIEW PHASE:
                               - Call reviewCompleteness(originalQuery, executedStepsJson, evidenceJson, iteration, maxIterations)
                               - If review.complete=false and review.extraSteps not empty:
                                 * Update plan with extraSteps
                                 * Return to EXECUTION PHASE (max 3 iterations)
                               - If review.complete=true or maxIterations reached:
                                 * Proceed to COMPOSE PHASE

                            4. COMPOSE PHASE:
                               - Build final answer from evidence
                               - Include: what was done, results, any warnings/violations
                               - Answer in user's language
                               - Be concise but complete

                            CRITICAL RULES:
                            - ALWAYS call getContext() first
                            - NEVER skip planning phase
                            - Execute steps in exact order from plan
                            - No git push/commit (create UserTask if needed)
                            - Include context (clientId, projectId) in all outputs

                            CURRENT_PHASE will be specified in subgraph prompts.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = koogProperties.maxIterations,
            )

        val toolRegistry =
            ToolRegistry {
                // Internal agent tools
                tools(
                    InternalAgentTools(
                        task,
                        contextAgent,
                        plannerAgent,
                        researchAgent,
                        reviewerAgent,
                        ingestAgent,
                        solutionArchitectAgent,
                    ),
                )

                // Knowledge tools
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))

                // System tools
                tools(
                    QualifierRoutingTools(
                        task,
                        taskService,
                        linkContentService,
                        indexedLinkService,
                        connectionService,
                    ),
                )

                // Scheduling
                tools(SchedulerTools(task, taskManagementService))

                // User interaction
                tools(UserInteractionTools(task, userTaskService))

                // Communication
                tools(CommunicationTools(task))

                // Coding tools
                tools(codingTools)

                // External read tools
                tools(JiraReadTools(task, jiraService))
                tools(ConfluenceReadTools(task, confluenceService))
                tools(EmailReadTools(task, emailService))
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run orchestrator agent.
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
    ): String {
        val startTime = System.currentTimeMillis()

        logger.info {
            "🎯 ORCHESTRATOR_START | correlationId=${task.correlationId} | " +
                "clientId=${task.clientId} | projectId=${task.projectId} | " +
                "inputLength=${userInput.length}"
        }

        return try {
            val agent: AIAgent<String, String> = create(task)
            
            val model =
                smartModelSelector.selectModel(
                    baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                    inputContent = task.content,
                )
            activeAgents[task.correlationId] = "OLLAMA"

            val output: String = agent.run(userInput)
            val duration = System.currentTimeMillis() - startTime

            logger.info {
                "✅ ORCHESTRATOR_SUCCESS | correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | outputLength=${output.length}"
            }

            output
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ ORCHESTRATOR_FAILED | correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | error=${e.message}"
            }
            throw e
        } finally {
            activeAgents.remove(task.correlationId)
        }
    }

    @Serializable
    @SerialName("OrchestratorDecision")
    @LLMDescription(
        "Routing decision for the orchestrator: finish with the final user answer",
    )
    private data class OrchestratorDecision(
        @property:LLMDescription("Decision type: FINAL means return final answer to user")
        val type: DecisionType,
        @property:LLMDescription("Final answer to show to the user when type=FINAL. Must be in the user's language.")
        val finalAnswer: String? = null,
    )

    @Serializable
    @SerialName("DecisionType")
    private enum class DecisionType { FINAL }

    private sealed interface NextAction {
        data class Final(
            val answer: String,
        ) : NextAction
    }

    private fun buildDecisionPrompt(lastAssistantMessage: String): String =
        """
        Decide the NEXT orchestrator action based on the conversation so far.

        - If you are ready to respond to the user, choose type=FINAL and fill finalAnswer.

        Context: last assistant message (for reference only):
        ---
        $lastAssistantMessage
        ---
        """.trimIndent()
}
