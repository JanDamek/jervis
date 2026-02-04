# Jervis – System Architecture & Design (2026)

**Status:** Production Documentation (2026-02-04)
**Purpose:** System architecture, design patterns, and knowledge base implementation

---

## Table of Contents

1. [Core Architecture Overview](#core-architecture-overview)
2. [Knowledge Graph Design](#knowledge-graph-design)
3. [Graph-Based Routing Architecture](#graph-based-routing-architecture)
4. [Background Engine & Task Processing](#background-engine--task-processing)
5. [Knowledge Base Implementation](#knowledge-base-implementation)
6. [Continuous Indexers](#continuous-indexers)
7. [RAG Integration](#rag-integration)
8. [Module Structure & Communication](#module-structure--communication)

---

## Core Architecture Overview

### Multi-Module Design

Jervis is built as a multi-module Spring Boot application with clear separation of concerns:

- **backend/server**: Main orchestrator with WebFlux, RAG, scheduling, and integrations
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

**Key Design Decisions:**
- **`type` attribute is indexed** (Persistent Index) for fast filtering
- **No schema changes** needed for new entity types
- **Maximum flexibility** for AI agent to create new entity types

### Edge Structure

```json
{
  "_key": "mentions::jira::JERV-123->file::Service.kt",
  "edgeType": "mentions",         // MANDATORY relationship type
  "_from": "c123_nodes/jira::JERV-123",
  "_to": "c123_nodes/file::Service.kt"
}
```

### Node Types (Examples)

#### CODE - Code Structure
```kotlin
// File
nodeKey: "file::src/main/kotlin/com/jervis/Service.kt"
type: "file"
props: { path: String, extension: String, language: String, linesOfCode: Int, lastModified: Instant }

// Class
nodeKey: "class::com.jervis.service.UserService"
type: "class"
props: { name: String, qualifiedName: String, isInterface: Boolean, visibility: String, filePath: String }

// Method
nodeKey: "method::com.jervis.service.UserService.createUser"
type: "method"
props: { name: String, signature: String, returnType: String, isConstructor: Boolean, visibility: String }
```

#### VCS - Version Control
```kotlin
// Git Commit
nodeKey: "commit::{commitHash}"
type: "commit"
props: { hash: String, message: String, authorName: String, timestamp: Instant, branchName: String? }

// Git Branch
nodeKey: "branch::{branchName}"
type: "branch"
props: { name: String, isDefault: Boolean, lastCommitHash: String, createdAt: Instant? }
```

#### ISSUE TRACKING - Jira, GitHub Issues
```kotlin
// Jira Ticket
nodeKey: "jira::{projectKey}-{issueNumber}"  // e.g., "jira::JERV-123"
type: "jira_issue"
props: { key: String, summary: String, description: String?, issueType: String, status: String, priority: String }

// Jira Comment
nodeKey: "jira_comment::{issueKey}-{commentId}"
type: "jira_comment"
props: { author: String, body: String, createdAt: Instant, updatedAt: Instant? }
```

#### DOCUMENTATION - Confluence, Wiki
```kotlin
// Confluence Page
nodeKey: "confluence::{spaceKey}::{pageId}"
type: "confluence_page"
props: { title: String, spaceKey: String, version: Int, createdAt: Instant, updatedAt: Instant, authorName: String }
```

#### COMMUNICATION - Email, Slack, Teams
```kotlin
// Email
nodeKey: "email::{messageId}"
type: "email"
props: { subject: String, from: String, to: List<String>, sentAt: Instant, receivedAt: Instant, hasAttachments: Boolean }
```

### Edge Types (Examples)

#### CODE Relationships
```kotlin
// Structure relationships
file --[contains]--> class
class --[contains]--> method
class --[extends]--> class
method --[calls]--> method
```

#### VCS Relationships
```kotlin
// Git structure
commit --[modifies]--> file
commit --[creates]--> file
commit --[parent]--> commit
branch --[contains]--> commit
```

#### ISSUE TRACKING Relationships
```kotlin
// Jira relationships
jira_issue --[blocks]--> jira_issue
jira_issue --[assigned_to]--> user
jira_issue --[mentions_file]--> file
commit --[fixes]--> jira_issue
```

#### DOCUMENTATION Relationships
```kotlin
// Confluence relationships
confluence_page --[documents]--> class
confluence_page --[references]--> jira_issue
```

### RAG Integration

#### Bidirectional Linking

Each node/edge has:
```kotlin
data class GraphNode(
    val key: String,
    val type: String,
    val props: Map<String, Any?>,
    val ragChunks: List<String> = emptyList(),  // UUIDs from Weaviate
)
```

**When creating/updating nodes:**
1. Extract text content from `props`
2. Split into chunks (max 512 tokens)
3. Store chunks in Weaviate with metadata:
   ```json
   {
     "text": "chunk content",
     "metadata": {
       "source": "graph",
       "graphNodeKey": "file::src/main/Service.kt",
       "type": "file",
       "clientId": "...",
       "timestamp": "..."
     }
   }
   ```
4. Store chunk UUIDs in `ragChunks`

**When searching semantically:**
1. RAG finds relevant chunks
2. Extract `graphNodeKey` from chunk metadata
3. Load full node from graph
4. Return structured data + context

---

## Graph-Based Routing Architecture

### Problem Solved

**Original architecture:** Auto-indexed everything directly into RAG without structuring, causing context overflow for large documents and inefficient use of expensive GPU models.

**Solution:** Two-stage architecture with CPU-based qualification (structuring) and GPU-based execution (analysis/actions).

```
┌─────────────────┐
│ CentralPoller   │ downloads data from API → MongoDB (state=NEW)
│ (interval-based)│
└─────────────────┘
        ↓
┌─────────────────┐
│ContinuousIndexer│ reads NEW documents (non-stop loop) →
│ (non-stop loop) │ creates task → state=INDEXED
└─────────────────┘ (INDEXED = "content passed to Jervis", not "already in RAG"!)
        ↓
┌─────────────────────────────────────────────────┐
│ BackgroundEngine - Qualification Loop (CPU)     │
│ • Runs continuously (30s interval)              │
│ • Processes tasks               │
└─────────────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────────────┐
│ KoogQualifierAgent (CPU - OLLAMA_QUALIFIER)     │
│ • SequentialIndexingTool (chunking: 4000/200)  │
│ • GraphRagLinkerTool (Graph ↔ RAG links)       │
│ • TaskRoutingTool (DONE vs READY_FOR_GPU)      │
│ • TaskMemory creation (context summary)        │
└─────────────────────────────────────────────────┘
        ↓
    ┌───┴───┐
    ↓       ↓
┌────────┐ ┌──────────────────────────────────────┐
│  DONE  │ │ READY_FOR_GPU (complex analysis)     │
└────────┘ └──────────────────────────────────────┘
                    ↓
        ┌─────────────────────────────────────────┐
        │ BackgroundEngine - Execution Loop (GPU) │
        │ • Runs ONLY when idle (no user requests)│
        │ • Preemption: interrupted by user       │
        └─────────────────────────────────────────┘
                    ↓
        ┌─────────────────────────────────────────┐
        │ KoogWorkflowAgent (GPU - OLLAMA)        │
        │ • TaskMemoryTool (loads context)        │
        │ • Focus on analysis/actions             │
        │ • No redundant structuring work         │
        └─────────────────────────────────────────┘
```

### Key Components

#### 1. Enums (PendingTaskTypeEnum, PendingTaskStateEnum)

- ✅ `QUALIFICATION_IN_PROGRESS` - Qualifier processing task
- ✅ `READY_FOR_GPU` - Qualification complete, waiting for GPU execution

#### 2. Continuous Indexers (ETL: MongoDB → PendingTask)

- **Non-stop polling** on NEW documents in MongoDB (30s delay when empty)
- **No API calls** - only read from MongoDB and create PendingTask
- **States:** NEW (from API) → INDEXING (processing) → INDEXED (task created)
- **INDEXED = "content passed to Jervis as pending task", NOT "already in RAG/Graph"!**

**Indexers:**
- ✅ `EmailContinuousIndexer` - creates DATA_PROCESSING task from emails + processes links
- ✅ `JiraContinuousIndexer` - creates DATA_PROCESSING task from Jira issues
- ✅ `ConfluenceContinuousIndexer` - creates DATA_PROCESSING task from Confluence pages
- ✅ `GitContinuousIndexer` - creates DATA_PROCESSING task from Git commits

#### 2.1 Link Handling Flow - SEPARATE PENDING TASKS

**CRITICAL: Links are NEVER downloaded in continuous indexer!**

**Architecture:** Document → Link → Link → Link (each as separate pending task)

**1. EmailContinuousIndexer (same for Jira/Confluence/Git):**
- Processes email
- Extracts links from body (using LinkExtractor)
- Creates **DATA_PROCESSING task FOR EMAIL** (without downloading links!)
- In email task: "This email contains 3 links: url1, url2, url3"
- For each found link creates **SEPARATE LINK_PROCESSING task**:
  * Link URL
  * Source context (emailId, subject, sender for Graph edge Email→Link)
  * Context around link (text before/after link)

**2. KoogQualifierAgent processes EMAIL task (DATA_PROCESSING):**
- Indexes email content into RAG/Graph
- Creates Email vertex
- Creates Person vertices (from, to, cc)
- Creates Graph edges: Email→Person (sender), Person→Email (recipient)
- Notes in metadata: "3 links will be processed separately"
- Routing: DONE (email indexed, links waiting in queue)

**3. KoogQualifierAgent processes LINK task (LINK_PROCESSING) - SEPARATELY:**
- Reads link info (URL, source email/jira/confluence, context)
- Qualifies safety (LinkSafetyQualifier):
  * Already indexed? → DONE (skip)
  * Unsafe pattern match? → DONE (blocked)
  * Whitelist domain? → SAFE
  * Blacklist domain/pattern? → UNSAFE
  * Otherwise → UNCERTAIN
- For SAFE: downloads content (document_from_web), indexes into RAG/Graph
- For UNSAFE: creates pattern (manage_link_safety), DONE
- For UNCERTAIN: creates pattern OR downloads (based on context analysis)
- Creates Link vertex + Graph edge: Link→Source (found_in Email/Jira/Confluence)
- **RECURSION STOP:** Link does NOT extract its own links - only mentions them in context
- Routing: DONE (link processed)

**Links can be found in:**
- Email body (EmailContinuousIndexer)
- Jira issue description/comments (JiraContinuousIndexer)
- Confluence page content (ConfluenceContinuousIndexer)
- Git commit message (GitContinuousIndexer)

#### 3. KoogQualifierAgent - New Tools (2025-12-07)

```kotlin
// RagTools.storeChunk - ATOMIC chunk storage (PREFERRED)
@Tool
fun storeChunk(
    documentId: String,
    content: String,       // Agent-extracted entity context
    nodeKey: String,       // Graph node key
    entityTypes: List<String> = emptyList(),
    graphRefs: List<String> = emptyList(),
    knowledgeType: String = "DOCUMENT"
): String
// Returns chunkId for graph linking
// Agent extracts context, service only embeds

// RagTools.searchHybrid - hybrid search for duplicate detection
@Tool
fun searchHybrid(
    query: String,
    alpha: Float = 0.5f,  // 0.0=BM25, 0.5=hybrid, 1.0=vector
    maxResults: Int = 10,
    knowledgeTypes: List<String>? = null
): String

// SequentialIndexingTool - LEGACY (only for large documents)
@Tool
fun indexDocument(
    documentId: String,
    content: String,
    title: String,
    location: String,
    knowledgeType: String = "DOCUMENT",
    relatedDocs: List<String> = emptyList()
): String
// Uses automatic chunking (4000 chars, 200 overlap)
// Only for large documents where precise extraction isn't needed

// GraphRagLinkerTool - bi-directional linking
@Tool
fun createNodeWithRagLinks(
    nodeKey: String,
    nodeType: String,
    properties: String,
    ragChunkIds: String = ""
): String

@Tool
fun createRelationship(
    fromKey: String,
    toKey: String,
    edgeType: String,
    properties: String = ""
): String

// TaskRoutingTool - routing decision
@Tool
fun routeTask(
    routing: String, // "DONE" or "READY_FOR_GPU"
    reason: String,
    contextSummary: String = "",
    graphNodeKeys: String = "",  // Comma-separated
    ragDocIds: String = ""       // Comma-separated
): String
```

#### Chunking Strategy

**PREFERRED: Agent-driven atomic chunking (RagTools.storeChunk)**

- Agent identifies main entity in text (Confluence page, Email, Jira issue)
- Agent extracts ONLY entity-defining text snippet (title, summary, key fields)
- Agent constructs nodeKey according to schema pattern (e.g., `confluence::<pageId>`)
- Agent calls `storeChunk()` with extracted context
- Service only embeds and stores into Weaviate with BM25 indexing
- **Benefits:** Precise control over chunks, hybrid search, no redundancy

**LEGACY: Automatic chunking (SequentialIndexingTool.indexDocument)**

- Only for large documents (≥40000 chars) where precise extraction isn't needed
- Chunk size: 4000 characters, Overlap: 200 characters (context continuity)
- Service automatically splits document and embeds all chunks
- Use: Exceptionally in KoogQualifierAgent for large plain-text documents

#### 4. TaskMemory - Context Passing

```kotlin
data class TaskMemoryDocument(
    val correlationId: String,           // 1:1 with PendingTask
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val contextSummary: String,          // Brief overview for GPU
    val graphNodeKeys: List<String>,     // Graph references
    val ragDocumentIds: List<String>,    // RAG chunk IDs
    val structuredData: Map<String, String>, // Metadata
    val routingDecision: String,         // DONE / READY_FOR_GPU
    val routingReason: String,
    val sourceType: String?,             // EMAIL, JIRA, GIT_COMMIT
    val sourceId: String?
)
```

**Repository:** `TaskMemoryRepository : CoroutineCrudRepository<TaskMemoryDocument, ObjectId>`

**Service API:**
```kotlin
suspend fun saveTaskMemory(...): TaskMemoryDocument
suspend fun loadTaskMemory(correlationId: String): TaskMemoryDocument?
suspend fun deleteTaskMemory(correlationId: String)
```

#### 5. KoogWorkflowAgent - TaskMemoryTool

```kotlin
// Loads context from Qualifier
@Tool
fun loadTaskContext(): String
```

**Usage in workflow:**
1. Agent calls `loadTaskContext()` at start
2. Gets: context summary, Graph node keys, RAG document IDs
3. Uses Graph/RAG tools with provided keys/IDs for full content
4. Focus on analysis and actions, not structuring

#### 6. BackgroundEngine - Preemption

**Mechanism:**
- `LlmLoadMonitor` tracks active user requests
- User request start → `registerRequestStart()` → `interruptNow()`
- `interruptNow()` cancels current background task (Job.cancel())
- Background tasks continue only after idle threshold (30s)

**Two loops:**
- **Qualification loop (CPU):** runs continuously, 30s interval
- **Execution loop (GPU):** runs ONLY when idle (no user requests)

**Preemption guarantees:** User requests ALWAYS have priority over background tasks

#### Routing Criteria (TaskRoutingTool)

**DONE (simple structuring):**
- Document indexed and structured (Graph + RAG)
- No action items or decisions
- Simple informational content
- Routine updates (status change, minor commit)

**READY_FOR_GPU (complex analysis):**
- Requires user action (reply to email, update Jira, review code)
- Complex decision making
- Analysis or investigation
- Code changes or architectural decisions
- Coordination of multiple entities
- Task mentions current user or requires their expertise

**Context Summary (for READY_FOR_GPU):**
- Brief overview of structured data
- Key findings and action items
- Questions requiring answer
- Graph node keys for quick reference
- RAG document IDs for full content retrieval

### Benefits of Architecture

1. **Cost efficiency:** Expensive GPU models only when necessary
2. **No context overflow:** Chunking solves large documents
3. **Bi-directional navigation:** Graph (structured) ↔ RAG (semantic)
4. **Efficient context passing:** TaskMemory eliminates redundant work
5. **User priority:** Preemption ensures immediate response
6. **Scalability:** CPU qualification can run in parallel on multiple tasks

---

## Background Engine & Task Processing

### Qualification Loop (CPU)

- **Interval:** 30 seconds
- **Process:** Reads NEW documents from MongoDB, creates PendingTasks
- **Agents:** KoogQualifierAgent with CPU model (OLLAMA_QUALIFIER)
- **Max iterations:** 10 (for chunking loops)

### Execution Loop (GPU)

- **Trigger:** Runs ONLY when idle (no user requests for 30s)
- **Agent:** KoogWorkflowAgent with GPU model (OLLAMA_PRIMARY)
- **Max iterations:** 500 (configurable via application.yml)
- **Preemption:** Immediately interrupted by user requests

### Task States Flow

```
NEW (from API) → INDEXING (processing) → INDEXED (task created)
    ↓
QUALIFICATION_IN_PROGRESS (CPU agent) → DONE or READY_FOR_GPU
    ↓
READY_FOR_GPU → EXECUTION (GPU agent) → COMPLETED
```

---

## Knowledge Base Implementation

### Architecture Overview

**Knowledge Base is the most critical component** of Jervis. Agent cannot function without quality structured data and relationships.

#### Dual Storage Model

```
┌─────────────────────────────────────────────────────────┐
│                    KNOWLEDGE BASE                        │
├─────────────────────────┬───────────────────────────────┤
│        RAG Store        │        Graph Store            │
│      (Weaviate)         │       (ArangoDB)              │
├─────────────────────────┼───────────────────────────────┤
│ • Vector embeddings     │ • Vertices (entities)         │
│ • Semantic search       │ • Edges (relationships)       │
│ • Chunk storage         │ • Structured navigation      │
│ • Metadata              │ • Traversal queries          │
└─────────────────────────┴───────────────────────────────┘
          ↓                           ↓
   ┌───────────────────────────────────────┐
   │   Bidirectional linking:              │
   │   - Chunks → Graph nodes (graphRefs)  │
   │   - Graph nodes → Chunks (ragChunks)  │
   │   - Edges → Chunks (evidenceChunkIds) │
   └───────────────────────────────────────┘
```

### Flow: Ingest → Storage

```kotlin
IngestRequest
   ↓
1. Chunking (simple paragraph split)
   ↓
2. Extraction
   - extractNodes()        // Pattern: "type:id"
   - extractRelationships() // Formats: "from|edge|to", "from->edge->to", "from -[edge]-> to"
   ↓
3. Normalization
   - normalizeGraphRefs()   // Canonical form
   - resolveCanonicalGraphRef() // Alias resolution via MongoDB registry
   ↓
4. RAG Storage (Weaviate)
   - Embedding via EmbeddingGateway
   - Metadata: sourceUrn, clientId, kind, graphRefs, graphAreas
   ↓
5. Graph Storage (ArangoDB)
   - buildGraphPayload()    // Parse relationships, expand short-hand refs
   - persistGraph()         // Upsert nodes + edges with evidence
   ↓
IngestResult (success, summary, ingestedNodes[])
```

### Relationship Extraction

#### Supported Formats

**1. Pipe format (recommended)**
```
from|edgeType|to
```
Example: `jira:TASK-123|MENTIONS|user:john`

**2. Arrow format**
```
from->edgeType->to
```
Example: `file:Service.kt->MODIFIED_BY->commit:abc123`

**3. Bracket format (ArangoDB-like)**
```
from -[edgeType]-> to
```
Example: `class:UserService -[CALLS]-> method:authenticate`

**4. Metadata block (embedded in content)**
```markdown
relationships: [
  "jira:TASK-123|MENTIONS|user:john",
  "jira:TASK-123|AFFECTS|file:Service.kt",
  "commit:abc123|FIXES|jira:TASK-123"
]
```

#### Short-hand Expansion

Main node can be referenced abbreviated in relationships:
```kotlin
mainNodeKey = "jira:TASK-123"
relationships = [
  "TASK-123|MENTIONS|user:john",  // Expands to: jira:TASK-123|MENTIONS|user:john
  "file:Service.kt|RELATED_TO|TASK-123"  // Expands to: file:Service.kt|RELATED_TO|jira:TASK-123
]
```

### Evidence-based Relationships

**CRITICAL FEATURE:** Every edge MUST have evidence (chunk ID).

#### Why?

1. **Traceability** - Can trace back where relationship came from
2. **Verification** - Agent can verify if relationship still valid
3. **Confidence** - More chunks = higher confidence in relationship
4. **Explainability** - Can show user specific text that supports relationship

#### Example

```kotlin
// Agent indexes Jira ticket
content = """
# TASK-123: Fix login bug

**Assignee:** John Doe

The issue affects UserService.kt authentication flow.
We need to modify the login() method.

relationships: [
  "TASK-123|ASSIGNED_TO|user:john",
  "TASK-123|AFFECTS|file:UserService.kt",
  "TASK-123|AFFECTS|method:UserService.login"
]
"""

// After ingest in ArangoDB:
edge {
  edgeType = "affects",
  from = "jira:task-123",
  to = "file:userservice.kt",
  evidenceChunkIds = ["chunk-uuid-abc"]  // ← Reference to chunk with this text
}
```

### Normalization & Canonicalization

#### Problem: Variable naming

Agent can reference same entity differently:
- `user:John`, `user:john`, `User:John`
- `jira:TASK-123`, `JIRA:task-123`
- `order:order_530798957`, `order:530798957`

#### Solution: Multi-stage normalization

**Stage 1: Format normalization (stable)**
```kotlin
normalizeSingleGraphRef("User:John  Smith") → "user:john  smith"
// Rules:
// - Namespace (before ':') → lowercase
// - Whitespace → single space
// - Special chars → '_'
```

**Stage 2: Canonicalization (semantic)**
```kotlin
canonicalizeGraphRef("order:order_530798957") → "order:530798957"
// Rules:
// - Remove redundant namespace prefix in value
// - order:order_X → order:X
// - product:product_lego → product:lego
```

**Stage 3: Alias resolution (per-client registry)**
```kotlin
// MongoDB: graph_entity_registry collection
{
  clientId: "client-abc",
  aliasKey: "user:john",
  canonicalKey: "user:john.doe@example.com",
  area: "user",
  seenCount: 42,
  lastSeenAt: "2026-02-01T10:00:00Z"
}

// On subsequent ingest:
resolveCanonicalGraphRef("user:john") → "user:john.doe@example.com"
// ✅ All aliases point to same canonical key
```

### Cache Strategy

```kotlin
// In-memory cache (ConcurrentHashMap)
graphRefCache["client-abc|user:john"] = "user:john.doe@example.com"

// Mutex per cache key (prevents race conditions)
graphRefLocks["client-abc|user:john"] = Mutex()
```

---

## Continuous Indexers

### EmailContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from emails + processes links
- **Process:**
  1. Reads NEW emails from MongoDB
  2. Extracts links using LinkExtractor
  3. Creates DATA_PROCESSING task for email (without downloading links)
  4. For each link creates SEPARATE LINK_PROCESSING task

### JiraContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from Jira issues
- **Process:**
  1. Reads NEW Jira issues from MongoDB
  2. Creates DATA_PROCESSING task
  3. Qualifier agent handles indexing and link processing

### ConfluenceContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from Confluence pages
- **Process:** Similar to Jira indexer

### GitContinuousIndexer

- **Purpose:** Creates DATA_PROCESSING task from Git commits
- **Process:** Similar to other indexers

---

## RAG Integration

### 1. RAG-first Search

```kotlin
val embedding = embeddingGateway.callEmbedding(query)
val results = weaviateVectorStore.search(
  query = VectorQuery(embedding, filters = VectorFilters(clientId, projectId))
)
```

### 2. Graph Expansion

```kotlin
// Seed nodes from chunk metadata
val seedNodes = results.flatMap { it.metadata["graphRefs"] }

// Traversal (2 hops)
seedNodes.forEach { seed ->
  graphDBService.traverse(clientId, seed, TraversalSpec(maxDepth = 2))
}
```

### 3. Evidence Pack Assembly

```kotlin
EvidencePack(
  items = [
    EvidenceItem(source = "RAG", content = "...", confidence = 0.92),
    EvidenceItem(source = "Graph", content = "...", confidence = 0.85)
  ],
  summary = "Found 5 RAG results and 12 related graph nodes."
)
```

---

## Module Structure & Communication

### Backend Server (Spring Boot WebFlux)

- **Orchestrator:** Coordinates all processes
- **RAG:** Vector storage and semantic search
- **Scheduling:** Background task management
- **Integrations:** External service connections

### Compute Services

- **service-joern:** Code analysis
- **service-tika:** Document processing
- **service-whisper:** Audio transcription
- **service-atlassian:** Atlassian Cloud API (Jira, Confluence, Bitbucket)

### Shared Modules

- **common-dto:** Data transfer objects
- **common-api:** `@HttpExchange` contracts
- **domain:** Pure domain types
- **ui-common:** Compose Multiplatform UI screens

### Apps

- **desktop:** Primary desktop application
- **mobile:** iOS/Android port from desktop

### Communication Contract

- **UI ↔ Server:** ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices:** REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Summary

### Key Architecture Principles

1. **Two-stage processing:** CPU qualification + GPU execution
2. **Bidirectional knowledge:** RAG (semantic) + Graph (structured)
3. **Evidence-based relationships:** Every edge has supporting evidence
4. **Multi-tenancy:** Per-client isolation in all storage layers
5. **Fail-fast design:** Errors propagate, no silent failures
6. **Type safety:** Explicit input/output types throughout

### Benefits

1. **Cost efficiency:** GPU models only when necessary
2. **Scalability:** Parallel CPU qualification, GPU execution on idle
3. **Explainability:** Evidence links for all relationships
4. **Flexibility:** Schema-less graph for new entity types
5. **Performance:** Hybrid search combining semantic + structured

---

**Document Version:** 2.0
**Last Updated:** 2026-02-04
**Applies To:** System architecture, design patterns, and knowledge base implementation in Jervis