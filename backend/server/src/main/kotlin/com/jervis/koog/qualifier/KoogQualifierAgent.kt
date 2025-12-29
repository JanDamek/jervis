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
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.vision.AttachmentDescription
import com.jervis.koog.vision.VisionAnalysisAgent
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import kotlinx.serialization.Serializable
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
        val visionResult = visionAgent.analyze(task.attachments, task.clientId)

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
        val knowledgeTool = KnowledgeStorageTools(task, knowledgeService, graphDBService)

        val toolRegistry =
            ToolRegistry {
                tools(qualifierTool)
                tools(knowledgeTool)
            }

        val graphStoreTool = knowledgeTool.asTools().first { it.name == "storeKnowledgeWithGraph" }
        val finalToolCall = qualifierTool.asTools().first { it.name == "routeTask" }

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
                                    +
                                        """
Analyze the following content and split it into a small number of groups for indexing.

Preprocessing (mandatory):
- First, clean the input text by removing all markup/tags and their attributes (HTML/XML/Confluence-style tags like <...>, <ac:...>, <ri:...>, etc.).
- Keep only human-readable text content, headings, list items, filenames, URLs, IDs, and metadata values.
- Preserve the original order of the text as it appears after cleaning.

Rules for groups:
- Every group must contain verbatim text copied from the cleaned text (no paraphrasing inside groups).
- The groups together must cover all cleaned text (no omissions). Do not duplicate large passages across groups.
- Prefer fewer, larger groups. Split only when themes are clearly distinct.
- Attachments and document metadata must be included in the most relevant group, or put them into one dedicated “Metadata & Attachments” group if they apply globally.

Also produce:
- mainNodeKey: the single stable key for the whole document, derived from the “Document Metadata” section (use “Document ID” if present; otherwise use the most specific available ID from that block). Keep the chosen identifier exactly as it appears in the input (do not rewrite its format).
- basicInfo: 1–2 sentence summary of the whole cleaned input.
- finalAction: write a short, concrete “next step” task description (it may include multiple follow-up activities), but do not mention tools, formats, JSON, or any framework details.

Content:
${state.taskContent}

${
                                        if (state.visionDescriptions.isNotEmpty()) {
                                            "Attachments:\n${
                                                state.visionDescriptions.joinToString("\n") { "- ${it.filename}: ${it.description}" }
                                            }\n"
                                        } else {
                                            ""
                                        }
                                    }                                        
                                        """.trimIndent()
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
                                +
                                    """
                                    Index the following text into a knowledge base.

Context:
basicInfo:
${state.basicInfo}

mainNodeKey:
${state.mainNodeKey}

Instructions:
1) Split the provided text into semantic chunks (200–500 words each). Do not omit anything; cover the full input text.
2) For each chunk, extract relationships as triplets in the form: from|edge|to
   - Every relationship must be a valid vertex–edge–vertex triplet.
   - Use stable keys: prefer IDs/URLs/ticket IDs when present.
   - Ensure at least the primary document node (mainNodeKey) is connected to important entities (e.g., tickets, parent page, space, key people, URLs).
3) For each chunk, output:
   - chunkContent (verbatim chunk text)
   - mainNodeKey (same value as above)
   - relationships (list of from|edge|to triplets for that chunk)

Text to index:
${state.groups.first()}                                    
                                    """.trimIndent()
                            }
                        }
                        requestLLMForceOneTool(graphStoreTool)
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
        val visionDescriptions: List<AttachmentDescription> = emptyList(),
        val analyzed: Boolean = false,
        val groups: List<String> = emptyList(),
        val finalAction: String? = null,
        val indexed: Boolean = false,
        val routed: Boolean = false,
        val basicInfo: String? = null,
        val mainNodeKey: String? = null,
    )
}

@Serializable
data class InputGroup(
    val groups: List<String>,
    val finalAction: String?,
)
