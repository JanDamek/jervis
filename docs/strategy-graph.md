# Koog Qualifier Agent - Strategy Graph Detail

**Version:** 2.0 (Clean Refactor)
**Koog Version:** 0.5.4
**Status:** In Progress (Phase 0-1 completed)

---

## 📊 Overview

This document describes the **strategy graph** for Koog Qualifier Agent - a deterministic, type-safe document processing pipeline.

**Key Principles:**
- ✅ Single state object (`QualifierPipelineState`) flows through all nodes
- ✅ No dummy state - full context preserved
- ✅ Edges evaluated in definition order (Koog best practice)
- ✅ Fail-fast error handling with fallbacks

---

## 🗺️ Strategy Graph (Current State)

### Phase 0: Initialization + Vision Analysis

**Nodes:**

| Node | Type Signature | Purpose | Duration Tracking |
|------|----------------|---------|-------------------|
| `nodeInitState` | `String -> QualifierPipelineState` | Initialize pipeline state from task | N/A |
| `nodeVisionStage1` | `QualifierPipelineState -> QualifierPipelineState` | Run vision analysis (Stage 1: general) | Phase 0 |

**Edges:**
```kotlin
edge(nodeStart forwardTo nodeInitState)
edge(nodeInitState forwardTo nodeVisionStage1)
```

**State Changes (Phase 0):**
- `nodeInitState`: Creates initial state with empty vision, GENERIC content type
- `nodeVisionStage1`: Updates `state.vision.generalVisionSummary`, enriches `state.attachments`

**Critical guarantees:**
- ✅ `state.vision` is populated and NEVER becomes null after Stage 1
- ✅ If no visual attachments, Stage 1 returns state unchanged (vision stays null)
- ✅ Attachments enriched with `visionAnalysis` metadata

---

### Phase 1: Content Type Detection

**Nodes:**

| Node | Type Signature | Purpose | State Preservation |
|------|----------------|---------|-------------------|
| `nodeStoreStateBeforeDetection` | `QualifierPipelineState -> QualifierPipelineState` | Store state in strategy-scoped var | Pass-through |
| `nodePrepareContentTypePrompt` | `QualifierPipelineState -> String` | Build prompt with text + vision context | State stored in `currentState` |
| `nodeDetectContentType` | `String -> Result<StructuredResponse<ContentTypeDetection>>` | LLM structured output (Koog built-in) | N/A |
| `nodeMergeDetectionResult` | `Result<...> -> QualifierPipelineState` | Parse result, update state with contentType | Restores from `currentState` |

**Edges:**
```kotlin
edge(nodeVisionStage1 forwardTo nodeStoreStateBeforeDetection)
edge(nodeStoreStateBeforeDetection forwardTo nodePrepareContentTypePrompt)
edge(nodePrepareContentTypePrompt forwardTo nodeDetectContentType)
edge(nodeDetectContentType forwardTo nodeMergeDetectionResult)
```

**State Preservation Pattern:**
```kotlin
// Strategy-scoped var (Koog best practice for structured nodes)
var currentState: QualifierPipelineState? = null

val nodeStoreStateBeforeDetection by node<QualifierPipelineState, QualifierPipelineState>(...) { state ->
    currentState = state  // Store for later
    state
}

val nodeMergeDetectionResult by node<Result<...>, QualifierPipelineState>(...) { result ->
    val state = currentState ?: error("State lost!")  // Restore
    // ... process result ...
    state.withContentType(detection, contentType)  // Return updated state
}
```

**Why this pattern?**
- `nodeLLMRequestStructured` returns `Result<StructuredResponse<T>>`, not `QualifierPipelineState`
- We can't pass state through Koog built-in nodes directly
- Strategy-scoped var preserves state across the structured LLM call
- **Reference:** Koog docs → *Custom nodes* (state preservation patterns)

**State Changes (Phase 1):**
- `nodeMergeDetectionResult`: Updates `state.contentType`, `state.contentTypeDetection`
- Fallback: On LLM failure → `contentType = GENERIC`, error logged in `state.metrics`

**Critical fixes from v1:**
- ❌ **OLD:** Created new empty `VisionContext` → lost Stage 1 results!
- ✅ **NEW:** Restores state from `currentState` → `vision` preserved

---

### Phase 2-4: Type-Specific Extraction, Indexing, Routing

**Status:** Not yet refactored (will be done in Steps 3-8)

**Temporary routing:**
```kotlin
// TODO: These edges will fail - Phase 2 nodes expect ContentTypeContext, but get QualifierPipelineState
edge((nodeMergeDetectionResult forwardTo nodePrepareEmailPrompt).onCondition { state ->
    state.contentType == ContentType.EMAIL
})
// ... etc
```

**Next steps:**
- Step 3: Refactor Phase 2 extraction nodes to accept `QualifierPipelineState`
- Step 4: Add Vision Stage 2 node (after content type detection)
- Step 5-6: Refactor Phase 3 indexing (unified mapping + chunk loop)
- Step 7: Fix chunk loop with `onToolNotCalled` (Koog 0.5.4)
- Step 8: Refactor routing subgraph with tool-based decision

---

## 🔍 Edge Evaluation Order (Koog Best Practice)

**Source:** *Koog docs → Troubleshooting → Graph behaves unexpectedly*

> Edges are evaluated **in the order they are defined**. The first matching condition wins.

**Example:**
```kotlin
// Edges checked in this order:
edge((node forwardTo pathA).onCondition { it.value > 10 })  // 1st
edge((node forwardTo pathB).onCondition { it.value > 5 })   // 2nd
edge((node forwardTo pathC).onCondition { true })           // 3rd (fallback)
```

