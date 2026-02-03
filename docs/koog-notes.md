# Koog Framework Usage Notes - Jervis Qualifier Agent

**Koog Version:** 0.5.4
**Project:** Jervis - Koog Qualifier Agent
**Last Updated:** 2025-12

---

## 📌 Source of Truth: Koog Version

```toml
# gradle/libs.versions.toml
koogAgents = "0.5.4"
```

**Verification:**
- ✅ Confirmed in `gradle/libs.versions.toml` (line 3)
- ✅ Used via `implementation(libs.koog.agents)` in `backend/server/build.gradle.kts`

**Important:** All Koog API references in this document are based on **Koog 0.5.4**.

---

## 🔍 Condition Semantics (Koog 0.5.4)

### Available Conditions

According to Koog 0.5.4 documentation (*Custom strategy graphs → Conditions*), the following edge conditions are available:

| Condition | Semantic | Usage in Jervis Qualifier |
|-----------|----------|---------------------------|
| `onToolCall` | LLM called exactly one tool | Primary path: LLM → tool execution |
| `onMultipleToolCalls` | LLM called multiple tools | Future use (not yet in qualifier) |
| **`onToolNotCalled`** | **LLM did NOT call any tool** | **Primary fail-safe / loop exit** |
| `onAssistantMessage` | LLM returned text response | Secondary: final responses, summaries |

### Correct Usage Patterns

#### Pattern 1: Tool Call Loop (Primary)

```kotlin
val nodeSendRequest by nodeLLMRequest()
val nodeExecuteTool by nodeExecuteTool()
val nodeSendToolResult by nodeLLMSendToolResult()

// Primary branch: tool was called
edge((nodeSendRequest forwardTo nodeExecuteTool).onToolCall { true })

// Fail-safe: tool NOT called → advance/exit
edge((nodeSendRequest forwardTo nodeAdvanceChunk).onToolNotCalled { true })

// After tool result, loop or exit
edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendToolResult forwardTo nodeAdvanceChunk).onToolNotCalled { true })
```

