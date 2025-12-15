# Koog Framework Libraries Reference

Version: 0.5.3

This document describes Koog framework libraries used in JERVIS project.

## Core Libraries

### `agents-core-jvm` - Core agent framework
- `AIAgent` - Main agent class with functional and graph strategies
- `AIAgentConfig` - Agent configuration (prompt, model, iterations)
- `ToolRegistry` - Tool registration and management
- Strategy DSL: `strategy()`, `node()`, `edge()`
- Execution flow: `nodeStart`, `nodeSendInput`, `nodeExecuteTool`, `nodeSendToolResult`, `nodeFinish`

### `agents-ext-jvm` - Extension tools
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
- **Other:**
  - `AskUser`, `SayToUser`, `ExitTool`

### `agents-tools-jvm` - Tool system
- `@Tool` - Annotation for tool methods
- `@LLMDescription` - LLM-readable descriptions
- `ToolSet` - Interface for tool collections
- `Tool<I, O>` - Tool interface
- `ToolRegistry` - Registry builder DSL

### `agents-features-memory-jvm` - Agent memory
- `AgentMemory` - Memory management
- `AgentMemoryProvider` - Memory storage interface
- `LocalFileMemoryProvider` - File-based storage
- Memory scopes and subjects

### `agents-mcp-jvm` - MCP protocol support
- `McpTool` - MCP tool wrapper
- `McpToolRegistryProvider` - MCP tool provider

## Prompt Executors

### `prompt-executor-llms-all-jvm` - All executors
- `simpleOllamaAIExecutor(baseUrl)` - Ollama executor
- `simpleAnthropicAIExecutor(apiKey)` - Anthropic executor (baseUrl not supported)
- `simpleOpenAIExecutor(apiToken)` - OpenAI executor (single param)
- `simpleGoogleAIExecutor(apiKey)` - Google executor (single param)

### `prompt-executor-model-jvm` - Executor model
- `PromptExecutor` - Base executor interface
- `LLMParams` - LLM parameters

### `prompt-llm-jvm` - LLM models
- `OllamaModels` - Ollama model definitions
- `LLModel` - Model interface

## Prompt Building

### `prompt-model-jvm` - Prompt DSL
- `Prompt.build(id) { ... }` - Prompt builder DSL
- `system(text)` - System message
- `user(text)` - User message
- `Message` - Message types

### Prompt Configuration & Context Window (CRITICAL)

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

### `prompt-structure-jvm` - Structured output
- `StructuredOutput` - JSON schema generation
- `JsonSchemaGenerator` - Schema generators

## RAG & Embeddings

### `rag-base-jvm` - RAG base
- `DocumentProvider` - Document loading
- `FileSystemProvider` - FS document provider
- `JVMFileSystemProvider` - JVM FS implementation

### `vector-storage-jvm` - Vector storage
- `VectorStorage` - Vector DB interface
- `DocumentEmbedder` - Document embedding
- `EmbeddingBasedDocumentStorage` - Embedded documents

### `embeddings-llm-jvm` - LLM embeddings
- `LLMEmbedder` - Embedding interface
- `OllamaEmbeddingModels` - Ollama embedding models

## Usage in JERVIS

### Agent Creation
```kotlin
val promptExecutor = simpleOllamaAIExecutor(baseUrl = "http://localhost:11434")
val strategy = strategy("name") { /* DSL */ }
val config = AIAgentConfig(prompt = Prompt.build("id") { system("...") }, model = "model-name", maxAgentIterations = 8)
val toolRegistry = ToolRegistry { tools(ListDirectoryTool(JVMFileSystemProvider.ReadOnly)) }
val agent = AIAgent(promptExecutor, toolRegistry, strategy, config)
```

### Tool Registration
```kotlin
ToolRegistry {
    // Single tool
    tools(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))

    // Multiple tools as list
    tools(listOf(
        ReadFileTool(JVMFileSystemProvider.ReadOnly),
        EditFileTool(JVMFileSystemProvider.ReadWrite)
    ))

    // Custom ToolSet
    tools(MyCustomToolSet())
}
```

### Provider Configuration
- Use `simpleOllamaAIExecutor(baseUrl)` for Ollama
- Use `simpleAnthropicAIExecutor(apiKey, baseUrl)` for Anthropic
- Use `simpleOpenAIExecutor(apiKey, baseUrl)` for OpenAI
- Use `simpleGoogleAIExecutor(apiKey, baseUrl)` for Google

## Agent Strategy Types

### Graph-Based Strategy (Production)
Complex workflow agents with strategy graphs for production use:
- Full persistence with controllable rollbacks for fault-tolerance
- Advanced OpenTelemetry tracing with nested graph events
- Strategy graph DSL: `strategy<Input, Output>("name") { ... }`
- Nodes: `val nodeName by node<I, O>("Name") { input -> output }`
- Subgraphs: `val subgraphName by subgraph<I, O>(name = "Name") { ... }`
- Edges: `edge(nodeA forwardTo nodeB)`
- Conditional routing: `.onCondition { state -> boolean }`
- Event routing: `.onToolCall { true }`, `.onAssistantMessage { true }`

Example:
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
- Lambda function handles user input, LLM calls, and tools
- Custom control flows in plain Kotlin
- History compression and automatic state management
- Good for MVP and prototyping

Example:
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

Multiple LLM calls:
```kotlin
functionalStrategy { input ->
    val draft = requestLLM("Draft: $input").asAssistantMessage().content
    val improved = requestLLM("Improve: $draft").asAssistantMessage().content
    requestLLM("Format: $improved").asAssistantMessage().content
}
```

With tools:
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

## Agent Events and Event Handler Feature

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

## References

- Koog version: 0.5.3
- Implementation: `com.jervis.koog.KoogPromptExecutorFactory`
- Agents: `KoogWorkflowAgent`, `KoogQualifierAgent`
- Custom tools: `com.jervis.koog.tools.*`

## Iteration Limits (maxAgentIterations)

- The maximum number of agent tool/LLM cycles is controlled by `AIAgentConfig.maxAgentIterations`.
- In JERVIS, the main GPU agent (KoogWorkflowAgent) reads this setting exclusively from application.yml (no system properties, no env vars), following project guidelines.

Configuration (application.yml):

```yaml
jervis:
  koog:
    workflow:
      # Max number of tool/LLM cycles for KoogWorkflowAgent (GPU)
      max-iterations: 500
```

Notes:
- The Qualifier agent keeps a small cap (10) by design to keep CPU pre‑filtering tight and cheap.
- Increase only the workflow agent limit unless you explicitly need deeper qualifier loops.
