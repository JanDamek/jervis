# Architecture - Complete System Overview

**Status:** Production Documentation (2026-02-05)
**Purpose:** Comprehensive architecture guide for all major components and frameworks

---

## Table of Contents

1. [Framework Overview](#framework-overview)
2. [Koog Agent Framework](#koog-agent-framework)
3. [Kotlin RPC (kRPC) Architecture](#kotlin-rpc-krpc-architecture)
4. [Polling & Indexing Pipeline](#polling--indexing-pipeline)
5. [Knowledge Graph Design](#knowledge-graph-design)
6. [Vision Processing Pipeline](#vision-processing-pipeline)
7. [Smart Model Selector](#smart-model-selector)
8. [Security Architecture](#security-architecture)
9. [Coding Agents](#coding-agents)

---

## Framework Overview

The Jervis system is built on several key architectural patterns:

- **Koog Framework (0.6.0)**: Type-safe DSL for building AI agents with nodes and edges
- **Kotlin RPC (kRPC)**: Type-safe, cross-platform messaging framework for client-server communication
- **3-Stage Polling Pipeline**: Polling → Indexing → Pending Tasks → Qualifier Agent
- **Knowledge Graph (ArangoDB)**: Centralized structured relationships between all entities
- **Vision Processing**: Two-stage vision analysis for document understanding

---

## Koog Agent Framework

### Core Libraries & Components

#### `agents-core-jvm` - Core Agent Framework

- `AIAgent` - Main agent class with functional and graph strategies
- `AIAgentConfig` - Agent configuration (prompt, model, iterations)
- `ToolRegistry` - Tool registration and management
- Strategy DSL: `strategy()`, `node()`, `edge()`
- Execution flow: `nodeStart`, `nodeSendInput`, `nodeExecuteTool`, `nodeSendToolResult`, `nodeFinish`

#### `agents-ext-jvm` - Extension Tools

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

#### `agents-tools-jvm` - Tool System

- `@Tool` - Annotation for tool methods
- `@LLMDescription` - LLM-readable descriptions
- `ToolSet` - Interface for tool collections
- `Tool<I, O>` - Tool interface
- `ToolRegistry` - Registry builder DSL

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

### Functional Strategy (Prototyping)

Lightweight agents without complex graphs:

- **Lambda function** handles user input, LLM calls, and tools
- **Custom control flows** in plain Kotlin
- **History compression** and automatic state management
- **Good for MVP and prototyping**

---

## Kotlin RPC (kRPC) Architecture

### Overview

The Jervis system uses Kotlin RPC (kRPC) for type-safe, cross-platform communication between UI and backend server.

### Communication Contract

- **UI ↔ Server**: ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Polling & Indexing Pipeline

### 3-Stage Pipeline

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────┐      ┌────────────┐
│   Polling   │  →   │   Indexing   │  →   │  Pending Tasks  │  →   │  Qualifier │
│   Handler   │      │  Collection  │      │                 │      │   Agent    │
└─────────────┘      └──────────────┘      └─────────────────┘      └────────────┘
```

### Stage 1: Polling Handler

**Purpose:** Download data from external APIs and store in indexing MongoDB collection.

#### Responsibilities:

1. **Scheduled execution** based on `ConnectionDocument` (e.g., every 5 minutes)
2. **Initial Sync vs Incremental Sync**:
   - **Initial Sync** (`lastSeenUpdatedAt == null`): Downloads ALL data with **pagination**
   - **Incremental Sync**: Downloads only changes since last poll (no pagination)
3. **Deduplication** - checks existence by `issueKey`/`messageId` (3 levels)
4. **Change detection** - saves document as `NEW` if:
   - Document doesn't exist (new ticket/email)
   - Document exists but `updatedAt` is newer (status change, new comment)

### Initial Sync s Pagination

---

## Knowledge Graph Design

### ArangoDB Schema - "Two-Collection" Approach

For each client, Jervis creates **3 ArangoDB objects**:

1. **`c{clientId}_nodes`** - Document Collection
   - Single collection for all node types (Users, Jira tickets, files, commits, Confluence pages...)
   - Heterogenous graph = maximum flexibility for AI agent
   - Each document has **`type`** attribute for entity discrimination

2. **`c{clientId}_edges`** - Edge Collection
   - All edges between nodes
   - **`edgeType`** attribute defines relationship type

3. **`c{clientId}_graph`** - Named Graph
   - ArangoDB Named Graph for optimized traversal queries
   - Definition: `c{clientId}_nodes` → `c{clientId}_edges` → `c{clientId}_nodes`
   - Enables AQL syntax: `FOR v IN 1..3 ANY startNode GRAPH 'c123_graph'`

### Node Structure

```json
{
  "_key": "jira::JERV-123",
  "type": "jira_issue",           // MANDATORY type discriminator
  "ragChunks": ["chunk_uuid_1"],  // Optional - Weaviate chunk IDs
  // ... arbitrary properties specific to entity type
  "summary": "Fix login bug",
  "status": "In Progress"
}
```

### Edge Structure

```json
{
  "_key": "mentions::jira::JERV-123->file::Service.kt",
  "edgeType": "mentions",         // MANDATORY relationship type
  "_from": "c123_nodes/jira::JERV-123",
  "_to": "c123_nodes/file::Service.kt"
}
```

---

## Vision Processing Pipeline

### Problem Statement

**Problem**: Apache Tika is blind - extracts text, but doesn't see **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

**Solution**: Integration of **Qwen2.5-VL** (vision model) into Qualifier Agent as **LLM node**, not as Tool.

### Vision Architecture

#### ❌ What NOT TO DO (Anti-patterns)

```kotlin
// ❌ WRONG - Vision as Tool
@Tool
suspend fun analyzeAttachment(attachmentId: String): String {
    val model = selectVisionModel(...)
    return llmGateway.call(model, image) // LLM call in Tool!
}
```

**Why is it wrong:**
- Tool API is for **actions** (save to DB, create task), not for LLM calls
- We lose type-safety of Koog graph
- Cannot use Koog multimodal (different models per node)
- Complicated testing

#### ✅ What TO DO (Correct approach)

```kotlin
// ✅ CORRECT - Vision as LLM node
val nodeVision by node<QualifierPipelineState, QualifierPipelineState>("Vision Analysis") { state ->
    val model = selectVisionModel(state.attachments)
    val visionResult = llmGateway.call(model, image)
    state.withVision(visionResult)
}
```

### Vision Integration with Koog

- **Vision as LLM node**: Part of Koog strategy graph
- **Type-safe**: `QualifierPipelineState -> QualifierPipelineState`
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

---

## Smart Model Selector

### Overview

SmartModelSelector is a Spring service that dynamically selects optimal Ollama LLM models based on input content length. It prevents context truncation for large documents while avoiding RAM/VRAM waste on small tasks.

### Problem Statement

#### Before SmartModelSelector:
- **Hardcoded models**: All tasks use same model (e.g., `qwen3-coder:30b` with 128k context)
- **Small tasks** (1k tokens): Waste RAM/VRAM allocating 128k context
- **Large tasks** (100k tokens): Get truncated at 128k limit

#### After SmartModelSelector:
- **Dynamic selection**: Automatically chooses optimal tier based on content length
- **Efficient resource usage**: Small tasks use small context (4k-16k)
- **No truncation**: Large tasks get appropriate context (64k-256k)

### Model Naming Convention

All models on Ollama server follow this pattern:
```
qwen3-coder-tool-{SIZE}k:30b
```

### Model Selection Logic

| Content Length | Model | Context | Use Case |
|----------------|-------|---------|----------|
| 0-4,000 tokens | qwen3-coder-tool-4k:30b | 4k | Small tasks, quick queries |
| 4,001-16,000 tokens | qwen3-coder-tool-16k:30b | 16k | Medium tasks, documents |
| 16,001-64,000 tokens | qwen3-coder-tool-64k:30b | 64k | Large documents, codebases |
| 64,001+ tokens | qwen3-coder-tool-256k:30b | 256k | Very large documents |

---

## Security Architecture

### Client Security Headers

#### Overview

Communication between UI (iOS, Android, Desktop) and backend server is protected by validation of mandatory security headers on every request. If client doesn't send correct headers, server rejects request and logs warning.

#### Header Requirements

Two mandatory headers must be sent with every RPC request:

#### 1. X-Jervis-Client Header

- **Type:** Client authentication token
- **Format:** UUID
- **Example:** `X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002`
- **Validation:** Must match server configuration

#### 2. X-Jervis-Platform Header

- **Type:** Platform identifier
- **Allowed values:** {iOS, Android, Desktop}
- **Example:** `X-Jervis-Platform: Desktop`
- **Validation:** Must be in allowed set

#### Security Constants

```kotlin
// SecurityConstants.kt
const val CLIENT_TOKEN = "a7f3c9e2-4b8d-11ef-9a1c-0242ac120002"
const val CLIENT_HEADER = "X-Jervis-Client"
const val PLATFORM_HEADER = "X-Jervis-Platform"
const val PLATFORM_IOS = "iOS"
const val PLATFORM_ANDROID = "Android"
const val PLATFORM_DESKTOP = "Desktop"
```

---

## Multi-Module Design

### Module Structure

- **backend/server**: Spring Boot WebFlux (orchestrator, RAG, scheduling, integrations)
- **backend/service-***: Compute-only services (joern, tika, whisper, atlassian)
- **shared/common-dto**: Data transfer objects
- **shared/common-api**: `@HttpExchange` contracts
- **shared/domain**: Pure domain types
- **shared/ui-common**: Compose Multiplatform UI screens
- **apps/desktop**: Primary desktop application
- **apps/mobile**: iOS/Android port from desktop

### Communication Patterns

- **UI ↔ Server**: ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Benefits of Architecture

1. **Cost efficiency**: Expensive GPU models only when necessary
2. **Scalability**: Parallel CPU qualification, GPU execution on idle
3. **Explainability**: Evidence links for all relationships
4. **Flexibility**: Schema-less graph for new entity types
5. **Performance**: Hybrid search combining semantic + structured
6. **Type safety**: Compile-time checks prevent runtime errors
7. **Fail-fast design**: Errors propagate, no silent failures
8. **Multi-tenancy**: Per-client isolation in all storage layers

---

## Coding Agents

Jervis integrates four autonomous coding agents, each running as a standalone kRPC microservice. All implement the shared `ICodingClient` interface (`execute(CodingRequest): CodingResult`) and communicate with the server over WebSocket/CBOR.

### Agent Overview

| Agent | Service | Port | Purpose | Default Provider |
|-------|---------|------|---------|-----------------|
| **Aider** | `service-aider` | 3100 | Fast, localized changes (1-3 files) | Ollama (qwen3-coder-tool:30b) |
| **OpenHands** | `service-coding-engine` | 3200 | Complex multi-file refactoring | Ollama (qwen3-coder-tool:30b) |
| **Junie** | `service-junie` | 3300 | Premium, ultra-fast (JetBrains) | Anthropic (claude-3-5-sonnet) |
| **Claude** | `service-claude` | 3400 | Agentic coding with strong reasoning | Anthropic (claude-sonnet-4) |

### Decision Matrix (CodingTools.kt)

The `execute()` tool auto-selects the agent based on strategy hints:

- **FAST** -> Aider (small, localized edits)
- **THOROUGH** -> OpenHands (deep multi-file refactoring)
- **REASONING** -> Claude (complex reasoning and planning)
- **PREMIUM** -> Junie (last resort, expensive, fastest)
- **AUTO** -> Heuristic: few files -> Aider, else Claude

### Claude Agent (`service-claude`)

The Claude agent wraps Anthropic's `claude` CLI (`@anthropic-ai/claude-code`) as a kRPC service:

- **Dockerfile**: Eclipse Temurin 21 + Node.js 20 + `npm install -g @anthropic-ai/claude-code`
- **CLI Flags**: `claude --print --dangerously-skip-permissions`
- **Auth** (priority order):
  1. `CLAUDE_CODE_OAUTH_TOKEN` env var – setup token from `claude setup-token` (Max/Pro subscription)
  2. `ANTHROPIC_API_KEY` env var – Console API key (pay-as-you-go)
- **Timeout**: max 45 minutes (5 min per iteration, up to 10 iterations)
- **Verification**: Optional post-execution command (`verifyCommand`)

### Credential Management

Coding agent authentication is managed via:

1. **K8s Secrets**: Primary source (`jervis-secrets` secret mounted as env vars)
2. **Settings UI**: "Coding Agenti" tab in Settings for runtime updates via `ICodingAgentSettingsService` RPC
3. **MongoDB**: `coding_agent_settings` collection stores API keys and setup tokens per agent

Claude supports two auth methods:
- **Setup Token** (recommended for Max/Pro): User runs `claude setup-token` locally, pastes the long-lived token (`sk-ant-oat01-...`) in Settings. Stored in MongoDB, passed to the service as `CLAUDE_CODE_OAUTH_TOKEN` env var at each invocation.
- **API Key**: Console pay-as-you-go key (`ANTHROPIC_API_KEY`).

### Build & Deploy

Each agent has its own build script in `k8s/build_<name>.sh` which calls the generic `build_service.sh` to:

1. Run `./gradlew :backend:service-<name>:clean :backend:service-<name>:build -x test`
2. Build Docker image for `linux/amd64`
3. Push to `registry.damek-soft.eu/jandamek/jervis-<name>:latest`
4. Apply K8s deployment and restart pods