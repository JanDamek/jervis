package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.feature.handler.agent.AgentClosingContext
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.planner.AIAgentPlannerStrategy
import ai.koog.agents.planner.PlannerAIAgent
import ai.koog.agents.planner.goap.goap
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
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
import com.jervis.service.text.TikaTextExtractionService
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
    private val tikaTextExtractionService: TikaTextExtractionService,
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
        val knowledgeTool =
            KnowledgeStorageTools(task, knowledgeService, graphDBService)

        val toolRegistry =
            ToolRegistry {
                tools(qualifierTool)
                tools(knowledgeTool)
            }

        val planner =
            goap<IndexingGoapState>(typeOf<IndexingGoapState>()) {
                action(
                    name = "RemoveMarkDownHtmlFromContent",
                    precondition = { state -> state.indexed.not() && state.groups.isEmpty() && state.optimization.not() },
                    belief = { state -> state.copy(optimization = true) },
                ) { _, state ->
                    logger.info { "Removing Markdown and HTML from content for optimization" }
                    state.copy(
                        optimization = true,
                        optimizedContent = tikaTextExtractionService.extractPlainText(state.content),
                    )
                }

                action(
                    name = "ApartContextToSmallGroups",
                    precondition = { state -> state.groups.isEmpty() },
                    belief = { state ->
                        state.copy(
                            groups =
                                listOf(
                                    "Objenavka je připravená k odběru",
                                    "Dodavatel oznámil dodání zboží",
                                ),
                        )
                    },
                    cost = { 2.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Splitting content to groups" }
                    val prompt =
                        """
Projdi celý vstup uživatele a najdi společné části, skupiny, grupy. 
Celý tento text podle skupin rozděl. Výtvoř z původního textu skupiny, 
tak aby tématický každá skupina mělo něco společného a obsahavala semanticky, obsahově vše co vstupní text.
                        """.trimIndent()
                    val response =
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +prompt
                                }
                            }
                            requestLLMStructured<InputGroup>()
                        }

                    state.copy(
                        groups = response.getOrNull()?.data?.groups ?: emptyList(),
                    )
                }

                action(
                    name = "IndexContent",
                    precondition = { state -> state.indexed.not() },
                    belief = { state -> state.copy(indexed = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Indexing content to RAG and Graph" }
                    val prompt =
                        """
Index the text into a knowledge base.

Instructions:
1) Split the provided text into semantic chunks (200–500 tokens each). Do not omit anything; cover the full input text.
2) For each chunk, extract relationships as triplets in the form: from|edge|to
   - Every relationship must be a valid vertex–edge–vertex triplet.
   - Use stable keys: prefer IDs/URLs/ticket IDs when present.
   - Ensure at least the primary document node (mainNodeKey) is connected to important entities (e.g., tickets, parent page, space, key people, URLs).
3) For each chunk, output:
   - chunkContent (verbatim chunk text)
   - mainNodeKey (same value as above)
   - relationships (list of "from|edge|to" triplets for that chunk. It's a list for GraphDB store. Use a simple domain names.)                                 
                        """.trimIndent()
                    ctx.llm.writeSession {
                        appendPrompt {
                            user {
                                +prompt
                            }
                        }
                        requestLLMOnlyCallingTools()
                    }
                    state.copy(indexed = true)
                }

                // Action 2: Store groups to RAG with relationships
                action(
                    name = "IndexAllSmallGroups",
                    precondition = { state -> state.groups.isNotEmpty() && state.indexed.not() },
                    belief = { state -> state.copy(indexed = true) },
                    cost = { 2.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Indexing groups to RAG and Graph" }
                    val prompt =
                        """
Index the following text into a knowledge base.

Instructions:
1) Split the provided text into semantic chunks (200–500 tokens each). Do not omit anything; cover the full input text.
2) For each chunk, extract relationships as triplets in the form: from|edge|to
   - Every relationship must be a valid vertex–edge–vertex triplet.
   - Use stable keys: prefer IDs/URLs/ticket IDs when present.
   - Ensure at least the primary document node (mainNodeKey) is connected to important entities (e.g., tickets, parent page, space, key people, URLs).
3) For each chunk, output:
   - chunkContent (verbatim chunk text)
   - mainNodeKey (same value as above)
   - relationships (list of from|edge|to triplets for that chunk)

Text to index:                                 
                        """.trimIndent()
                    state.groups.forEach { group ->
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +prompt
                                    +group
                                }
                            }
                            requestLLMOnlyCallingTools()
                        }
                    }
                    state.copy(indexed = true)
                }

                action(
                    name = "Execute routing",
                    precondition = { state -> state.indexed && !state.routed },
                    belief = { state -> state.copy(routed = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Executing routing decision" }
                    val prompt =
                        """
Execute the routing decision you determined earlier.

Available routing tools:
- routeTask(DONE): Mark task as complete (no further action)
- routeTask(LIFT_UP): Escalate to GPU agent for complex processing
- delegateLinkProcessing: For safe content-rich URLs (use carefully, read safety rules)

If you determined need for scheduled task or user task, you can optionally:
1. Use delegateLinkProcessing for relevant links (if applicable and safe)
2. Then route with DONE or LIFT_UP
                        """.trimIndent()
                    val response =
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +prompt
                                }
                            }
                            requestLLMOnlyCallingTools()
                        }

                    state.copy(routed = response is Message.Tool.Call)
                }

                action(
                    name = "VerifiIndexing",
                    precondition = { state -> state.indexed && !state.verified },
                    belief = { state -> state.copy(verified = true) },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Verifying indexing" }
                    val response =
                        ctx.llm.writeSession {
                            appendPrompt {
                                user {
                                    +"Use tool to verify indexing data is in correct format."
                                    +"If you find data in knowledgebase, RAG and Graph mar as verified."
                                }
                            }
                            requestLLMStructured<VerifyResponse>()
                        }
                    state.copy(verified = response.getOrNull()?.data?.verified == true)
                }

                goal(
                    name = "All Indexed and routed",
                    description = "All content indexed and task routed",
                    condition = { state -> state.indexed && state.routed && state.verified },
                    cost = { 1.0 },
                )
            }

        val systemPrompt =
            """
            You are a knowledge indexing specialist.

Your goal: Index ALL content completely into RAG and Graph, then route appropriately.

Workflow:
1. Analyze content, identify thematic groups, and determine routing strategy
2. Index all groups with semantic chunks and graph relationships
3. Execute routing (DONE, LIFT_UP, or create tasks if needed)

Important: Use tools to execute actions, do not explain - just do it.
            """.trimIndent()
        val userPrompt =
            """
Context for this task: ${task.content}
"Attachments:\n
                            ${
                if (visionResult.descriptions.isNotEmpty()) {
                    "${visionResult.descriptions.joinToString("\n") { "- ${it.filename}: ${it.description}" }}\n"
                } else {
                    "No attachments."
                }
            }            
            """.trimIndent()
        // Create and run the agent
        val strategy = AIAgentPlannerStrategy("qualifier-indexing-planner", planner)
        val agentConfig =
            AIAgentConfig(
                prompt =
                    prompt("qualifier") {
                        system {
                            +systemPrompt
                        }
                        user {
                            +userPrompt
                        }
                    },
                model =
                    modelSelector.selectModel(
                        SmartModelSelector.BaseModelTypeEnum.AGENT,
                        systemPrompt + userPrompt,
                    ),
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
                        onAgentCompleted { ctx: AgentCompletedContext ->
                            logger.info { "QUALIFIER_AGENT_COMPLETE: ${ctx.agentId} | task=${task.id}" }
                        }
                        onAgentClosing { ctx: AgentClosingContext ->
                            logger.info { "QUALIFIER_AGENT_CLOSING: ${ctx.agentId} | task=${task.id}" }
                        }
                        onNodeExecutionCompleted { ctx ->
                            logger.info { "NODE_EXECUTION_COMPLETE: ${ctx.context} | ${ctx.input} | ${ctx.output} | task=${task.id}" }
                        }
                    }
                },
            )

        agent.run(IndexingGoapState(content = task.content))

        logger.info { "QUALIFIER_COMPLETE: task=${task.id}" }
    }

    @Serializable
    class VerifyResponse {
        val verified: Boolean = false
    }

    /**
     * GOAP state for knowledge indexing workflow.
     * Tracks progress: analyze+plan → index groups → route
     */
    data class IndexingGoapState(
        val content: String,
        val optimization: Boolean = false,
        val optimizedContent: String = "",
        val groups: List<String> = emptyList(),
        val indexed: Boolean = false,
        val routed: Boolean = false,
        val verified: Boolean = false,
    )
}

@Serializable
data class InputGroup(
    val groups: List<String>,
)
