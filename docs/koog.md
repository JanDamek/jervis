# Jervis – Koog Framework Reference (2026)

**Status:** Production Documentation (2026-02-04)
**Purpose:** Complete Koog framework reference for Kotlin AI agents

---

## Table of Contents

1. [Core Libraries & Components](#core-libraries--components)
2. [Agent Strategy Types](#agent-strategy-types)
3. [Graph-Based Strategy (Production)](#graph-based-strategy-production)
4. [Functional Strategy (Prototyping)](#functional-strategy-prototyping)
5. [Tool Integration & Registration](#tool-integration--registration)
6. [LLM Response Handling](#llm-response-handling)
7. [State Management](#state-management)
8. [Edge Routing Patterns](#edge-routing-patterns)
9. [Prompt Building & Context Window](#prompt-building--context-window)
10. [Agent Events & Tracing](#agent-events--tracing)
11. [Iteration Limits](#iteration-limits)

---

## Core Libraries & Components

### `agents-core-jvm` - Core Agent Framework

- `AIAgent` - Main agent class with functional and graph strategies
- `AIAgentConfig` - Agent configuration (prompt, model, iterations)
- `ToolRegistry` - Tool registration and management
- Strategy DSL: `strategy()`, `node()`, `edge()`
- Execution flow: `nodeStart`, `nodeSendInput`, `nodeExecuteTool`, `nodeSendToolResult`, `nodeFinish`

### `agents-ext-jvm` - Extension Tools

- **File System Tools:**
  - `ListDirectoryTool(provider)` - List directory contents
  - `ReadFileTool(provider)` - Read file content
  - `EditFileTool(provider)` - Edit files with patches
  - `WriteFileTool(provider)` - Write/create files
  - Providers: `JVMFileSystemProvider.ReadOnly`, `JVMFileSystemProvider.ReadWrite`

- **Shell Tools:**
  - `ExecuteShellCommandTool(executor, confirmationHandler)`
  - `JvmShellCommandExecutor()` - JVM shell executor
  - `PrintShellCommandConfirmationHandler()` - Print confirmations

- **Other:** `AskUser`, `SayToUser`, `ExitTool`

### `agents-tools-jvm` - Tool System

- `@Tool` - Annotation for tool methods
- `@LLMDescription` - LLM-readable descriptions
- `ToolSet` - Interface for tool collections
- `Tool<I, O>` - Tool interface
- `ToolRegistry` - Registry builder DSL

### `agents-features-memory-jvm` - Agent Memory

- `AgentMemory` - Memory management
- `AgentMemoryProvider` - Memory storage interface
- `LocalFileMemoryProvider` - File-based storage
- Memory scopes and subjects

### `agents-mcp-jvm` - MCP Protocol Support

- `McpTool` - MCP tool wrapper
- `McpToolRegistryProvider` - MCP tool provider

---

## Agent Strategy Types

### Graph-Based Strategy (Production)

Complex workflow agents with strategy graphs for production use:

- **Full persistence** with controllable rollbacks for fault-tolerance
- **Advanced OpenTelemetry tracing** with nested graph events
- **Strategy graph DSL:** `strategy<Input, Output>("name") { ... }`
- **Nodes:** `val nodeName by node<I, O>("Name") { input -> output }`
- **Subgraphs:** `val subgraphName by subgraph<I, O>(name = "Name") { ... }`
- **Edges:** `edge(nodeA forwardTo nodeB)`
- **Conditional routing:** `.onCondition { state -> boolean }`
- **Event routing:** `.onToolCall { true }`, `.onAssistantMessage { true }`

**Example:**
```kotlin
strategy<String, String>("My Strategy") {
    val mySubgraph by subgraph<String, String>(name = "📋 Phase 1") {
        val nodeA by node<String, String>("Node A") { input ->
            logger.info { "Processing: $input" }
            "processed: $input"
        }
        val nodeB by nodeLLMRequest(name = "LLM Request")
        edge(nodeStart forwardTo nodeA)
        edge(nodeA forwardTo nodeB)
        edge(nodeB forwardTo nodeFinish)
    }
    edge(nodeStart forwardTo mySubgraph)
    edge(mySubgraph forwardTo nodeFinish)
}
```

### Functional Strategy (Prototyping)

Lightweight agents without complex graphs:

- **Lambda function** handles user input, LLM calls, and tools
- **Custom control flows** in plain Kotlin
- **History compression** and automatic state management
- **Good for MVP and prototyping**

**Example:**
```kotlin
AIAgent<String, String>(
    systemPrompt = "You are a math assistant.",
    promptExecutor = simpleOllamaAIExecutor(),
    llmModel = OllamaModels.Meta.LLAMA_3_2,
    strategy = functionalStrategy { input ->
        // Single LLM call
        val response = requestLLM(input)
        response.asAssistantMessage().content
    }
)
```

**Multiple LLM calls:**
```kotlin
functionalStrategy { input ->
    val draft = requestLLM("Draft: $input").asAssistantMessage().content
    val improved = requestLLM("Improve: $draft").asAssistantMessage().content
    requestLLM("Format: $improved").asAssistantMessage().content
}
```

**With tools:**
```kotlin
functionalStrategy { input ->
    var responses = requestLLMMultiple(input)

    // Loop while LLM requests tools
    while (responses.containsToolCalls()) {
        val pendingCalls = extractToolCalls(responses)
        val results = executeMultipleTools(pendingCalls)
        responses = sendMultipleToolResults(results)
    }

    responses.single().asAssistantMessage().content
}
```

**Migration Path:**
1. Start with functional agent for MVP
2. Prototype custom logic in plain Kotlin
3. Refactor to graph-based strategy for production (fault-tolerance + tracing)

---

## Graph-Based Strategy (Production)

### Core Concepts

**Type Safety:** Output type of node/subgraph MUST match input type of next node/subgraph.

```kotlin
// ✅ CORRECT:
node<A, B> -> node<B, C> -> subgraph<C, D>

// ❌ WRONG:
node<A, B> -> node<C, D>  // Type mismatch! B ≠ C
```

**Edge Types:**
- **Direct connection:** Types match
- **Transformation:** Types don't match, use `.transformed { ... }`
- **Conditional:** Types match, use `.onCondition { ... }`

### Node Types

**Built-in nodes:**
- `nodeStart` - Entry point
- `nodeLLMRequest` - LLM request
- `nodeExecuteTool` - Tool execution
- `nodeLLMSendToolResult` - Send tool result to LLM
- `nodeFinish` - Exit point

**Custom nodes:**
```kotlin
val nodeName by node<I, O>("Name") { input -> output }
```

**Subgraphs:**
```kotlin
val subgraphName by subgraph<I, O>(name = "Name") {
    // ... node definitions
}
```

### Edge Routing Patterns

#### Pattern 1: Tool Call vs Assistant Message

**Basic pattern for LLM interaction:**

```kotlin
val nodeLLM by nodeLLMRequest()
val nodeExecuteTool by nodeExecuteTool()
val nodeSendToolResult by nodeLLMSendToolResult()
val nodeParseResponse by node<String, ParsedResult>()

// LLM can either call tool or return text
edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeLLM forwardTo nodeParseResponse).onAssistantMessage { true })

// After tool execution, send result back to LLM
edge(nodeExecuteTool forwardTo nodeSendToolResult)

// LLM after tool result can call another tool or finish
edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendToolResult forwardTo nodeParseResponse).onAssistantMessage { true })
```

#### Pattern 2: Conditional Branching on Data Class Properties

**LLM cannot return sealed classes. Instead:**

1. LLM returns String (assistant message) or calls tool
2. Node parses String → data class with boolean/enum fields
3. Edges route based on these fields

```kotlin
data class Phase1Result(
    val success: Boolean,          // Parsing succeeded?
    val chunks: List<String>,      // Data
    val earlyRouting: String?,     // "DONE" | "LIFT_UP" | null
    val error: String?             // Error message
)

// Node parses LLM response
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

// Edges route based on properties
edge((nodeParseP1 forwardTo nodeHandleSuccess).onCondition { it.success })
edge((nodeParseP1 forwardTo nodeHandleError).onCondition { !it.success })
edge((nodeParseP1 forwardTo nodeEarlyExit).onCondition { it.earlyRouting != null })
```

#### Pattern 3: Retry Logic

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

// Routing based on retry decision
edge((nodeDecideRetry forwardTo nodeRetryLLM).onCondition { it.shouldRetry })
edge((nodeDecideRetry forwardTo nodeFail).onCondition { !it.shouldRetry })
```

---

## Functional Strategy (Prototyping)

### Basic Pattern

```kotlin
functionalStrategy { input ->
    // Single LLM call
    val response = requestLLM(input)
    response.asAssistantMessage().content
}
```

### Multiple LLM Calls

```kotlin
functionalStrategy { input ->
    val draft = requestLLM("Draft: $input").asAssistantMessage().content
    val improved = requestLLM("Improve: $draft").asAssistantMessage().content
    requestLLM("Format: $improved").asAssistantMessage().content
}
```

### With Tools

```kotlin
functionalStrategy { input ->
    var responses = requestLLMMultiple(input)

    // Loop while LLM requests tools
    while (responses.containsToolCalls()) {
        val pendingCalls = extractToolCalls(responses)
        val results = executeMultipleTools(pendingCalls)
        responses = sendMultipleToolResults(results)
    }

    responses.single().asAssistantMessage().content
}
```

---

## Tool Integration & Registration

### Tool Output Types

**Koog automatically serializes/deserializes tool results as JSON.**

```kotlin
// Tool definition
@Tool
suspend fun storeKnowledge(...): StoreKnowledgeResult {
    // ... implementation ...
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

**LLM sees tool as JSON schema, calls with parameters, gets JSON result.**

### Tool Registration

**Single tool:**
```kotlin
ToolRegistry {
    // Single tool
    tools(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
}
```

**Multiple tools as list:**
```kotlin
ToolRegistry {
    // Multiple tools as list
    tools(listOf(
        ReadFileTool(JVMFileSystemProvider.ReadOnly),
        EditFileTool(JVMFileSystemProvider.ReadWrite)
    ))
}
```

**Custom ToolSet:**
```kotlin
ToolRegistry {
    // Custom ToolSet
    tools(MyCustomToolSet())
}
```

---

## LLM Response Handling

### ✅ Best Practice: Parsing Node Pattern

```kotlin
// 1. LLM returns String
val nodeLLM by nodeLLMRequest()

// 2. Node parses String → structured data
val nodeParse by node<String, StructuredResult> { assistantMessage ->
    try {
        // Parsing logic (delimiter, JSON, regex...)
        StructuredResult(success = true, data = ...)
    } catch (e: Exception) {
        StructuredResult(success = false, error = e.message)
    }
}

// 3. Edges route based on parsed data
edge((nodeLLM forwardTo nodeParse).onAssistantMessage { true })
edge((nodeParse forwardTo nodeSuccess).onCondition { it.success })
edge((nodeParse forwardTo nodeRetry).onCondition { !it.success })
```

### ❌ Anti-Pattern: External Mutable State

```kotlin
// ❌ WRONG:
var capturedResponse: String? = null

val nodeParse by node<String, Unit> { msg ->
    capturedResponse = msg  // Side effect!
}

edge(nodeParse forwardTo nodeUseResponse).onCondition {
    capturedResponse != null  // Unreliable!
}
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

// State flows through edges
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
// ❌ WRONG:
var currentState: ProcessingState? = null

val nodeStoreState by node<ProcessingState, ProcessingState> { state ->
    currentState = state  // Side effect - bad pattern!
    state
}

val nodeUseState by node<Unit, Result> {
    processChunk(currentState!!.nextChunk())  // Dangerous!
}
```

---

## Edge Routing Patterns

### Pattern 1: Tool Call vs Assistant Message

**Basic pattern for LLM interaction:**

```kotlin
val nodeLLM by nodeLLMRequest()
val nodeExecuteTool by nodeExecuteTool()
val nodeSendToolResult by nodeLLMSendToolResult()

// LLM can either call tool or return text
edge((nodeLLM forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeLLM forwardTo nodeFinish).onAssistantMessage { true })

// After tool execution, send result back to LLM
edge(nodeExecuteTool forwardTo nodeSendToolResult)

// LLM after tool result can call another tool or finish
edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
```

### Pattern 2: Conditional Branching on Data Class Properties

**LLM cannot return sealed classes. Instead:**

1. LLM returns String (assistant message) or calls tool
2. Node parses String → data class with boolean/enum fields
3. Edges route based on these fields

```kotlin
data class Phase1Result(
    val success: Boolean,          // Parsing succeeded?
    val chunks: List<String>,      // Data
    val earlyRouting: String?,     // "DONE" | "LIFT_UP" | null
    val error: String?             // Error message
)

// Node parses LLM response
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

// Edges route based on properties
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

// Routing based on retry decision
edge((nodeDecideRetry forwardTo nodeRetryLLM).onCondition { it.shouldRetry })
edge((nodeDecideRetry forwardTo nodeFail).onCondition { !it.shouldRetry })
```

---

## Prompt Building & Context Window

### Prompt DSL

```kotlin
val prompt = Prompt.build("my-prompt") {
    system("You are a helpful assistant.")
    user("Please help me with ...")
}
```

### Context Window (CRITICAL)

**PROBLEM:** Koog's `contextLength` in `LLModel` is **DECLARATIVE ONLY** - it's metadata for Koog framework, **NOT sent to LLM API**.

**Example:**
```kotlin
LLModel(
    model = "qwen2.5:32b",
    contextLength = 240_000, // This is ONLY for Koog framework tracking
    // ... other params
)
```

This does **NOT** configure Ollama's `num_ctx`. Ollama defaults to 4096, causing prompt truncation!

**CRITICAL:** Context window = **INPUT + OUTPUT combined!**
- If you send 45k tokens input and expect 50k tokens output = **95k tokens total**
- Always reserve space for output (50% input / 50% output is safe ratio)
- Add safety margin (10-20%) for metadata and tool calls

**SOLUTION:** Configure `num_ctx` in Ollama Modelfile (custom model).

#### Ollama Context Window Configuration

**For Ollama models, you MUST:**
1. Create custom Modelfile with `PARAMETER num_ctx`
2. Set matching `contextLength` in `LLModel` (for Koog tracking)

**Step 1: Create Modelfile**

Calculate required context:
```
Input estimate:  45,000 tokens (document + prompt)
Output estimate: 50,000 tokens (response, can be larger than input!)
Safety margin:   20% (tools, metadata)
Total needed:    114,000 tokens
Rounded up:      120,000 tokens
```

Create `Modelfile`:
```modelfile
FROM qwen2.5:32b
PARAMETER num_ctx 120000
PARAMETER temperature 0.0
```

Create custom model:
```bash
ollama create qwen2.5-qualifier -f ./Modelfile
```

**Step 2: Configure in code**

```kotlin
// models-config.yaml
providers:
  - name: OLLAMA_QUALIFIER
    models:
      - modelId: qwen2.5-qualifier  # Custom model with num_ctx=120000
        contextLength: 120000  # MUST match Modelfile!

// KoogQualifierAgent.kt
AIAgentConfig(
    model = LLModel(
        provider = LLMProvider.Ollama,
        id = "qwen2.5-qualifier",  # Uses Modelfile with num_ctx=120000
        contextLength = 120_000,    # MUST match Modelfile num_ctx
        // ...
    )
)
```

**Why Modelfile approach?**
- Koog's `simpleOllamaAIExecutor()` doesn't support runtime `num_ctx`
- Modelfile bakes `num_ctx` into model definition
- No need for custom executor implementation
- Cleaner and more maintainable

**Input/Output Ratio Guidelines:**

| Use Case | Input Tokens | Output Tokens | Total Needed | Recommended num_ctx |
|----------|--------------|---------------|--------------|---------------------|
| Qualifier Phase 1 (chunking) | 45k | 50k | 95k | **120k** (with margin) |
| Qualifier Phase 2 (per chunk) | 10k | 5k | 15k | **20k** (with margin) |
| Workflow Agent (complex) | 80k | 80k | 160k | **200k** (with margin) |
| Short tasks | 2k | 2k | 4k | **8k** (default OK) |

**Common Mistakes:**

```kotlin
// ❌ WRONG - Uses all 240k for input, no room for output!
LLModel(
    model = "qwen2.5:32b",
    contextLength = 240_000 // Will truncate output at ~0 tokens!
)

// ❌ WRONG - contextLength not backed by Modelfile
LLModel(
    model = "qwen2.5:32b",  // Default num_ctx=4096 in Ollama
    contextLength = 240_000 // Koog thinks it's 240k, but Ollama truncates at 4k!
)

// ✅ CORRECT - Modelfile sets num_ctx, contextLength matches
// Modelfile: PARAMETER num_ctx 120000
LLModel(
    model = "qwen2.5-qualifier",  // Custom model with num_ctx=120000
    contextLength = 120_000        // Matches Modelfile, 50% input / 50% output
)
```

**Truncation Warning:**
If you see in Ollama logs:
```
level=WARN msg="truncating input prompt" limit=4096 prompt=4152
```
→ Your executor is NOT setting `num_ctx`!

---

## Agent Events & Tracing

### Event Types

Agent execution emits predefined events for observability:

**Agent lifecycle:**
- `AgentStartingEvent` - agent run started
- `AgentCompletedEvent` - agent run completed with result
- `AgentExecutionFailedEvent` - error during agent run
- `AgentClosingEvent` - agent closure/termination

**Strategy events:**
- `GraphStrategyStartingEvent` - graph strategy started
- `FunctionalStrategyStartingEvent` - functional strategy started
- `StrategyCompletedEvent` - strategy completed with result

**Node events:**
- `NodeExecutionStartingEvent` - node run started with input
- `NodeExecutionCompletedEvent` - node completed with input/output
- `NodeExecutionFailedEvent` - node failed with error

**Subgraph events:**
- `SubgraphExecutionStartingEvent` - subgraph started
- `SubgraphExecutionCompletedEvent` - subgraph completed
- `SubgraphExecutionFailedEvent` - subgraph failed

**LLM events:**
- `LLMCallStartingEvent` - LLM call started with prompt
- `LLMCallCompletedEvent` - LLM call completed with responses
- `LLMStreamingStartingEvent` - streaming started
- `LLMStreamingFrameReceivedEvent` - streaming frame received
- `LLMStreamingCompletedEvent` - streaming completed
- `LLMStreamingFailedEvent` - streaming failed

**Tool events:**
- `ToolExecutionStartingEvent` - tool called by model
- `ToolValidationFailedEvent` - tool validation error
- `ToolExecutionFailedEvent` - tool execution failed
- `ToolExecutionCompletedEvent` - tool completed with result

### EventHandler Feature

Install EventHandler to process agent events:

```kotlin
AIAgent(
    promptExecutor = executor,
    toolRegistry = registry,
    strategy = strategy,
    agentConfig = config,
    installFeatures = {
        install(feature = EventHandler) {
            onAgentStarting { eventContext: AgentStartingContext ->
                logger.info { "🔵 AGENT_START | agentId=${eventContext.agent.id}" }
            }
            onAgentCompleted { eventContext: AgentCompletedContext ->
                logger.info { "🟢 AGENT_COMPLETE | result=${eventContext.result}" }
            }
            onNodeExecutionStarting { eventContext ->
                logger.info { "📦 NODE_START | node=${eventContext.nodeName}" }
            }
            onNodeExecutionCompleted { eventContext ->
                logger.info { "✅ NODE_COMPLETE | node=${eventContext.nodeName}" }
            }
            onLLMCallStarting { eventContext ->
                logger.info { "🤖 LLM_CALL_START | model=${eventContext.model.model}" }
            }
            onLLMCallCompleted { eventContext ->
                logger.info { "🤖 LLM_CALL_COMPLETE | responses=${eventContext.responses.size}" }
            }
            onToolExecutionStarting { eventContext ->
                logger.info { "🔧 TOOL_START | tool=${eventContext.toolName}" }
            }
            onToolExecutionCompleted { eventContext ->
                logger.info { "✅ TOOL_COMPLETE | tool=${eventContext.toolName}" }
            }
        }
    }
)
```

**Event Context Objects:**
- `AgentStartingContext` - agentId, runId
- `AgentCompletedContext` - agentId, runId, result
- `NodeExecutionStartingContext` - runId, nodeName, input
- `NodeExecutionCompletedContext` - runId, nodeName, input, output
- `LLMCallStartingContext` - runId, callId, prompt, model, tools
- `LLMCallCompletedContext` - runId, callId, prompt, model, responses
- `ToolExecutionStartingContext` - runId, toolCallId, toolName, toolArgs
- `ToolExecutionCompletedContext` - runId, toolCallId, toolName, toolArgs, result

### Tracing Feature (DebugService)

Use Tracing feature to export events to external systems:

```kotlin
install(Tracing) {
    // Log to logger
    addMessageProcessor(TraceFeatureMessageLogWriter(logger))

    // Write to file
    addMessageProcessor(TraceFeatureMessageFileWriter(
        outputPath,
        { path: Path -> SystemFileSystem.sink(path).buffered() }
    ))

    // Send to remote endpoint
    addMessageProcessor(TraceFeatureMessageRemoteWriter(connectionConfig))

    // Filter specific events
    addMessageProcessor(fileWriter.apply {
        setMessageFilter { message ->
            message is LLMCallStartingEvent || message is LLMCallCompletedEvent
        }
    })
}
```

**Custom Message Processor:**
```kotlin
class CustomTraceProcessor : FeatureMessageProcessor() {
    private var _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> get() = _isOpen.asStateFlow()

    override suspend fun processMessage(message: FeatureMessage) {
        when (message) {
            is NodeExecutionStartingEvent -> {
                // Process node start
            }
            is LLMCallCompletedEvent -> {
                // Process LLM completion
            }
        }
    }

    override suspend fun close() {
        // Cleanup
    }
}

install(Tracing) {
    addMessageProcessor(CustomTraceProcessor())
}
```

---

## Iteration Limits

### maxAgentIterations

- The maximum number of agent tool/LLM cycles is controlled by `AIAgentConfig.maxAgentIterations`.
- In JERVIS, the main GPU agent (KoogWorkflowAgent) reads this setting exclusively from application.yml (no system properties, no env vars), following project guidelines.

**Configuration (application.yml):**
```yaml
jervis:
  koog:
    workflow:
      # Max number of tool/LLM cycles for KoogWorkflowAgent (GPU)
      max-iterations: 500
```

**Notes:**
- The Qualifier agent keeps a small cap (10) by design to keep CPU pre-filtering tight and cheap.
- Increase only the workflow agent limit unless you explicitly need deeper qualifier loops.

---

## Summary

### Key Koog Principles

1. **Type Safety:** Output type = Input type of next node
2. **No External State:** Everything flows through node/edge flow
3. **Typed Tool Results:** Data classes, not String parsing
4. **LLM Parsing Pattern:** String → node → ParseResult → conditional edges
5. **Conditional Branching:** `.onCondition { }` on data class properties
6. **Retry Logic:** Counter in data class + edge conditions
7. **Subgraph Input/Output:** Different types = explicit transformation
8. **Tool Call Routing:** `.onToolCall` vs `.onAssistantMessage`

### Benefits

1. **Production-ready:** Fault-tolerance + tracing
2. **Type-safe:** Compile-time checks prevent runtime errors
3. **Flexible:** Graph-based for complex workflows, functional for prototyping
4. **Observable:** Rich event system for monitoring
5. **Scalable:** Iteration limits and context window management

---
## References

- [Koog Official Docs](https://docs.koog.ai)
- [Koog GitHub](https://github.com/koog-ai/koog)
- [Complex Workflow Agents](https://docs.koog.ai/complex-workflow-agents/)

## Version History

- **2026-01-27**: Initial guide based on Koog 0.6.0 and JERVIS implementation
- **2026-02-04**: Consolidated from individual documentation files