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
import com.jervis.koog.qualifier.types.ConfluenceExtractionOutput
import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.EmailExtractionOutput
import com.jervis.koog.qualifier.types.GenericChunkingOutput
import com.jervis.koog.qualifier.types.IndexingContext
import com.jervis.koog.qualifier.types.JiraExtractionOutput
import com.jervis.koog.qualifier.types.LogSummarizationOutput
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
     * To keep the pipeline strongly-typed without fighting the DSL, we keep a single mutable pipeline-context
     * object per strategy instance (agent instance). This avoids the broken dummy-state loop from the old version.
     */
    private data class PipelineCtx(
        var vision: VisionContext? = null,
        var contentType: ContentType? = null,
        var indexing: IndexingContext? = null,
    )

    @Serializable
    private data class ContentTypeDetection(
        val contentType: String,
        val reason: String,
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
                        val visionPrompt =
                            ai.koog.prompt.dsl.prompt("jervis-vision-stage1") {
                                system(
                                    "Describe the attached images. Be factual. If something is unreadable, say so.",
                                )
                                user {
                                    ai.koog.prompt.markdown.markdown {
                                        +"Text context:"
                                        br()
                                        +inputText
                                        br()
                                        br()
                                        +"Describe the images."
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
                // PHASE 2: TYPE-SPECIFIC EXTRACTION (Structured)
                // ============================================================
                val nodePrepareExtractionPrompt by node<ContentType, String>(name = "🧩 Phase 2: Build Extraction Prompt") { type ->
                    val vctx = pipeline.vision ?: VisionContext(task.content, null, null, task.attachments)

                    buildString {
                        appendLine("You will extract structured information from the given content.")
                        appendLine("Be strict, do not hallucinate. If unknown, use empty string / empty list / null.")
                        appendLine()

                        appendLine("CONTENT TYPE: $type")
                        appendLine()
                        appendLine("CONTENT:")
                        appendLine(vctx.originalText)

                        vctx.generalVisionSummary?.let {
                            appendLine()
                            appendLine("VISUAL CONTEXT (general):")
                            appendLine(it)
                        }

                        appendLine()
                        appendLine(
                            when (type) {
                                ContentType.EMAIL -> {
                                    "Extract: sender, recipients[], subject, classification"
                                }

                                ContentType.JIRA -> {
                                    "Extract: key, status, type, assignee, reporter, epic?, sprint?, changeDescription"
                                }

                                ContentType.CONFLUENCE -> {
                                    "Extract: author, title, topic"
                                }

                                ContentType.LOG -> {
                                    "Summarize: summary, keyEvents[], criticalDetails[]"
                                }

                                ContentType.GENERIC -> {
                                    "Chunk: baseInfo, chunks[] (semantic blocks, no tiny fragments)"
                                }
                            },
                        )
                    }
                }

                val nodeExtractEmail by nodeLLMRequestStructured<EmailExtractionOutput>(
                    name = "📧 Phase 2: Extract EMAIL",
                    examples =
                        listOf(
                            EmailExtractionOutput(
                                sender = "john.doe@example.com",
                                recipients = listOf("support@example.com"),
                                subject = "Bug Report: Login fails on mobile",
                                classification = "Bug report",
                            ),
                        ),
                )

                val nodeExtractJira by nodeLLMRequestStructured<JiraExtractionOutput>(
                    name = "🎫 Phase 2: Extract JIRA",
                    examples =
                        listOf(
                            JiraExtractionOutput(
                                key = "SDB-2080",
                                status = "In Progress",
                                type = "Bug",
                                assignee = "John Doe",
                                reporter = "Jane Smith",
                                epic = "EPIC-123",
                                sprint = "Sprint 42",
                                changeDescription = "Status changed from Open to In Progress; added reproduction steps.",
                            ),
                        ),
                )

                val nodeExtractConfluence by nodeLLMRequestStructured<ConfluenceExtractionOutput>(
                    name = "📄 Phase 2: Extract CONFLUENCE",
                    examples =
                        listOf(
                            ConfluenceExtractionOutput(
                                author = "John Doe",
                                title = "API Documentation",
                                topic = "REST endpoints and authentication",
                            ),
                        ),
                )

                val nodeExtractLog by nodeLLMRequestStructured<LogSummarizationOutput>(
                    name = "📜 Phase 2: Summarize LOG",
                    examples =
                        listOf(
                            LogSummarizationOutput(
                                summary = "Crash caused by NullPointerException in UserService",
                                keyEvents = listOf("ERROR at 10:23:45 - NullPointerException", "Restart at 10:25:00"),
                                criticalDetails = listOf("UserService.java:42", "user_id=12345"),
                            ),
                        ),
                )

                val nodeExtractGeneric by nodeLLMRequestStructured<GenericChunkingOutput>(
                    name = "📝 Phase 2: Chunk GENERIC",
                    examples =
                        listOf(
                            GenericChunkingOutput(
                                baseInfo = "Technical documentation about Kubernetes deployment strategies",
                                chunks =
                                    listOf(
                                        "Introduction: Kubernetes provides multiple deployment strategies...",
                                        "Rolling updates: Gradually replaces pods while keeping service available...",
                                    ),
                            ),
                        ),
                )

                // ============================================================
                // PHASE 3: BUILD UNIFIED IndexingContext (no broken loops)
                // ============================================================
                val nodeBuildIndexingContext by node<Any, IndexingContext>(name = "📦 Phase 3: Build IndexingContext") { extractionResult ->
                    val type = pipeline.contentType ?: ContentType.GENERIC
                    val vctx = pipeline.vision ?: VisionContext(task.content, null, null, task.attachments)

                    fun baseNodeKey(): String =
                        when (type) {
                            ContentType.JIRA -> {
                                val key = (extractionResult as? Result<*>)?.getOrNull()
                                val jiraKey =
                                    (key as? StructuredResponse<*>)
                                        ?.structure
                                        ?.let { it as? JiraExtractionOutput }
                                        ?.key
                                        ?.takeUnless { it.isBlank() }
                                if (jiraKey != null) {
                                    "jira_${jiraKey.replace("-", "_").lowercase()}"
                                } else {
                                    "jira_${task.correlationId.replace("-", "_")}"
                                }
                            }

                            ContentType.EMAIL -> {
                                "email_${task.correlationId.replace("-", "_")}"
                            }

                            ContentType.CONFLUENCE -> {
                                "confluence_${task.correlationId.replace("-", "_")}"
                            }

                            ContentType.LOG -> {
                                "log_${task.correlationId.replace("-", "_")}"
                            }

                            ContentType.GENERIC -> {
                                "doc_${task.correlationId.replace("-", "_")}"
                            }
                        }

                    val ctx =
                        when (type) {
                            ContentType.EMAIL -> {
                                val r = extractionResult as Result<StructuredResponse<EmailExtractionOutput>>
                                val ex = r.getOrNull()?.structure
                                val baseInfo =
                                    if (ex != null) {
                                        "Email from ${ex.sender} to ${ex.recipients.joinToString(", ")}: ${ex.subject}"
                                    } else {
                                        "Email (unparsed) | correlationId=${task.correlationId}"
                                    }

                                IndexingContext(
                                    contentType = ContentType.EMAIL,
                                    baseNodeKey = baseNodeKey(),
                                    baseInfo = baseInfo,
                                    indexableChunks = chunkText(vctx.originalText),
                                    visionContext = vctx,
                                    metadata =
                                        mapOf(
                                            "sender" to (ex?.sender ?: ""),
                                            "recipients" to (ex?.recipients?.joinToString(", ") ?: ""),
                                            "subject" to (ex?.subject ?: ""),
                                            "classification" to (ex?.classification ?: ""),
                                        ),
                                )
                            }

                            ContentType.JIRA -> {
                                val r = extractionResult as Result<StructuredResponse<JiraExtractionOutput>>
                                val ex = r.getOrNull()?.structure
                                val baseInfo =
                                    if (ex != null) {
                                        "[${ex.key}] ${ex.type} - ${ex.status} - ${ex.changeDescription}"
                                    } else {
                                        "JIRA (unparsed) | correlationId=${task.correlationId}"
                                    }

                                IndexingContext(
                                    contentType = ContentType.JIRA,
                                    baseNodeKey = baseNodeKey(),
                                    baseInfo = baseInfo,
                                    indexableChunks = chunkText(vctx.originalText),
                                    visionContext = vctx,
                                    metadata =
                                        mapOf(
                                            "key" to (ex?.key ?: ""),
                                            "status" to (ex?.status ?: ""),
                                            "type" to (ex?.type ?: ""),
                                            "assignee" to (ex?.assignee ?: ""),
                                            "reporter" to (ex?.reporter ?: ""),
                                            "epic" to (ex?.epic ?: ""),
                                            "sprint" to (ex?.sprint ?: ""),
                                        ),
                                )
                            }

                            ContentType.CONFLUENCE -> {
                                val r = extractionResult as Result<StructuredResponse<ConfluenceExtractionOutput>>
                                val ex = r.getOrNull()?.structure
                                val baseInfo =
                                    if (ex != null) {
                                        "${ex.title} by ${ex.author} - ${ex.topic}"
                                    } else {
                                        "Confluence (unparsed) | correlationId=${task.correlationId}"
                                    }

                                IndexingContext(
                                    contentType = ContentType.CONFLUENCE,
                                    baseNodeKey = baseNodeKey(),
                                    baseInfo = baseInfo,
                                    indexableChunks = chunkText(vctx.originalText),
                                    visionContext = vctx,
                                    metadata =
                                        mapOf(
                                            "author" to (ex?.author ?: ""),
                                            "title" to (ex?.title ?: ""),
                                            "topic" to (ex?.topic ?: ""),
                                        ),
                                )
                            }

                            ContentType.LOG -> {
                                val r = extractionResult as Result<StructuredResponse<LogSummarizationOutput>>
                                val ex = r.getOrNull()?.structure
                                val baseInfo =
                                    ex?.summary ?: "Log summary (unparsed) | correlationId=${task.correlationId}"

                                val chunks =
                                    if (ex != null) {
                                        listOf(
                                            "Summary: ${ex.summary}",
                                            "Key Events:\n- ${ex.keyEvents.joinToString("\n- ")}",
                                            "Critical Details:\n- ${ex.criticalDetails.joinToString("\n- ")}",
                                        )
                                    } else {
                                        // fallback: do not index raw logs as huge chunks; store a small slice
                                        listOf("Log (unparsed). First 4000 chars:\n${vctx.originalText.take(4000)}")
                                    }

                                IndexingContext(
                                    contentType = ContentType.LOG,
                                    baseNodeKey = baseNodeKey(),
                                    baseInfo = baseInfo,
                                    indexableChunks = chunks,
                                    visionContext = vctx,
                                    metadata =
                                        mapOf(
                                            "summary" to (ex?.summary ?: ""),
                                            "keyEventsCount" to (ex?.keyEvents?.size?.toString() ?: "0"),
                                            "criticalDetailsCount" to (ex?.criticalDetails?.size?.toString() ?: "0"),
                                        ),
                                )
                            }

                            ContentType.GENERIC -> {
                                val r = extractionResult as Result<StructuredResponse<GenericChunkingOutput>>
                                val ex = r.getOrNull()?.structure
                                val baseInfo = ex?.baseInfo ?: "Document | correlationId=${task.correlationId}"
                                val chunks =
                                    ex?.chunks?.filter { it.isNotBlank() }?.ifEmpty { null }
                                        ?: chunkText(vctx.originalText)

                                IndexingContext(
                                    contentType = ContentType.GENERIC,
                                    baseNodeKey = baseNodeKey(),
                                    baseInfo = baseInfo,
                                    indexableChunks = chunks,
                                    visionContext = vctx,
                                    metadata = emptyMap(),
                                )
                            }
                        }

                    logger.info {
                        "📦 INDEXING_CTX | correlationId=${task.correlationId} | type=${ctx.contentType} | baseNodeKey=${ctx.baseNodeKey} | chunks=${ctx.indexableChunks.size}"
                    }

                    pipeline.indexing = ctx
                    ctx
                }

                // ============================================================
                // PHASE 4: UNIFIED INDEXING (deterministic, no graph-loop hacks)
                // ============================================================
                val nodeIndexToRagAndGraph by node<IndexingContext, IndexingContext>(name = "🧱 Phase 4: Index to RAG + Graph") { ctx ->
                    logger.info {
                        "🧱 INDEX_START | correlationId=${task.correlationId} | baseNodeKey=${ctx.baseNodeKey} | type=${ctx.contentType}"
                    }

                    // Base info chunk
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

                    graphService.upsertNode(
                        clientId = task.clientId,
                        node =
                            GraphNode(
                                key = ctx.baseNodeKey,
                                entityType = ctx.contentType.name.lowercase(),
                                ragChunks = listOf(baseChunkId),
                            ),
                    )

                    // Chunk indexing (RAG + chunk-nodes). Relationship edges are best handled inside your Graph service;
                    // here we keep it safe and consistent even if edge API differs.
                    ctx.indexableChunks.forEachIndexed { idx, chunk ->
                        val chunkKey = "${ctx.baseNodeKey}_chunk_${idx + 1}"

                        val chunkId =
                            knowledgeService.storeChunk(
                                StoreChunkRequest(
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                    content = chunk,
                                    graphRefs = listOf(ctx.baseNodeKey, chunkKey),
                                    sourceUrn = task.sourceUrn,
                                ),
                            )

                        graphService.upsertNode(
                            clientId = task.clientId,
                            node =
                                GraphNode(
                                    key = chunkKey,
                                    entityType = "chunk",
                                    ragChunks = listOf(chunkId),
                                ),
                        )

                        // If you have an explicit edge API, wire it here:
                        // graphService.upsertEdge(clientId = task.clientId, edge = GraphEdge(from = chunkKey, to = ctx.baseNodeKey, type = "PART_OF"))
                    }

                    logger.info {
                        "🧱 INDEX_DONE | correlationId=${task.correlationId} | baseChunkId=$baseChunkId | chunks=${ctx.indexableChunks.size}"
                    }

                    ctx
                }

                // ============================================================
                // PHASE 5: FINAL ROUTING (tool-based, Koog-docs style)
                // ============================================================
                val nodePrepareRoutingPrompt by node<IndexingContext, String>(name = "🎯 Phase 5: Build Routing Prompt") { ctx ->
                    """
All content has been indexed to RAG + Graph.

SUMMARY:
- Content Type: ${ctx.contentType}
- Base Node: ${ctx.baseNodeKey}
- Stored Chunks: ${ctx.indexableChunks.size}

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

                    // IMPORTANT: after routeTask execution we end the graph without any further LLM call
                    val nodeReturnEmpty by node<Any, String>(name = "Return empty output") { "" }

                    edge(nodeStart forwardTo nodeSendRoutingRequest)

                    // If assistant replies instead of calling tool, finish (but you'll see it in logs)
                    edge(nodeSendRoutingRequest forwardTo nodeFinish onAssistantMessage { true })

                    // Normal tool path
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

                edge(nodeApplyContentType forwardTo nodePrepareExtractionPrompt)

                // Route to the right structured extractor:
                edge((nodePrepareExtractionPrompt forwardTo nodeExtractEmail).onCondition { pipeline.contentType == ContentType.EMAIL })
                edge((nodePrepareExtractionPrompt forwardTo nodeExtractJira).onCondition { pipeline.contentType == ContentType.JIRA })
                edge(
                    (nodePrepareExtractionPrompt forwardTo nodeExtractConfluence).onCondition {
                        pipeline.contentType ==
                            ContentType.CONFLUENCE
                    },
                )
                edge((nodePrepareExtractionPrompt forwardTo nodeExtractLog).onCondition { pipeline.contentType == ContentType.LOG })
                edge((nodePrepareExtractionPrompt forwardTo nodeExtractGeneric).onCondition { pipeline.contentType == ContentType.GENERIC })

                // Converge into IndexingContext builder (single node):
                edge(nodeExtractEmail forwardTo nodeBuildIndexingContext)
                edge(nodeExtractJira forwardTo nodeBuildIndexingContext)
                edge(nodeExtractConfluence forwardTo nodeBuildIndexingContext)
                edge(nodeExtractLog forwardTo nodeBuildIndexingContext)
                edge(nodeExtractGeneric forwardTo nodeBuildIndexingContext)

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
            "KoogQualifierAgent | Model selected: ${dynamicModel.id} | contextLength=${dynamicModel.contextLength}"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-qualifier") {
                        system(
                            """
                            You are JERVIS Qualification Agent.
                            You must follow the instructions and output structured JSON for structured requests.
                            Do not hallucinate. If unknown, use empty strings/lists/nulls.
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

    private fun chunkText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return listOf("")

        // Prefer paragraph-ish chunks; then cap size.
        val paras = trimmed.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        val coarse = if (paras.isEmpty()) listOf(trimmed) else paras

        val maxLen = 2500
        val out = ArrayList<String>(coarse.size)

        coarse.forEach { p ->
            if (p.length <= maxLen) {
                out += p
            } else {
                out += p.chunked(maxLen)
            }
        }

        return out
    }
}