**Why `onToolNotCalled` is primary:**
- ✅ Deterministic: clear semantic distinction (tool called vs not called)
- ✅ Fail-safe: handles cases where LLM doesn't call tool (prompt issue, model confusion)
- ✅ Explicit: makes intent clear (we expected tool, but it didn't happen)

#### Pattern 2: Assistant Message (Secondary)

```kotlin
// Final response expected (no tool call)
edge((nodeSendRequest forwardTo nodeFinish).onAssistantMessage { true })
```

**When to use `onAssistantMessage`:**
- ✅ Final phase where text response is expected (e.g., routing explanation)
- ✅ Complement to `onToolNotCalled` (not replacement)
- ❌ **Never** as generic fallback for "no tool called" in loops

### ❌ Anti-Pattern (AVOID)

```kotlin
// ❌ WRONG: Using onAssistantMessage as generic "no tool" fallback
edge((nodeSendRequest forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendRequest forwardTo nodeAdvance).onAssistantMessage { true })
// Problem: onAssistantMessage is less explicit than onToolNotCalled
```

**Correct version:**
```kotlin
// ✅ CORRECT: Explicit semantic distinction
edge((nodeSendRequest forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendRequest forwardTo nodeAdvance).onToolNotCalled { true })
```

---

## 🔄 Chunk Loop Pattern (Koog Best Practice)

### Reference: Koog Docs

- **Source:** *Custom strategy graphs → Tool execution patterns*
- **Pattern:** LLM Request → Tool Execute → Send Result → Loop/Exit

### Jervis Implementation (Corrected)

```kotlin
val chunkProcessingSubgraph by subgraph<ChunkLoopState, ChunkLoopState>(name = "Chunk Processing Loop") {
    // Subgraph-scoped state
    var currentLoopState: ChunkLoopState? = null

    val nodeStoreLoopState by node<ChunkLoopState, ChunkLoopState>("Store Loop State") { state ->
        currentLoopState = state
        state
    }

    val nodeCheckHasMore by node<ChunkLoopState, ChunkLoopState>("Check Has More") { state ->
        state
    }

    val nodePrepareChunk by node<ChunkLoopState, String>("Prepare Chunk") { state ->
        val chunk = state.nextChunk()
        buildChunkPrompt(state.pipeline, chunk, state.currentIndex)
    }

    val nodeSendChunkRequest by nodeLLMRequest("Send Chunk Request")
    val nodeExecuteStoreKnowledge by nodeExecuteTool("Execute storeKnowledge")
    val nodeSendStoreResult by nodeLLMSendToolResult("Send Store Result")

    val nodeAdvance by node<String, ChunkLoopState>("Advance Chunk") { _ ->
        currentLoopState!!.advance()  // NO dummy state!
    }

    // Edges - Tool-driven flow
    edge(nodeStart forwardTo nodeStoreLoopState)
    edge(nodeStoreLoopState forwardTo nodeCheckHasMore)
    edge((nodeCheckHasMore forwardTo nodePrepareChunk).onCondition { it.hasMore() })
    edge((nodeCheckHasMore forwardTo nodeFinish).onCondition { !it.hasMore() })

    edge(nodePrepareChunk forwardTo nodeSendChunkRequest)

    // Primary: tool called → execute
    edge((nodeSendChunkRequest forwardTo nodeExecuteStoreKnowledge).onToolCall { true })

    // Fail-safe: tool NOT called → skip chunk, advance
    edge((nodeSendChunkRequest forwardTo nodeAdvance).onToolNotCalled { true })

    edge(nodeExecuteStoreKnowledge forwardTo nodeSendStoreResult)

    // After tool result: loop if more tools, advance if done
    edge((nodeSendStoreResult forwardTo nodeExecuteStoreKnowledge).onToolCall { true })
    edge((nodeSendStoreResult forwardTo nodeAdvance).onToolNotCalled { true })

    // Loop back or finish
    edge((nodeAdvance forwardTo nodeStoreLoopState).onCondition { it.hasMore() })
    edge((nodeAdvance forwardTo nodeFinish).onCondition { !it.hasMore() })
}
```

**Key principles:**
1. ✅ `onToolCall` → primary path (tool execution)
2. ✅ `onToolNotCalled` → fail-safe (skip/advance)
3. ✅ No `onAssistantMessage` in loop (not needed)
4. ✅ Subgraph-scoped var for state preservation

---

## 🎯 Edge Evaluation Order

### Koog Behavior

**Source:** *Troubleshooting → Graph behaves unexpectedly*

> Edges are evaluated **in the order they are defined**. The first matching condition wins.

### Implications

```kotlin
// Order matters!
edge((nodeA forwardTo nodeB).onCondition { it.value > 10 })  // Checked FIRST
edge((nodeA forwardTo nodeC).onCondition { it.value > 5 })   // Checked SECOND
edge((nodeA forwardTo nodeD).onCondition { true })           // Checked LAST (fallback)
```

If `value = 12`:
- ✅ Goes to `nodeB` (first match)
- ❌ Never evaluated `nodeC` or `nodeD`

### Best Practice

```kotlin
// Most specific conditions first, most generic last
edge((node forwardTo specificPath).onCondition { /* specific */ })
edge((node forwardTo genericPath).onCondition { /* generic */ })
edge((node forwardTo fallback).onCondition { true })  // Always last
```

---

## 📚 History Compression

### Reference: Koog Docs

- **Source:** *History compression*
- **Node:** `nodeLLMCompressHistory<T>()`

### Usage in Qualifier

```kotlin
val nodeCheckHistorySize by node<String, String> { input ->
    input  // Pass through, just for condition check
}

val nodeCompressHistory by nodeLLMCompressHistory<String>()

// Compress if message count exceeds threshold
edge((nodeSendStoreResult forwardTo nodeCompressHistory).onCondition {
    llm.readSession { prompt.messages.size > 80 }
})
edge(nodeCompressHistory forwardTo nodeAdvance)

// Normal path (no compression needed)
edge((nodeSendStoreResult forwardTo nodeAdvance).onCondition {
    llm.readSession { prompt.messages.size <= 80 }
})
```

**Threshold:** 80 messages
**Reasoning:**
- Each chunk processing: ~2 messages (request + result)
- 80 msgs = ~40 chunks processed
- Safety margin before token overflow

---

## 🛠️ Subgraph State Preservation

### Problem: State Loss

❌ **Anti-pattern:**
```kotlin
val mySubgraph by subgraph<State, State> {
    val nodeTransform by node<State, String> { state ->
        doSomething(state)
        "result"  // Lost state!
    }

    val nodeRestore by node<String, State> { _ ->
        State(...)  // Dummy state - wrong!
    }
}
```

✅ **Correct pattern:**
```kotlin
val mySubgraph by subgraph<WrapperState, WrapperState> {
    // Subgraph-scoped variable
    var preservedState: WrapperState? = null

    val nodeStore by node<WrapperState, WrapperState> { state ->
        preservedState = state
        state
    }

    val nodeTransform by node<WrapperState, String> { state ->
        doSomething(state)
        "intermediate result"
    }

    val nodeRestore by node<String, WrapperState> { result ->
        preservedState!!.copy(result = result)  // Restore + update
    }
}
```

**Wrapper type example:**
```kotlin
data class WrapperState(
    val pipeline: QualifierPipelineState,  // Full state
    val currentIndex: Int,
)
```

---

## 🔍 Troubleshooting Patterns

### Graph Fails to Reach Finish

**Source:** *Troubleshooting → Graph fails to reach finish node*

**Common causes:**
1. ❌ Missing edge from node to finish
2. ❌ Condition never satisfied
3. ❌ Infinite loop (no exit condition)

**Fix:**
```kotlin
// Always provide exit path
edge((nodeCheck forwardTo nodeProcess).onCondition { it.hasMore() })
edge((nodeCheck forwardTo nodeFinish).onCondition { !it.hasMore() })  // Exit!
```

### Stuck in Node

**Source:** *Troubleshooting → Agent stuck in specific node*

**Cause:** All outgoing edges have false conditions

**Fix:**
```kotlin
// Add fallback edge
edge((nodeA forwardTo nodeB).onCondition { specificCondition() })
edge((nodeA forwardTo nodeFallback).onCondition { true })  // Always matches
```

---

## 📝 Control Questions (Answered)

### 1. How does Koog evaluate edges in order?

**Answer:** Edges are evaluated **sequentially in definition order**. First matching condition wins, subsequent edges are not checked.

**Source:** *Troubleshooting → Graph behaves unexpectedly*

**Implication:** Order edges from most specific to most generic. Place fallback (`onCondition { true }`) last.

### 2. What is the recommended LLM → Tool pattern?

**Answer:**
```
nodeLLMRequest
  → (onToolCall) → nodeExecuteTool
  → nodeLLMSendToolResult
    → (onToolCall) → nodeExecuteTool (loop)
    → (onToolNotCalled) → nodeFinish/advance
```

**Source:** *Custom strategy graphs → Tool execution*

**Key:** Use `onToolNotCalled` for deterministic exit, not `onAssistantMessage` in loops.

### 3. How to preserve state in subgraphs?

**Answer:** Use **subgraph-scoped variables** + **wrapper types**:
```kotlin
subgraph<WrapperState, WrapperState> {
    var state: WrapperState? = null

    val nodeStore by node<WrapperState, WrapperState> { s ->
        state = s; s
    }

    val nodeRestore by node<X, WrapperState> { _ ->
        state!!.copy(...)  // Never return dummy!
    }
}
```

**Source:** *Nodes and components → Custom nodes* + Jervis experience

### 4. `onToolNotCalled` vs `onAssistantMessage`?

**Answer:**

| Condition | Semantic | Use Case |
|-----------|----------|----------|
| `onToolNotCalled` | LLM explicitly did NOT call tool | Loop exit, fail-safe, skip |
| `onAssistantMessage` | LLM returned text | Final responses, summaries |

**Source:** *Custom strategy graphs → Conditions* (Koog 0.5.4)

**Rule:** Prefer `onToolNotCalled` in tool-driven loops for explicit semantics.

---

## 📖 Koog Documentation References

All statements in this document are based on:

1. **Custom strategy graphs**
   - Nodes, edges, conditions, subgraphs
   - Tool execution patterns

2. **Troubleshooting**
   - Edge evaluation order
   - Graph fails to reach finish
   - Agent stuck in node

3. **History compression**
   - `nodeLLMCompressHistory<T>()`
   - Token management

4. **Nodes and components**
   - Custom node implementation
   - Built-in nodes (nodeLLMRequest, nodeExecuteTool, etc.)

5. **Conditions**
   - `onToolCall`, `onToolNotCalled`, `onAssistantMessage`, `onMultipleToolCalls`
   - Condition semantics (Koog 0.5.4)

**Version verified:** Koog 0.5.4 (gradle/libs.versions.toml)

---

## ✅ Corrections from Previous Version

### What was wrong

❌ Claimed Koog version was 0.5.3
❌ Stated `onToolNotCalled` doesn't exist
❌ Recommended using `onAssistantMessage` as replacement

### What is correct (Koog 0.5.4)

✅ Koog version is **0.5.4** (verified in libs.versions.toml)
✅ `onToolNotCalled` **exists and is documented** in Koog 0.5.4
✅ `onToolNotCalled` is **primary** for tool-driven loops
✅ `onAssistantMessage` is **secondary** for text responses

### Impact on refactoring

All chunk loop patterns, routing subgraphs, and edge conditions **must use `onToolNotCalled`** correctly according to Koog 0.5.4 API.
