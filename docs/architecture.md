# Architecture - Complete System Overview

**Last updated:** 2026-02-02  
**Status:** Complete System Reference  
**Purpose:** Comprehensive architecture guide for all major components and frameworks

---

## Table of Contents

1. [Framework Overview](#framework-overview)
2. [Koog Agent Framework](#koog-agent-framework)
3. [Kotlin RPC (kRPC) Architecture](#kotlin-rpc-krpc-architecture)
4. [Polling & Indexing Pipeline](#polling--indexing-pipeline)
5. [Knowledge Graph Design](#knowledge-graph-design)
6. [Vision Processing Pipeline](#vision-processing-pipeline)

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

### Core Concepts

Koog is an open-source Kotlin framework for building AI agents with a type-safe DSL. Agents are built using **nodes** and **edges** that define the processing flow.

### Agent Structure

```kotlin
val agentStrategy = strategy<String, OutputType>("Agent Name") {
    val nodeLLMRequest by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo nodeLLMRequest)
    edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })
    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge((nodeSendToolResult forwardTo nodeLLMRequest).onToolCall { true })
}
```

### Tool Registration and Best Practices

Tools must be properly annotated and NEVER mentioned by name in prompts:

```kotlin
@Tool
@LLMDescription(
    "Analyze and interpret user request to understand the goal. " +
    "Returns: NormalizedRequest with type, entities and outcome."
)
suspend fun interpretRequest(): String {
    // Implementation
}
```

**Critical Rules:**
- ❌ NEVER mention tool names in prompts (e.g., "call interpretRequest()")
- ❌ NEVER say "Always call X after Y"
- ✅ Use generic descriptions: "Analyze the request type"
- ✅ Let LLM discover tools via @LLMDescription

### Structured Output

Use `nodeLLMRequestStructured` for typed responses:

```kotlin
val nodeDecision by nodeLLMRequestStructured<DecisionType>(
    name = "decide-next-action",
    examples = listOf(
        DecisionType(type = "FINAL", answer = "Task completed")
    )
)
```

### Event Handling

Add event listeners for monitoring and UI feedback:

```kotlin
val agentConfig = AIAgentConfig(
    prompt = ...,
    model = ...,
    features = features {
        install(EventHandler) {
            onToolCall { ctx: ToolCallContext ->
                logger.info { "TOOL_CALL | tool=${ctx.tool?.name}" }
                // Send progress to UI
            }

            onToolResult { ctx: ToolResultContext ->
                logger.info { "TOOL_RESULT | tool=${ctx.tool?.name} | success=${ctx.result != null}" }
            }
        }
    }
)
```

### Timeout Management

**Critical:** LLM calls must have NO timeout as they can take arbitrarily long:

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = null // No timeout for LLM calls
    connectTimeoutMillis = connectTimeout.toLong()
    socketTimeoutMillis = null // No socket timeout for streaming
}
```

**Why?**
- Ollama models on ports 11434/11435 may take 15+ minutes for complex reasoning
- Timeouts cause `HttpRequestTimeoutException` and break agent workflow
- Streaming responses need unlimited socket timeout

### Prompt Engineering Best Practices

#### System Prompt Structure

```kotlin
system("""
    You are [ROLE] - [PURPOSE].

    MANDATORY WORKFLOW:
    1. [STEP 1]: Description
    2. [STEP 2]: Description
    3. [STEP 3]: Description

    CRITICAL STOP RULES - NEVER VIOLATE:
    ❌ NEVER [bad behavior]
    ❌ NEVER [bad behavior]
    ✅ ALWAYS [good behavior]
    ✅ ALWAYS [good behavior]

    [Additional context-specific rules]
""".trimIndent())
```

**Key Points:**
- Keep system prompts under 50 lines
- Use clear section headers (WORKFLOW, STOP RULES, etc.)
- Avoid concrete tool names
- Use visual markers (❌ ✅) for emphasis
- Be specific about what NOT to do

### Agent Patterns

#### 1. Orchestrator Pattern

Coordinates multiple sub-agents:

```kotlin
val toolRegistry = ToolRegistry {
    tools(InternalAgentTools(...))  // Wraps sub-agents as tools
    tools(KnowledgeStorageTools(...))
    tools(CodingTools(...))
}

@Tool
suspend fun interpretRequest(): String {
    val request = interpreterAgent.run(task)
    return json.encodeToString(request)
}
```

#### 2. Planner Pattern

Uses GOAP or sequential planning:

```kotlin
goap<StateType>(stateType) {
    action(
        name = "ActionName",
        precondition = { state -> !state.done },
        belief = { state -> state.copy(done = true) },
        cost = { 1.0 }
    ) { ctx, state ->
        ctx.requestLLM("prompt")
        // Return new state
    }
}
```

#### 3. Specialist Pattern

Single-purpose agents with focused prompts:

```kotlin
strategy<String, NormalizedRequest>("Interpret Request") {
    val nodeInterpret by nodeLLMRequestStructured<NormalizedRequest>(
        examples = examples
    ).transform { it.getOrThrow().data }

    edge(nodeStart forwardTo nodeInterpret)
    edge(nodeInterpret forwardTo nodeFinish)
}
```

---

## Kotlin RPC (kRPC) Architecture

### What is kRPC?

Kotlin RPC is a type-safe, compile-time safe messaging framework created by JetBrains for Kotlin and Kotlin Multiplatform (KMP). It provides:

- **Type-safe RPC calls**: Automatic code generation for client/server
- **Cross-platform**: Runs on JVM, iOS, Android, JavaScript
- **Multiple transports**: HTTP, WebSocket, Custom
- **Serialization support**: CBOR, JSON, Custom

### System Architecture in Jervis

```
┌─────────────────────────────────────────────────────────────┐
│                     Jervis Project                           │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  UI Layer (iOS/Android/Desktop)                             │
│  ├── NetworkModule                                          │
│  └── Sends: X-Jervis-Client, X-Jervis-Platform headers     │
│           ↓ (HTTPS/WebSocket)                              │
│  ┌─────────────────────────────────────────────────────────┤
│  │ Backend Server (Ktor)                                    │
│  │                                                           │
│  │  1. Ktor Application Layer                              │
│  │     ├── Security Plugin (CallReceived hook)             │
│  │     │   └── Validates headers                           │
│  │     └── WebSockets Feature                              │
│  │                                                           │
│  │  2. kRPC Layer (kotlinx-rpc)                           │
│  │     ├── RPC Handler (/rpc endpoint)                     │
│  │     ├── WebSocket Transport                             │
│  │     └── CBOR Serialization                              │
│  │                                                           │
│  │  3. Service Layer                                        │
│  │     ├── IClientService                                  │
│  │     ├── IProjectService                                 │
│  │     └── ... (11+ additional services)                   │
│  │                                                           │
│  └─────────────────────────────────────────────────────────┤
│
└─────────────────────────────────────────────────────────────┘
```

### Request Flow

#### 1. Client Initiates RPC Call

```
Client (iOS/Android/Desktop)
    ↓
Sends: GET /rpc (WebSocket Upgrade)
Headers:
  - X-Jervis-Client: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002
  - X-Jervis-Platform: iOS
  - ... (standard WebSocket headers)
    ↓
```

#### 2. Server - Ktor Application Layer

```
Ktor Server receives request
    ↓
CallReceived Hook (Security Plugin)
    ├── Extracts headers
    ├── Validates X-Jervis-Client token
    ├── Validates X-Jervis-Platform
    └── If invalid:
        ├── Sets response status (401/400)
        └── Logs WARNING
    ↓ (if valid)
WebSockets.install() processes upgrade
    ↓
```

#### 3. kRPC Layer

```
RPC Handler processes WebSocket connection
    ↓
Identifies service & method
    ↓
Routes to appropriate RPC implementation
    (e.g., ClientRpcImpl.someMethod())
    ↓
Service processes request
    ↓
Response serialized (CBOR)
    ↓
Sent back to client
```

### Architecture Design Rationale

#### 1. Security at Application Boundary

- **Early validation**: Headers are checked at the Ktor plugin level, **before** kRPC handler
- **Consistent**: All requests pass the same validation regardless of content
- **Flexible**: Easily extended for additional security checks

#### 2. Type Safety Throughout

- Generated client code ensures compile-time type checking
- No string-based method invocation
- Automatic serialization/deserialization

#### 3. Performance

- WebSocket for persistent, low-latency connection
- CBOR serialization (more efficient than JSON)
- Minimal overhead

---

## Polling & Indexing Pipeline

### Overview

Jervis uses a **3-stage pipeline** for processing data from external systems (Jira, Confluence, Email):

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────┐      ┌────────────┐
│   Polling   │  →   │   Indexing   │  →   │  Pending Tasks  │  →   │  Qualifier │
│   Handler   │      │  Collection  │      │                 │      │   Agent    │
└─────────────┘      └──────────────┘      └─────────────────┘      └────────────┘
```

### Stage 1: Polling Handler

**Purpose:** Download data from external APIs and store in MongoDB indexing collection.

#### Responsibilities

1. **Regular execution** according to `ConnectionDocument` (e.g., every 5 minutes)
2. **Initial Sync vs Incremental Sync**:
   - **Initial Sync** (`lastSeenUpdatedAt == null`): Downloads ALL data with **pagination**
   - **Incremental Sync**: Downloads only changes since last poll (without pagination)
3. **Deduplication** - checks existence by `issueKey`/`messageId` (3 levels)
4. **Change detection** - saves document as `NEW` if:
   - Document doesn't exist (new ticket/email)
   - Document exists but `updatedAt` is newer (status change, new comment)

#### Initial Sync with Pagination

**Important**: First run downloads ALL data, not just 1000 items!

##### Jira Initial Sync

```kotlin
val jql = "status NOT IN (Closed, Done, Resolved)"  // Only OPEN issues
val allIssues = fetchAllIssuesWithPagination(
    query = jql,
    batchSize = 100  // 100 issues per batch
)
// Fetches ALL open issues (not Done/Closed), max 10,000
```

##### Confluence Initial Sync

```kotlin
val cql = if (spaceKey != null) "space = \"$spaceKey\"" else null
val allPages = fetchAllPagesWithPagination(
    spaceKey = spaceKey,
    batchSize = 100  // 100 pages per batch
)
// Fetches ALL pages, max 10,000
```

#### Incremental Sync (Regular Polling)

```kotlin
val jql = "updated >= \"2025-01-01 12:00\""  // Only changes
val changes = fetchFullIssues(
    query = jql,
    maxResults = 1000  // Sufficient, not all data
)
```

#### Deduplication (4 Levels)

1. **Pagination-level**: Filter duplicates **during collection** from API
2. **Repository-level (first check)**: `findExisting()` before processing
3. **Repository-level (double-check)**: Race condition protection before `save()`
4. **MongoDB unique index**: `(connectionDocumentId, issueKey)` as final fail-safe

#### DO's and DON'Ts

**❌ DON'T:**
- Download document already in indexing collection with no changes
- Create PendingTask directly (only Indexer does this)
- Call RAG/Weaviate (only Qualifier Agent does this)

**✅ DO:**
- Check `jiraUpdatedAt` vs `existing.jiraUpdatedAt`
- Save as `state = "NEW"` only on change
- Save FULL data (description, comments, attachments)
- Update `connectionDocument.pollingStates` after successful poll

### Stage 2: Indexing Collection (MongoDB)

**Purpose:** Temporary storage for documents awaiting processing.

**Schema**: Polymorphic using sealed classes with states: `NEW`, `INDEXED`, `FAILED`

### Stage 3: Pending Tasks

**Purpose:** Queue for Qualifier Agent processing.

### Stage 4: Qualifier Agent

**Purpose:** Final content analysis and routing decision (DONE or LIFT_UP for GPU processing).

---

## Knowledge Graph Design

The Knowledge Graph (ArangoDB) serves as the central repository for structured relationships between all entities in the system. Each client has an isolated graph, ensuring multi-tenancy.

### Core Entities

- **Projects**: Organizational units for document collection
- **Documents**: Indexed content (issues, wiki pages, emails, logs)
- **Relationships**: Connections between documents (references, dependencies, parent-child)
- **Metadata**: Extracted information and analysis results

### Multi-Tenancy

- Complete isolation between clients
- Separate graph instance per client (or virtual partitioning)
- No cross-client queries possible

---

## Vision Processing Pipeline

### Overview

**Problem**: Apache Tika is blind - it extracts text but doesn't understand the **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

### Two-Stage Vision Architecture

**Stage 1**: General vision analysis
- Detect document type
- Extract visual elements (graphs, tables, code snippets, handwriting)
- Generate high-level summary

**Stage 2**: Type-specific vision analysis
- Process recognized document types with specialized models
- Extract structured data from visual elements
- Generate type-specific metadata

### Key Principles

- ✅ **Vision context preservation**: Never lost between processing stages
- ✅ **Fail-fast approach**: Errors collected, not silently ignored
- ✅ **Type-safe processing**: Deterministic flow through all phases

---

## Summary

The Jervis architecture combines multiple patterns:
- **Koog** for AI agent orchestration
- **kRPC** for secure, type-safe client-server communication
- **Polling pipeline** for data ingestion
- **Knowledge graph** for relationship management
- **Vision processing** for visual content understanding

Together, these create a robust, scalable system for document analysis and knowledge extraction from multiple external sources.
