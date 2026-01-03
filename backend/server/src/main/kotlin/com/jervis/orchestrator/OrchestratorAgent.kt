package com.jervis.orchestrator

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.AiderCodingTool
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.OpenHandsCodingTool
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.tools.scheduler.SchedulerTools
import com.jervis.koog.tools.user.UserInteractionTools
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ResearchAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.model.*
import com.jervis.orchestrator.tools.InternalAgentTools
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * OrchestratorAgent - Main user-facing agent using pure Koog 0.6.0 patterns.
 *
 * Architecture:
 * 1. Get mandatory context (ContextAgent)
 * 2. Create execution plan (PlannerAgent)
 * 3. Execute plan steps (tool calls + coding delegation)
 * 4. Review completeness (ReviewerAgent)
 * 5. Iterate if needed or compose final answer
 *
 * Internal agents used as Tools:
 * - ContextAgent: Provide mandatory context
 * - PlannerAgent: Decompose query into ordered steps
 * - ResearchAgent: Gather evidence via tool-loop
 * - ReviewerAgent: Check completeness and create follow-ups
 *
 * External agents (A2A):
 * - AiderCodingTool: Fast surgical edits
 * - OpenHandsCodingTool: Complex debugging, build/test
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
    // Internal agents
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_ITERATIONS = 3
    }

    private val activeAgents = ConcurrentHashMap<String, String>()

    fun isProviderInUse(providerName: String): Boolean = activeAgents.containsValue(providerName)

    /**
     * Create orchestrator agent instance.
     * Simple tool-calling loop - internal agents are tools.
     */
    suspend fun create(task: TaskDocument): AIAgent<String, String> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        // Simple tool-calling loop strategy like KoogWorkflowAgent
        val agentStrategy =
            strategy("JERVIS Orchestrator") {
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()
                val nodeCompressHistory by nodeLLMCompressHistory()

                edge(nodeStart forwardTo nodeLLMRequest)

                // On assistant message → done
                edge((nodeLLMRequest forwardTo nodeFinish).onAssistantMessage { true })

                // On tool call → execute
                edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })

                // Compress history if needed
                edge(
                    (nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 50 } },
                )
                edge(nodeCompressHistory forwardTo nodeSendToolResult)

                edge(
                    (nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } },
                )

                // Loop back
                edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
            }

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

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
                                 * action=coding, executor=aider → runAiderCoding()
                                 * action=coding, executor=openhands → delegateToOpenHands()
                                 * action=verify → runVerificationWithOpenHands()
                                 * action=rag_ingest → storeInKnowledge()
                                 * action=rag_lookup → searchKnowledge()
                                 * action=graph_update → upsertNode()
                                 * action=jira_update → (JIRA tools)
                                 * action=confluence_update → (Confluence tools)
                                 * action=email_send → (Email tools)
                                 * action=slack_post → (Slack tools)
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
                            - Delegate coding to Aider/OpenHands (never edit files directly)
                            - Always verify after coding (unless plan explicitly skips it)
                            - NO git push/commit (create UserTask if needed)
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
                    ),
                )

                // Coding tools (A2A external)
                tools(AiderCodingTool(task))
                tools(OpenHandsCodingTool(task))

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
        val provider = "OLLAMA"

        logger.info {
            "🎯 ORCHESTRATOR_START | correlationId=${task.correlationId} | " +
                "clientId=${task.clientId} | projectId=${task.projectId} | " +
                "inputLength=${userInput.length}"
        }

        activeAgents[task.correlationId] = provider
        return try {
            val agent: AIAgent<String, String> = create(task)
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
}
