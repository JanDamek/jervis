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

**TaskTypeEnum values:**
- `EMAIL_PROCESSING` – Email content from IMAP/POP3
- `WIKI_PROCESSING` – Confluence pages
- `BUGTRACKER_PROCESSING` – Jira/GitHub/GitLab issues
- `GIT_PROCESSING` – Git commits and diffs
- `USER_INPUT_PROCESSING` – User chat messages
- `USER_TASK` – User-created tasks
- `SCHEDULED_TASK` – Cron-scheduled tasks
- `LINK_PROCESSING` – URLs extracted from other documents
- `MEETING_PROCESSING` – Transcribed meeting recordings (from MeetingContinuousIndexer)

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
- **Concurrency:** 10 parallel KB requests (matching CPU Ollama `OLLAMA_NUM_PARALLEL=10`)

### Execution Loop (GPU)

- **Trigger:** Runs ONLY when idle (no user requests for 30s)
- **Agent:** Python Orchestrator (LangGraph) with GPU model (OLLAMA_PRIMARY)
- **Max iterations:** 500 (configurable via application.yml)
- **Preemption:** Immediately interrupted by user requests
- **Dual-queue:** Status emissions include both FOREGROUND and BACKGROUND pending items

### Auto-Requeue on Inline Messages

When orchestration is dispatched, `TaskDocument.orchestrationStartedAt` is set to the current timestamp.
On completion ("done"), `BackgroundEngine` checks for new USER messages that arrived during orchestration:

```
orchestrationStartedAt = Instant.now()  ← set on dispatch

... orchestration runs (PYTHON_ORCHESTRATING) ...

onComplete("done"):
  newMessageCount = chatMessageRepository.countAfterTimestamp(
      projectId, orchestrationStartedAt
  )
  if (newMessageCount > 0):
      task.state = READY_FOR_GPU      ← auto-requeue (not DISPATCHED_GPU)
      task.orchestrationStartedAt = null
      // Agent will re-process with full context including new messages
  else:
      // Normal completion flow (DISPATCHED_GPU or DELETE)
```

This ensures that messages sent while the agent is busy are not lost -- the task is automatically
re-processed with the full conversation context once the current orchestration finishes.

### Task States Flow

```
NEW (from API) → INDEXING (processing) → INDEXED (task created)
    ↓
QUALIFICATION_IN_PROGRESS (CPU agent) → DONE or READY_FOR_GPU
    ↓
READY_FOR_GPU → PYTHON_ORCHESTRATING (GPU agent) → COMPLETED
                    │                     │
                    │                     └── new messages arrived? → READY_FOR_GPU (auto-requeue)
                    └── interrupted → USER_TASK → user responds → READY_FOR_GPU (loop)
```

For Python orchestrator task flow see [orchestrator-final-spec.md § 9](orchestrator-final-spec.md#9-async-dispatch--result-polling-architektura).

---

## Ollama Instance Architecture (GPU / CPU Separation)

Two Ollama instances – GPU for interactive queries, CPU for all background work (ingest + embedding):

```
┌─────────────────────────────────────────────────────────────────────────┐
│               NAS Server (2×24 cores, 200GB RAM, P40 GPU)              │
│               Ollama sees max 24 cores (single NUMA node)              │
│                                                                         │
│  ┌──────────────────────────┐  ┌──────────────────────────────────────┐ │
│  │  GPU Instance (:11434)   │  │  CPU Instance (:11435)              │ │
│  │  Qwen 30B on P40         │  │  Qwen2.5 7B + 14B + Embedding 8B   │ │
│  │  OLLAMA_NUM_PARALLEL=2   │  │  OLLAMA_NUM_PARALLEL=10            │ │
│  │  Interactive queries     │  │  OLLAMA_NUM_THREADS=18             │ │
│  │  Python orchestrator     │  │  OLLAMA_MAX_LOADED_MODELS=3        │ │
│  │  Coding tools            │  │  OLLAMA_FLASH_ATTENTION=1          │ │
│  │                          │  │  OLLAMA_KV_CACHE_TYPE=q8_0         │ │
│  │  6 cores reserved        │  │  18 cores for inference            │ │
│  └──────────────────────────┘  └──────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### Instance Details

| Instance | Port | Models | Purpose | Concurrency |
|----------|------|--------|---------|-------------|
| GPU Primary | 11434 | Qwen3-coder-tool:30b | Interactive chat, orchestrator, coding tools | `NUM_PARALLEL=2` |
| CPU Ingest + Embed | 11435 | Qwen2.5:7b, Qwen2.5:14b, qwen3-embedding:8b | Qualification, summary, relevance, embedding | `NUM_PARALLEL=10`, `NUM_THREADS=18` |

> **Note:** `OLLAMA_NUM_PARALLEL` is per-model. Each of the 3 loaded models gets 10 parallel slots independently.
> Ollama can only use cores from one NUMA node (max 24 on this server), so `NUM_THREADS=18` leaves 6 for GPU instance + OS.

### Model Routing (CPU Instance)

The CPU instance runs three models simultaneously (`OLLAMA_MAX_LOADED_MODELS=3`, 200GB RAM):

| Task | Model | Rationale |
|------|-------|-----------|
| Link relevance check | `qwen2.5:7b` (simple) | Binary classification, small context (~3k chars), speed priority |
| Summary generation + entity extraction | `qwen2.5:14b` (complex) | Structured JSON output, entity detection, actionability routing |
| Vector embeddings (RAG) | `qwen3-embedding:8b` | Single forward pass, no generation, very fast on CPU |
| Koog qualifier agent | Configured in Modelfile | Structuring, chunking, graph/RAG linking |

### Key Configuration Values

**Kotlin side** (`models-config.yaml`, `application.yml`):
- `OLLAMA_QUALIFIER.maxConcurrentRequests: 10`
- `OLLAMA_EMBEDDING.maxConcurrentRequests: 50` (embedding is fast, Ollama queues excess)
- `preload.ollama.cpu.concurrency: 10`
- `TaskQualificationService.effectiveConcurrency: 10`
- `ollama.embedding.baseUrl` → port 11435 (same as qualifier)

**Python KB side** (`config.py`, K8s ConfigMap):
- `OLLAMA_INGEST_BASE_URL: http://192.168.100.117:11435`
- `OLLAMA_EMBEDDING_BASE_URL: http://192.168.100.117:11435`
- `INGEST_MODEL_SIMPLE: qwen2.5:7b`
- `INGEST_MODEL_COMPLEX: qwen2.5:14b`

### Why CPU for Ingest + Embedding

1. **GPU stays free** for 30B interactive model (P40 VRAM nearly full)
2. **Batch parallelism** – 10 concurrent slots per model, process ingest queue faster than sequential GPU
3. **No latency competition** – user queries never compete with background ingest or embedding
4. **CPU penalty is small** for 7B/14B/8B models – memory bandwidth bound, not compute bound
5. **200GB RAM** holds all 3 models with room for KV cache across parallel slots
6. **Simpler ops** – one CPU Ollama process instead of two, one port to monitor

### Ollama ENV Setup (CPU Instance)

```bash
# /etc/systemd/system/ollama-cpu.service or docker run env:
OLLAMA_HOST=0.0.0.0:11435
OLLAMA_NUM_PARALLEL=10       # 10 parallel slots per loaded model
OLLAMA_NUM_THREADS=18        # 18 of 24 cores (NUMA node limit)
OLLAMA_MAX_LOADED_MODELS=3   # qwen2.5:7b + qwen2.5:14b + qwen3-embedding:8b
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
```

---

**Document Version:** 4.0
**Last Updated:** 2026-02-07
