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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.reflect.typeOf

@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val modelSelector: SmartModelSelector,
    @org.springframework.context.annotation.Lazy private val visionAgent: VisionAnalysisAgent,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
    private val tikaTextExtractionService: TikaTextExtractionService,
) {
    private val logger = KotlinLogging.logger {}
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val indexingStrategyType = typeOf<IndexingGoapState>()

    fun cancel(sessionKey: String) {
        val job = activeJobs.remove(sessionKey)
        if (job != null) {
            logger.info { "QUALIFIER_CANCEL | sessionKey=$sessionKey | Cancelling active job" }
            job.cancel()
        }
    }

    suspend fun run(
        task: TaskDocument,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): String {
        val clientIdStr = task.clientId.toString()
        val projectIdStr = task.projectId?.toString().orEmpty()
        val sessionKey = if (projectIdStr.isBlank()) clientIdStr else "$clientIdStr:$projectIdStr"
        val currentJob = coroutineContext[Job]
        if (currentJob != null) {
            activeJobs[sessionKey] = currentJob
        }

        try {
            val visionResult = visionAgent.analyze(task.attachments, task.clientId)

            val cleanedContent =
                tikaTextExtractionService.extractPlainText(
                    content = task.content,
                    fileName = "chat-${task.correlationId}.txt",
                )
            val attachmentsText =
                if (visionResult.descriptions.isNotEmpty()) {
                    visionResult.descriptions.map { "- ${it.filename}: ${it.description}" }
                        .joinToString(
                            prefix = "\n\nAttachments:\n",
                            separator = "\n",
                        )
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
                goap<IndexingGoapState>(indexingStrategyType) {
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
                        kotlinx.coroutines.runBlocking {
                            onProgress("Analyzuji a segmentuji obsah...", mapOf("step" to "node_start", "node" to "AnalyzeAndGroup", "agent" to "qualifier"))
                        }

                        val strategyPrompt =
                            """
Analyze the following content and prepare it for structured indexing into RAG and GraphDB.

PHASE 1: Acquisition & Normalization
- Identify the Source (EMAIL, CONFLUENCE, JIRA, FILE, LINK, or CHAT).
- Pick one stable mainNodeKey for the primary document (format <type>:<id>, e.g., jira:TLIT-822, confluence:2056421411).
- Identify 'sourceUrn' (stable URI for the whole document).
- If source is Confluence, produce a 'PageBrief': goal, requirements, decisions, open questions, and outline.
- If critical information is missing or permissions are needed, prepare to set finalAction=USER_TASK.

PHASE 2: Segmentation (Block-aware)
Split the content into logical blocks while preserving hierarchy:
- SECTIONS: Use H1/H2/H3 as boundaries.
- TABLES: Treat tables as separate blocks.
- CODE: Treat code blocks as separate blocks.
- ASSETS: Treat images/attachments as blocks with their descriptions.
- EMAIL: Split into Subject, Body, Signature blocks where it makes sense.

PHASE 3: Metadata EXTRACTION (STRICT SCHEMA)
For each block, you MUST identify:
- chunkId: A stable identifier (e.g., "chunk:1", "section:intro").
- kind: ONE OF [TEXT, TABLE, CODE, PDF_TEXT, IMAGE_OCR, IMAGE_CAPTION].
- sectionPath: Full path (e.g., "Documentation/UserGuide/Installation").
- assetId: Reference to attachment if applicable.
- orderIndex: Position in the document (0, 1, 2...).

PHASE 4: Semantic Linking (GraphDB)
- Identify entities (e.g., 'user:jandamek', 'jira:TASK-1').
- Define relationships: MENTIONS, AFFECTS, DEPENDS_ON.
- EVIDENCE: Every relationship MUST link to a specific chunkId.

What to produce:
1) basicInfo: 1–2 sentences summarizing the whole input.
2) mainNodeKey: the primary document key (format <type>:<id>).
3) sourceUrn: stable URN.
4) groups: list of group texts. Each group starts with a metadata header (JSON-like: chunkId, kind, sectionPath, assetId, orderIndex) then verbatim portions.
5) finalAction: instruction for routing (DONE, LIFT_UP, USER_TASK, or NONE). DONE if just knowledge, LIFT_UP if it's a task for the main agent, USER_TASK if human input is needed, NONE if called internally as Ingest tool.
6) pageBrief: (Optional) for Confluence/Docs: goals, outline, etc.

Content:
${state.content}
                            """.trimIndent()

                        val response =
                            ctx.requestLLMStructured<GroupingResult>(strategyPrompt).getOrNull()?.data

                        if (response == null) {
                            logger.error { "QUALIFIER_ERROR: AnalyzeAndGroup failed to produce structured response" }
                            return@action state.copy(analyzed = false)
                        }

                        state.copy(
                            analyzed = true,
                            basicInfo = response.basicInfo,
                            mainNodeKey = response.mainNodeKey,
                            sourceUrn = response.sourceUrn,
                            allGroups = response.groups,
                            pendingGroups = response.groups,
                            finalAction = response.finalAction,
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
                        kotlinx.coroutines.runBlocking {
                            onProgress("Ukládám segmenty do znalostní báze...", mapOf("step" to "node_start", "node" to "IndexPendingGroups", "agent" to "qualifier"))
                        }

                        var internalNextState = state
                        while (internalNextState.pendingGroups.isNotEmpty()) {
                            val groupText = internalNextState.pendingGroups.first()

                            val indexPrompt =
                                """
Index the following block into the knowledge base (RAG + GraphDB).

Context:
- mainNodeKey (Source): ${internalNextState.mainNodeKey}
- sourceUrn: ${internalNextState.sourceUrn}
- Summary: ${internalNextState.basicInfo}

Mandatory Schema Rules (No "generic tags" allowed):
1. For each block, use 'storeKnowledge' tool:
   - kind: [TEXT/TABLE/CODE/PDF_TEXT/IMAGE_OCR/IMAGE_CAPTION]
   - sectionPath: Breadcrumb-style path (e.g., 'Project/Subproject/Section')
   - orderIndex: Index from segmentation
   - sourceUrn: Use the one provided above
   - graphRefs: Extract entities (e.g., 'user:jandamek', 'jira:TASK-1') for linking.
2. Build the document structure in GraphDB:
   - Create nodes: SourceNode (mainNodeKey), SectionNode (sectionPath), ChunkNode (chunkId), AssetNode (assetId).
   - Create edges: HAS_SECTION, HAS_CHUNK, HAS_ASSET.
   - Resolve entities against known registry.
   - Create semantic edges: MENTIONS, AFFECTS, DEPENDS_ON.
   - CRITICAL: Every relationship MUST have evidence (link to specific chunkId).
3. If this is a Confluence page, ensure PageBrief is created as a summary node in GraphDB.
                                """.trimIndent()

                            ctx.requestLLM(indexPrompt)
                            internalNextState = internalNextState.copy(pendingGroups = internalNextState.pendingGroups.drop(1))
                        }

                        internalNextState.copy(indexed = true)
                    }

                    action(
                        name = "SearchForVerify",
                        precondition = { state -> state.indexed && !state.verified && state.verifyQuery.isBlank() },
                        belief = { state -> state.copy(verifyQuery = "prepared") },
                        cost = { 1.0 },
                    ) { ctx, state ->
                        logger.info { "GOAP_ACTION: Preparing verification query" }

                        val verifyPrompt =
                            """
Create a concise verification query that can be used to retrieve the indexed content back from the knowledge base.

Rules:
- Use stable identifiers from the input when possible (mainNodeKey, ticket IDs, URLs, space/page IDs, sourceUrn).
- Keep it short and high-signal.
- Output only the query text.

Context:
- basicInfo: ${state.basicInfo}
- mainNodeKey: ${state.mainNodeKey}
- sourceUrn: ${state.sourceUrn}
                            """.trimIndent()

                        val responseContent = ctx.requestLLM(verifyPrompt).content.trim()
                        state.copy(verifyQuery = responseContent)
                    }

                    action(
                        name = "verifiIndexing",
                        precondition = { state -> state.indexed && !state.verified && state.verifyQuery.isNotBlank() },
                        belief = { state -> state.copy(verified = true, indexed = true) },
                    ) { ctx, state ->
                        logger.info { "GOAP_ACTION: Verifying indexing" }
                        kotlinx.coroutines.runBlocking {
                            onProgress("Provádím křížovou kontrolu uložených dat...", mapOf("step" to "node_start", "node" to "verifiIndexing", "agent" to "qualifier"))
                        }

                        val verifyActionPrompt =
                            """
Verify that the content has been correctly indexed into both the knowledge base and the graph.

Requirements:
- Retrieve the indexed data using the provided verifyQuery.
- Search the RAG knowledge base to verify indexed content, and query the graph to check relationships.
- Compare the retrieved data against the original input themes (basicInfo and the indexed groups).
- Ensure relationships are traversable and entities are linked as expected.
- Ensure 'sourceUrn' is correctly populated in RAG and Graph.
- If anything important is missing, set verified=false.
- If verified=false, do not attempt to re-index here; leave it for the planner loop to re-run indexing.

Inputs:
- verifyQuery: ${state.verifyQuery}
- mainNodeKey: ${state.mainNodeKey}
- sourceUrn: ${state.sourceUrn}
- basicInfo: ${state.basicInfo}
- expectedGroupCount: ${state.allGroups.size}
                            """.trimIndent()

                        val verifiedResponse = ctx.requestLLMStructured<VerifyResponse>(verifyActionPrompt).getOrNull()?.data
                        val verified = verifiedResponse?.verified == true
                        if (verified) {
                            state.copy(verified = true, indexed = true)
                        } else {
                            logger.warn { "QUALIFIER_VERIFY_FAILED | mainNodeKey=${state.mainNodeKey} | Retrying indexing" }
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
                        logger.info { "GOAP_ACTION: Executing routing decision: ${state.finalAction}" }
                        kotlinx.coroutines.runBlocking {
                            onProgress("Směruji požadavek k dalšímu zpracování...", mapOf("step" to "node_start", "node" to "ExecuteRouting", "agent" to "qualifier"))
                        }

                        if (state.finalAction.equals("NONE", ignoreCase = true)) {
                            return@action state.copy(routed = true)
                        }

                        val routingPrompt =
                            """
Execute the final action based on this instruction:
Decision: ${state.finalAction}
Summary of content: ${state.basicInfo}

If the decision is USER_TASK or LIFT_UP, provide a clear 'reason' in the tool call.
                            """.trimIndent()

                        ctx.llm.writeSession {
                            appendPrompt { user { +routingPrompt } }
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
You are a high-end Ingest & Qualification Agent.

Your goal:
- Transform raw input (EMAIL/CONFLUENCE/FILE/LINK/CHAT) into a structured machine-usable representation.
- AUTOMATICALLY handle all phases: Acquisition, Normalization, Segmentation, Asset Textification, and Storage.
- STOARGE RULE: Use 'storeKnowledge' ONLY with the specified schema fields. NEVER use generic tags or notes.
- GRAPH RULE: Create Source, Section, Chunk, and Asset nodes with explicit edges (HAS_SECTION, HAS_CHUNK, etc.).
- SÉMANTICS: Create a PageBrief (goal, requirements, decisions) and link to existing knowledge.
- EVIDENCE: All relationships (MENTIONS, AFFECTS, DEPENDS_ON) must link to a specific chunk ID.
- RECONSTRUCTION: Ensure 'orderIndex' is saved for all chunks to allow document reconstruction.

You are self-sufficient. If text is missing, use OCR/Captioning tools. If permissions are missing, save as Pending and ask user.
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
                        ai.koog.prompt.dsl.prompt("qualifier") {
                            system { +systemPrompt }
                            user { +userPrompt }
                        },
                    model =
                        modelSelector.selectModel(
                            SmartModelSelector.BaseModelTypeEnum.AGENT,
                            systemPrompt + userPrompt,
                        ),
                    maxAgentIterations = 100,
                )

            val agent =
                PlannerAIAgent(
                    promptExecutor = promptExecutorFactory.create("OLLAMA_QUALIFIER"),
                    strategy = strategy,
                    agentConfig = agentConfig,
                    toolRegistry = toolRegistry,
                )

            agent.run(IndexingGoapState(content = normalizedInput))
        } catch (e: Exception) {
            logger.error(e) { "QUALIFIER_ERROR: task=${task.id} | error=${e.message}" }
            throw e
        } finally {
            activeJobs.remove(sessionKey)
        }

        logger.info { "QUALIFIER_COMPLETE: task=${task.id}" }
        return "OK"
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
        val sourceUrn: String = "",
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
    val sourceUrn: String = "",
    val groups: List<String>,
    val finalAction: String,
    val pageBrief: String? = null,
)
