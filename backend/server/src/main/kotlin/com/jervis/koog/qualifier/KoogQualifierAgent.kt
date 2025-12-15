package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.jervis.configuration.properties.KoogProperties
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.entity.shouldProcessWithVision
import com.jervis.graphdb.GraphDBService
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
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
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    // Data classes for type-safe data flow
    data class Phase1Result(
        val chunks: List<String>,
        val baseNodeKey: String? = null, // Base document node key (e.g., "doc_literature_screening")
        val baseInfo: String? = null, // High-level document description for base node
        val earlyRouting: String? = null, // "DONE" or "LIFT_UP" if agent decided to skip processing
    )

    data class ChunkResult(
        val chunkId: String,
        val mainNodeKey: String,
    )

    data class ProcessingState(
        val chunks: List<String>,
        val baseNodeKey: String, // Base document node to link chunks to
        val baseInfo: String?, // Not used anymore (baseInfo already prepended to chunks)
        val processedResults: List<ChunkResult>,
        val currentIndex: Int,
    ) {
        fun hasMoreChunks(): Boolean = currentIndex < chunks.size

        fun nextChunk(): String? = chunks.getOrNull(currentIndex)

        fun withResult(result: ChunkResult): ProcessingState =
            copy(
                processedResults = processedResults + result,
                currentIndex = currentIndex + 1,
            )
    }

    fun create(task: PendingTaskDocument): AIAgent<String, String> {
        // Shared state for Phase 1 to capture routing
        var capturedRouting: String? = null

        // Store original document for retry
        var originalDocument: String = ""

        val agentStrategy =
            strategy<String, String>("Jervis Qualifier Map-Reduce Strategy") {
                // =================================================================
                // PHASE 0: VISION AUGMENTATION (conditional - only if attachments exist)
                // =================================================================
                val visionSubgraph by subgraph<VisionAugmentationInput, VisionAugmentationOutput>(
                    name = "🔍 Phase 0: Vision Augmentation"
                ) {
                    // Node: Check if we need vision processing
                    val nodeCheckAttachments by node<VisionAugmentationInput, VisionAugmentationInput>(
                        "Check Attachments"
                    ) { input ->
                        val visualAttachments = input.attachments.filter { it.shouldProcessWithVision() }
                        logger.info {
                            "🔍 VISION_CHECK | correlationId=${task.correlationId} | " +
                                "totalAttachments=${input.attachments.size} | visualAttachments=${visualAttachments.size}"
                        }
                        input
                    }

                    // Node: Skip vision if no visual attachments
                    val nodeSkipVision by node<VisionAugmentationInput, VisionAugmentationOutput>(
                        "Skip Vision"
                    ) { input ->
                        logger.info { "🔍 VISION_SKIP | correlationId=${task.correlationId} | reason=no_visual_attachments" }
                        VisionAugmentationOutput(
                            augmentedContent = input.textContent,
                            processedAttachments = input.attachments,
                        )
                    }

                    // Node: Load attachment binary data
                    // FAIL-FAST: If any attachment fails to load, entire task fails
                    val nodeLoadAttachments by node<VisionAugmentationInput, List<AttachmentVisionRequest>>(
                        "Load Attachments"
                    ) { input ->
                        val visualAttachments = input.attachments.filter { it.shouldProcessWithVision() }
                        logger.info {
                            "🔍 VISION_LOAD | correlationId=${task.correlationId} | loading=${visualAttachments.size} attachments"
                        }

                        val loaded = visualAttachments.map { attachment ->
                            loadAttachmentData(attachment, directoryStructureService)
                        }

                        logger.info {
                            "🔍 VISION_LOADED | correlationId=${task.correlationId} | loaded=${loaded.size}"
                        }
                        loaded
                    }

                    // Node: Process each attachment with vision model
                    // FAIL-FAST: If ANY attachment vision analysis fails, entire task fails
                    val nodeProcessVision by node<List<AttachmentVisionRequest>, List<AttachmentVisionResponse>>(
                        "Process Vision"
                    ) { attachments ->
                        val results = mutableListOf<AttachmentVisionResponse>()

                        for (attachment in attachments) {
                            logger.info {
                                "🔍 VISION_PROCESS | correlationId=${task.correlationId} | " +
                                    "file=${attachment.filename} | size=${attachment.widthPixels}x${attachment.heightPixels}"
                            }

                            // Select vision model dynamically based on image size
                            val visionModel = if (attachment.widthPixels != null && attachment.heightPixels != null) {
                                smartModelSelector.selectVisionModel(
                                    baseModelName = "qwen3-vl:latest",
                                    textPrompt = PROMPT_VISION,
                                    images = listOf(
                                        SmartModelSelector.ImageMetadata(
                                            widthPixels = attachment.widthPixels,
                                            heightPixels = attachment.heightPixels,
                                            format = attachment.mimeType,
                                        )
                                    ),
                                    outputReserve = 2000,
                                )
                            } else {
                                // Fallback for non-image files (PDFs) - use 8k tier
                                smartModelSelector.selectVisionModel(
                                    baseModelName = "qwen3-vl:latest",
                                    textPrompt = PROMPT_VISION,
                                    images = listOf(
                                        SmartModelSelector.ImageMetadata(
                                            widthPixels = 1920,
                                            heightPixels = 1080,
                                            format = attachment.mimeType,
                                        )
                                    ),
                                    outputReserve = 2000,
                                )
                            }

                            logger.debug {
                                "🔍 VISION_MODEL | correlationId=${task.correlationId} | " +
                                    "file=${attachment.filename} | model=${visionModel.id}"
                            }

                            // Execute vision analysis (fail-fast on error)
                            // Uses direct Ollama HTTP API since Koog Prompt doesn't support vision yet
                            val description = executeVisionAnalysis(
                                attachment = attachment,
                                visionModel = visionModel,
                                ollamaBaseUrl = ollamaProviderSelector.getBaseUrl(),
                            )

                            val visionResult = com.jervis.entity.VisionAnalysisResult(
                                model = visionModel.id,
                                description = description,
                                confidence = 0.0,
                                analyzedAt = java.time.Instant.now(),
                            )

                            results.add(
                                AttachmentVisionResponse(
                                    attachmentId = attachment.attachmentId,
                                    visionAnalysis = visionResult,
                                )
                            )

                            logger.info {
                                "🔍 VISION_SUCCESS | correlationId=${task.correlationId} | " +
                                    "file=${attachment.filename} | descriptionLength=${description.length}"
                            }
                        }

                        results
                    }

                    // Node: Augment content with vision results
                    val nodeAugmentContent by node<List<AttachmentVisionResponse>, VisionAugmentationOutput>(
                        "Augment Content"
                    ) { visionResults ->
                        val augmentedText = augmentContentWithVision(
                            originalContent = task.content,
                            visionResults = visionResults,
                        )

                        val updatedAttachments = updateAttachmentsWithVision(
                            originalAttachments = task.attachments,
                            visionResults = visionResults,
                        )

                        logger.info {
                            "🔍 VISION_AUGMENT | correlationId=${task.correlationId} | " +
                                "originalLength=${task.content.length} | augmentedLength=${augmentedText.length} | " +
                                "visionResults=${visionResults.size}"
                        }

                        VisionAugmentationOutput(
                            augmentedContent = augmentedText,
                            processedAttachments = updatedAttachments,
                        )
                    }

                    // Vision subgraph routing
                    edge(nodeStart forwardTo nodeCheckAttachments)
                    edge((nodeCheckAttachments forwardTo nodeSkipVision).onCondition { input ->
                        input.attachments.none { it.shouldProcessWithVision() }
                    })
                    edge((nodeCheckAttachments forwardTo nodeLoadAttachments).onCondition { input ->
                        input.attachments.any { it.shouldProcessWithVision() }
                    })
                    edge(nodeLoadAttachments forwardTo nodeProcessVision)
                    edge(nodeProcessVision forwardTo nodeAugmentContent)
                    edge(nodeSkipVision forwardTo nodeFinish)
                    edge(nodeAugmentContent forwardTo nodeFinish)
                }

                // Node: Prepare vision input
                val nodePrepareVision by node<String, VisionAugmentationInput>("Prepare Vision Input") { _ ->
                    VisionAugmentationInput(
                        textContent = task.content,
                        attachments = task.attachments,
                    )
                }

                // Node: Extract text from vision output
                val nodeExtractAugmentedText by node<VisionAugmentationOutput, String>("Extract Augmented Text") { output ->
                    // Update task attachments with vision analysis (mutable for Phase 2 access)
                    // Note: This is a workaround - ideally we'd pass this through the strategy
                    output.augmentedContent
                }

                // =================================================================
                // PHASE 1: QUALIFY & SPLIT with LLM analysis
                // =================================================================
                val phase1Subgraph by subgraph<String, Phase1Result>(name = "📋 Phase 1: Qualify & Split") {
                    val nodePreparePh1 by node<String, String>("Prepare Phase 1 Input") { input ->
                        logger.info { "📋 PHASE_1_START | correlationId=${task.correlationId} | contentLength=${input.length}" }
                        // Reset state and save original document
                        capturedRouting = null
                        originalDocument = input

                        // Return input with phase-specific prompt injected
                        val prompt =
                            """
${PROMPT_PHASE_1}

<DOCUMENT_CONTENT>
$input
</DOCUMENT_CONTENT>

**FINAL INSTRUCTION:**
Process the content inside <DOCUMENT_CONTENT> tags above.
If valid content, output the chunks separated by ---CHUNK--- followed by ---BASEINFO---.
Use EXACT RAW TEXT from document - do not write placeholders.

START NOW:
                            """.trimIndent()

                        logger.debug {
                            "📋 PHASE_1_PROMPT | correlationId=${task.correlationId} | promptLength=${prompt.length} | promptPreview=${
                                prompt.take(
                                    500,
                                )
                            }"
                        }
                        prompt
                    }

                    val nodeSendPh1Request by nodeLLMRequest(name = "Send Phase 1 LLM Request")
                    val nodeExecutePh1Tool by nodeExecuteTool(name = "Execute Phase 1 Tool")
                    val nodeSendPh1Result by nodeLLMSendToolResult(name = "Send Phase 1 Tool Result")

                    // Track retry attempts
                    var retryCount = 0
                    val maxRetries = 1

                    // Use a data class to handle both success and retry cases
                    data class Ph1ParseResult(
                        val success: Boolean,
                        val result: Phase1Result?,
                        val originalMessage: String? = null,
                        val retryAttempt: Int = 0, // Track retry per parse attempt
                    )

                    // Track current retry attempt in a mutable variable
                    var currentRetryAttempt = 0

                    val nodeExtractPh1Result by node<String, Ph1ParseResult>("Extract Phase 1 Result") { assistantMessage ->
                        logger.debug {
                            "📋 PHASE_1_RESPONSE | correlationId=${task.correlationId} | responseLength=${assistantMessage.length} | " +
                                "responsePreview=${assistantMessage.take(500)}"
                        }

                        // Check if routing was captured via tool call (DONE or LIFT_UP)
                        if (capturedRouting != null) {
                            logger.info {
                                "📋 PHASE_1_EARLY_ROUTING | correlationId=${task.correlationId} | decision=$capturedRouting | " +
                                    "reason='skip indexing' | contentPreview=${originalDocument?.take(200)}"
                            }
                            return@node Ph1ParseResult(
                                success = true,
                                result =
                                    Phase1Result(
                                        chunks = emptyList(),
                                        baseNodeKey = null,
                                        baseInfo = null,
                                        earlyRouting = capturedRouting,
                                    ),
                            )
                        }

                        // Parse plain text with delimiter from assistant message
                        try {
                            val text = assistantMessage.trim()

                            // Split by delimiter: ---CHUNK--- or ===CHUNK===
                            val chunkDelimiter = if (text.contains("---CHUNK---")) "---CHUNK---" else "===CHUNK==="
                            val baseInfoDelimiter =
                                if (text.contains("---BASEINFO---")) "---BASEINFO---" else "===BASEINFO==="

                            // Check if text contains delimiters
                            if (!text.contains(chunkDelimiter) || !text.contains(baseInfoDelimiter)) {
                                logger.warn {
                                    "Phase 1: Missing delimiters in response. Expected $chunkDelimiter and $baseInfoDelimiter"
                                }
                                return@node Ph1ParseResult(
                                    success = false,
                                    result = null,
                                    originalMessage = assistantMessage,
                                    retryAttempt = currentRetryAttempt,
                                )
                            }

                            // Split into parts
                            val parts = text.split(baseInfoDelimiter)
                            if (parts.size != 2) {
                                logger.warn { "Phase 1: Expected 2 parts split by $baseInfoDelimiter, got ${parts.size}" }
                                return@node Ph1ParseResult(
                                    success = false,
                                    result = null,
                                    originalMessage = assistantMessage,
                                    retryAttempt = currentRetryAttempt,
                                )
                            }

                            val chunksSection = parts[0].trim()
                            val baseInfo = parts[1].trim()

                            // Extract chunks
                            val chunks =
                                chunksSection
                                    .split(chunkDelimiter)
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }

                            if (chunks.isEmpty() || baseInfo.isEmpty()) {
                                logger.warn { "Phase 1: Parsed empty chunks or baseInfo" }
                                return@node Ph1ParseResult(
                                    success = false,
                                    result = null,
                                    originalMessage = assistantMessage,
                                    retryAttempt = currentRetryAttempt,
                                )
                            }

                            logger.info {
                                "📋 PHASE_1_COMPLETE | correlationId=${task.correlationId} | chunksCount=${chunks.size} | " +
                                    "baseInfo='${baseInfo.take(100)}'"
                            }

                            // Reset retry attempt on success
                            currentRetryAttempt = 0

                            val baseNodeKey = "doc_${task.correlationId.replace("-", "_")}"
                            Ph1ParseResult(
                                success = true,
                                result =
                                    Phase1Result(
                                        chunks = chunks,
                                        baseNodeKey = baseNodeKey,
                                        baseInfo = baseInfo,
                                        earlyRouting = null,
                                    ),
                            )
                        } catch (e: Exception) {
                            logger.error(e) { "Phase 1: Text parsing error" }
                            Ph1ParseResult(
                                success = false,
                                result = null,
                                originalMessage = assistantMessage,
                                retryAttempt = currentRetryAttempt,
                            )
                        }
                    }

                    val nodeUnwrapSuccess by node<Ph1ParseResult, Phase1Result>("Unwrap Phase 1 Success") { parseResult ->
                        parseResult.result!!
                    }

                    data class RetryDecision(
                        val shouldRetry: Boolean,
                        val prompt: String?,
                        val nextRetryAttempt: Int,
                    )

                    val nodeRetryOrFail by node<Ph1ParseResult, RetryDecision>("Phase 1 Retry Decision") { parseResult ->
                        val currentAttempt = parseResult.retryAttempt
                        val nextAttempt = currentAttempt + 1

                        logger.warn {
                            "Phase 1: Invalid response (attempt $nextAttempt/$maxRetries). Model did not use delimiters (---CHUNK--- / ---BASEINFO---)."
                        }

                        if (nextAttempt > maxRetries) {
                            logger.error { "Phase 1: Max retries ($maxRetries) exceeded, will mark as ERROR" }
                            return@node RetryDecision(
                                shouldRetry = false,
                                prompt = null,
                                nextRetryAttempt = nextAttempt,
                            )
                        }

                        // Strong retry prompt with full original document
                        val retryPrompt =
                            """
🚨 ERROR: You did NOT use the required format.

**WHAT YOU MUST OUTPUT:**

[EXACT RAW TEXT FROM DOCUMENT - FIRST PART]

---CHUNK---

[EXACT RAW TEXT FROM DOCUMENT - SECOND PART]

---BASEINFO---

[One sentence summary]

**NEGATIVE CONSTRAINTS:**
❌ Do NOT output "chunk1 text" or placeholders
❌ Do NOT explain or chat
❌ Do NOT use JSON
❌ Use EXACT RAW TEXT from the document below

<DOCUMENT_CONTENT>
$originalDocument
</DOCUMENT_CONTENT>

**FINAL INSTRUCTION:**
Output chunks with EXACT text from <DOCUMENT_CONTENT> separated by ---CHUNK---, then ---BASEINFO---.

START NOW:
                            """.trimIndent()

                        RetryDecision(shouldRetry = true, prompt = retryPrompt, nextRetryAttempt = nextAttempt)
                    }

                    val nodeHandleRetryFailed by node<RetryDecision, Phase1Result>("Handle Phase 1 Retry Failed") {
                        logger.error {
                            "📋 PHASE_1_RETRY_FAILED | correlationId=${task.correlationId} | maxRetries=$maxRetries | result=ERROR"
                        }
                        Phase1Result(
                            chunks = emptyList(),
                            baseNodeKey = null,
                            baseInfo = null,
                            earlyRouting = "ERROR",
                        )
                    }

                    // Node to prepare retry: update attempt counter and return prompt
                    val nodeCreateRetryPrompt by node<RetryDecision, String>("Create Phase 1 Retry Prompt") { decision ->
                        currentRetryAttempt = decision.nextRetryAttempt
                        logger.info {
                            "📋 PHASE_1_RETRY | correlationId=${task.correlationId} | attempt=$currentRetryAttempt/$maxRetries"
                        }
                        decision.prompt!!
                    }

                    edge(nodeStart forwardTo nodePreparePh1)
                    edge(nodePreparePh1 forwardTo nodeSendPh1Request)
                    edge((nodeSendPh1Request forwardTo nodeExecutePh1Tool).onToolCall { true })
                    edge((nodeSendPh1Request forwardTo nodeExtractPh1Result).onAssistantMessage { true })
                    edge(nodeExecutePh1Tool forwardTo nodeSendPh1Result)
                    // Handle multiple tool calls
                    edge((nodeSendPh1Result forwardTo nodeExecutePh1Tool).onToolCall { true })
                    edge((nodeSendPh1Result forwardTo nodeExtractPh1Result).onAssistantMessage { true })

                    // Parse result routing: success or retry
                    edge(
                        (nodeExtractPh1Result forwardTo nodeUnwrapSuccess).onCondition { parseResult ->
                            parseResult.success
                        },
                    )
                    edge(
                        (nodeExtractPh1Result forwardTo nodeRetryOrFail).onCondition { parseResult ->
                            !parseResult.success
                        },
                    )

                    // Retry decision routing
                    edge(
                        (nodeRetryOrFail forwardTo nodeHandleRetryFailed).onCondition { decision ->
                            !decision.shouldRetry
                        },
                    )
                    edge(
                        (nodeRetryOrFail forwardTo nodeCreateRetryPrompt).onCondition { decision ->
                            decision.shouldRetry
                        },
                    )

                    val nodeSendRetry by nodeLLMRequest(name = "Send Phase 1 Retry Request")
                    edge(nodeCreateRetryPrompt forwardTo nodeSendRetry)
                    // Retry can also have tool calls
                    edge((nodeSendRetry forwardTo nodeExecutePh1Tool).onToolCall { true })
                    // Or go back to extraction
                    edge((nodeSendRetry forwardTo nodeExtractPh1Result).onAssistantMessage { true })

                    // Success paths
                    edge(nodeUnwrapSuccess forwardTo nodeFinish)
                    edge(nodeHandleRetryFailed forwardTo nodeFinish)
                }

                // =================================================================
                // PHASE 2: MAP - Process each chunk
                // =================================================================
                // First create base document node
                val nodeCreateBaseDoc by node<Phase1Result, Phase1Result>("Create Base Document Node") { phase1 ->
                    if (phase1.baseInfo != null && phase1.baseNodeKey != null) {
                        logger.info {
                            "📄 BASE_NODE_CREATE_START | correlationId=${task.correlationId} | nodeKey=${phase1.baseNodeKey}"
                        }
                        try {
                            kotlinx.coroutines.runBlocking {
                                // Use GraphRagTools logic inline
                                val storeRequest =
                                    com.jervis.rag.StoreChunkRequest(
                                        clientId = task.clientId,
                                        projectId = task.projectId,
                                        content = phase1.baseInfo,
                                        graphRefs = listOf(phase1.baseNodeKey),
                                        sourceUrn = task.sourceUrn,
                                    )
                                val chunkId = knowledgeService.storeChunk(storeRequest)

                                // Create graph node
                                graphService.upsertNode(
                                    clientId = task.clientId,
                                    node =
                                        com.jervis.graphdb.model.GraphNode(
                                            key = phase1.baseNodeKey.replace("::", "_"),
                                            entityType = "document",
                                            ragChunks = listOf(chunkId),
                                        ),
                                )
                                logger.info {
                                    "📄 BASE_NODE_CREATED | correlationId=${task.correlationId} | " +
                                        "nodeKey=${phase1.baseNodeKey} | chunkId=$chunkId"
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to create base document node" }
                        }
                    }
                    phase1
                }

                val nodeInitProcessing by node<Phase1Result, ProcessingState>("Initialize Chunk Processing") { phase1 ->
                    logger.info {
                        "🔄 PHASE_2_START | correlationId=${task.correlationId} | totalChunks=${phase1.chunks.size}"
                    }
                    // Prepend baseInfo to each chunk
                    val chunksWithBase =
                        if (phase1.baseInfo != null) {
                            phase1.chunks.map { chunk ->
                                """${phase1.baseInfo}

---

$chunk"""
                            }
                        } else {
                            phase1.chunks
                        }

                    ProcessingState(
                        chunks = chunksWithBase,
                        baseNodeKey = phase1.baseNodeKey ?: "doc_unknown",
                        baseInfo = null, // No longer needed, already prepended
                        processedResults = emptyList(),
                        currentIndex = 0,
                    )
                }

                // Store state in a mutable variable that can be accessed by nodes
                var currentState: ProcessingState? = null

                val processingSubgraph by subgraph<ProcessingState, ProcessingState>(name = "🔄 Phase 2: Chunk Processing") {
                    val nodeStoreState by node<ProcessingState, ProcessingState>("Store Processing State") { state ->
                        currentState = state
                        state
                    }

                    val nodePrepareChunk by node<ProcessingState, String>("Prepare Chunk for Processing") { state ->
                        val chunk = state.nextChunk()
                        val chunkIndex = state.currentIndex + 1

                        logger.info {
                            "🔄 CHUNK_PROCESSING | correlationId=${task.correlationId} | " +
                                "chunkIndex=$chunkIndex/${state.chunks.size} | chunkLength=${chunk?.length ?: 0}"
                        }

                        """
${PROMPT_PHASE_2}

**BASE_NODE_KEY: ${state.baseNodeKey}**

**CHUNK $chunkIndex of ${state.chunks.size}:**
$chunk
                        """.trimIndent()
                    }

                    val nodeSendChunk by nodeLLMRequest(name = "Send Chunk LLM Request")
                    val nodeExecuteStore by nodeExecuteTool(name = "Execute Store Tool")
                    val nodeSendStoreResult by nodeLLMSendToolResult(name = "Send Store Tool Result")

                    // Track retry attempts per chunk
                    var chunkRetryCount = 0
                    val maxChunkRetries = 1

                    data class RetryDecisionPhase2(
                        val shouldRetry: Boolean,
                        val retryPrompt: String,
                    )

                    // Handle invalid response - retry with strong prompt
                    val nodeChunkRetryOrSkip by node<String, RetryDecisionPhase2>("Chunk Retry Decision") { assistantMessage ->
                        chunkRetryCount++
                        logger.warn {
                            "🔄 CHUNK_RETRY | correlationId=${task.correlationId} | " +
                                "attempt=$chunkRetryCount/$maxChunkRetries | " +
                                "response='${assistantMessage.take(100)}'"
                        }

                        val shouldRetry = chunkRetryCount <= maxChunkRetries
                        if (!shouldRetry) {
                            logger.error { "Phase 2: Max retries exceeded, skipping chunk" }
                        }

                        // Strong retry prompt
                        val retryPrompt =
                            """
🚨 ERROR: You replied with text instead of calling the storeKnowledge tool.

**WHAT YOU DID WRONG:**
You wrote "${assistantMessage.take(100)}" instead of calling the tool.

**WHAT YOU MUST DO NOW:**
Call the `storeKnowledge` tool with these parameters:
- content: Semantic description of what this chunk is about
- graphStructure: List of relationships (MUST include "-[PART_OF]-> ${currentState?.baseNodeKey}")
- mainNodeKey: Primary concept identifier

🚨 **DO NOT REPLY WITH TEXT. CALL THE TOOL.**

**CHUNK TO PROCESS:**
${currentState?.nextChunk() ?: ""}
                            """.trimIndent()

                        RetryDecisionPhase2(shouldRetry = shouldRetry, retryPrompt = retryPrompt)
                    }

                    val nodeSkipChunk by node<RetryDecisionPhase2, String>("Skip Chunk After Retry Failure") { _ ->
                        logger.warn {
                            "🔄 CHUNK_SKIPPED | correlationId=${task.correlationId} | reason='max retries exceeded'"
                        }
                        "Skipped - no valid response: ChunkId: skipped, MainNode: skipped"
                    }

                    val nodeCreateRetryPromptPhase2 by node<RetryDecisionPhase2, String>("Create Chunk Retry Prompt") { decision ->
                        decision.retryPrompt
                    }

                    val nodeSendChunkRetry by nodeLLMRequest(name = "Send Chunk Retry Request")

                    val nodeRecordResult by node<String, ProcessingState>("Record Chunk Result") { result ->
                        // Reset retry counter for next chunk
                        chunkRetryCount = 0

                        // Extract chunkId and mainNodeKey from tool result
                        val chunkIdRegex = """ChunkId:\s*([a-zA-Z0-9\-_]+)""".toRegex()
                        val mainNodeRegex = """MainNode:\s*([a-zA-Z0-9\-_:]+)""".toRegex()

                        val chunkId = chunkIdRegex.find(result)?.groupValues?.get(1) ?: "unknown"
                        val mainNode = mainNodeRegex.find(result)?.groupValues?.get(1) ?: "unknown"

                        if (chunkId == "unknown" || mainNode == "unknown") {
                            logger.warn {
                                "🔄 CHUNK_PARSE_WARNING | correlationId=${task.correlationId} | " +
                                    "rawResult='${result.take(200)}'"
                            }
                        }

                        val state = currentState ?: throw IllegalStateException("No current state")
                        logger.info {
                            "🔄 CHUNK_COMPLETE | correlationId=${task.correlationId} | " +
                                "chunkIndex=${state.currentIndex + 1}/${state.chunks.size} | chunkId=$chunkId | mainNode=$mainNode"
                        }

                        // Update state with result
                        state.withResult(ChunkResult(chunkId, mainNode))
                    }

                    // Start: store state and check if has more chunks
                    edge(nodeStart forwardTo nodeStoreState)
                    edge((nodeStoreState forwardTo nodePrepareChunk).onCondition { state -> state.hasMoreChunks() })
                    edge((nodeStoreState forwardTo nodeFinish).onCondition { state -> !state.hasMoreChunks() })

                    // Processing flow
                    edge(nodePrepareChunk forwardTo nodeSendChunk)
                    edge((nodeSendChunk forwardTo nodeExecuteStore).onToolCall { true })
                    // If LLM returns text instead of tool call, retry
                    edge((nodeSendChunk forwardTo nodeChunkRetryOrSkip).onAssistantMessage { true })
                    edge(nodeExecuteStore forwardTo nodeSendStoreResult)

                    // Handle multiple tool calls: if LLM returns more tool calls, execute them
                    edge((nodeSendStoreResult forwardTo nodeExecuteStore).onToolCall { true })
                    // Only proceed to record result when LLM returns assistant message
                    edge((nodeSendStoreResult forwardTo nodeRecordResult).onAssistantMessage { true })

                    // Retry flow
                    edge((nodeChunkRetryOrSkip forwardTo nodeCreateRetryPromptPhase2).onCondition { decision -> decision.shouldRetry })
                    edge((nodeChunkRetryOrSkip forwardTo nodeSkipChunk).onCondition { decision -> !decision.shouldRetry })
                    edge(nodeCreateRetryPromptPhase2 forwardTo nodeSendChunkRetry)
                    edge((nodeSendChunkRetry forwardTo nodeExecuteStore).onToolCall { true })
                    // If retry also fails with text, create decision again
                    edge((nodeSendChunkRetry forwardTo nodeChunkRetryOrSkip).onAssistantMessage { true })
                    edge(nodeSkipChunk forwardTo nodeRecordResult)

                    // Loop back or finish
                    edge((nodeRecordResult forwardTo nodeStoreState).onCondition { state -> state.hasMoreChunks() })
                    edge((nodeRecordResult forwardTo nodeFinish).onCondition { state -> !state.hasMoreChunks() })
                }

                // =================================================================
                // PHASE 3: FINAL ROUTING (previously Phase 4)
                // =================================================================
                val nodePrepareRouting by node<ProcessingState, String>("Prepare Final Routing") { state ->
                    logger.info {
                        "🎯 PHASE_3_START | correlationId=${task.correlationId} | " +
                            "baseNode=${state.baseNodeKey} | processedChunks=${state.processedResults.size}"
                    }
                    """
${PROMPT_PHASE_3}

**SUMMARY:**
- Base document node created: ${state.baseNodeKey}
- Processed ${state.processedResults.size} chunks
- All chunks linked to base node via -[PART_OF]-> edges
- All chunks indexed to RAG + Graph
- Any tasks/links/actions have been delegated

Decide: DONE or LIFT_UP?
                    """.trimIndent()
                }

                val routingSubgraph by subgraph<String, String>(name = "🎯 Phase 3: Final Routing") {
                    val nodeSendRoutingRequest by nodeLLMRequest(name = "Send Routing LLM Request")
                    val nodeExecuteRouting by nodeExecuteTool(name = "Execute Routing Tool")
                    val nodeSendRoutingResult by nodeLLMSendToolResult(name = "Send Routing Tool Result")

                    edge(nodeStart forwardTo nodeSendRoutingRequest)
                    edge((nodeSendRoutingRequest forwardTo nodeExecuteRouting).onToolCall { true })
                    edge(nodeExecuteRouting forwardTo nodeSendRoutingResult)
                    // Handle multiple tool calls (unlikely but consistent pattern)
                    edge((nodeSendRoutingResult forwardTo nodeExecuteRouting).onToolCall { true })
                    edge((nodeSendRoutingResult forwardTo nodeFinish).onAssistantMessage { true })
                }

                // Helper node to convert Phase1Result to String for early exit
                val nodeEarlyExit by node<Phase1Result, String>("Handle Early Exit") { result ->
                    logger.info {
                        "🎯 EARLY_EXIT | correlationId=${task.correlationId} | routing=${result.earlyRouting}"
                    }
                    "Phase 1 completed with routing: ${result.earlyRouting ?: "UNKNOWN"}"
                }

                // =================================================================
                // MAIN FLOW
                // =================================================================
                // Vision (Phase 0) → Phase 1 → Check result
                edge(nodeStart forwardTo nodePrepareVision)
                edge(nodePrepareVision forwardTo visionSubgraph)
                edge(visionSubgraph forwardTo nodeExtractAugmentedText)
                edge(nodeExtractAugmentedText forwardTo phase1Subgraph)

                // If Phase 1 returned early routing (DONE/LIFT_UP), finish immediately
                edge((phase1Subgraph forwardTo nodeEarlyExit).onCondition { result -> result.earlyRouting != null })
                edge(nodeEarlyExit forwardTo nodeFinish)

                // If Phase 1 returned empty chunks, finish (shouldn't happen, but safe)
                edge((phase1Subgraph forwardTo nodeEarlyExit).onCondition { result -> result.chunks.isEmpty() })

                // Normal flow: Phase 1 → Create base → Phase 2 → Phase 3 (Routing)
                edge(
                    (phase1Subgraph forwardTo nodeCreateBaseDoc).onCondition { result ->
                        result.earlyRouting == null && result.chunks.isNotEmpty()
                    },
                )
                edge(nodeCreateBaseDoc forwardTo nodeInitProcessing)
                edge(nodeInitProcessing forwardTo processingSubgraph)
                edge(processingSubgraph forwardTo nodePrepareRouting)
                edge(nodePrepareRouting forwardTo routingSubgraph)
                edge(routingSubgraph forwardTo nodeFinish)
            }

        // Dynamic model selection based on task content length
        // SmartModelSelector uses exact BPE token counting (jtokkit)
        // Output reserve: Phase 1 generates chunks (similar size to input) + metadata
        // Formula: output ≈ 1.5x input (chunks are ~same size, plus baseInfo and delimiters)
        val inputTokens = tokenCountingService.countTokens(task.content)
        val dynamicOutputReserve = (inputTokens * 1.5).toInt().coerceAtLeast(2000)

        val dynamicModel =
            smartModelSelector.selectModel(
                baseModelName = MODEL_QUALIFIER_NAME, // Base: qwen3-coder-tool:30b
                inputContent = task.content,
                outputReserve = dynamicOutputReserve, // Dynamic: 1.5x input tokens
            )

        logger.info {
            "KoogQualifierAgent | Dynamic model selected: ${dynamicModel.id} | " +
                "contextLength=${dynamicModel.contextLength} | " +
                "taskContentLength=${task.content.length} | " +
                "inputTokens=$inputTokens | " +
                "outputReserve=$dynamicOutputReserve | " +
                "baseModel=$MODEL_QUALIFIER_NAME"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-qualifier-main") {
                        system("You are JERVIS Qualification Agent. Follow phase-specific instructions provided in user messages.")
                    },
                model = dynamicModel, // Dynamic model from SmartModelSelector
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
                        coroutineScope = scope,
                        onRoutingCaptured = { routing -> capturedRouting = routing },
                    ),
                )
            }

        return AIAgent(
            promptExecutor = promptExecutorFactory.getExecutor(ollamaProviderSelector.getProvider()),
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                install(feature = EventHandler) {
                    onAgentStarting { eventContext: AgentStartingContext ->
                        logger.info { "Starting agent: ${eventContext.agent.id}" }
                    }
                    onAgentCompleted { eventContext: AgentCompletedContext ->
                        logger.info { "Result: ${eventContext.result}" }
                    }
                }
            },
        )
    }

    suspend fun run(task: PendingTaskDocument): QualifierResult {
        val startTime = System.currentTimeMillis()
        logger.info { "🔵 KOOG_QUALIFIER_START (Map-Reduce Strategy) | correlationId=${task.correlationId}" }
        try {
            val agent = create(task)
            agent.run(task.content)
            val duration = System.currentTimeMillis() - startTime
            logger.info { "🟢 KOOG_QUALIFIER_COMPLETE | correlationId=${task.correlationId} | duration=${duration}ms" }
            return QualifierResult(completed = true)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(
                e,
            ) { "❌ KOOG_QUALIFIER_FAILED | correlationId=${task.correlationId} | duration=${duration}ms | error=${e.message}" }

            // Check if this is a timeout or transient error that should be retried
            val isRetryableError = isRetryableError(e)

            if (isRetryableError) {
                // Return task to READY_FOR_QUALIFICATION for retry when LLM is in better condition
                try {
                    pendingTaskService.updateState(
                        taskId = task.id,
                        expected = PendingTaskStateEnum.QUALIFYING,
                        next = PendingTaskStateEnum.READY_FOR_QUALIFICATION,
                    )
                    logger.info {
                        "TASK_RETURNED_FOR_RETRY: taskId=${task.id} correlationId=${task.correlationId} " +
                            "reason='${e.message?.take(200)}'"
                    }
                } catch (stateError: Exception) {
                    logger.error(stateError) { "Failed to return task ${task.id} to READY_FOR_QUALIFICATION" }
                }

                throw e // Propagate to allow background engine to handle
            }

            // For non-retryable errors, mark as ERROR and escalate to user task
            try {
                val reason =
                    buildString {
                        append("KoogQualifierAgent failed: ")
                        append(e.message ?: e::class.simpleName)
                        if (e.cause != null) {
                            append(" (caused by: ${e.cause?.message})")
                        }
                    }

                // Mark task as ERROR with detailed message
                pendingTaskService.markAsError(
                    taskId = task.id,
                    errorMessage = reason,
                )

                // Escalate to user task so they're notified
                userTaskService.failAndEscalateToUserTask(
                    task = task,
                    reason = reason,
                    error = e,
                )

                logger.info { "TASK_MARKED_ERROR_AND_ESCALATED: taskId=${task.id} correlationId=${task.correlationId}" }
            } catch (escalationError: Exception) {
                logger.error(escalationError) { "Failed to mark task as error or escalate: ${task.id}" }
            }

            throw e
        }
    }

    /**
     * Determine if the error is retryable (timeout, connection issues)
     * or permanent (logic errors, stuck in node).
     */
    private fun isRetryableError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        val causeMessage = e.cause?.message?.lowercase() ?: ""

        // Timeout errors - LLM is slow or overloaded
        if (message.contains("timeout") || causeMessage.contains("timeout")) {
            return true
        }

        // Socket/connection errors - network or LLM server issues
        if (message.contains("socket") || message.contains("connection")) {
            return true
        }

        // HTTP errors that indicate temporary server issues
        if (message.contains("502") || message.contains("503") || message.contains("504")) {
            return true
        }

        // "Stuck in node" errors are logic errors - not retryable
        if (message.contains("stuck in node")) {
            return false
        }

        // Other exceptions are considered permanent errors
        return false
    }

    data class QualifierResult(
        val completed: Boolean,
    )

    companion object {
        const val MODEL_QUALIFIER_NAME = "qwen3-coder-tool:30b"

        const val PROMPT_VISION = """
You are a Vision Analysis AI analyzing visual content from documents.

**YOUR TASK:**
Analyze the provided image/PDF and extract ALL relevant information visible in it.

**WHAT TO EXTRACT:**
- Text content (OCR if needed)
- Error messages or stack traces
- UI elements, buttons, forms
- Charts, graphs, diagrams with data
- Screenshots of applications or websites
- Scanned documents or forms
- Any technical details visible

**OUTPUT FORMAT:**
Provide a detailed description in plain text. Be thorough and precise.
Include ALL visible text, numbers, and technical details.
Describe visual elements (charts, UI) clearly.

**EXAMPLES:**
- Screenshot: "Error dialog showing 'NullPointerException at line 42 in UserService.java'. Stack trace shows Spring Boot application crash. Button 'OK' visible at bottom."
- Chart: "Bar chart titled 'Q4 Revenue by Region'. X-axis: North, South, East, West. Y-axis: Revenue in millions. Values: North=15M, South=12M, East=18M, West=10M."
- Form: "Employee registration form with fields: Name (text input), Email (text input), Department (dropdown showing 'Engineering'), Submit button at bottom."

Start your analysis now:
"""

        const val PROMPT_PHASE_1 = """
You are a Data Extraction Engine.

Your goal is to split the input document into chunks and generate a base summary.

**Chunking guidelines:**
- Split by semantic coherence (topics, sections, paragraphs).
- Target ~2000 tokens per chunk.
- For short texts (emails): ONE chunk = entire text.
- **CRITICAL:** The chunks must contain the EXACT RAW TEXT from the source document. Do not summarize the chunks.

**OUTPUT FORMAT RULES:**

1. If the document requires coding/analysis → Call tool `routeTask("LIFT_UP")`
2. If the document is empty/spam → Call tool `routeTask("DONE")`
3. Check for actionable items and call tools if needed:
   - Scheduled tasks/meetings → `scheduleTask`
   - Safe URLs → `delegateLinkProcessing`
   - User tasks → `createUserTask`
4. Otherwise, output the content in this EXACT structure:

[RAW TEXT OF CHUNK 1]

---CHUNK---

[RAW TEXT OF CHUNK 2]

---BASEINFO---

[High-level summary of the document]

**NEGATIVE CONSTRAINTS:**
❌ NEVER output the string "chunk1 text" or placeholders. Use the REAL document text.
❌ NEVER use JSON format.
❌ NEVER add chatting (e.g. "Here is the output").
❌ NEVER write explanations.
❌ NEVER summarize chunks - copy EXACT RAW TEXT.
"""

        const val PROMPT_PHASE_2 = """
You are in **PHASE 2: MAP (Chunk Processing)**.

**YOUR TASK:**
For this specific chunk:
1. Extract key concepts, entities, and relationships
2. Call `storeKnowledge` with:
   - `content`: Semantic description (what this chunk is about)
   - `graphStructure`: List of relationships including:
     - Internal relationships: "entity1 -[RELATION]-> entity2"
     - **MANDATORY**: Link to base document: "your_chunk_node -[PART_OF]-> BASE_NODE_KEY"
   - `mainNodeKey`: Primary concept identifier for this chunk

3. If you find actionable items in this chunk:
   - Scheduled tasks → call `scheduleTask`
   - Safe URLs → call `delegateLinkProcessing`
   - User actions → call `createUserTask`

**CRITICAL RULES:**
- Call `storeKnowledge` ONCE per chunk (or multiple times if chunk has distinct topics)
- ALWAYS include the `-[PART_OF]-> BASE_NODE_KEY` edge in graphStructure
- BASE_NODE_KEY will be provided in the chunk context below
- After tool results, respond with "Chunk processed." and stop
- Keep descriptions semantic and meaningful, not just text excerpts
"""

        const val PROMPT_PHASE_3 = """
You are in **PHASE 3: FINAL ROUTING**.

**YOUR TASK:**
Decide final routing:
- **`DONE`**: All knowledge has been indexed, no complex analysis needed, task complete
- **`LIFT_UP`**: Document requires complex reasoning, coding, or detailed user response → escalate to GPU agent

Call `routeTask("DONE")` or `routeTask("LIFT_UP")` based on document complexity.

**CRITICAL RULES:**
- Call `routeTask` exactly ONCE
- After tool result, respond with "Routing complete." and stop
"""
    }
}
