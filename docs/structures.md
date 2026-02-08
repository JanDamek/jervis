# Data Processing Pipeline & Routing

**Status:** Production Documentation (2026-02-07)
**Purpose:** Two-stage data processing architecture (CPU qualification → GPU execution)

> **Related docs:**
> - [knowledge-base.md](knowledge-base.md) – Knowledge Base SSOT (graph schema, RAG, ingest, normalization, indexers)
> - [kb-analysis-and-recommendations.md](kb-analysis-and-recommendations.md) – KB analýza, kritické problémy, doporučení (CZ)
> - [architecture.md](architecture.md) – System-wide architecture overview
> - [koog-audit.md](koog-audit.md) – Koog removal audit (historical)

---

## Table of Contents

1. [Graph-Based Routing Architecture](#graph-based-routing-architecture)
2. [Background Engine & Task Processing](#background-engine--task-processing)

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
│ Qualifier – SimpleQualifierAgent (CPU - OLLAMA) │
│ • Calls KB microservice for indexing/linking   │
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
        │ Python Orchestrator (LangGraph)          │
        │ • Loads task context from TaskMemory    │
        │ • Focus on analysis/actions             │
        │ • No redundant structuring work         │
        └─────────────────────────────────────────┘
```

### Key Components

#### 1. Continuous Indexers (ETL: MongoDB → PendingTask)

- **Non-stop polling** on NEW documents in MongoDB (30s delay when empty)
- **No API calls** - only read from MongoDB and create PendingTask
- **States:** NEW (from API) → INDEXING (processing) → INDEXED (task created)
- **INDEXED = "content passed to Jervis as pending task", NOT "already in RAG/Graph"!**

For indexer details see [knowledge-base.md § Continuous Indexers](knowledge-base.md#continuous-indexers).

#### 1.1 Link Handling Flow - SEPARATE PENDING TASKS

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

**2. Qualifier (SimpleQualifierAgent) processes EMAIL task (DATA_PROCESSING):**
- Indexes email content into RAG/Graph
- Creates Email vertex
- Creates Person vertices (from, to, cc)
- Creates Graph edges: Email→Person (sender), Person→Email (recipient)
- Notes in metadata: "3 links will be processed separately"
- Routing: DONE (email indexed, links waiting in queue)

**3. Qualifier (SimpleQualifierAgent) processes LINK task (LINK_PROCESSING) - SEPARATELY:**
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

#### 2. Qualifier (SimpleQualifierAgent) - Tools

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
- Use: Exceptionally in Qualifier for large plain-text documents

#### 3. TaskMemory - Context Passing

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

#### 4. Python Orchestrator - Task Context Loading

The Python Orchestrator loads task context from TaskMemory at the start of execution:
1. Loads context summary, Graph node keys, RAG document IDs
2. Uses KB microservice for full content retrieval
3. Focuses on analysis and actions, not structuring

#### 5. Preemption

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

### Benefits

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
- **Agents:** SimpleQualifierAgent with CPU model (OLLAMA_QUALIFIER)
- **Max iterations:** 10 (for chunking loops)

### Execution Loop (GPU)

- **Trigger:** Runs ONLY when idle (no user requests for 30s)
- **Agent:** Python Orchestrator (LangGraph) with GPU model (OLLAMA_PRIMARY)
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

For Python orchestrator task flow see [orchestrator-final-spec.md § 9](orchestrator-final-spec.md#9-async-dispatch--result-polling-architektura).

---

**Document Version:** 3.0
**Last Updated:** 2026-02-07
