package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.extension.requestLLM
import ai.koog.agents.core.dsl.extension.requestLLMStructured
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

        val cleanedContent = tikaTextExtractionService.extractPlainText(task.content)
        val attachmentsText =
            if (visionResult.descriptions.isNotEmpty()) {
                visionResult.descriptions.joinToString(
                    prefix = "\n\nAttachments:\n",
                    separator = "\n",
                ) { "- ${it.filename}: ${it.description}" }
            } else {
                "\n\nAttachments:\n- none"
            }

        val normalizedInput = cleanedContent + attachmentsText

        logger.info {
            "VISION_COMPLETE | correlationId=${task.correlationId} | " +
                "contentLength=${task.content.length} | attachments=${visionResult.descriptions.size}"
        }

        val toolRegistry =
            ToolRegistry {
                tools(
                    QualifierRoutingTools(
                        task,
                        taskService,
                        linkContentService,
                        indexedLinkService,
                        connectionService,
                    ),
                )
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))
            }

        val planner =
            goap<IndexingGoapState>(typeOf<IndexingGoapState>()) {
                action(
                    name = "AnalyzeAndGroup",
                    precondition = { state -> !state.analyzed },
                    belief = { state ->
                        state.copy(
                            analyzed = true,
                            basicInfo = "prepared",
                            mainNodeKey = "prepared",
                            allGroups = listOf("prepared"),
                            pendingGroups = listOf("prepared"),
                            finalAction = "prepared",
                        )
                    },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Analyzing and grouping content" }

                    val prompt =
                        """
Analyze the following content and split it into a small number of thematic groups for indexing.

Rules:
- Every group must contain verbatim content copied from the input (do not paraphrase group content).
- The groups together must cover all input text exactly once (no omissions, no duplicates).
- Prefer fewer, larger groups.
- Keep attachments and metadata in the most relevant group (or in a separate Metadata group if shared).
- The input is already cleaned from markup/HTML; treat it as plain text.

Main node key:
- Pick one stable mainNodeKey for the primary document.
- Prefer stable IDs when present (e.g., jira:TLIT-822, confluence:2056421411, git:commit:<sha>, git:file:<path>@<sha>, url:<url>).
- This mainNodeKey will be used to link GraphDB relationships and as the primary document anchor.

What to produce:
1) basicInfo: 1–2 sentences summarizing the whole input.
2) mainNodeKey: the primary document key.
3) groups: list of group texts. Each group starts with a single title line, then contains verbatim portions of the input for that theme.
4) finalAction: a clear plain-text instruction of what should happen next (may be multi-step).

Content:
${state.content}
                        """.trimIndent()

                    val response =
                        ctx.llm.writeSession {
                            appendPrompt { user { +prompt } }
                            requestLLMStructured<GroupingResult>()
                        }

                    val data = response.getOrNull()?.data
                    state.copy(
                        analyzed = true,
                        basicInfo = data?.basicInfo.orEmpty(),
                        mainNodeKey = data?.mainNodeKey.orEmpty(),
                        allGroups = data?.groups ?: emptyList(),
                        pendingGroups = data?.groups ?: emptyList(),
                        finalAction = data?.finalAction.orEmpty(),
                    )
                }

                action(
                    name = "IndexPendingGroups",
                    precondition = { state -> state.analyzed && !state.indexed },
                    belief = { state -> state.copy(indexed = true, pendingGroups = emptyList()) },
                    cost = { state -> (state.pendingGroups.size * 0.25) + 1.0 },
                ) { ctx, state ->
                    if (state.mainNodeKey.isBlank()) {
                        return@action state.copy(indexed = false)
                    }

                    logger.info { "GOAP_ACTION: Indexing pending groups to RAG and Graph" }

                    var nextState = state
                    while (nextState.pendingGroups.isNotEmpty()) {
                        val groupText = nextState.pendingGroups.first()

                        val prompt =
                            """
Index the following text into the knowledge base.

Context:
- basicInfo: ${nextState.basicInfo}
- mainNodeKey: ${nextState.mainNodeKey}

Instructions:
1) Split the provided text into semantic chunks (200–500 tokens each). Do not omit anything; cover the full input text.
2) For each chunk, extract GraphDB relationships as triplets in the form: from|edge|to
   - Every relationship must be a valid vertex–edge–vertex triplet.
   - Use stable keys: prefer IDs/URLs/ticket IDs when present.
   - Ensure the mainNodeKey is connected to important entities (tickets, parent page, space, people, URLs, commit/file keys).
   - Use simple domain edge names, consistent across chunks.
3) Persist both semantic content and graph relationships so they can be retrieved later for verification.

Text:
$groupText
                            """.trimIndent()

                        ctx.requestLLM(prompt)
                        nextState = nextState.copy(pendingGroups = nextState.pendingGroups.drop(1))
                    }

                    nextState.copy(indexed = true)
                }

                action(
                    name = "SearchForVerify",
                    precondition = { state -> state.indexed && !state.verified && state.verifyQuery.isBlank() },
                    belief = { state -> state.copy(verifyQuery = "prepared") },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Preparing verification query" }

                    val prompt =
                        """
Create a concise verification query that can be used to retrieve the indexed content back from the knowledge base.

Rules:
- Use stable identifiers from the input when possible (mainNodeKey, ticket IDs, URLs, space/page IDs).
- Keep it short and high-signal.
- Output only the query text.

Context:
- basicInfo: ${state.basicInfo}
- mainNodeKey: ${state.mainNodeKey}
                        """.trimIndent()

                    val response = ctx.requestLLM(prompt)
                    state.copy(verifyQuery = response.content.trim())
                }

                action(
                    name = "VerifiIndexing",
                    precondition = { state -> state.indexed && !state.verified && state.verifyQuery.isNotBlank() },
                    belief = { state -> state.copy(verified = true, indexed = true) },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Verifying indexing" }

                    val prompt =
                        """
Verify that the content has been correctly indexed into both the knowledge base and the graph.

Requirements:
- Retrieve the indexed data using the provided verifyQuery.
- Compare the retrieved data against the original input themes (basicInfo and the indexed groups).
- If anything important is missing, set verified=false.
- If verified=false, do not attempt to re-index here; leave it for the planner loop to re-run indexing.

Inputs:
- verifyQuery: ${state.verifyQuery}
- mainNodeKey: ${state.mainNodeKey}
- basicInfo: ${state.basicInfo}
- expectedGroupCount: ${state.allGroups.size}
                        """.trimIndent()

                    val response = ctx.requestLLMStructured<VerifyResponse>(prompt)
                    val verified = response.getOrNull()?.data?.verified == true
                    if (verified) {
                        state.copy(verified = true, indexed = true)
                    } else {
                        state.copy(
                            verified = false,
                            indexed = false,
                            pendingGroups = state.allGroups,
                        )
                    }
                }

                action(
                    name = "ExecuteRouting",
                    precondition = { state -> state.indexed && state.verified && !state.routed },
                    belief = { state -> state.copy(routed = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Executing routing decision" }

                    val prompt =
                        """
Execute the final action based on this instruction:
${state.finalAction}
                        """.trimIndent()

                    ctx.llm.writeSession {
                        appendPrompt { user { +prompt } }
                        requestLLMOnlyCallingTools()
                    }

                    state.copy(routed = true)
                }

                goal(
                    name = "All Indexed and routed",
                    description = "All content indexed and task routed",
                    condition = { state -> state.analyzed && state.indexed && state.verified && state.routed },
                    cost = { 1.0 },
                )
            }

        val systemPrompt =
            """
You are a knowledge indexing specialist.

Your goal:
- Index ALL content completely into semantic storage and graph storage.
- Ensure the primary document key (mainNodeKey) connects to important entities (tickets, pages, spaces, people, URLs, commits/files).
- After indexing, verify the content can be retrieved and matches the source, then execute routing.

Keep outputs simple and deterministic. Prefer stable identifiers when available.
            """.trimIndent()

        val userPrompt =
            """
Context for this task:
$normalizedInput
            """.trimIndent()

        val strategy = AIAgentPlannerStrategy("qualifier-indexing-planner", planner)

        val agentConfig =
            AIAgentConfig(
                prompt =
                    prompt("qualifier") {
                        system { +systemPrompt }
                        user { +userPrompt }
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

        agent.run(IndexingGoapState(content = normalizedInput))

        logger.info { "QUALIFIER_COMPLETE: task=${task.id}" }
    }

    @Serializable
    data class VerifyResponse(
        val verified: Boolean = false,
    )

    data class IndexingGoapState(
        val content: String,
        val analyzed: Boolean = false,
        val basicInfo: String = "",
        val mainNodeKey: String = "",
        val allGroups: List<String> = emptyList(),
        val pendingGroups: List<String> = emptyList(),
        val finalAction: String = "",
        val indexed: Boolean = false,
        val routed: Boolean = false,
        val verifyQuery: String = "",
        val verified: Boolean = false,
    )
}

@Serializable
data class GroupingResult(
    val basicInfo: String,
    val mainNodeKey: String,
    val groups: List<String>,
    val finalAction: String,
)