If `value = 12`:
- ✅ Goes to `pathA` (first match)
- ❌ Never evaluates `pathB` or `pathC`

**Best practice:**
- Most specific conditions first
- Most generic conditions last
- Always provide fallback (`onCondition { true }`) to avoid stuck nodes

---

## 📐 Current Graph Structure (Phase 0-1 only)

```
[nodeStart]
    ↓
[nodeInitState] (Phase 0.0: Init)
    ↓ QualifierPipelineState
[nodeVisionStage1] (Phase 0.1: Vision Stage 1)
    ↓ QualifierPipelineState (vision enriched)
[nodeStoreStateBeforeDetection] (Phase 1.0: Store State)
    ↓ QualifierPipelineState
[nodePrepareContentTypePrompt] (Phase 1.1: Prepare Prompt)
    ↓ String
[nodeDetectContentType] (Phase 1.2: LLM Structured)
    ↓ Result<StructuredResponse<ContentTypeDetection>>
[nodeMergeDetectionResult] (Phase 1.3: Merge Result)
    ↓ QualifierPipelineState (contentType set, vision preserved)
[TODO: Phase 2-4...]
```

---

## ✅ Verification Checklist (Phase 0-1)

### Compilation
- ✅ New state model classes compile (`QualifierPipelineState`, `TaskMetadata`, etc.)
- ✅ Import for `onToolNotCalled` added (Koog 0.5.4)
- ✅ Strategy nodes use correct type signatures
- ⚠️ **Expected:** Phase 2 edges will fail type check (not yet refactored)

### State Preservation
- ✅ `nodeInitState` creates initial state from task
- ✅ `nodeVisionStage1` preserves all fields, only updates `vision` + `attachments`
- ✅ `nodeMergeDetectionResult` restores state from `currentState` var
- ✅ `state.vision` NEVER becomes null after Stage 1 (if vision ran)

### Logging
- ✅ All nodes log with `correlationId` from `state.taskMeta.correlationId`
- ✅ Duration tracking: Phase 0 (`withPhase0Duration`), Phase 1 (`withPhase1Duration`)
- ✅ Errors logged in `state.metrics.errors`

### Edge Flow
- ✅ `nodeStart → nodeInitState → nodeVisionStage1` works
- ✅ `nodeVisionStage1 → ... → nodeMergeDetectionResult` works
- ⚠️ **Expected:** Edges to Phase 2 will fail (type mismatch)

---

## 🚧 Known Issues (To Be Fixed in Next Steps)

1. **Phase 2 nodes not refactored yet**
   - Expect: Type errors at edges from `nodeMergeDetectionResult`
   - Fix: Step 3 (refactor Phase 2 to accept `QualifierPipelineState`)

2. **Vision Stage 2 not implemented**
   - Missing: Type-specific vision details extraction
   - Fix: Step 4 (add `nodeVisionStage2` after content type detection)

3. **Phase 3 chunk loop has dummy state**
   - Problem: `nodeAdvanceToNextChunk` returns dummy `ChunkProcessingState`
   - Fix: Step 7 (use `ChunkLoopState` wrapper + subgraph-scoped var)

4. **Routing uses `onAssistantMessage` instead of `onToolNotCalled`**
   - Problem: Not following Koog 0.5.4 best practice
   - Fix: Step 8 (refactor routing subgraph)

---

## 📚 Koog Documentation References

All patterns in this document are based on:

1. **Custom strategy graphs** (nodes, edges, conditions, subgraphs)
2. **Troubleshooting** (edge evaluation order, graph completion)
3. **Nodes and components** (custom node implementation)
4. **Conditions** (`onToolCall`, `onToolNotCalled`, `onAssistantMessage` - Koog 0.5.4)

**Version:** Koog 0.5.4 (verified in `gradle/libs.versions.toml`)

---

## 📝 Change Log

### 2025-12 - Phase 0-1 Refactoring (Step 2)

**What changed:**
- ✅ Added `nodeInitState` - single source of truth initialization
- ✅ Refactored `nodeVisionStage1` to accept/return `QualifierPipelineState`
- ✅ Fixed `nodeMergeDetectionResult` - preserves vision context (no more empty VisionContext!)
- ✅ Added strategy-scoped `currentState` var for state preservation across structured nodes
- ✅ Updated edges: `nodeStart → nodeInitState → nodeVisionStage1 → ... → nodeMergeDetectionResult`

**What was broken:**
- ❌ `nodeBuildContentTypeContext` created new empty `VisionContext` → lost Stage 1 vision!
- ❌ No initialization node → unclear where state comes from
- ❌ Edges went directly from `nodeStart` to `nodeVisionStage1` (String input)

**How to verify:**
1. Compile project (`./gradlew backend:server:compileKotlin`)
2. Check logs: `correlationId` appears in all Phase 0-1 logs
3. Run qualifier on task with image → `VISION_STAGE1_COMPLETE` logged
4. Check state after Phase 1 → `state.vision.generalVisionSummary` not null (if vision ran)

---

## 🔮 Next Steps

1. **Step 3:** Refactor Phase 2 (Type-Specific Extraction) - accept `QualifierPipelineState`
2. **Step 4:** Add Vision Stage 2 node (type-specific vision details)
3. **Step 5-6:** Refactor Phase 3 (Unified Indexing + Base Node creation)
4. **Step 7:** Fix chunk loop with `onToolNotCalled` + `ChunkLoopState` wrapper
5. **Step 8:** Refactor routing subgraph with tool-based decision + fallback
6. **Step 9:** Add history compression guard (threshold 80 messages)
7. **Step 10:** Generate Mermaid diagram + update guidelines

**Expected completion:** Steps 3-10 (~1000 more lines of changes)
