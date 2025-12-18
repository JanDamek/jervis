package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
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
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.io.path.writeBytes
import com.jervis.configuration.properties.KoogProperties
import com.jervis.domain.atlassian.shouldProcessWithVision
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.qualifier.types.ConfluenceExtractionOutput
import com.jervis.koog.qualifier.types.ContentType
import com.jervis.koog.qualifier.types.ContentTypeContext
import com.jervis.koog.qualifier.types.ContentTypeDetection
import com.jervis.koog.qualifier.types.EmailExtractionOutput
import com.jervis.koog.qualifier.types.GenericChunkingOutput
import com.jervis.koog.qualifier.types.IndexingContext
import com.jervis.koog.qualifier.types.JiraExtractionOutput
import com.jervis.koog.qualifier.types.LogSummarizationOutput
import com.jervis.koog.qualifier.types.VisionContext
import com.jervis.koog.tools.ContentClassificationTools
import com.jervis.koog.tools.GraphRagTools
import com.jervis.koog.tools.TaskTools
import com.jervis.rag.KnowledgeService
import com.jervis.service.background.PendingTaskService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import com.jervis.service.token.TokenCountingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Koog Qualifier Agent - Document intake, classification, and indexing to RAG + Graph.
 *
 * New architecture (tool-based, type-aware, unified indexing):
 *
 * **Phase 0: Vision Analysis (Two-Stage)**
 * - Stage 1: General description of visual content
 * - Stage 2: Type-specific details (after content type detection)
 *
 * **Phase 1: Content Type Detection**
 * - Determine content type: EMAIL, JIRA, CONFLUENCE, LOG, GENERIC
 * - Tool-based routing to appropriate extractor
 *
 * **Phase 2: Type-Specific Extraction**
 * - EMAIL: sender, recipients, subject, classification
 * - JIRA: key, status, assignee, epic, sprint + classification tool
 * - CONFLUENCE: author, title, topic
 * - LOG: summarization (not chunking) - summary, key events, critical details
 * - GENERIC: standard semantic chunking
 *
 * **Phase 3: Unified Indexing**
 * - Same indexing process for all types
 * - Store to RAG + create graph nodes
 * - Link chunks to base document node
 *
 * **Phase 4: Final Routing**
 * - Tool-based routing: DONE or LIFT_UP
 * - No field-based early routing
 *
 * @param promptExecutorFactory Factory for Koog prompt executors
 * @param graphService Service for graph database operations
 * @param knowledgeService Service for RAG operations
 * @param koogProperties Koog framework configuration
 * @param pendingTaskService Service for pending task state management
 * @param ollamaProviderSelector Selector for Ollama provider
 * @param taskManagementService Service for task scheduling
 * @param userTaskService Service for user task creation
 * @param linkContentService Service for link content processing
 * @param indexedLinkService Service for indexed link management
 * @param smartModelSelector Service for dynamic model selection
 * @param tokenCountingService Service for token counting
 * @param directoryStructureService Service for attachment storage
 * @param connectionService Service for connection management
 */
