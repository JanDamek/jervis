package com.jervis.orchestrator

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.CommunicationEvent
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.transport.ClientCallContext
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import ai.koog.agents.a2a.client.feature.A2AAgentClient
import ai.koog.agents.a2a.client.feature.A2AClientRequest
import ai.koog.agents.a2a.client.feature.nodeA2AClientSendMessage
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
import com.jervis.common.dto.TaskParams
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.KnowledgeStorageTools
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID
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
 * - Coding delegation via Koog A2A (routing is driven by Koog Structured Output decision)
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
    private val endpointProperties: EndpointProperties,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val graphDBService: GraphDBService,
    private val connectionService: ConnectionService,
    private val jiraService: JiraService,
    private val confluenceService: ConfluenceService,
    private val emailService: EmailService,
    // Internal agents
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
    private val ingestAgent: IngestAgent,
    private val solutionArchitectAgent: SolutionArchitectAgent,
    private val ktorClientFactory: com.jervis.configuration.KtorClientFactory,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val A2A_AGENT_AIDER = "aider"
        private const val A2A_AGENT_OPENHANDS = "openhands"
        private const val A2A_PATH = "/a2a"
    }

    private val activeAgents = ConcurrentHashMap<String, String>()

    fun isProviderInUse(providerName: String): Boolean = activeAgents.containsValue(providerName)

    /**
     * Create orchestrator agent instance.
     * Simple tool-calling loop - internal agents are tools.
     */
    suspend fun create(task: TaskDocument): AIAgent<String, String> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val aiderBaseUrl =
            endpointProperties.aider.baseUrl.ifBlank {
                error("Missing endpoints.aider.baseUrl (e.g. http://localhost:8081)")
            }
        val openHandsBaseUrl =
            endpointProperties.coding.baseUrl.ifBlank {
                error("Missing endpoints.coding.baseUrl (e.g. http://localhost:8082)")
            }

        val aiderA2aUrl = toA2aUrl(aiderBaseUrl)
        val openHandsA2aUrl = toA2aUrl(openHandsBaseUrl)

        val aiderClient = createA2AClient(aiderA2aUrl, ktorClientFactory.getHttpClient("aider"))
        val openHandsClient = createA2AClient(openHandsA2aUrl, ktorClientFactory.getHttpClient("coding"))

        runCatching { aiderClient.connect() }
            .getOrElse { throw IllegalStateException("Failed to connect A2A client for Aider at $aiderA2aUrl", it) }
        runCatching { openHandsClient.connect() }
            .getOrElse {
                throw IllegalStateException(
                    "Failed to connect A2A client for OpenHands at $openHandsA2aUrl",
                    it,
                )
            }

        val decisionExamples =
            listOf(
                OrchestratorDecision(
                    type = DecisionType.A2A,
                    agent = A2A_AGENT_AIDER,
                    payload = "Fix a small localized bug in Foo.kt and adjust unit test if needed.",
                ),
                OrchestratorDecision(
                    type = DecisionType.A2A,
                    agent = A2A_AGENT_OPENHANDS,
                    payload = "Refactor across multiple modules. Search logs, run tests, and produce a robust fix.",
                ),
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
                        DecisionType.A2A -> {
                            val agentId =
                                decision.agent
                                    ?: throw IllegalStateException("OrchestratorDecision: agent is required for type=A2A")
                            val payload =
                                decision.payload?.takeIf { it.isNotBlank() }
                                    ?: throw IllegalStateException("OrchestratorDecision: payload is required for type=A2A")

                            val params = Json.decodeFromString<TaskParams>(payload)

                            val message =
                                Message(
                                    messageId = UUID.randomUUID().toString(),
                                    role = Role.User,
                                    parts = listOf(TextPart(payload)),
                                    contextId = task.correlationId,
                                    metadata =
                                        params?.let {
                                            Json.encodeToJsonElement(
                                                TaskParams.serializer(),
                                                it,
                                            ) as? JsonObject
                                        },
                                )

                            NextAction.Delegate(
                                A2AClientRequest(
                                    agentId = agentId,
                                    callContext = ClientCallContext(),
                                    params = MessageSendParams(message = message),
                                ),
                            )
                        }

                        DecisionType.FINAL -> {
                            val answer =
                                decision.finalAnswer?.takeIf { it.isNotBlank() }
                                    ?: throw IllegalStateException("OrchestratorDecision: finalAnswer is required for type=FINAL")
                            NextAction.Final(answer)
                        }
                    }
                }

                // Native Koog A2A node
                val nodeA2ASendMessage by nodeA2AClientSendMessage()

                // Process response event
                val nodeProcessA2AResponse by node<CommunicationEvent, String> { event ->
                    val responseText =
                        when (event) {
                            is Message -> {
                                event.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
                            }

                            is Task -> {
                                val status = event.status
                                val statusText =
                                    status
                                        .message
                                        ?.parts
                                        ?.filterIsInstance<TextPart>()
                                        ?.joinToString("\n") { it.text }
                                "Task(id=${event.id}, state=${status.state})" + (statusText?.let { "\n$it" } ?: "")
                            }
                        }

                    // Append response to session
                    llm.writeSession {
                        appendPrompt {
                            system("A2A_RESPONSE:\n$responseText")
                        }
                    }

                    responseText
                }

                edge(nodeStart forwardTo nodeLLMRequest)

                // === LLM output routing ===
                // Any assistant message is followed by a structured routing decision (A2A delegate vs FINAL)
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
                        .onCondition { llm.readSession { prompt.messages.size > 50 } },
                )
                edge(nodeCompressHistory forwardTo nodeSendToolResult)

                edge(
                    (nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } },
                )

                // === After sending tool result, route again ===
                edge(
                    (nodeSendToolResult forwardTo nodeTransformToDecision)
                        .onAssistantMessage { true },
                )

                // Route based on structured decision
                edge(
                    (nodeMapNextAction forwardTo nodeA2ASendMessage)
                        .onIsInstance(NextAction.Delegate::class)
                        .transformed { it.request },
                )
                edge(
                    (nodeMapNextAction forwardTo nodeFinish)
                        .onIsInstance(NextAction.Final::class)
                        .transformed { it.answer },
                )

                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })

                // === A2A call flow ===
                edge(nodeA2ASendMessage forwardTo nodeProcessA2AResponse)
                edge(nodeProcessA2AResponse forwardTo nodeLLMRequest)
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

                            2. ARCHITECT PHASE (Mandatory for coding/verify):
                               - Before delegating any "coding" or "verify" step, you MUST call proposeTechnicalSpecification(step, context, evidence)
                               - This tool designers the technical solution and returns A2ADelegationSpec.
                               - The spec tells you WHICH agent to use (Aider vs OpenHands) and WHAT exactly to do.

                            3. EXECUTION PHASE:
                               - Execute plan steps in order using appropriate tools:
                                 * For coding/verify delegation:
                                   - MUST call proposeTechnicalSpecification() first.
                                   - Then use the resulting A2ADelegationSpec to make an A2A delegation decision.
                                   - When asked for OrchestratorDecision:
                                     - type=A2A
                                     - agent = spec.agent
                                     - payload = "FILES: " + spec.targetFiles + "\nINSTRUCTIONS: " + spec.instructions + "\nVERIFY: " + spec.verifyInstructions
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

                            4. REVIEW PHASE:
                               - Call reviewCompleteness(originalQuery, executedStepsJson, evidenceJson, iteration, maxIterations)
                               - If review.complete=false and review.extraSteps not empty:
                                 * Update plan with extraSteps
                                 * Return to EXECUTION PHASE (max 3 iterations)
                               - If review.complete=true or maxIterations reached:
                                 * Proceed to COMPOSE PHASE

                            5. COMPOSE PHASE:
                               - Build final answer from evidence
                               - Include: what was done, results, any warnings/violations
                               - Answer in user's language
                               - Be concise but complete

                            CRITICAL RULES:
                            - ALWAYS call getContext() first
                            - NEVER skip planning phase
                            - ALWAYS call proposeTechnicalSpecification() before Aider/OpenHands delegation
                            - Execute steps in exact order from plan
                            - Delegate coding/verify via Koog Structured Output decision using specification from Architect
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

                // External coding is delegated via A2A_CALL (A2AAgentClient), not exposed as tools.

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
            installFeatures = {
                install(A2AAgentClient) {
                    a2aClients =
                        mapOf(
                            A2A_AGENT_AIDER to aiderClient,
                            A2A_AGENT_OPENHANDS to openHandsClient,
                        )
                }
            },
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

    @Serializable
    @SerialName("OrchestratorDecision")
    @LLMDescription(
        "Routing decision for the orchestrator: either delegate work to an external A2A coding agent or finish with the final user answer",
    )
    private data class OrchestratorDecision(
        @property:LLMDescription("Decision type: A2A means delegate to coding agent; FINAL means return final answer to user")
        val type: DecisionType,
        @property:LLMDescription("Target A2A agent id when type=A2A. Allowed values: 'aider' or 'openhands'.")
        val agent: String? = null,
        @property:LLMDescription("Instruction payload to send to the A2A agent when type=A2A. Must be a concrete, actionable instruction.")
        val payload: String? = null,
        @property:LLMDescription("Final answer to show to the user when type=FINAL. Must be in the user's language.")
        val finalAnswer: String? = null,
    )

    @Serializable
    @SerialName("DecisionType")
    private enum class DecisionType { A2A, FINAL }

    private sealed interface NextAction {
        data class Delegate(
            val request: A2AClientRequest<MessageSendParams>,
        ) : NextAction

        data class Final(
            val answer: String,
        ) : NextAction
    }

    private fun buildDecisionPrompt(lastAssistantMessage: String): String =
        """
        Decide the NEXT orchestrator action based on the conversation so far.

        - If you need to delegate coding or verification to an external coding agent, choose type=A2A and fill:
          * agent: 'aider' for small, localized edits OR 'openhands' for complex/refactor/debug/test
          * payload: the exact instruction to execute (for verify use: 'VERIFY\n<commands>')

        - If you are ready to respond to the user, choose type=FINAL and fill finalAnswer.

        Context: last assistant message (for reference only):
        ---
        $lastAssistantMessage
        ---
        """.trimIndent()

    private fun toA2aUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return if (normalized.endsWith(A2A_PATH)) normalized else normalized + A2A_PATH
    }

    private fun createA2AClient(
        url: String,
        httpClient: io.ktor.client.HttpClient,
    ): A2AClient {
        val uri = URI(url)
        val baseUrl =
            buildString {
                append(uri.scheme)
                append("://")
                append(uri.host)
                if (uri.port != -1) {
                    append(":")
                    append(uri.port)
                }
            }
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: ""

        val transport = HttpJSONRPCClientTransport(baseUrl + path, httpClient)
        val agentCardResolver = UrlAgentCardResolver(baseUrl = baseUrl, path = "/.well-known/agent-card.json")
        return A2AClient(transport = transport, agentCardResolver = agentCardResolver)
    }
}
