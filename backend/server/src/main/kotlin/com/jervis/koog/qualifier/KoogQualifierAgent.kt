package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.structure.StructuredResponse
import com.jervis.configuration.properties.KoogProperties
import com.jervis.domain.atlassian.shouldProcessWithVision
import com.jervis.dto.TaskStateEnum
import com.jervis.entity.TaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphNode
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.IndexingContext
import com.jervis.koog.qualifier.types.VisionContext
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.content.ContentAnalysisTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.tools.scheduler.SchedulerTools
import com.jervis.rag.KnowledgeService
import com.jervis.rag.StoreChunkRequest
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeBytes

@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val graphService: GraphDBService,
    private val knowledgeService: KnowledgeService,
    private val koogProperties: KoogProperties,
    private val taskService: TaskService,
    private val ollamaProviderSelector: OllamaProviderSelector,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val directoryStructureService: DirectoryStructureService,
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    /**
     * NOTE:
     * Koog "structured request nodes" accept String input and return Result<StructuredResponse<T>>.
     * We keep a single mutable pipeline-context object per strategy instance (agent instance).
     */
    private data class PipelineCtx(
        var vision: VisionContext? = null,
        var contentType: ContentType? = null,
        var indexing: IndexingContext? = null,
        var plan: IndexingPlan? = null,
    )

    @Serializable
    private data class ContentTypeDetection(
        val contentType: String,
        val reason: String,
    )

    /**
     * LLM-produced indexing plan.
     * - Kotlin is responsible for stable IDs/keys and persistence.
     * - LLM is responsible for semantic chunk text + conceptual vertices/edges + associations.
     */
    @Serializable
    private data class IndexingPlan(
        val baseInfo: String,
        val vertices: List<PlannedVertex> = emptyList(),
        val edges: List<PlannedEdge> = emptyList(),
        val chunks: List<PlannedChunk> = emptyList(),
    )

    @Serializable
    private data class PlannedVertex(
        val alias: String,
        val type: String,
        val label: String,
    )

    @Serializable
    private data class PlannedEdge(
        val from: String,
        val to: String,
        val relation: String,
        val label: String? = null,
    )

    @Serializable
    private data class PlannedChunk(
        val content: String,
        val vertexAliases: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    fun create(task: TaskDocument): AIAgent<String, String> {
        val pipeline = PipelineCtx()

        val agentStrategy =
            strategy<String, String>("Jervis Qualifier Strategy") {
                // ============================================================
                // PHASE 0: VISION (stage 1 - general)
                // ============================================================
                val nodeVisionStage1 by node<String, VisionContext>(name = "🔍 Phase 0: Vision Stage 1") { inputText ->
                    val visualAttachments = task.attachments.filter { it.shouldProcessWithVision() }
                    logger.info {
                        "🔍 VISION_STAGE1_START | correlationId=${task.correlationId} | " +
                            "textLength=${inputText.length} | totalAttachments=${task.attachments.size} | visualAttachments=${visualAttachments.size}"
                    }

                    if (visualAttachments.isEmpty()) {
                        val ctx =
                            VisionContext(
                                originalText = inputText,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            )
                        pipeline.vision = ctx
                        return@node ctx
                    }

                    // Only images are passed to Koog "attachments.image(Path(...))"
                    val tmpFiles =
                        visualAttachments
                            .filter { it.mimeType.startsWith("image/") }
                            .mapNotNull { att ->
                                runCatching {
                                    val bytes = directoryStructureService.readAttachment(att.storagePath)
                                    val suffix =
                                        when {
                                            att.mimeType.contains("png") -> ".png"
                                            att.mimeType.contains("jpeg") || att.mimeType.contains("jpg") -> ".jpg"
                                            att.mimeType.contains("webp") -> ".webp"
                                            else -> ".png"
                                        }
                                    val tmp = Files.createTempFile("koog-vision-${att.id}-", suffix)
                                    tmp.writeBytes(bytes)
                                    att.filename to tmp
                                }.getOrNull()
                            }

                    if (tmpFiles.isEmpty()) {
                        val ctx =
                            VisionContext(
                                originalText = inputText,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            )
                        pipeline.vision = ctx
                        return@node ctx
                    }

                    try {
                        val visionContextText = buildVisionPromptContext(task = task, fullText = inputText)
                        val visionPrompt =
                            prompt("jervis-vision-stage1") {
                                system(
                                    "Describe ONLY what is visible in the attached images. Attachments may be screenshots (errors/UI), photos, charts/graphs, or scanned documents. Be factual. Do not summarize the provided text context; use it only to understand what the images relate to. If text in the image is unreadable, say so.",
                                )
                                user {
                                    markdown {
                                        +"Context (condensed):"
                                        br()
                                        +visionContextText
                                        br()
                                        br()
                                        +"Describe ONLY what is visible in the images. "
                                        +"Use the context only to understand what the images relate to. "
                                        +"If text is unreadable, say so."
                                    }
                                    attachments {
                                        tmpFiles.forEach { (_, tmpPath) ->
                                            image(Path(tmpPath.toString()))
                                        }
                                    }
                                }
                            }

                        val visionModel =
                            smartModelSelector.selectVisionModel(
                                baseModelName = "qwen3-vl:latest",
                                textPrompt = "Describe images.",
                                images =
                                    visualAttachments.map {
                                        SmartModelSelector.ImageMetadata(
                                            widthPixels = 1920,
                                            heightPixels = 1080,
                                            format = it.mimeType,
                                        )
                                    },
                                outputReserve = 1500,
                            )

                        val executor = promptExecutorFactory.getExecutor(ollamaProviderSelector.getProvider())
                        val response =
                            executor.execute(prompt = visionPrompt, model = visionModel, tools = emptyList()).first()

                        val ctx =
                            VisionContext(
                                originalText = inputText,
                                generalVisionSummary = response.content.trim().takeUnless { it.isBlank() },
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            )

                        logger.info {
                            "🔍 VISION_STAGE1_COMPLETE | correlationId=${task.correlationId} | hasGeneralSummary=${ctx.generalVisionSummary != null} | model=${visionModel.id}"
                        }

                        pipeline.vision = ctx
                        ctx
                    } finally {
                        tmpFiles.forEach { (_, p) -> runCatching { Files.deleteIfExists(p) } }
                    }
                }

                // ============================================================
                // PHASE 1: CONTENT TYPE DETECTION (Structured)
                // ============================================================
                val nodePrepareContentTypePrompt by node<VisionContext, String>(name = "📋 Phase 1: Build ContentType Prompt") { vctx ->
                    buildString {
                        appendLine("Detect the content type of this document.")
                        appendLine()
                        appendLine("Return one of: EMAIL, JIRA, CONFLUENCE, LOG, GENERIC")
                        appendLine()
                        appendLine("CONTENT:")
                        appendLine(vctx.originalText)

                        vctx.generalVisionSummary?.let {
                            appendLine()
                            appendLine("VISUAL CONTEXT (general):")
                            appendLine(it)
                        }
                    }
                }

                val nodeDetectContentType by nodeLLMRequestStructured<ContentTypeDetection>(
                    name = "📋 Phase 1: Detect Content Type (Structured)",
                    examples =
                        listOf(
                            ContentTypeDetection(
                                contentType = "EMAIL",
                                reason = "Contains From/To/Subject and email formatting",
                            ),
                            ContentTypeDetection(
                                contentType = "JIRA",
                                reason = "Contains issue key like SDB-2080, status, assignee",
                            ),
                            ContentTypeDetection(
                                contentType = "LOG",
                                reason = "Contains timestamps, ERROR/WARN and stack traces",
                            ),
                        ),
                )

                val nodeApplyContentType by node<Result<StructuredResponse<ContentTypeDetection>>, ContentType>(
                    name = "📋 Phase 1: Apply ContentType",
                ) { result ->
                    val detected =
                        result
                            .getOrNull()
                            ?.structure
                            ?.contentType
                            ?.trim()
                            ?.uppercase()
                            .orEmpty()

                    val type =
                        when (detected) {
                            "EMAIL" -> ContentType.EMAIL
                            "JIRA" -> ContentType.JIRA
                            "CONFLUENCE" -> ContentType.CONFLUENCE
                            "LOG" -> ContentType.LOG
                            else -> ContentType.GENERIC
                        }

                    val reason = result.getOrNull()?.structure?.reason
                    logger.info {
                        "📋 CONTENT_TYPE | correlationId=${task.correlationId} | contentType=$type | reason=${reason ?: "n/a"}"
                    }

                    pipeline.contentType = type
                    type
                }

                // ============================================================
                // PHASE 2: LLM BUILDS SEMANTIC CHUNKS + CONCEPT GRAPH (Structured)
                // - Keep it GENERAL: works for EMAIL/JIRA/CONFLUENCE now, and can be extended.
                // - Use small/simple structures (Lists + flat objects) to reduce retries.
                // ============================================================

                fun buildIndexingPlanPrompt(type: ContentType): String {
                    val vctx = pipeline.vision ?: VisionContext(task.content, null, null, task.attachments)

                    val typeSpecificHints =
                        when (type) {
                            ContentType.EMAIL -> {
                                """
                                Hints for EMAIL:
                                - Prefer vertices like: company_sender, person_recipient, order_12345, status_delayed, location_store.
                                - Chunks should capture: what happened, what will happen next, important facts (order/items/prices) if present.
                                """.trimIndent()
                            }

                            ContentType.JIRA -> {
                                """
                                Hints for JIRA:
                                - Prefer vertices like: issue_SDB_1234, status_in_progress, assignee_john_doe, component_auth, error_nullpointer.
                                - If screenshots are present, treat them as evidence of UI/error state.
                                - Chunks should capture: the change/request, key fields, reproduction/impact, next action.
                                """.trimIndent()
                            }

                            ContentType.CONFLUENCE -> {
                                """
                                Hints for CONFLUENCE:
                                - Prefer vertices like: page_title_x, system_name, component_x, decision_y, diagram_architecture.
                                - If charts/diagrams are present, treat them as evidence of structure/metrics.
                                - Chunks should capture: topic, decisions, key concepts, procedures.
                                """.trimIndent()
                            }

                            ContentType.LOG -> {
                                """
                                Hints for LOG:
                                - Prefer vertices like: system_service_name, error_type, endpoint_x, component_y, timeframe.
                                - Chunks should capture: failure summary, timeline, suspected cause, affected area.
                                """.trimIndent()
                            }

                            ContentType.GENERIC -> {
                                """
                                Hints for GENERIC:
                                - Prefer vertices for the most important named entities and concepts.
                                - Chunks should be thematic blocks that remain useful out of context.
                                """.trimIndent()
                            }
                        }

                    return buildString {
                        appendLine("You are creating an INDEXING PLAN for RAG + Graph.")
                        appendLine("Kotlin will generate stable IDs/keys and persist your plan.")
                        appendLine("You MUST provide semantic content; do not output boilerplate or meaningless separators.")
                        appendLine()
                        appendLine("Rules:")
                        appendLine("- baseInfo: 1-2 sentences describing what this source is about.")
                        appendLine(
                            "- vertices: 2-8 conceptual entities relevant for retrieval (company/person/order/issue/error/system/etc).",
                        )
                        appendLine("  Each vertex has:")
                        appendLine("    * alias: stable, human-readable identifier (lowercase snake_case).")
                        appendLine("    * type: lowercase category like 'company','person','order','issue','status','system','error'.")
                        appendLine("    * label: short human label.")
                        appendLine(
                            "- edges: 0-12 relations between vertex aliases. relation is lowercase like 'sent','mentions','has_status','affects','caused_by'.",
                        )
                        appendLine("- chunks: 3-10 chunks. Each chunk is 1-6 sentences, self-contained, no headers/boilerplate.")
                        appendLine(
                            "  Each chunk lists vertexAliases it is about (0..n) and optional tags like 'summary','facts','action','error'.",
                        )
                        appendLine("- Be factual. If unknown, omit it.")
                        appendLine()
                        appendLine("CONTENT TYPE: $type")
                        appendLine()
                        appendLine(typeSpecificHints)
                        appendLine()
                        appendLine("SOURCE CONTENT (raw):")
                        appendLine(vctx.originalText)

                        vctx.generalVisionSummary?.let {
                            appendLine()
                            appendLine("VISUAL CONTEXT (general):")
                            appendLine(it)
                        }
                    }
                }

                val planningEmailSubgraph by subgraph<ContentType, Result<StructuredResponse<IndexingPlan>>>(
                    name = "🧠 Phase 2: Plan EMAIL",
                ) {
                    val nodeBuildPrompt by node<ContentType, String>(name = "Build EMAIL plan prompt") { ct ->
                        buildIndexingPlanPrompt(
                            ct,
                        )
                    }
                    val nodePlan by nodeLLMRequestStructured<IndexingPlan>(
                        name = "Plan EMAIL indexing",
                        examples =
                            listOf(
                                IndexingPlan(
                                    baseInfo = "Email from an e-shop notifying about a delayed order and next steps.",
                                    vertices =
                                        listOf(
                                            PlannedVertex(alias = "company_alza", type = "company", label = "Alza.cz"),
                                            PlannedVertex(
                                                alias = "order_530798957",
                                                type = "order",
                                                label = "Order 530798957",
                                            ),
                                            PlannedVertex(
                                                alias = "status_delayed_pickup",
                                                type = "status",
                                                label = "Delayed pickup",
                                            ),
                                        ),
                                    edges =
                                        listOf(
                                            PlannedEdge(
                                                from = "company_alza",
                                                to = "order_530798957",
                                                relation = "mentions",
                                            ),
                                            PlannedEdge(
                                                from = "order_530798957",
                                                to = "status_delayed_pickup",
                                                relation = "has_status",
                                            ),
                                        ),
                                    chunks =
                                        listOf(
                                            PlannedChunk(
                                                content = "The seller informs the customer that the order will be delayed and not ready for pickup on time.",
                                                vertexAliases =
                                                    listOf(
                                                        "company_alza",
                                                        "order_530798957",
                                                        "status_delayed_pickup",
                                                    ),
                                                tags = listOf("summary"),
                                            ),
                                            PlannedChunk(
                                                content = "The seller will provide an updated pickup or delivery time after checking additional information.",
                                                vertexAliases = listOf("company_alza", "order_530798957"),
                                                tags = listOf("next_steps"),
                                            ),
                                        ),
                                ),
                            ),
                    )

                    edge(nodeStart forwardTo nodeBuildPrompt)
                    edge(nodeBuildPrompt forwardTo nodePlan)
                    edge(nodePlan forwardTo nodeFinish)
                }

                val planningJiraSubgraph by subgraph<ContentType, Result<StructuredResponse<IndexingPlan>>>(
                    name = "🧠 Phase 2: Plan JIRA",
                ) {
                    val nodeBuildPrompt by node<ContentType, String>(name = "Build JIRA plan prompt") { ct ->
                        buildIndexingPlanPrompt(
                            ct,
                        )
                    }
                    val nodePlan by nodeLLMRequestStructured<IndexingPlan>(
                        name = "Plan JIRA indexing",
                        examples =
                            listOf(
                                IndexingPlan(
                                    baseInfo = "Jira issue describing a bug and its current status with next action.",
                                    vertices =
                                        listOf(
                                            PlannedVertex(alias = "issue_sdb_2080", type = "issue", label = "SDB-2080"),
                                            PlannedVertex(
                                                alias = "status_in_progress",
                                                type = "status",
                                                label = "In Progress",
                                            ),
                                            PlannedVertex(
                                                alias = "component_login",
                                                type = "component",
                                                label = "Login",
                                            ),
                                        ),
                                    edges =
                                        listOf(
                                            PlannedEdge(
                                                from = "issue_sdb_2080",
                                                to = "status_in_progress",
                                                relation = "has_status",
                                            ),
                                            PlannedEdge(
                                                from = "issue_sdb_2080",
                                                to = "component_login",
                                                relation = "affects",
                                            ),
                                        ),
                                    chunks =
                                        listOf(
                                            PlannedChunk(
                                                content = "The issue reports a login-related problem and tracks its progress and ownership.",
                                                vertexAliases = listOf("issue_sdb_2080", "component_login"),
                                                tags = listOf("summary"),
                                            ),
                                            PlannedChunk(
                                                content = "Current status is In Progress; the team is working on reproduction and a fix.",
                                                vertexAliases = listOf("issue_sdb_2080", "status_in_progress"),
                                                tags = listOf("status"),
                                            ),
                                        ),
                                ),
                            ),
                    )

                    edge(nodeStart forwardTo nodeBuildPrompt)
                    edge(nodeBuildPrompt forwardTo nodePlan)
                    edge(nodePlan forwardTo nodeFinish)
                }

                val planningConfluenceSubgraph by subgraph<ContentType, Result<StructuredResponse<IndexingPlan>>>(
                    name = "🧠 Phase 2: Plan CONFLUENCE",
                ) {
                    val nodeBuildPrompt by node<ContentType, String>(name = "Build Confluence plan prompt") { ct ->
                        buildIndexingPlanPrompt(
                            ct,
                        )
                    }
                    val nodePlan by nodeLLMRequestStructured<IndexingPlan>(
                        name = "Plan Confluence indexing",
                        examples =
                            listOf(
                                IndexingPlan(
                                    baseInfo = "Confluence page documenting an architecture decision and key components.",
                                    vertices =
                                        listOf(
                                            PlannedVertex(
                                                alias = "topic_architecture",
                                                type = "topic",
                                                label = "Architecture",
                                            ),
                                            PlannedVertex(alias = "system_jervis", type = "system", label = "JERVIS"),
                                            PlannedVertex(
                                                alias = "decision_rag_first",
                                                type = "decision",
                                                label = "RAG-first approach",
                                            ),
                                        ),
                                    edges =
                                        listOf(
                                            PlannedEdge(
                                                from = "system_jervis",
                                                to = "decision_rag_first",
                                                relation = "uses",
                                            ),
                                            PlannedEdge(
                                                from = "topic_architecture",
                                                to = "system_jervis",
                                                relation = "describes",
                                            ),
                                        ),
                                    chunks =
                                        listOf(
                                            PlannedChunk(
                                                content = "The page describes an architecture topic and documents the reasoning behind a decision.",
                                                vertexAliases = listOf("topic_architecture", "decision_rag_first"),
                                                tags = listOf("summary"),
                                            ),
                                            PlannedChunk(
                                                content = "It outlines the system components and how the chosen approach impacts the workflow.",
                                                vertexAliases = listOf("system_jervis", "decision_rag_first"),
                                                tags = listOf("details"),
                                            ),
                                        ),
                                ),
                            ),
                    )

                    edge(nodeStart forwardTo nodeBuildPrompt)
                    edge(nodeBuildPrompt forwardTo nodePlan)
                    edge(nodePlan forwardTo nodeFinish)
                }

                val planningGenericSubgraph by subgraph<ContentType, Result<StructuredResponse<IndexingPlan>>>(
                    name = "🧠 Phase 2: Plan GENERIC",
                ) {
                    val nodeBuildPrompt by node<ContentType, String>(name = "Build GENERIC plan prompt") { ct ->
                        buildIndexingPlanPrompt(
                            ct,
                        )
                    }
                    val nodePlan by nodeLLMRequestStructured<IndexingPlan>(
                        name = "Plan GENERIC indexing",
                        examples =
                            listOf(
                                IndexingPlan(
                                    baseInfo = "Document describing a situation, key facts, and possible next actions.",
                                    vertices =
                                        listOf(
                                            PlannedVertex(alias = "topic_main", type = "topic", label = "Main topic"),
                                            PlannedVertex(
                                                alias = "system_or_context",
                                                type = "system",
                                                label = "System/Context",
                                            ),
                                        ),
                                    edges =
                                        listOf(
                                            PlannedEdge(
                                                from = "topic_main",
                                                to = "system_or_context",
                                                relation = "relates_to",
                                            ),
                                        ),
                                    chunks =
                                        listOf(
                                            PlannedChunk(
                                                content = "This chunk captures the core meaning of the document in a self-contained way.",
                                                vertexAliases = listOf("topic_main"),
                                                tags = listOf("summary"),
                                            ),
                                            PlannedChunk(
                                                content = "This chunk captures key facts that are useful for later retrieval and reasoning.",
                                                vertexAliases = listOf("topic_main", "system_or_context"),
                                                tags = listOf("facts"),
                                            ),
                                        ),
                                ),
                            ),
                    )

                    edge(nodeStart forwardTo nodeBuildPrompt)
                    edge(nodeBuildPrompt forwardTo nodePlan)
                    edge(nodePlan forwardTo nodeFinish)
                }

                // ============================================================
                // PHASE 3: BUILD IndexingContext from plan (Kotlin only for IDs)
                // ============================================================
                val nodeBuildIndexingContext by node<Result<StructuredResponse<IndexingPlan>>, IndexingContext>(
                    name = "📦 Phase 3: Build IndexingContext (from plan)",
                ) { result ->
                    val type = pipeline.contentType ?: ContentType.GENERIC
                    val vctx = pipeline.vision ?: VisionContext(task.content, null, null, task.attachments)

                    val plan =
                        result.getOrNull()?.structure
                            ?: IndexingPlan(
                                baseInfo = "Unparsed content (${type.name}) | correlationId=${task.correlationId}",
                                vertices = emptyList(),
                                edges = emptyList(),
                                chunks =
                                    listOf(
                                        PlannedChunk(
                                            content = normalizeForRag(vctx.originalText.take(2500)),
                                            tags = listOf("raw"),
                                        ),
                                    ),
                            )

                    pipeline.plan = plan

                    val baseNodeKey =
                        computeBaseNodeKey(
                            task = task,
                            type = type,
                            rawText = vctx.originalText,
                            baseInfo = plan.baseInfo,
                        )

                    val chunks =
                        plan.chunks
                            .map { normalizeForRag(it.content) }
                            .filter { it.isNotBlank() }
                            .take(12)

                    val ctx =
                        IndexingContext(
                            contentType = type,
                            baseNodeKey = baseNodeKey,
                            baseInfo =
                                normalizeForRag(plan.baseInfo).takeIf { it.isNotBlank() }
                                    ?: "Document | correlationId=${task.correlationId}",
                            indexableChunks = chunks,
                            visionContext = vctx,
                            metadata = emptyMap(),
                        )

                    pipeline.indexing = ctx

                    logger.info {
                        "📦 INDEXING_CTX | correlationId=${task.correlationId} | type=${ctx.contentType} | baseNodeKey=${ctx.baseNodeKey} | chunks=${ctx.indexableChunks.size} | vertices=${plan.vertices.size} | edges=${plan.edges.size}"
                    }

                    ctx
                }

                // ============================================================
                // PHASE 4: PERSIST (RAG + Graph). No chunk-nodes.
                // - RAG chunks reference base node + relevant vertex nodes (by stable keys)
                // - Graph has base node + semantic vertex nodes + semantic edges
                // ============================================================
                val nodeIndexToRagAndGraph by node<IndexingContext, IndexingContext>(name = "🧱 Phase 4: Index to RAG + Graph") { ctx ->
                    val plan = pipeline.plan
                    logger.info {
                        "🧱 INDEX_START | correlationId=${task.correlationId} | baseNodeKey=${ctx.baseNodeKey} | type=${ctx.contentType}"
                    }

                    val aliasToKey = buildAliasKeyMap(baseNodeKey = ctx.baseNodeKey, plan = plan)

                    // Store base summary chunk
                    val ragChunkIds = mutableListOf<String>()
                    val baseChunkId =
                        knowledgeService.storeChunk(
                            StoreChunkRequest(
                                clientId = task.clientId,
                                projectId = task.projectId,
                                content = ctx.baseInfo,
                                graphRefs = listOf(ctx.baseNodeKey),
                                sourceUrn = task.sourceUrn,
                            ),
                        )
                    ragChunkIds += baseChunkId

                    // Store content chunks. Each chunk references base + semantic vertices.
                    val vertexToChunkIds = linkedMapOf<String, MutableList<String>>()

                    val plannedChunks = plan?.chunks ?: ctx.indexableChunks.map { PlannedChunk(content = it) }
                    plannedChunks.forEachIndexed { idx, pc ->
                        val content = normalizeForRag(pc.content)
                        if (content.isBlank()) return@forEachIndexed

                        val vertexKeys =
                            pc.vertexAliases
                                .mapNotNull { aliasToKey[it.trim()] }
                                .distinct()
                                .take(3)

                        val graphRefs =
                            buildList {
                                add(ctx.baseNodeKey)
                                addAll(vertexKeys)
                            }

                        val chunkId =
                            knowledgeService.storeChunk(
                                StoreChunkRequest(
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                    content = content,
                                    graphRefs = graphRefs,
                                    sourceUrn = task.sourceUrn,
                                ),
                            )

                        ragChunkIds += chunkId

                        vertexKeys.forEach { vk ->
                            vertexToChunkIds.getOrPut(vk) { mutableListOf() }.add(chunkId)
                        }

                        logger.debug {
                            "🧱 STORED_CHUNK | correlationId=${task.correlationId} | idx=${idx + 1} | vertices=${vertexKeys.size} | tags=${
                                pc.tags.joinToString(
                                    ",",
                                )
                            }"
                        }
                    }

                    // Upsert base node with all RAG chunks
                    graphService.upsertNode(
                        clientId = task.clientId,
                        node =
                            GraphNode(
                                key = ctx.baseNodeKey,
                                entityType = ctx.contentType.name.lowercase(),
                                ragChunks = ragChunkIds.distinct(),
                            ),
                    )

                    // Link base document node -> semantic vertices (makes graphRefs useful across queries)
                    plan?.vertices.orEmpty().forEach { v ->
                        val vKey = aliasToKey[v.alias] ?: return@forEach
                        runCatching {
                            upsertGraphEdgeReflectively(
                                graphService = graphService,
                                clientId = task.clientId,
                                fromKey = ctx.baseNodeKey,
                                toKey = vKey,
                                relation = "mentions",
                                label = null,
                            )
                        }.onFailure { ex ->
                            logger.debug(ex) {
                                "GRAPH_EDGE_UPSERT_FAILED | correlationId=${task.correlationId} | from=${ctx.baseNodeKey} | to=$vKey | relation=mentions"
                            }
                        }
                    }

                    // Upsert semantic vertices (no chunk nodes)
                    plan?.vertices.orEmpty().forEach { v ->
                        val vKey = aliasToKey[v.alias] ?: return@forEach
                        val vChunkIds = vertexToChunkIds[vKey].orEmpty()
                        graphService.upsertNode(
                            clientId = task.clientId,
                            node =
                                GraphNode(
                                    key = vKey,
                                    entityType =
                                        v.type
                                            .trim()
                                            .lowercase()
                                            .ifBlank { "entity" },
                                    ragChunks = vChunkIds.distinct(),
                                ),
                        )
                    }

                    // Upsert edges via reflection (compile-safe across variants)
                    plan?.edges.orEmpty().forEach { e ->
                        val fromKey = aliasToKey[e.from]
                        val toKey = aliasToKey[e.to]
                        if (fromKey == null || toKey == null) return@forEach

                        runCatching {
                            upsertGraphEdgeReflectively(
                                graphService = graphService,
                                clientId = task.clientId,
                                fromKey = fromKey,
                                toKey = toKey,
                                relation = e.relation,
                                label = e.label,
                            )
                        }.onFailure { ex ->
                            logger.warn(ex) {
                                "GRAPH_EDGE_UPSERT_FAILED | correlationId=${task.correlationId} | from=$fromKey | to=$toKey | relation=${e.relation}"
                            }
                        }
                    }

                    logger.info {
                        "🧱 INDEX_DONE | correlationId=${task.correlationId} | baseNodeKey=${ctx.baseNodeKey} | ragChunks=${ragChunkIds.size} | vertices=${plan?.vertices?.size ?: 0} | edges=${plan?.edges?.size ?: 0}"
                    }

                    ctx
                }

                // ============================================================
                // PHASE 5: FINAL ROUTING (tool-based)
                // ============================================================
                val nodePrepareRoutingPrompt by node<IndexingContext, String>(name = "🎯 Phase 5: Build Routing Prompt") { ctx ->
                    """
All content has been indexed to RAG + Graph.

SUMMARY:
- Content Type: ${ctx.contentType}
- Base Node: ${ctx.baseNodeKey}

NOW call the tool routeTask with ONE of:
- DONE    (all information indexed, no further actions needed)
- LIFT_UP (requires complex analysis/coding/user response by main GPU agent)

IMPORTANT:
- You MUST call the tool.
- Use exactly: routeTask(DONE) or routeTask(LIFT_UP).
                    """.trimIndent()
                }

                val routingSubgraph by subgraph<String, String>(name = "Final Routing") {
                    val nodeSendRoutingRequest by nodeLLMRequest(name = "Send Routing Request")
                    val nodeExecuteRoutingTool by nodeExecuteTool(name = "Execute routeTask")
                    val nodeReturnEmpty by node<Any, String>(name = "Return empty output") { "" }

                    edge(nodeStart forwardTo nodeSendRoutingRequest)
                    edge(nodeSendRoutingRequest forwardTo nodeFinish onAssistantMessage { true })
                    edge(nodeSendRoutingRequest forwardTo nodeExecuteRoutingTool onToolCall { true })
                    edge(nodeExecuteRoutingTool forwardTo nodeReturnEmpty)
                    edge(nodeReturnEmpty forwardTo nodeFinish)
                }

                // ============================================================
                // MAIN GRAPH EDGES
                // ============================================================
                edge(nodeStart forwardTo nodeVisionStage1)

                edge(nodeVisionStage1 forwardTo nodePrepareContentTypePrompt)
                edge(nodePrepareContentTypePrompt forwardTo nodeDetectContentType)
                edge(nodeDetectContentType forwardTo nodeApplyContentType)

                // Phase 2 planning is split into subgraphs by detected type (Koog best practice).
                edge((nodeApplyContentType forwardTo planningEmailSubgraph).onCondition { pipeline.contentType == ContentType.EMAIL })
                edge((nodeApplyContentType forwardTo planningJiraSubgraph).onCondition { pipeline.contentType == ContentType.JIRA })
                edge(
                    (nodeApplyContentType forwardTo planningConfluenceSubgraph).onCondition {
                        pipeline.contentType ==
                            ContentType.CONFLUENCE
                    },
                )
                edge(
                    (nodeApplyContentType forwardTo planningGenericSubgraph).onCondition {
                        pipeline.contentType == ContentType.LOG || pipeline.contentType == ContentType.GENERIC ||
                            pipeline.contentType == null
                    },
                )

                // Converge: all planning subgraphs produce Result<StructuredResponse<IndexingPlan>>
                edge(planningEmailSubgraph forwardTo nodeBuildIndexingContext)
                edge(planningJiraSubgraph forwardTo nodeBuildIndexingContext)
                edge(planningConfluenceSubgraph forwardTo nodeBuildIndexingContext)
                edge(planningGenericSubgraph forwardTo nodeBuildIndexingContext)

                edge(nodeBuildIndexingContext forwardTo nodeIndexToRagAndGraph)
                edge(nodeIndexToRagAndGraph forwardTo nodePrepareRoutingPrompt)
                edge(nodePrepareRoutingPrompt forwardTo routingSubgraph)
                edge(routingSubgraph forwardTo nodeFinish)
            }

        val dynamicModel =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        logger.info {
            "KoogQualifierAgent | Model selected: ${dynamicModel.id} | contextLength=${dynamicModel.contextLength} | provider: ${dynamicModel.provider}"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-qualifier") {
                        system(
                            """
You are JERVIS Qualification Agent.
You must follow the instructions and output structured JSON for structured requests.
Do not hallucinate. If unknown, omit it.
                            """.trimIndent(),
                        )
                    },
                model = dynamicModel,
                maxAgentIterations = koogProperties.maxIterations,
            )

        val toolRegistry =
            ToolRegistry {
                tools(KnowledgeStorageTools(task, knowledgeService, graphService))

                tools(
                    QualifierRoutingTools(
                        task = task,
                        taskService = taskService,
                        linkContentService = linkContentService,
                        indexedLinkService = indexedLinkService,
                        connectionService = connectionService,
                        coroutineScope = scope,
                    ),
                )

                tools(SchedulerTools(task, taskManagementService))
                tools(ContentAnalysisTools(task, userTaskService, scope))
            }

        return AIAgent(
            promptExecutor = promptExecutorFactory.getExecutor(ollamaProviderSelector.getProvider()),
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "Agent starting: ${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "Agent completed: ${ctx.result}" }
                    }
                }
            },
        )
    }

    suspend fun run(task: TaskDocument): QualifierResult {
        val startTime = System.currentTimeMillis()
        logger.info { "🚀 QUALIFIER_START | correlationId=${task.correlationId}" }

        try {
            val agent = create(task)
            agent.run(task.content)

            val duration = System.currentTimeMillis() - startTime
            logger.info { "✅ QUALIFIER_COMPLETE | correlationId=${task.correlationId} | duration=${duration}ms" }
            return QualifierResult(completed = true)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) { "❌ QUALIFIER_FAILED | correlationId=${task.correlationId} | duration=${duration}ms" }

            val isRetryable = isRetryableError(e)

            if (isRetryable) {
                runCatching {
                    taskService.updateState(
                        task = task,
                        next = TaskStateEnum.READY_FOR_QUALIFICATION,
                    )
                    logger.info { "TASK_RETURNED_FOR_RETRY | taskId=${task.id}" }
                }.onFailure { stateError ->
                    logger.error(stateError) { "Failed to return task for retry: ${task.id}" }
                }
                throw e
            }

            runCatching {
                val reason = "KoogQualifierAgent failed: ${e.message ?: e::class.simpleName}"
                taskService.markAsError(task = task, errorMessage = reason)
                userTaskService.failAndEscalateToUserTask(task = task, reason = reason, error = e)
                logger.info { "TASK_MARKED_ERROR_AND_ESCALATED | taskId=${task.id}" }
            }.onFailure { escalationError ->
                logger.error(escalationError) { "Failed to escalate task: ${task.id}" }
            }

            throw e
        }
    }

    private fun isRetryableError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        val causeMessage = e.cause?.message?.lowercase() ?: ""

        return when {
            "timeout" in message || "timeout" in causeMessage -> true
            "socket" in message || "connection" in message -> true
            "502" in message || "503" in message || "504" in message -> true
            "stuck in node" in message -> false
            else -> false
        }
    }

    data class QualifierResult(
        val completed: Boolean,
    )

    // ============================================================
    // Helpers: Kotlin does stable IDs + normalization only.
    // ============================================================

    private fun computeBaseNodeKey(
        task: TaskDocument,
        type: ContentType,
        rawText: String,
        baseInfo: String,
    ): String =
        when (type) {
            ContentType.EMAIL -> {
                val emailId = parseEmailIdFromText(rawText)
                if (emailId != null) {
                    "email_email:$emailId"
                } else {
                    "email_${shortHash(task.sourceUrn.value)}"
                }
            }

            ContentType.JIRA -> {
                val key = parseJiraKeyFromText(rawText) ?: parseJiraKeyFromText(baseInfo)
                if (key != null) {
                    "jira_${key.replace("-", "_").lowercase()}"
                } else {
                    "jira_${shortHash(task.sourceUrn.value)}"
                }
            }

            ContentType.CONFLUENCE -> {
                "confluence_${shortHash(task.sourceUrn.value)}"
            }

            ContentType.LOG -> {
                "log_${shortHash(task.sourceUrn.value)}"
            }

            ContentType.GENERIC -> {
                "doc_${shortHash(task.sourceUrn.value)}"
            }
        }

    private fun parseEmailIdFromText(rawText: String): String? {
        // Matches lines like: - **Email ID:** 69426bee41e778f2d0885196
        val regex = Regex("""Email ID:\*\*\s*([0-9a-fA-F]{24})""")
        return regex
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
    }

    private fun parseJiraKeyFromText(text: String): String? {
        // Matches typical keys: ABC-1234
        val regex = Regex("""\b([A-Z][A-Z0-9]+-\d+)\b""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun buildAliasKeyMap(
        baseNodeKey: String,
        plan: IndexingPlan?,
    ): Map<String, String> {
        if (plan == null) return emptyMap()
        val m = LinkedHashMap<String, String>()

        plan.vertices.forEach { v ->
            val alias = v.alias.trim()
            if (alias.isBlank()) return@forEach

            val type =
                v.type
                    .trim()
                    .lowercase()
                    .ifBlank { "entity" }
            // IMPORTANT: do NOT scope to the base node; we want stable keys that can be reused across documents.
            // GraphDB is per-client anyway.
            val key = "$type:${normalizeKeyPart(alias)}"
            m[alias] = key
        }

        return m
    }

    private fun normalizeKeyPart(value: String): String {
        val cleaned =
            value
                .lowercase()
                .trim()
                .replace(Regex("""\s+"""), "_")
                .replace(Regex("""[^a-z0-9:_\-]+"""), "_")
                .replace(Regex("""_+"""), "_")
                .trim('_')

        return when {
            cleaned.isBlank() -> shortHash(value)
            cleaned.length <= 80 -> cleaned
            else -> cleaned.take(80)
        }
    }

    private fun normalizeForRag(text: String): String {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines =
            t
                .lines()
                .map { it.replace('\u00A0', ' ').trim() }
                .filter { it.isNotBlank() }
        return lines
            .joinToString("\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun shortHash(value: String): String = sha256(value).take(12)

    private fun sha256(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reflection-based edge upsert to avoid compile coupling to GraphEdge API shape.
     * Supports common variants:
     * - GraphDBService.upsertEdge(clientId, edge)
     * - GraphDBService.upsertEdges(clientId, edges)
     */
    private fun upsertGraphEdgeReflectively(
        graphService: GraphDBService,
        clientId: Any,
        fromKey: String,
        toKey: String,
        relation: String,
        label: String?,
    ) {
        val edgeObj = newGraphEdgeInstance(fromKey, toKey, relation, label)

        val svcClass = graphService.javaClass
        val methods = svcClass.methods.toList()

        // Try upsertEdge(clientId, edge)
        methods.firstOrNull { it.name == "upsertEdge" && it.parameterCount == 2 }?.let { m ->
            m.invoke(graphService, clientId, edgeObj)
            return
        }

        // Try upsertEdges(clientId, list)
        methods.firstOrNull { it.name == "upsertEdges" && it.parameterCount == 2 }?.let { m ->
            m.invoke(graphService, clientId, listOf(edgeObj))
            return
        }

        // Try any method that looks like (clientId, edge) but named differently
        methods
            .firstOrNull { it.parameterCount == 2 && it.parameterTypes[1].isAssignableFrom(edgeObj.javaClass) }
            ?.let { m ->
                m.invoke(graphService, clientId, edgeObj)
                return
            }

        logger.debug { "Graph edge upsert skipped: no compatible method found on ${svcClass.name}" }
    }

    private fun newGraphEdgeInstance(
        fromKey: String,
        toKey: String,
        relation: String,
        label: String?,
    ): Any {
        val clazz = Class.forName("com.jervis.graphdb.model.GraphEdge")

        // Prefer (from, to, type)
        clazz.declaredConstructors
            .firstOrNull { c ->
                c.parameterTypes.size == 3 && c.parameterTypes.all { it == String::class.java }
            }?.let { c ->
                c.isAccessible = true
                return c.newInstance(fromKey, toKey, relation)
            }

        // Try (from, to, type, label)
        clazz.declaredConstructors
            .firstOrNull { c ->
                c.parameterTypes.size == 4 && c.parameterTypes.all { it == String::class.java }
            }?.let { c ->
                c.isAccessible = true
                return c.newInstance(fromKey, toKey, relation, label ?: "")
            }

        // Fallback: first constructor, try to fill strings
        val c = clazz.declaredConstructors.first()
        c.isAccessible = true
        val args =
            Array(c.parameterTypes.size) { idx ->
                when (idx) {
                    0 -> fromKey
                    1 -> toKey
                    2 -> relation
                    else -> label ?: ""
                }
            }
        return c.newInstance(*args)
    }

    // ============================================================
    // Vision helper: Build prompt context for vision
    // ============================================================
    private fun buildVisionPromptContext(
        task: TaskDocument,
        fullText: String,
    ): String {
        // Vision does not need the whole document; keep it small to reduce tokens and noise.
        val normalized = normalizeForRag(fullText)
        val head = normalized.take(1000)
        val tail = if (normalized.length > 1600) normalized.takeLast(400) else ""

        val attachmentNames =
            task.attachments
                .take(12)
                .joinToString(", ") {
                    val name = it.filename.ifBlank { it.id.toString() }
                    val mt = it.mimeType.ifBlank { "unknown" }
                    "$name ($mt)"
                }.ifBlank { "none" }

        return buildString {
            appendLine("sourceUrn: ${task.sourceUrn.value}")
            appendLine("correlationId: ${task.correlationId}")
            appendLine("attachments: $attachmentNames")
            appendLine()
            appendLine("text_excerpt_head:")
            appendLine(head)
            if (tail.isNotBlank()) {
                appendLine()
                appendLine("text_excerpt_tail:")
                appendLine(tail)
            }
        }.trim()
    }
}