@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val graphService: GraphDBService,
    private val knowledgeService: KnowledgeService,
    private val koogProperties: KoogProperties,
    private val pendingTaskService: PendingTaskService,
    private val ollamaProviderSelector: OllamaProviderSelector,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val tokenCountingService: TokenCountingService,
    private val directoryStructureService: com.jervis.service.storage.DirectoryStructureService,
    private val connectionService: com.jervis.service.connection.ConnectionService,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    /**
     * Create AI agent for the given task with type-aware strategy.
     *
     * @param task Pending task document to process
     * @return Configured AI agent ready to run
     */
    fun create(task: PendingTaskDocument): AIAgent<String, String> {
        val agentStrategy =
            strategy<String, String>("Jervis Qualifier Strategy") {
                // =================================================================
                // PHASE 0: VISION ANALYSIS (Stage 1: General)
                // Stage 2 will be called after content type detection
                // =================================================================
                val nodeVisionStage1 by node<String, VisionContext>("🔍 Phase 0: Vision Stage 1") { _ ->
                    val visualAttachments = task.attachments.filter { it.shouldProcessWithVision() }
                    logger.info {
                        "🔍 VISION_STAGE1_START | correlationId=${task.correlationId} | " +
                            "totalAttachments=${task.attachments.size} | visualAttachments=${visualAttachments.size}"
                    }

                    if (visualAttachments.isEmpty()) {
                        logger.info { "🔍 VISION_SKIP | correlationId=${task.correlationId} | reason=no_visual_attachments" }
                        return@node VisionContext(
                            originalText = task.content,
                            generalVisionSummary = null,
                            typeSpecificVisionDetails = null,
                            attachments = task.attachments,
                        )
                    }

                    // Prepare temp files for image attachments (Koog attachments DSL requirement)
                    val tmpFiles: List<Pair<String, java.nio.file.Path>> =
                        visualAttachments
                            .filter { it.mimeType.startsWith("image/") }
                            .map { att ->
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
                            }

                    if (tmpFiles.isEmpty()) {
                        logger.info { "🔍 VISION_SKIP | correlationId=${task.correlationId} | reason=no_image_attachments" }
                        return@node VisionContext(
                            originalText = task.content,
                            generalVisionSummary = null,
                            typeSpecificVisionDetails = null,
                            attachments = task.attachments,
                        )
                    }

                    try {
                        // Build prompt with attachments.image(Path(...)) - Koog pattern
                        val visionPrompt = prompt("jervis-vision-stage1") {
                            system(
                                """
                                You are a vision assistant. Describe what you see in the attached images.
                                Be factual and concise. If something is unreadable, say so.
                                """.trimIndent(),
                            )
                            user {
                                markdown {
                                    +"Context text (may help disambiguate images):"
                                    br()
                                    +task.content
                                    br()
                                    br()
                                    +"Task: Provide a short general visual summary of the attachments."
                                }
                                attachments {
                                    tmpFiles.forEach { (_, tmpPath) ->
                                        image(Path(tmpPath.toString()))
                                    }
                                }
                            }
                        }

                        // Select vision model
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
                                outputReserve = 2000,
                            )

                        logger.info {
                            "🔍 VISION_MODEL_SELECTED | correlationId=${task.correlationId} | model=${visionModel.id}"
                        }

                        val executor = promptExecutorFactory.getExecutor(ollamaProviderSelector.getProvider())
                        val response = executor.execute(prompt = visionPrompt, model = visionModel, tools = emptyList()).first()
                        val summary = response.content?.trim().takeUnless { it.isNullOrBlank() }

                        logger.info {
                            "🔍 VISION_STAGE1_COMPLETE | correlationId=${task.correlationId} | hasGeneralSummary=${summary != null}"
                        }

                        VisionContext(
                            originalText = task.content,
                            generalVisionSummary = summary,
                            typeSpecificVisionDetails = null,
                            attachments = task.attachments,
                        )
                    } finally {
                        // Cleanup temp files
                        tmpFiles.forEach { (_, pth) -> runCatching { Files.deleteIfExists(pth) } }
                    }
                }

                // =================================================================
                // PHASE 1: CONTENT TYPE DETECTION
                // =================================================================
                val nodePrepareContentTypePrompt by node<VisionContext, String> { visionCtx ->
                    buildString {
                        append("Detect the content type of this document:\n\n")
                        append(visionCtx.originalText)
                        if (visionCtx.generalVisionSummary != null) {
                            append("\n\n**VISUAL CONTEXT:**\n")
                            append(visionCtx.generalVisionSummary)
                        }
                        append("\n\nReturn one of: EMAIL, JIRA, CONFLUENCE, LOG, GENERIC")
                    }
                }

                val nodeDetectContentType by nodeLLMRequestStructured<ContentTypeDetection>(
                    name = "📋 Phase 1: Detect Content Type",
                    examples =
                        listOf(
                            ContentTypeDetection(
                                contentType = "EMAIL",
                                reason = "Contains email headers (From, To, Subject) and email-style formatting",
                            ),
                            ContentTypeDetection(
                                contentType = "JIRA",
                                reason = "Contains JIRA key (SDB-2080), status, assignee fields",
                            ),
                            ContentTypeDetection(
                                contentType = "LOG",
                                reason = "Contains timestamps, ERROR/WARN messages, stack traces",
                            ),
                        ),
                )

                val nodeBuildContentTypeContext by node<Result<StructuredResponse<ContentTypeDetection>>, ContentTypeContext>(
                    "Build Content Type Context",
                ) { result ->
                    if (result.isFailure) {
                        logger.error { "📋 CONTENT_TYPE_DETECTION_FAILED | correlationId=${task.correlationId}" }
                        // Default to GENERIC on failure
                        return@node ContentTypeContext(
                            contentType = ContentType.GENERIC,
                            originalText = task.content,
                            visionContext =
                                VisionContext(
                                    originalText = task.content,
                                    generalVisionSummary = null,
                                    typeSpecificVisionDetails = null,
                                    attachments = task.attachments,
                                ),
                        )
                    }

                    val detection = result.getOrThrow().structure
                    val contentType =
                        when (detection.contentType.uppercase()) {
                            "EMAIL" -> ContentType.EMAIL
                            "JIRA" -> ContentType.JIRA
                            "CONFLUENCE" -> ContentType.CONFLUENCE
                            "LOG" -> ContentType.LOG
                            else -> ContentType.GENERIC
                        }

                    logger.info {
                        "📋 CONTENT_TYPE_DETECTED | correlationId=${task.correlationId} | " +
                            "contentType=$contentType | reason='${detection.reason}'"
                    }

                    // TODO: Get VisionContext from previous node (for now create empty)
                    ContentTypeContext(
                        contentType = contentType,
                        originalText = task.content,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                    )
                }

                // =================================================================
                // PHASE 2: TYPE-SPECIFIC EXTRACTION
                // =================================================================

                // Email extraction - prepare prompt
                val nodePrepareEmailPrompt by node<ContentTypeContext, String> { ctx ->
                    buildString {
                        append("Extract EMAIL information from this content:\n\n")
                        append(ctx.originalText)
                        append("\n\nExtract: sender, recipients, subject, classification")
                    }
                }

                val nodeExtractEmail by nodeLLMRequestStructured<EmailExtractionOutput>(
                    name = "📧 Extract Email Info",
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

                // JIRA extraction - prepare prompt
                val nodePrepareJiraPrompt by node<ContentTypeContext, String> { ctx ->
                    buildString {
                        append("Extract JIRA information from this content:\n\n")
                        append(ctx.originalText)
                        append("\n\nExtract: key, status, type, assignee, reporter, epic, sprint, changeDescription")
                    }
                }

                val nodeExtractJira by nodeLLMRequestStructured<JiraExtractionOutput>(
                    name = "🎫 Extract JIRA Info",
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
                                changeDescription = "Status changed from Open to In Progress. Added comment about reproduction steps.",
                            ),
                        ),
                )

                // Confluence extraction - prepare prompt
                val nodePrepareConfluencePrompt by node<ContentTypeContext, String> { ctx ->
                    buildString {
                        append("Extract CONFLUENCE information from this content:\n\n")
                        append(ctx.originalText)
                        append("\n\nExtract: author, title, topic")
                    }
                }

                val nodeExtractConfluence by nodeLLMRequestStructured<ConfluenceExtractionOutput>(
                    name = "📄 Extract Confluence Info",
                    examples =
                        listOf(
                            ConfluenceExtractionOutput(
                                author = "John Doe",
                                title = "API Documentation",
                                topic = "REST API endpoints and authentication",
                            ),
                        ),
                )

                // LOG summarization - prepare prompt
                val nodePrepareLogPrompt by node<ContentTypeContext, String> { ctx ->
                    buildString {
                        append("Summarize this LOG content:\n\n")
                        append(ctx.originalText)
                        append("\n\nProvide: summary, keyEvents, criticalDetails")
                    }
                }

                val nodeExtractLog by nodeLLMRequestStructured<LogSummarizationOutput>(
                    name = "📜 Summarize LOG",
                    examples =
                        listOf(
                            LogSummarizationOutput(
                                summary = "Application crashed due to NullPointerException in UserService",
                                keyEvents =
                                    listOf(
                                        "ERROR at 10:23:45 - NullPointerException",
                                        "Service restarted at 10:25:00",
                                    ),
                                criticalDetails =
                                    listOf(
                                        "UserService.java:42",
                                        "user_id=12345",
                                        "response_time=5000ms",
                                    ),
                            ),
                        ),
                )

                // Generic chunking - prepare prompt
                val nodePrepareGenericPrompt by node<ContentTypeContext, String> { ctx ->
                    buildString {
                        append("Chunk this GENERIC content into semantic blocks:\n\n")
                        append(ctx.originalText)
                        append("\n\nProvide: baseInfo (summary), chunks (list of semantic blocks)")
                    }
                }

                val nodeExtractGeneric by nodeLLMRequestStructured<GenericChunkingOutput>(
                    name = "📝 Chunk Generic Content",
                    examples =
                        listOf(
                            GenericChunkingOutput(
                                baseInfo = "Technical documentation about Kubernetes deployment strategies",
                                chunks =
                                    listOf(
                                        "Introduction: Kubernetes provides multiple deployment strategies...",
                                        "Rolling updates: The default strategy that gradually replaces pods...",
                                    ),
                            ),
                        ),
                )

                // =================================================================
                // BUILD INDEXING CONTEXT from extraction results
                // Convert type-specific extractions to unified IndexingContext
                // =================================================================

                val nodeBuildEmailIndexingContext by node<Result<StructuredResponse<EmailExtractionOutput>>, IndexingContext>(
                    "Build Email Indexing Context",
                ) { result ->
                    val extraction = result.getOrThrow().structure
                    val baseNodeKey = "email_${task.correlationId.replace("-", "_")}"

                    val baseInfo =
                        "Email from ${extraction.sender} to ${extraction.recipients.joinToString(", ")}: ${extraction.subject}"

                    // Create chunks from email content
                    val emailContent = task.content
                    val chunks =
                        if (emailContent.length > 3000) {
                            // Split long emails into chunks
                            emailContent.chunked(2500)
                        } else {
                            listOf(emailContent)
                        }

                    logger.info {
                        "📧 EMAIL_INDEXING_CONTEXT | correlationId=${task.correlationId} | " +
                            "sender=${extraction.sender} | chunks=${chunks.size}"
                    }

                    IndexingContext(
                        contentType = ContentType.EMAIL,
                        baseNodeKey = baseNodeKey,
                        baseInfo = baseInfo,
                        indexableChunks = chunks,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                        metadata =
                            mapOf(
                                "sender" to extraction.sender,
                                "recipients" to extraction.recipients.joinToString(", "),
                                "subject" to extraction.subject,
                                "classification" to extraction.classification,
                            ),
                    )
                }

                val nodeBuildJiraIndexingContext by node<Result<StructuredResponse<JiraExtractionOutput>>, IndexingContext>(
                    "Build JIRA Indexing Context",
                ) { result ->
                    val extraction = result.getOrThrow().structure
                    val baseNodeKey = "jira_${extraction.key.replace("-", "_").lowercase()}"

                    val baseInfo =
                        "[${extraction.key}] ${extraction.type} - ${extraction.status} - ${extraction.changeDescription}"

                    // Create chunks from JIRA content
                    val jiraContent = task.content
                    val chunks =
                        if (jiraContent.length > 3000) {
                            jiraContent.chunked(2500)
                        } else {
                            listOf(jiraContent)
                        }

                    logger.info {
                        "🎫 JIRA_INDEXING_CONTEXT | correlationId=${task.correlationId} | " +
                            "key=${extraction.key} | chunks=${chunks.size}"
                    }

                    IndexingContext(
                        contentType = ContentType.JIRA,
                        baseNodeKey = baseNodeKey,
                        baseInfo = baseInfo,
                        indexableChunks = chunks,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                        metadata =
                            mapOf(
                                "key" to extraction.key,
                                "status" to extraction.status,
                                "type" to extraction.type,
                                "assignee" to extraction.assignee,
                                "reporter" to extraction.reporter,
                                "epic" to (extraction.epic ?: ""),
                                "sprint" to (extraction.sprint ?: ""),
                            ),
                    )
                }

                val nodeBuildConfluenceIndexingContext by node<Result<StructuredResponse<ConfluenceExtractionOutput>>, IndexingContext>(
                    "Build Confluence Indexing Context",
                ) { result ->
                    val extraction = result.getOrThrow().structure
                    val baseNodeKey = "confluence_${task.correlationId.replace("-", "_")}"

                    val baseInfo = "${extraction.title} by ${extraction.author} - ${extraction.topic}"

                    // Create chunks from Confluence content
                    val confluenceContent = task.content
                    val chunks =
                        if (confluenceContent.length > 3000) {
                            confluenceContent.chunked(2500)
                        } else {
                            listOf(confluenceContent)
                        }

                    logger.info {
                        "📄 CONFLUENCE_INDEXING_CONTEXT | correlationId=${task.correlationId} | " +
                            "title=${extraction.title} | chunks=${chunks.size}"
                    }

                    IndexingContext(
                        contentType = ContentType.CONFLUENCE,
                        baseNodeKey = baseNodeKey,
                        baseInfo = baseInfo,
                        indexableChunks = chunks,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                        metadata =
                            mapOf(
                                "author" to extraction.author,
                                "title" to extraction.title,
                                "topic" to extraction.topic,
                            ),
                    )
                }

                val nodeBuildLogIndexingContext by node<Result<StructuredResponse<LogSummarizationOutput>>, IndexingContext>(
                    "Build LOG Indexing Context",
                ) { result ->
                    val extraction = result.getOrThrow().structure
                    val baseNodeKey = "log_${task.correlationId.replace("-", "_")}"

                    val baseInfo = extraction.summary

                    // For logs, index summary + key events + critical details (NOT raw log chunks)
                    val chunks =
                        listOf(
                            "Summary: ${extraction.summary}",
                            "Key Events:\n${extraction.keyEvents.joinToString("\n- ", "- ")}",
                            "Critical Details:\n${extraction.criticalDetails.joinToString("\n- ", "- ")}",
                        )

                    logger.info {
                        "📜 LOG_INDEXING_CONTEXT | correlationId=${task.correlationId} | " +
                            "keyEvents=${extraction.keyEvents.size} | chunks=${chunks.size}"
                    }

                    IndexingContext(
                        contentType = ContentType.LOG,
                        baseNodeKey = baseNodeKey,
                        baseInfo = baseInfo,
                        indexableChunks = chunks,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                        metadata =
                            mapOf(
                                "summary" to extraction.summary,
                                "keyEventsCount" to extraction.keyEvents.size.toString(),
                                "criticalDetailsCount" to extraction.criticalDetails.size.toString(),
                            ),
                    )
                }

                val nodeBuildGenericIndexingContext by node<Result<StructuredResponse<GenericChunkingOutput>>, IndexingContext>(
                    "Build Generic Indexing Context",
                ) { result ->
                    val extraction = result.getOrThrow().structure
                    val baseNodeKey = "doc_${task.correlationId.replace("-", "_")}"

                    logger.info {
                        "📝 GENERIC_INDEXING_CONTEXT | correlationId=${task.correlationId} | " +
                            "chunks=${extraction.chunks.size}"
                    }

                    IndexingContext(
                        contentType = ContentType.GENERIC,
                        baseNodeKey = baseNodeKey,
                        baseInfo = extraction.baseInfo,
                        indexableChunks = extraction.chunks,
                        visionContext =
                            VisionContext(
                                originalText = task.content,
                                generalVisionSummary = null,
                                typeSpecificVisionDetails = null,
                                attachments = task.attachments,
                            ),
                        metadata = emptyMap(),
                    )
                }

                // =================================================================
                // PHASE 3: UNIFIED INDEXING (same for all content types)
                // =================================================================

                val nodeCreateBaseNode by node<IndexingContext, IndexingContext>("Create Base Node") { ctx ->
                    logger.info {
                        "📦 CREATE_BASE_NODE | correlationId=${task.correlationId} | " +
                            "baseNodeKey=${ctx.baseNodeKey} | contentType=${ctx.contentType}"
                    }

                    try {
                        // Store base info to RAG
                        val baseChunkId =
                            knowledgeService.storeChunk(
                                com.jervis.rag.StoreChunkRequest(
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                    content = ctx.baseInfo,
                                    graphRefs = listOf(ctx.baseNodeKey),
                                    sourceUrn = task.sourceUrn,
                                ),
                            )

                        // Create base graph node
                        graphService.upsertNode(
                            clientId = task.clientId,
                            node =
                                com.jervis.graphdb.model.GraphNode(
                                    key = ctx.baseNodeKey,
                                    entityType = ctx.contentType.name.lowercase(),
                                    ragChunks = listOf(baseChunkId),
                                ),
                        )

                        logger.info {
                            "✅ BASE_NODE_CREATED | correlationId=${task.correlationId} | " +
                                "nodeKey=${ctx.baseNodeKey} | chunkId=$baseChunkId"
                        }
                    } catch (e: Exception) {
                        logger.error(e) {
                            "❌ BASE_NODE_FAILED | correlationId=${task.correlationId} | " +
                                "baseNodeKey=${ctx.baseNodeKey} | error=${e.message}"
                        }
                        throw e
                    }

                    ctx
                }

                // Processing state for chunk iteration
                data class ChunkProcessingState(
                    val ctx: IndexingContext,
                    val currentIndex: Int,
                ) {
                    fun hasMore(): Boolean = currentIndex < ctx.indexableChunks.size

                    fun nextChunk(): String = ctx.indexableChunks[currentIndex]
                }

                val nodeInitChunkProcessing by node<IndexingContext, ChunkProcessingState>(
                    "Initialize Chunk Processing",
                ) { ctx ->
                    logger.info {
                        "🔄 INIT_CHUNK_PROCESSING | correlationId=${task.correlationId} | " +
                            "totalChunks=${ctx.indexableChunks.size}"
                    }
                    ChunkProcessingState(ctx = ctx, currentIndex = 0)
                }

                // Subgraph for processing chunks (loop)
                val chunkProcessingSubgraph by subgraph<ChunkProcessingState, ChunkProcessingState>(
                    name = "Chunk Processing Loop",
                ) {
                    val nodeCheckHasMore by node<ChunkProcessingState, ChunkProcessingState>("Check Has More Chunks") { state ->
                        state
                    }

                    val nodePrepareChunkForIndexing by node<ChunkProcessingState, String>("Prepare Chunk") { state ->
                        val chunk = state.nextChunk()
                        val chunkIndex = state.currentIndex + 1
                        val totalChunks = state.ctx.indexableChunks.size

                        logger.info {
                            "📄 PREPARE_CHUNK | correlationId=${task.correlationId} | " +
                                "chunk=$chunkIndex/$totalChunks | baseNode=${state.ctx.baseNodeKey}"
                        }

                        // Build prompt with vision context if available
                        buildString {
                            append("Index this chunk to knowledge graph and RAG.\n\n")
                            append("**BASE_NODE_KEY:** ${state.ctx.baseNodeKey}\n")
                            append("**CONTENT_TYPE:** ${state.ctx.contentType}\n\n")

                            if (state.ctx.visionContext.generalVisionSummary != null) {
                                append("**VISUAL CONTEXT:**\n")
                                append(state.ctx.visionContext.generalVisionSummary)
                                append("\n\n")
                            }

                            if (state.ctx.visionContext.typeSpecificVisionDetails != null) {
                                append("**TYPE-SPECIFIC VISUAL DETAILS:**\n")
                                append(state.ctx.visionContext.typeSpecificVisionDetails)
                                append("\n\n")
                            }

                            append("**CHUNK $chunkIndex of $totalChunks:**\n")
                            append(chunk)
                            append("\n\n")
                            append(
                                "Use storeKnowledge tool to:\n" +
                                    "1. Extract entities and concepts\n" +
                                    "2. Create graph relationships\n" +
                                    "3. MUST link to base node via -[PART_OF]-> ${state.ctx.baseNodeKey}\n",
                            )
                        }
                    }

                    val nodeSendChunkRequest by nodeLLMRequest("Send Chunk Index Request")
                    val nodeExecuteStoreKnowledge by nodeExecuteTool("Execute storeKnowledge")
                    val nodeSendStoreResult by nodeLLMSendToolResult("Send Store Result")

                    val nodeAdvanceToNextChunk by node<String, ChunkProcessingState>("Advance to Next Chunk") { _ ->
                        // Get current state from subgraph context (simplified - in real code would need proper state management)
                        // For now, return dummy state - will be fixed with proper edge wiring
                        ChunkProcessingState(
                            ctx =
                                IndexingContext(
                                    contentType = ContentType.GENERIC,
                                    baseNodeKey = "dummy",
                                    baseInfo = "",
                                    indexableChunks = emptyList(),
                                    visionContext =
                                        VisionContext(
                                            originalText = "",
                                            generalVisionSummary = null,
                                            typeSpecificVisionDetails = null,
                                            attachments = emptyList(),
                                        ),
                                ),
                            currentIndex = 0,
                        )
                    }

                    // Chunk processing edges
                    edge(nodeStart forwardTo nodeCheckHasMore)
                    edge((nodeCheckHasMore forwardTo nodePrepareChunkForIndexing).onCondition { it.hasMore() })
                    edge((nodeCheckHasMore forwardTo nodeFinish).onCondition { !it.hasMore() })

                    edge(nodePrepareChunkForIndexing forwardTo nodeSendChunkRequest)
                    edge((nodeSendChunkRequest forwardTo nodeExecuteStoreKnowledge).onToolCall { true })
                    edge(nodeExecuteStoreKnowledge forwardTo nodeSendStoreResult)
                    edge((nodeSendStoreResult forwardTo nodeExecuteStoreKnowledge).onToolCall { true }) // Handle multiple tool calls
                    edge((nodeSendStoreResult forwardTo nodeAdvanceToNextChunk).onAssistantMessage { true })

                    edge((nodeAdvanceToNextChunk forwardTo nodeCheckHasMore).onCondition { it.hasMore() })
                    edge((nodeAdvanceToNextChunk forwardTo nodeFinish).onCondition { !it.hasMore() })
                }

                // =================================================================
                // PHASE 4: FINAL ROUTING (tool-based: DONE or LIFT_UP)
                // =================================================================

                val nodePrepareRoutingRequest by node<ChunkProcessingState, String>("Prepare Routing Request") { state ->
                    logger.info {
                        "🎯 PREPARE_ROUTING | correlationId=${task.correlationId} | " +
                            "contentType=${state.ctx.contentType} | processedChunks=${state.ctx.indexableChunks.size}"
                    }

                    """
All chunks have been processed and indexed to RAG + Graph.

**SUMMARY:**
- Content Type: ${state.ctx.contentType}
- Base Node: ${state.ctx.baseNodeKey}
- Processed Chunks: ${state.ctx.indexableChunks.size}
- All chunks linked to base node via -[PART_OF]-> edges

**DECISION:**
Call routeTask tool:
- routeTask("DONE") if task is complete and indexed
- routeTask("LIFT_UP") if requires complex analysis, coding, or user consultation

**CALL THE TOOL NOW.**
                    """.trimIndent()
                }

                val routingSubgraph by subgraph<String, String>(name = "Final Routing") {
                    val nodeSendRoutingRequest by nodeLLMRequest("Send Routing Request")
                    val nodeExecuteRouting by nodeExecuteTool("Execute routeTask")
                    val nodeSendRoutingResult by nodeLLMSendToolResult("Send Routing Result")

                    edge(nodeStart forwardTo nodeSendRoutingRequest)
                    edge((nodeSendRoutingRequest forwardTo nodeExecuteRouting).onToolCall { true })
                    edge(nodeExecuteRouting forwardTo nodeSendRoutingResult)
                    edge((nodeSendRoutingResult forwardTo nodeFinish).onAssistantMessage { true })
                }

                // =================================================================
                // MAIN FLOW EDGES
                // =================================================================

                // Phase 0: Vision Stage 1
                edge(nodeStart forwardTo nodeVisionStage1)

                // Phase 1: Content type detection
                edge(nodeVisionStage1 forwardTo nodePrepareContentTypePrompt)
                edge(nodePrepareContentTypePrompt forwardTo nodeDetectContentType)
                edge(nodeDetectContentType forwardTo nodeBuildContentTypeContext)

                // Phase 2: Route to type-specific extractors based on content type
                edge(
                    (nodeBuildContentTypeContext forwardTo nodePrepareEmailPrompt).onCondition { ctx ->
                        ctx.contentType == ContentType.EMAIL
                    },
                )
                edge(
                    (nodeBuildContentTypeContext forwardTo nodePrepareJiraPrompt).onCondition { ctx ->
                        ctx.contentType == ContentType.JIRA
                    },
                )
                edge(
                    (nodeBuildContentTypeContext forwardTo nodePrepareConfluencePrompt).onCondition { ctx ->
                        ctx.contentType == ContentType.CONFLUENCE
                    },
                )
                edge(
                    (nodeBuildContentTypeContext forwardTo nodePrepareLogPrompt).onCondition { ctx ->
                        ctx.contentType == ContentType.LOG
                    },
                )
                edge(
                    (nodeBuildContentTypeContext forwardTo nodePrepareGenericPrompt).onCondition { ctx ->
                        ctx.contentType == ContentType.GENERIC
                    },
                )

                // Connect prepare nodes to extract nodes
                edge(nodePrepareEmailPrompt forwardTo nodeExtractEmail)
                edge(nodePrepareJiraPrompt forwardTo nodeExtractJira)
                edge(nodePrepareConfluencePrompt forwardTo nodeExtractConfluence)
                edge(nodePrepareLogPrompt forwardTo nodeExtractLog)
                edge(nodePrepareGenericPrompt forwardTo nodeExtractGeneric)

                // Build IndexingContext from extraction results
                edge(nodeExtractEmail forwardTo nodeBuildEmailIndexingContext)
                edge(nodeExtractJira forwardTo nodeBuildJiraIndexingContext)
                edge(nodeExtractConfluence forwardTo nodeBuildConfluenceIndexingContext)
                edge(nodeExtractLog forwardTo nodeBuildLogIndexingContext)
                edge(nodeExtractGeneric forwardTo nodeBuildGenericIndexingContext)

                // Phase 3: Unified indexing (all types converge here)
                edge(nodeBuildEmailIndexingContext forwardTo nodeCreateBaseNode)
                edge(nodeBuildJiraIndexingContext forwardTo nodeCreateBaseNode)
                edge(nodeBuildConfluenceIndexingContext forwardTo nodeCreateBaseNode)
                edge(nodeBuildLogIndexingContext forwardTo nodeCreateBaseNode)
                edge(nodeBuildGenericIndexingContext forwardTo nodeCreateBaseNode)

                // Initialize and process chunks
                edge(nodeCreateBaseNode forwardTo nodeInitChunkProcessing)
                edge(nodeInitChunkProcessing forwardTo chunkProcessingSubgraph)

                // Phase 4: Final routing
                edge(chunkProcessingSubgraph forwardTo nodePrepareRoutingRequest)
                edge(nodePrepareRoutingRequest forwardTo routingSubgraph)
                edge(routingSubgraph forwardTo nodeFinish)
            }

        // Dynamic model selection
        val inputTokens = tokenCountingService.countTokens(task.content)
        val dynamicOutputReserve = (inputTokens * 1.5).toInt().coerceAtLeast(2000)

        val dynamicModel =
            smartModelSelector.selectModel(
                baseModelName = MODEL_QUALIFIER_NAME,
                inputContent = task.content,
                outputReserve = dynamicOutputReserve,
            )

        logger.info {
            "KoogQualifierAgent | Model selected: ${dynamicModel.id} | " +
                "contextLength=${dynamicModel.contextLength} | inputTokens=$inputTokens"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-qualifier") {
                        system("You are JERVIS Qualification Agent. Follow phase-specific instructions.")
                    },
                model = dynamicModel,
                maxAgentIterations = koogProperties.maxIterations,
            )

        val toolRegistry =
            ToolRegistry {
                tools(GraphRagTools(graphService, knowledgeService, task))
                tools(
                    TaskTools(
                        task = task,
                        taskManagementService = taskManagementService,
                        userTaskService = userTaskService,
                        pendingTaskService = pendingTaskService,
                        linkContentService = linkContentService,
                        indexedLinkService = indexedLinkService,
                        connectionService = connectionService,
                        coroutineScope = scope,
                    ),
                )
                tools(ContentClassificationTools(task, userTaskService, scope))
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

    /**
     * Run qualification agent for the given task.
     *
     * @param task Pending task to process
     * @return Qualifier result
     */
    suspend fun run(task: PendingTaskDocument): QualifierResult {
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

            // Check if retryable
            val isRetryable = isRetryableError(e)

            if (isRetryable) {
                try {
                    pendingTaskService.updateState(
                        taskId = task.id,
                        expected = PendingTaskStateEnum.QUALIFYING,
                        next = PendingTaskStateEnum.READY_FOR_QUALIFICATION,
                    )
                    logger.info { "TASK_RETURNED_FOR_RETRY | taskId=${task.id}" }
                } catch (stateError: Exception) {
                    logger.error(stateError) { "Failed to return task for retry: ${task.id}" }
                }
                throw e
            }

            // Mark as error and escalate
            try {
                val reason = "KoogQualifierAgent failed: ${e.message ?: e::class.simpleName}"
                pendingTaskService.markAsError(taskId = task.id, errorMessage = reason)
                userTaskService.failAndEscalateToUserTask(task = task, reason = reason, error = e)
                logger.info { "TASK_MARKED_ERROR_AND_ESCALATED | taskId=${task.id}" }
            } catch (escalationError: Exception) {
                logger.error(escalationError) { "Failed to escalate task: ${task.id}" }
            }

            throw e
        }
    }

    /**
     * Check if error is retryable (timeouts, network issues) vs permanent (logic errors).
     */
    private fun isRetryableError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        val causeMessage = e.cause?.message?.lowercase() ?: ""

        return when {
            message.contains("timeout") || causeMessage.contains("timeout") -> true
            message.contains("socket") || message.contains("connection") -> true
            message.contains("502") || message.contains("503") || message.contains("504") -> true
            message.contains("stuck in node") -> false
            else -> false
        }
    }

    /**
     * Qualifier result.
     */
    data class QualifierResult(
        val completed: Boolean,
    )

    companion object {
        const val MODEL_QUALIFIER_NAME = "qwen3-coder-tool:30b"
    }
}
