# Koog Qualifier Agent - Architecture Overview

**Version:** 2.0 (Clean Refactor - 2025-12)
**Status:** Production
**Framework:** Koog 0.5.4

---

## 📋 Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [State Model](#state-model)
3. [Phase 0: Vision Analysis (Two-Stage)](#phase-0-vision-analysis)
4. [Phase 0.5: Vision Stage 2 (Type-Specific)](#phase-05-vision-stage-2)
5. [Phase 1: Content Type Detection](#phase-1-content-type-detection)
6. [Phase 2: Type-Specific Extraction](#phase-2-type-specific-extraction)
7. [Phase 3: Unified Indexing](#phase-3-unified-indexing)
8. [Phase 4: Final Routing](#phase-4-final-routing)
9. [How to Test](#how-to-test)

---

## Architecture Overview

The Qualifier Agent is a **CPU-based document intake, classification, and indexing agent** built on Koog framework.

**Purpose:**
- Receive unstructured input (Email, JIRA, Confluence, Logs, Generic text)
- Analyze visual content (two-stage vision)
- Detect content type
- Extract type-specific information
- Index to RAG (vector store) + Graph (knowledge graph)
- Route decision: DONE (simple) or LIFT_UP (complex → GPU agent)

**Key Principles:**
- ✅ **Deterministic flow** - always reaches `nodeFinish`
- ✅ **Type-safe state machine** - single `QualifierPipelineState` truth source
- ✅ **No dummy state** - full context preserved through all phases
- ✅ **Fail-fast** - errors collected, not silently ignored
- ✅ **Vision context preservation** - NEVER lost between phases

**Model:** `qwen3-coder-tool:30b` (CPU, Ollama Qualifier provider)
**Max iterations:** Configurable via `KoogProperties.maxIterations`

---

## State Model

### Single Source of Truth: `QualifierPipelineState`

The entire qualifier strategy graph operates on **ONE state object** that accumulates results from each phase.

**Location:** `com.jervis.koog.qualifier.state.QualifierPipelineState`

**Structure:**
```kotlin
data class QualifierPipelineState(
    // Immutable context
    val taskMeta: TaskMetadata,           // correlationId, clientId, projectId, sourceUrn
    val originalText: String,             // Original task content
    val attachments: List<AttachmentMetadata>,

    // Phase 0: Vision (NEVER lost!)
    val vision: VisionContext,            // generalVisionSummary + typeSpecificVisionDetails

    // Phase 1: Content type detection
    val contentTypeDetection: ContentTypeDetection?,  // LLM structured output
    val contentType: ContentType,         // EMAIL, JIRA, CONFLUENCE, LOG, GENERIC

    // Phase 2: Type-specific extraction
    val extraction: ExtractionResult?,    // Sealed class: Email | Jira | Confluence | Log | Generic

    // Phase 3: Indexing
    val indexing: IndexingState,          // baseNodeKey, baseInfo, chunks[], processedCount

    // Phase 4: Routing
    val routingDecision: RoutingDecision?,  // DONE or LIFT_UP + reason

    // Diagnostics
    val metrics: ProcessingMetrics,       // Timing, tokens, errors
)
```

**Initialization:**
```kotlin
val nodeInitState by node<String, QualifierPipelineState> { _ ->
    QualifierPipelineState.initial(task)
}
```

**Update pattern (immutable):**
```kotlin
val updatedState = state.copy(
    vision = state.vision.copy(generalVisionSummary = "...")
)
// OR using helpers:
val updatedState = state.withVision(newVisionContext)
```

**Why single state?**
- ✅ No context loss between phases
- ✅ Type-safe flow (compiler-checked)
- ✅ Clear data lineage (can trace what changed when)
- ✅ Testable (deterministic state transitions)

### Helper State Types

**`ChunkLoopState`** - Wrapper for chunk processing loop:
```kotlin
data class ChunkLoopState(
    val pipeline: QualifierPipelineState,  // Full state (NO dummy!)
    val currentIndex: Int,
) {
    fun hasMore(): Boolean
    fun nextChunk(): String
    fun advance(): ChunkLoopState
}
```

**`IndexingState`** - Tracks base node + chunk processing:
```kotlin
data class IndexingState(
    val baseNodeKey: String,       // e.g., "email_abc123"
    val baseInfo: String,           // Summary for base node
    val chunks: List<String>,       // Indexable chunks
    val processedChunkCount: Int,
    val createdBaseNode: Boolean,
    val errors: List<String>,
)
```

**`TaskMetadata`** - Immutable context:
```kotlin
data class TaskMetadata(
    val correlationId: String,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val sourceUrn: SourceUrn,
)
```

---

## Phase 0: Vision Analysis

### Stage 1: General Description

**Trigger:** If task has visual attachments (images: PNG, JPG, WEBP)

**Model:** `qwen3-vl:latest` (dynamically selected based on image count/size)

**Process:**
1. Filter attachments for visual content (`shouldProcessWithVision()`)
2. Select vision model (via `SmartModelSelector`)
3. Run vision analysis: "What is in the image?" (general description)
4. Store result in `state.vision.generalVisionSummary`
5. Enrich attachments with `visionAnalysis` field

**Output:**
```kotlin
VisionContext(
    originalText = task.content,
    generalVisionSummary = "Screenshot showing JIRA ticket SDB-2080 with status 'In Progress'. Visible fields: assignee, reporter, description with error log.",
    typeSpecificVisionDetails = null,  // Stage 2 not yet run
    attachments = enrichedAttachments,
)
```

**Key guarantees:**
- ✅ `state.vision` is populated and **NEVER becomes null** again
- ✅ Attachments enriched with vision analysis metadata
- ✅ If no visual attachments, `generalVisionSummary = null` (but VisionContext exists)

**Node:**
```kotlin
val nodeVisionStage1 by node<QualifierPipelineState, QualifierPipelineState>("🔍 Phase 0: Vision Stage 1") { state ->
    val visualAttachments = state.attachments.filter { it.shouldProcessWithVision() }
    if (visualAttachments.isEmpty()) {
        return@node state  // No changes, vision stays null
    }

    val visionResult = runTwoStageVision(
        ...,
        contentType = null,  // Stage 2 not yet - we don't know type
        ...
    )

    state.withVision(visionResult).withAttachments(enrichedAttachments)
}
```

**PDF Decision:**
- ❌ **PDFs are NOT supported in vision analysis** (current implementation)
- PDFs are filtered out before vision processing (`mimeType.startsWith("image/")`)
- **Reasoning:** Koog attachments DSL requires image files; PDF→image rendering not implemented
- **Future:** Could implement PDF page rendering (first 1-3 pages) → images → vision

---

## Phase 0.5: Vision Stage 2

### Type-Specific Details (After Content Type Detection)

**Trigger:** If `state.vision.generalVisionSummary != null` AND `state.contentType != GENERIC`

**Purpose:** Extract type-specific details from images based on detected content type.

**Examples:**
- **EMAIL:** Extract visible email addresses, sender/recipient names from screenshot
- **JIRA:** Extract JIRA key (e.g., SDB-2080), status, assignee from screenshot
- **CONFLUENCE:** Extract page title, author, visible headings
- **LOG:** Extract visible error codes, timestamps, stack trace snippets

**Process:**
1. After content type detection completes
2. Check if Stage 2 should run (`shouldRunStage2(state)`)
3. Run vision analysis with type-specific prompt
4. Store result in `state.vision.typeSpecificVisionDetails`

**Node:**
```kotlin
val nodeVisionStage2 by node<QualifierPipelineState, QualifierPipelineState>("🔍 Phase 0.5: Vision Stage 2") { state ->
    if (!shouldRunStage2(state)) {
        return@node state
    }

    val stage2Result = runTwoStageVision(
        ...,
        contentType = state.contentType,  // NOW we know the type!
        ...
    )

    state.withVision(
        state.vision.copy(typeSpecificVisionDetails = stage2Result.typeSpecificVisionDetails)
    )
}
```

**When Stage 2 runs:**
- ✅ `generalVisionSummary != null` (Stage 1 completed)
- ✅ `contentType` is EMAIL, JIRA, CONFLUENCE, or LOG (not GENERIC)
- ✅ Visual attachments exist

**When Stage 2 skips:**
- ❌ No visual attachments
- ❌ `contentType == GENERIC` (no type-specific extraction needed)
- ❌ Stage 1 failed or was skipped

**Edge routing:**
```kotlin
// After content type detection
edge(nodeBuildContentTypeContext forwardTo nodeVisionStage2)

// Stage 2 branches to type-specific extraction
edge((nodeVisionStage2 forwardTo nodePrepareEmailPrompt).onCondition { it.contentType == EMAIL })
edge((nodeVisionStage2 forwardTo nodePrepareJiraPrompt).onCondition { it.contentType == JIRA })
// ... etc
```

---

## Phase 1: Content Type Detection

**Purpose:** Determine the content type for appropriate processing strategy.

**Input:** `state.originalText` + `state.vision.generalVisionSummary` (if available)

**Output:** `ContentType` enum: `EMAIL`, `JIRA`, `CONFLUENCE`, `LOG`, `GENERIC`

**Process:**
1. Prepare prompt with text + vision context
2. LLM structured output: `ContentTypeDetection(contentType: String, reason: String)`
3. Parse result → map to enum
4. **Fallback:** On failure or unknown type → `GENERIC` + log error

**Nodes:**
```kotlin
val nodePrepareContentTypePrompt by node<QualifierPipelineState, String> { state ->
    buildString {
        append("Detect content type:\n\n")
        append(state.originalText)
        if (state.vision.generalVisionSummary != null) {
            append("\n\nVISUAL CONTEXT:\n")
            append(state.vision.generalVisionSummary)
        }
    }
}

val nodeDetectContentType by nodeLLMRequestStructured<ContentTypeDetection>(...)

val nodeBuildContentTypeContext by node<Result<StructuredResponse<ContentTypeDetection>>, QualifierPipelineState> { result ->
    val detection = result.getOrNull()?.structure
    val contentType = detection?.contentType?.uppercase()?.let {
        when (it) {
            "EMAIL" -> ContentType.EMAIL
            "JIRA" -> ContentType.JIRA
            "CONFLUENCE" -> ContentType.CONFLUENCE
            "LOG" -> ContentType.LOG
            else -> ContentType.GENERIC
        }
    } ?: ContentType.GENERIC.also {
        logger.warn { "Content type detection failed, defaulting to GENERIC" }
    }

    // CRITICAL: Preserve state.vision from previous phase!
    state.withContentType(detection, contentType)
}
```

**Key fix from v1:**
- ❌ **OLD:** Created new empty `VisionContext` → **LOST Stage 1 results!**
- ✅ **NEW:** Preserves `state.vision` from previous phase

**Edges:**
```kotlin
edge(nodeVisionStage1 forwardTo nodePrepareContentTypePrompt)
edge(nodePrepareContentTypePrompt forwardTo nodeDetectContentType)
edge(nodeDetectContentType forwardTo nodeBuildContentTypeContext)
```

---

## Phase 2: Type-Specific Extraction

**Purpose:** Extract structured information based on content type.

**Process:** Route to appropriate extractor → Structured LLM output → Store in `state.extraction`

### EMAIL Extraction
- **Fields:** sender, recipients[], subject, classification
- **Output:** `ExtractionResult.Email(EmailExtraction(...))`

### JIRA Extraction
- **Fields:** key, status, type, assignee, reporter, epic, sprint, changeDescription
- **Output:** `ExtractionResult.Jira(JiraExtraction(...))`

### CONFLUENCE Extraction
- **Fields:** author, title, topic
- **Output:** `ExtractionResult.Confluence(ConfluenceExtraction(...))`

### LOG Summarization
- **Fields:** summary, keyEvents[], criticalDetails[]
- **Note:** Not raw chunking - semantic summarization!
- **Output:** `ExtractionResult.Log(LogSummarization(...))`

### GENERIC Chunking
- **Fields:** baseInfo, chunks[]
- **Output:** `ExtractionResult.Generic(baseInfo, chunks)`

**Sealed class:**
```kotlin
sealed class ExtractionResult {
    data class Email(val data: EmailExtraction) : ExtractionResult()
    data class Jira(val data: JiraExtraction) : ExtractionResult()
    data class Confluence(val data: ConfluenceExtraction) : ExtractionResult()
    data class Log(val data: LogSummarization) : ExtractionResult()
    data class Generic(val chunks: List<String>, val baseInfo: String) : ExtractionResult()
}
```

**See:** `docs/qualifier/strategy-graph.md` for detailed node/edge structure.

---

## Phase 3: Unified Indexing

**Purpose:** Same indexing process for ALL content types.

### 3.1 Build IndexingState from ExtractionResult

**Single mapping node:**
```kotlin
val nodeBuildIndexingState by node<QualifierPipelineState, QualifierPipelineState> { state ->
    val indexingState = when (val ext = state.extraction!!) {
        is ExtractionResult.Email -> buildEmailIndexingState(ext.data, state)
        is ExtractionResult.Jira -> buildJiraIndexingState(ext.data, state)
        // ... etc
    }

    state.withIndexing(indexingState)
}
```

**Example: Email indexing state:**
```kotlin
private fun buildEmailIndexingState(data: EmailExtraction, state: QualifierPipelineState): IndexingState {
    val baseNodeKey = "email_${state.taskMeta.correlationId.replace("-", "_")}"
    val baseInfo = "Email from ${data.sender}: ${data.subject}"
    val chunks = if (data.content.length > 3000) data.content.chunked(2500) else listOf(data.content)

    return IndexingState(
        baseNodeKey = baseNodeKey,
        baseInfo = baseInfo,
        chunks = chunks,
    )
}
```

### 3.2 Create Base Node

**Process:**
1. Store `baseInfo` to RAG (`knowledgeService.storeChunk`)
2. Create base graph node (`graphService.upsertNode`)
3. Link RAG chunk to graph node (`ragChunks = [baseChunkId]`)
4. Update `state.indexing.createdBaseNode = true`

### 3.3 Chunk Processing Loop

**See:** `docs/qualifier/strategy-graph.md` - "Chunk Loop Subgraph" for full details.

**Key innovation:** `ChunkLoopState` wrapper prevents dummy state anti-pattern.

**Process:**
1. For each chunk:
   - Prepare prompt: "Index this chunk, MUST link to base node via PART_OF edge"
   - Send LLM request
   - LLM calls `storeKnowledge` tool
   - Execute tool → send result back to LLM
   - Loop if more tool calls
   - Advance to next chunk
2. Update `state.indexing.processedChunkCount`

**Subgraph type signature:**
```kotlin
val chunkProcessingSubgraph by subgraph<ChunkLoopState, ChunkLoopState>(name = "Chunk Processing Loop")
```

**NO dummy state!** Full `QualifierPipelineState` preserved in `ChunkLoopState.pipeline`.

---

## Phase 4: Final Routing

**Purpose:** Decide if task is complete (DONE) or needs GPU analysis (LIFT_UP).

**Tool-based:** LLM must call `routeTask("DONE")` or `routeTask("LIFT_UP")`.

**Routing criteria:**
- **DONE:** Task indexed, no actions needed, informational content
- **LIFT_UP:** Requires coding, complex analysis, user consultation, decision-making

**Process:**
1. Prepare routing prompt with summary (content type, base node, chunk count)
2. LLM request → tool call expected
3. Execute `routeTask` tool
4. Store decision in `state.routingDecision`
5. **Fallback:** If LLM doesn't call tool → default to DONE + log warning

**Subgraph:**
```kotlin
val routingSubgraph by subgraph<String, String>(name = "Final Routing") {
    val nodeSendRoutingRequest by nodeLLMRequest()
    val nodeExecuteRouting by nodeExecuteTool()
    val nodeSendRoutingResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeSendRoutingRequest)
    edge((nodeSendRoutingRequest forwardTo nodeExecuteRouting).onToolCall { true })
    edge((nodeSendRoutingRequest forwardTo nodeFinish).onAssistantMessage { true })  // Fallback
    edge(nodeExecuteRouting forwardTo nodeSendRoutingResult)
    edge((nodeSendRoutingResult forwardTo nodeFinish).onAssistantMessage { true })
}
```

---

## How to Test

### Minimal Integration Tests (5 scenarios)

**1. No attachments → GENERIC flow**
```kotlin
// Input: text only, no attachments
// Expected: Vision skip, GENERIC detection, generic chunking, DONE routing
```

**2. Image attachments → Vision Stage 1 only**
```kotlin
// Input: text + PNG image
// Expected: Vision Stage 1 run, generalVisionSummary != null, Stage 2 skip (GENERIC), DONE
```

**3. JIRA detection → Stage 2 + JIRA extraction**
```kotlin
// Input: JIRA text + screenshot
// Expected: Stage 1, JIRA detection, Stage 2 (JIRA-specific), JiraExtraction, indexing, DONE/LIFT_UP
```

**4. LOG → summarization chunks**
```kotlin
// Input: log file content
// Expected: LOG detection, summarization (not raw chunking), 3 chunks (summary, keyEvents, criticalDetails)
```

**5. Graph reaches nodeFinish always**
```kotlin
// For each content type, verify flow completes without "stuck in node" errors
```

### Validation Checklist

- ✅ No "dummy state" returned from any node
- ✅ `state.vision` never becomes null after Stage 1
- ✅ All extraction branches populate `state.extraction` correctly
- ✅ Chunk loop processes all chunks (`processedChunkCount == chunks.size`)
- ✅ Routing decision is set (DONE or LIFT_UP)
- ✅ Graph always reaches `nodeFinish`
- ✅ No infinite loops (history compression guards)

---

## References

- **Strategy Graph Details:** `docs/qualifier/strategy-graph.md`
- **Koog Usage Notes:** `docs/qualifier/koog-notes.md`
- **Guidelines:** `docs/guidelines.md` - "Strategy Graph Best Practices"
- **Implementation:** `backend/server/src/main/kotlin/com/jervis/koog/qualifier/KoogQualifierAgent.kt`
