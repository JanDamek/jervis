package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.planner.AIAgentPlannerStrategy
import ai.koog.agents.planner.PlannerAIAgent
import ai.koog.agents.planner.goap.goap
import ai.koog.prompt.dsl.prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.vision.VisionAnalysisAgent
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.reflect.typeOf

/**
 * KoogQualifierAgent
 *
 * Goals (per Koog rules):
 * - Short, tool-agnostic prompts.
 * - Tools are scoped ONLY via subgraphs (no "tool format" instructions in prompts).
 * - LLM outputs are simple POJOs: strings and lists (no deep nesting).
 * - Routing is done via tool call inside Koog.
 */
@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val providerSelector: OllamaProviderSelector,
    private val modelSelector: SmartModelSelector,
    private val visionAgent: VisionAnalysisAgent,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(task: TaskDocument) {
        val visionResult = visionAgent.analyze(task.attachments)

        logger.info {
            "VISION_COMPLETE | correlationId=${task.correlationId} | " +
                "contentLength=${task.content.length} | attachments=${visionResult.descriptions.size}"
        }

        val qualifierTool =
            QualifierRoutingTools(
                task,
                taskService,
                linkContentService,
                indexedLinkService,
                connectionService,
            )

        val tools = qualifierTool.asTools()

        val toolRegistry =
            ToolRegistry {
                tools(qualifierTool)
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))
            }

        val planner =
            goap<IndexingGoapState>(typeOf<IndexingGoapState>()) {
                // Action 1: Analyze content and plan routing
                action(
                    name = "AnalyzeAndPlanRouting",
                    precondition = { state -> !state.analyzed },
                    belief = { state -> state.copy(analyzed = true) },
                    cost = { 2.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Analyzing content and planning routing" }

                    val response =
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +"""Analyze this content and group content into thematic groups for indexing.

Content:
${state.taskContent}

${
                                        if (state.visionDescriptions.isNotEmpty()) {
                                            "Attachments:\n${
                                                state.visionDescriptions.joinToString(
                                                    "\n",
                                                ) { "- ${it.filename}: ${it.description}" }
                                            }\n"
                                        } else {
                                            ""
                                        }
                                    }

Tasks:
1. Identify distinct thematic groups in the content
2. Determine final routing for this task:
   - DONE: Content is purely informational, indexing is sufficient
   - LIFT_UP: Requires further processing, coding, or user interaction
   - Create SCHEDULED_TASK: If follow-up action needed at specific time
   - Create USER_TASK: If requires user decision or input

Provide the thematic groups."""
                                }
                            }
                            requestLLMStructured<InputGroup>()
                        }

                    state.copy(
                        analyzed = response.isSuccess,
                        groups = response.getOrNull()?.data?.groups ?: emptyList(),
                        finalAction = response.getOrNull()?.data?.finalAction,
                    )
                }

                // Action 2: Store groups to RAG with relationships
                action(
                    name = "IndexGroups",
                    precondition = { state -> state.analyzed && !state.indexed },
                    belief = { state -> state.copy(indexed = true) },
                    cost = { 2.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Indexing groups to RAG and Graph" }

                    ctx.llm.writeSession {
                        appendPrompt {
                            user {
                                +"""Index the identified thematic groups to knowledge base.

For each group:
1. Create semantic chunks (200-500 words each)
2. Extract relationships as triplets: from|edge|to
3. Store using storeKnowledgeWithGraph tool
Content for chunking: ${state.groups.first()}
Store all groups completely."""
                            }
                        }
                        requestLLMForceOneTool(tools.first { it.name == "storeKnowledgeWithGraph" })
                    }
                    state.groups.drop(1)
                    state.copy(indexed = state.groups.isEmpty())
                }

                // Action 3: Execute routing decision
                action(
                    name = "ExecuteRouting",
                    precondition = { state -> state.indexed && !state.routed },
                    belief = { state -> state.copy(routed = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Executing routing decision" }

                    val response =
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +"""Execute the routing decision you determined earlier.

Available routing tools:
- routeTask(DONE): Mark task as complete (no further action)
- routeTask(LIFT_UP): Escalate to GPU agent for complex processing
- delegateLinkProcessing: For safe content-rich URLs (use carefully, read safety rules)

If you determined need for scheduled task or user task, you can optionally:
1. Use delegateLinkProcessing for relevant links (if applicable and safe)
2. Then route with DONE or LIFT_UP

Execute the routing now for this ${state.finalAction}"""
                                }
                            }
                            requestLLMOnlyCallingTools()
                        }

                    state.copy(routed = response.content.isEmpty())
                }

                // Goals
                goal(
                    name = "AllIndexedAndRouted",
                    description = "All content indexed and task routed",
                    condition = { state -> state.indexed && state.routed },
                )
            }

        // Create and run the agent
        val strategy = AIAgentPlannerStrategy("qualifier-indexing-planner", planner)
        val agentConfig =
            AIAgentConfig(
                prompt =
                    prompt("qualifier") {
                        system {
                            +"""You are a knowledge indexing specialist.

Your goal: Index ALL content completely into RAG and Graph, then route appropriately.

Workflow:
1. Analyze content, identify thematic groups, and determine routing strategy
2. Index all groups with semantic chunks and graph relationships
3. Execute routing (DONE, LIFT_UP, or create tasks if needed)

Important: Use tools to execute actions, do not explain - just do it."""
                        }
                    },
                model = modelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 20,
            )

        val agent =
            PlannerAIAgent(
                promptExecutor = promptExecutorFactory.getExecutor("OLLAMA"),
                strategy = strategy,
                agentConfig = agentConfig,
                toolRegistry = toolRegistry,
                installFeatures = {
                    install(feature = EventHandler) {
                        onAgentStarting { ctx: AgentStartingContext ->
                            logger.info { "QUALIFIER_AGENT_START: ${ctx.agent.id} | task=${task.id}" }
                        }
                    }
                },
            )

        val initialState =
            IndexingGoapState(
                taskContent = task.content,
                visionDescriptions = visionResult.descriptions,
            )

        agent.run(initialState)

        logger.info { "QUALIFIER_COMPLETE: task=${task.id}" }
    }

    /**
     * GOAP state for knowledge indexing workflow.
     * Tracks progress: analyze+plan → index groups → route
     */
    data class IndexingGoapState(
        val taskContent: String,
        val visionDescriptions: List<com.jervis.koog.vision.AttachmentDescription> = emptyList(),
        val analyzed: Boolean = false,
        val groups: List<String> = emptyList(),
        val finalAction: String? = null,
        val indexed: Boolean = false,
        val routed: Boolean = false,
    )
}

data class InputGroup(
    val groups: List<String>,
    val finalAction: String?,
)
