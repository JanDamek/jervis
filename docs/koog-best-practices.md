# Koog Framework Best Practices

> Kompletní guide pro idiomatické používání Koog frameworku pro Kotlin AI agenty

## 📚 Obsah

1. [Type System & Data Flow](#type-system--data-flow)
2. [Subgraphs: Input/Output Types](#subgraphs-inputoutput-types)
3. [Edge Routing Patterns](#edge-routing-patterns)
4. [LLM Response Handling](#llm-response-handling)
5. [Tool Integration](#tool-integration)
6. [State Management](#state-management)
7. [Anti-Patterns](#anti-patterns)
8. [Practical Examples](#practical-examples)

---

## Type System & Data Flow

### Základní pravidlo

**Output type uzlu/subgraphu MUSÍ odpovídat Input typu následujícího uzlu/subgraphu.**

```kotlin
// ✅ SPRÁVNĚ:
node<A, B> → node<B, C> → subgraph<C, D>

// ❌ ŠPATNĚ:
node<A, B> → node<C, D>  // Type mismatch! B ≠ C
```

### Typy edge connections

```kotlin
// 1. Přímé spojení (typy sedí)
edge(nodeA forwardTo nodeB)  // nodeA: <X, Y>, nodeB: <Y, Z>

// 2. Transformace (typy nesedí)
edge((nodeA forwardTo nodeB).transformed { y: Y -> Z(...) })

// 3. Conditional (+ type check)
edge((nodeA forwardTo nodeB).onCondition { output: Y -> output.isValid })
```

---

## Subgraphs: Input/Output Types

### Pattern 1: Konverzační subgraphy (`<String, String>`)

**Použití:** Sekvenční LLM operace bez explicitního state branching

```kotlin
val researchSubgraph by subgraph<String, String>(name = "Research") {
    val nodeLLM by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeLLM)
    edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
    edge((nodeLLM forwardTo nodeFinish).onAssistantMessage { true })
    edge(nodeExecuteTool forwardTo nodeSendResult)
    edge((nodeSendResult forwardTo nodeExecuteTool).onToolCall { true })
    edge((nodeSendResult forwardTo nodeFinish).onAssistantMessage { true })
}

// Používá se pro:
// - Lineární flow (research → analysis → execution)
// - State v LLM conversation history, ne v typed objektu
// - Každý subgraph pokračuje v konverzaci předchozího
```

**Spojení mezi konverzačními subgraphy:**
```kotlin
edge((researchSubgraph forwardTo analysisSubgraph).transformed { it })
```
*Note: `.transformed { it }` je nutný i když typ je stejný, kvůli Koog type systému.*

### Pattern 2: State-based subgraphy (`<InputType, OutputType>`)

**Použití:** Explicitní state transformace s type-safe routing

```kotlin
data class VisionInput(val content: String, val attachments: List<Attachment>)
data class VisionOutput(val augmentedContent: String, val processedAttachments: List<Attachment>)

val visionSubgraph by subgraph<VisionInput, VisionOutput>(name = "Vision") {
    val nodeLoadAttachments by node<VisionInput, List<AttachmentData>> { input ->
        input.attachments.map { loadAttachment(it) }
    }

    val nodeProcessVision by node<List<AttachmentData>, VisionOutput> { data ->
        val augmented = processWithVisionModel(data)
        VisionOutput(augmented, ...)
    }

    edge(nodeStart forwardTo nodeLoadAttachments)
    edge(nodeLoadAttachments forwardTo nodeProcessVision)
    edge(nodeProcessVision forwardTo nodeFinish)
}

// Používá se pro:
// - Transformace dat mezi fázemi
// - Conditional branching podle output typu
// - Explicitní state management
```

---

## Edge Routing Patterns

### Pattern 1: Tool Call vs Assistant Message

**Základní pattern pro LLM interakci:**

```kotlin
val nodeLLM by nodeLLMRequest()
val nodeExecuteTool by nodeExecuteTool()
val nodeSendToolResult by nodeLLMSendToolResult()
val nodeParseResponse by node<String, ParsedResult>()

// LLM může buď zavolat tool, nebo vrátit text
edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeLLM forwardTo nodeParseResponse).onAssistantMessage { true })

// Po executu toolu se výsledek pošle zpět LLM
edge(nodeExecuteTool forwardTo nodeSendToolResult)

// LLM po tool resultu může zavolat další tool, nebo skončit
edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendToolResult forwardTo nodeParseResponse).onAssistantMessage { true })
```

### Pattern 2: Conditional Branching na Data Class Properties

**LLM nemůže vracet sealed classes. Místo toho:**

1. LLM vrací String (assistant message) nebo volá tool
2. Node parsuje String → data class s boolean/enum fields
3. Edges routují podle těchto fields

```kotlin
// Output data class s routing flags
data class Phase1Result(
    val success: Boolean,          // Parsing uspěl?
    val chunks: List<String>,      // Data
    val earlyRouting: String?,     // "DONE" | "LIFT_UP" | null
    val error: String?             // Chybová zpráva
)

// Node parsuje LLM response
val nodeParseP1 by node<String, Phase1Result> { assistantMessage ->
    if (assistantMessage.contains("---CHUNK---")) {
        Phase1Result(
            success = true,
            chunks = parse(assistantMessage),
            earlyRouting = null,
            error = null
        )
    } else {
        Phase1Result(
            success = false,
            chunks = emptyList(),
            earlyRouting = null,
            error = "Missing delimiters"
        )
    }
}

// Edges routují podle properties
edge((nodeParseP1 forwardTo nodeHandleSuccess).onCondition { it.success })
edge((nodeParseP1 forwardTo nodeHandleError).onCondition { !it.success })
edge((nodeParseP1 forwardTo nodeEarlyExit).onCondition { it.earlyRouting != null })
```

### Pattern 3: Retry Logic

```kotlin
data class RetryDecision(
    val shouldRetry: Boolean,
    val retryPrompt: String,
    val attemptCount: Int
)

val nodeDecideRetry by node<ParseResult, RetryDecision> { parseResult ->
    val nextAttempt = parseResult.attemptCount + 1
    RetryDecision(
        shouldRetry = nextAttempt <= MAX_RETRIES,
        retryPrompt = createRetryPrompt(parseResult.error),
        attemptCount = nextAttempt
    )
}

// Routing podle retry decision
edge((nodeDecideRetry forwardTo nodeRetryLLM).onCondition { it.shouldRetry })
edge((nodeDecideRetry forwardTo nodeFail).onCondition { !it.shouldRetry })
```

---

## LLM Response Handling

### ✅ Best Practice: Parsing Node Pattern

```kotlin
// 1. LLM vrací String
val nodeLLM by nodeLLMRequest()

// 2. Node parsuje String → structured data
val nodeParse by node<String, StructuredResult> { assistantMessage ->
    try {
        // Parsing logic (delimiter, JSON, regex...)
        StructuredResult(success = true, data = ...)
    } catch (e: Exception) {
        StructuredResult(success = false, error = e.message)
    }
}

// 3. Edges routují podle parsed data
edge((nodeLLM forwardTo nodeParse).onAssistantMessage { true })
edge((nodeParse forwardTo nodeSuccess).onCondition { it.success })
edge((nodeParse forwardTo nodeRetry).onCondition { !it.success })
```

### ❌ Anti-Pattern: External Mutable State

```kotlin
// ❌ ŠPATNĚ:
var capturedResponse: String? = null

val nodeParse by node<String, Unit> { msg ->
    capturedResponse = msg  // Side effect!
}

edge(nodeParse forwardTo nodeUseResponse).onCondition {
    capturedResponse != null  // Nespolehlivé!
}
```

---

## Tool Integration

### Tool Output Types

**Koog automaticky serializuje/deserializuje tool results jako JSON.**

```kotlin
// Tool definition
@Tool
suspend fun storeKnowledge(...): StoreKnowledgeResult {
    // ... implementace ...
    return StoreKnowledgeResult(
        success = true,
        chunkId = "chunk_123",
        mainNodeKey = "node_456",
        nodesCreated = 5,
        edgesCreated = 8
    )
}

data class StoreKnowledgeResult(
    val success: Boolean,
    val chunkId: String,
    val mainNodeKey: String,
    val nodesCreated: Int,
    val edgesCreated: Int
)
```

**LLM vidí tool jako JSON schema, volá s parametry, dostává JSON result.**

### ✅ Best Practice: Typed Tool Results

```kotlin
// Node zpracovává typed result (ne String parsing!)
val nodeProcessToolResult by node<ToolResult, NextState> { result ->
    if (result.success) {
        NextState.Success(result.chunkId)
    } else {
        NextState.Retry(result.error)
    }
}
```

### ❌ Anti-Pattern: String Parsing Tool Results

```kotlin
// ❌ ŠPATNĚ:
@Tool
fun storeTool(): String {
    return "✓ Stored. ChunkId: $id, MainNode: $node"
}

// Pak nutno parsovat regexem:
val chunkId = """ChunkId:\s*([a-z0-9_]+)""".toRegex().find(result)?.groupValues?.get(1)
```

---

## State Management

### ✅ Best Practice: State Flow Through Nodes

```kotlin
data class ProcessingState(
    val chunks: List<String>,
    val currentIndex: Int,
    val results: List<ChunkResult>,
    val retryCount: Int
) {
    fun nextChunk(): String? = chunks.getOrNull(currentIndex)
    fun hasMore(): Boolean = currentIndex < chunks.size
    fun withResult(result: ChunkResult) = copy(
        currentIndex = currentIndex + 1,
        results = results + result,
        retryCount = 0
    )
}

// State teče přes edges
val nodeProcessChunk by node<ProcessingState, ProcessingState> { state ->
    val chunk = state.nextChunk()
    // Process chunk...
    state.withResult(result)
}

// Loop back if more chunks
edge((nodeProcessChunk forwardTo nodeProcessChunk).onCondition { it.hasMore() })
edge((nodeProcessChunk forwardTo nodeFinish).onCondition { !it.hasMore() })
```

### ❌ Anti-Pattern: External Mutable State

```kotlin
// ❌ ŠPATNĚ:
var currentState: ProcessingState? = null

val nodeStoreState by node<ProcessingState, ProcessingState> { state ->
    currentState = state  // Side effect - špatný pattern!
    state
}

val nodeUseState by node<Unit, Result> {
    processChunk(currentState!!.nextChunk())  // Nebezpečné!
}
```

---

## Anti-Patterns

### ❌ 1. Sdílený Mutable State

```kotlin
// ❌ ŠPATNĚ:
var capturedRouting: String? = null
var originalDocument: String = ""
var retryCount = 0

// ✅ SPRÁVNĚ:
data class State(
    val routing: String?,
    val document: String,
    val retryCount: Int
)
```

### ❌ 2. runBlocking v Node

```kotlin
// ❌ ŠPATNĚ:
val nodeCreateDoc by node {
    kotlinx.coroutines.runBlocking {
        service.create(...)
    }
}

// ✅ SPRÁVNĚ:
@Tool
suspend fun createDocument(...): CreateResult

// Nebo suspend node:
val nodeCreateDoc by node {
    service.create(...)  // Suspend function
}
```

### ❌ 3. String Parsing Tool Results

```kotlin
// ❌ ŠPATNĚ:
@Tool
fun tool(): String = "Success: $data"

val regex = """Success:\s*(.+)""".toRegex()

// ✅ SPRÁVNĚ:
@Tool
fun tool(): ToolResult = ToolResult(success = true, data = data)
```

### ❌ 4. Subgraph bez Type Variance

```kotlin
// ⚠️ PROBLÉM:
subgraph<State, State>  // Nelze type-safe route!

// ✅ LEPŠÍ:
subgraph<State, StateResult>  // Result má properties pro routing

data class StateResult(
    val state: State,
    val shouldContinue: Boolean,
    val error: String?
)
```

---

## Practical Examples

### Example 1: Vision Augmentation (Conditional Execution)

```kotlin
data class VisionInput(val content: String, val attachments: List<Attachment>)
data class VisionOutput(val augmentedContent: String, val processedAttachments: List<Attachment>)

val visionSubgraph by subgraph<VisionInput, VisionOutput> {
    val nodeCheckAttachments by node<VisionInput, VisionInput> { input ->
        logger.info("Visual attachments: ${input.attachments.count { it.isVisual }}")
        input
    }

    val nodeSkipVision by node<VisionInput, VisionOutput> { input ->
        VisionOutput(augmentedContent = input.content, processedAttachments = input.attachments)
    }

    val nodeProcessVision by node<VisionInput, VisionOutput> { input ->
        val augmented = processWithVisionModel(input.attachments)
        VisionOutput(augmentedContent = augmented, processedAttachments = input.attachments)
    }

    edge(nodeStart forwardTo nodeCheckAttachments)
    edge((nodeCheckAttachments forwardTo nodeSkipVision).onCondition {
        it.attachments.none { att -> att.isVisual }
    })
    edge((nodeCheckAttachments forwardTo nodeProcessVision).onCondition {
        it.attachments.any { att -> att.isVisual }
    })
    edge(nodeSkipVision forwardTo nodeFinish)
    edge(nodeProcessVision forwardTo nodeFinish)
}
```

### Example 2: LLM Parsing with Retry

```kotlin
data class ParseResult(
    val success: Boolean,
    val data: ParsedData?,
    val error: String?,
    val attemptCount: Int
)

val parseSubgraph by subgraph<String, ParseResult> {
    val nodePreparePrompt by node<String, String> { input ->
        "Parse this content: $input"
    }

    val nodeLLM by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendResult by nodeLLMSendToolResult()

    var attemptCount = 0  // Local to subgraph

    val nodeParse by node<String, ParseResult> { assistantMsg ->
        attemptCount++
        try {
            ParseResult(
                success = true,
                data = parse(assistantMsg),
                error = null,
                attemptCount = attemptCount
            )
        } catch (e: Exception) {
            ParseResult(
                success = false,
                data = null,
                error = e.message,
                attemptCount = attemptCount
            )
        }
    }

    val nodeRetry by node<ParseResult, String> { result ->
        "ERROR: ${result.error}. Try again with correct format."
    }

    edge(nodeStart forwardTo nodePreparePrompt)
    edge(nodePreparePrompt forwardTo nodeLLM)
    edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
    edge((nodeLLM forwardTo nodeParse).onAssistantMessage { true })
    edge(nodeExecuteTool forwardTo nodeSendResult)
    edge((nodeSendResult forwardTo nodeExecuteTool).onToolCall { true })
    edge((nodeSendResult forwardTo nodeParse).onAssistantMessage { true })

    // Success path
    edge((nodeParse forwardTo nodeFinish).onCondition { it.success })

    // Retry path (max 2 attempts)
    edge((nodeParse forwardTo nodeRetry).onCondition {
        !it.success && it.attemptCount < 2
    })
    edge(nodeRetry forwardTo nodeLLM)

    // Fail path (max attempts exceeded)
    edge((nodeParse forwardTo nodeFinish).onCondition {
        !it.success && it.attemptCount >= 2
    })
}
```

### Example 3: Chunked Processing Loop

```kotlin
data class ProcessingState(
    val chunks: List<String>,
    val currentIndex: Int,
    val results: List<ChunkResult>
) {
    fun hasMore() = currentIndex < chunks.size
    fun nextChunk() = chunks[currentIndex]
    fun withResult(result: ChunkResult) = copy(
        currentIndex = currentIndex + 1,
        results = results + result
    )
}

val processingSubgraph by subgraph<ProcessingState, ProcessingState> {
    val nodePrepareChunk by node<ProcessingState, String> { state ->
        "Process chunk ${state.currentIndex + 1}/${state.chunks.size}: ${state.nextChunk()}"
    }

    val nodeLLM by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendResult by nodeLLMSendToolResult()

    val nodeUpdateState by node<String, ProcessingState> { toolResult ->
        // Tool vrací typed result, parsed automaticky Koog frameworkem
        val result = ChunkResult.fromString(toolResult)
        currentProcessingState.withResult(result)
    }

    edge(nodeStart forwardTo nodePrepareChunk)
    edge(nodePrepareChunk forwardTo nodeLLM)
    edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
    edge(nodeExecuteTool forwardTo nodeSendResult)
    edge((nodeSendResult forwardTo nodeExecuteTool).onToolCall { true })
    edge((nodeSendResult forwardTo nodeUpdateState).onAssistantMessage { true })

    // Loop back if more chunks
    edge((nodeUpdateState forwardTo nodePrepareChunk).onCondition { it.hasMore() })
    // Finish if no more chunks
    edge((nodeUpdateState forwardTo nodeFinish).onCondition { !it.hasMore() })
}
```

---

## Summary: Klíčové principy

1. **Type Safety**: Output type = Input type následujícího node
2. **No External State**: Vše through node/edge flow
3. **Typed Tool Results**: Data classes, ne String parsing
4. **LLM Parsing Pattern**: String → node → ParseResult → conditional edges
5. **Conditional Branching**: `.onCondition { }` na data class properties
6. **Retry Logic**: Counter v data class + edge conditions
7. **Subgraph Input/Output**: Různé typy = explicitní transformation
8. **Tool Call Routing**: `.onToolCall` vs `.onAssistantMessage`

---

## Refactoring Guide: Od Anti-Patterns k Best Practices

### 1. Odstranění External Mutable State

**Před:**
```kotlin
// ❌ External var - dostupná všude
var capturedRouting: String? = null
var currentState: ProcessingState? = null

val mySubgraph by subgraph<Input, Output> {
    val node1 by node<...> {
        currentState = ...  // Mutuje externí stav
    }
}
```

**Po:**
```kotlin
// ✅ Subgraph-scoped var - local tracking only
val mySubgraph by subgraph<Input, Output> {
    var localState: ProcessingState? = null  // Scoped to subgraph

    val node1 by node<...> {
        localState = ...  // OK - local to subgraph
    }
}
```

### 2. Odstranění `runBlocking` Anti-Pattern

**Před:**
```kotlin
// ❌ runBlocking v suspend node
val nodeProcessData by node<Input, Output> { input ->
    kotlinx.coroutines.runBlocking {
        service.doSomething(input)
    }
    output
}
```

**Po:**
```kotlin
// ✅ Direct suspend call (nodes are already suspend)
val nodeProcessData by node<Input, Output> { input ->
    service.doSomething(input)  // Direct suspend call
    output
}
```

### 3. Odstranění Regex Parsing Tool Results

**Před:**
```kotlin
// ❌ Tool returns String, parse with regex
suspend fun storeData(...): String {
    return "ChunkId: $chunkId, NodeKey: $nodeKey"
}

val nodeProcess by node<String, State> { toolResult ->
    val chunkIdRegex = """ChunkId:\s*([a-z0-9]+)""".toRegex()
    val chunkId = chunkIdRegex.find(toolResult)?.groupValues?.get(1) ?: "unknown"
    // ... brittle parsing
}
```

**Po:**
```kotlin
// ✅ Tool returns typed data class
@Serializable
data class StoreDataResult(
    val success: Boolean,
    val chunkId: String,
    val nodeKey: String,
    val errorMessage: String? = null
)

suspend fun storeData(...): StoreDataResult {
    return StoreDataResult(
        success = true,
        chunkId = chunkId,
        nodeKey = nodeKey
    )
}

// Koog automatically handles JSON serialization/deserialization
// LLM sees JSON, but you don't need to parse it manually
// If you need the result, track it in subgraph-local var during tool execution
```

### 4. Zjednodušení State Tracking

**Před:**
```kotlin
// ❌ Complex result tracking with unnecessary detail
data class ChunkResult(val chunkId: String, val mainNode: String)
data class State(val processedResults: List<ChunkResult>)

val node by node<String, State> { result ->
    val chunkId = parseChunkId(result)  // Regex parsing
    val mainNode = parseMainNode(result)
    state.copy(processedResults = state.processedResults + ChunkResult(chunkId, mainNode))
}
```

**Po:**
```kotlin
// ✅ Simple counter when details aren't needed
data class State(val processedCount: Int)

val node by node<String, State> { assistantMessage ->
    state.copy(processedCount = state.processedCount + 1)
}

// Data is already in Graph+RAG, no need to track IDs again
```

### 5. JSON Structured Output místo Delimiter Parsing

**Před:**
```kotlin
// ❌ Delimiter-based parsing (fragile)
const val PROMPT = """
Output format:
---BASEINFO---
High-level summary here
---CHUNK---
Chunk 1 text
---CHUNK---
Chunk 2 text
"""

val node by node<String, Result> { llmOutput ->
    val parts = llmOutput.split("---CHUNK---")
    val baseInfo = parts.first().substringAfter("---BASEINFO---")
    // ... fragile string splitting
}
```

**Po:**
```kotlin
// ✅ JSON structured output (robust)
@Serializable
data class LLMOutput(
    val baseInfo: String,
    val blocks: List<String>
)

const val PROMPT = """
Output ONLY valid JSON:
{
  "baseInfo": "High-level summary",
  "blocks": ["verbatim block 1", "verbatim block 2"]
}
"""

val node by node<String, Result> { llmOutput ->
    val cleanJson = llmOutput.trim()
        .removePrefix("```json")
        .removeSuffix("```")
        .trim()

    val parsed = json.decodeFromString<LLMOutput>(cleanJson)
    // ... type-safe access to parsed.baseInfo, parsed.blocks
}
```

### 6. Callback Pattern → Typed Tool Results

**Před:**
```kotlin
// ❌ Callback to capture tool result
var captured: String? = null

tools(TaskTools(
    onRoutingCaptured = { routing -> captured = routing }
))
```

**Po:**
```kotlin
// ✅ Tool returns typed result, inspect directly
@Serializable
data class RouteTaskResult(
    val routing: String,
    val success: Boolean,
    val message: String
)

suspend fun routeTask(routing: String): RouteTaskResult {
    // ... logic ...
    return RouteTaskResult(routing, true, "Success")
}

// Node/edge can inspect result from LLM session context if needed,
// or rely on routing logic happening in tool itself
```

---

*Dokumentace vytvořena na základě analýzy a refactoringu `KoogQualifierAgent` v projektu Jervis.*
