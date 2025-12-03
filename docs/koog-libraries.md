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

## References

- Koog version: 0.5.3
- Implementation: `com.jervis.koog.KoogPromptExecutorFactory`
- Agents: `KoogWorkflowAgent`, `KoogQualifierAgent`
- Custom tools: `com.jervis.koog.tools.*`
