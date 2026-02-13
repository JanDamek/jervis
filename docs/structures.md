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
        ┌──────────────────────────────────────────────────┐
        │ Python Orchestrator (LangGraph, KB-First)        │
        │ • 4 task categories: ADVICE, SINGLE_TASK,        │
        │   EPIC, GENERATIVE                               │
        │ • Hierarchical context: step→goal→epic           │
        │ • MongoDB context store (orchestrator_context)   │
        │ • Distributed lock for multi-pod                 │
        │ • KB-only communication (no direct PVC access)   │
        └──────────────────────────────────────────────────┘
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

#### Three-Way Task Routing (SimpleQualifierAgent)

The qualifier delegates heavy lifting to the KB microservice (`POST /ingest/full`),
which returns routing hints: `hasActionableContent`, `isAssignedToMe`, `hasFutureDeadline`,
`suggestedDeadline`, `urgency`.

**Decision tree:**

```
KB ingest result →
  ├─ hasActionableContent=false
  │    → DISPATCHED_GPU (info_only — indexed, no action needed)
  │
  ├─ isAssignedToMe=true AND hasActionableContent=true
  │    → READY_FOR_GPU (immediate, high priority)
  │
  ├─ hasFutureDeadline=true AND hasActionableContent=true
  │    ├─ deadline < scheduleLeadDays away → READY_FOR_GPU (too close, do now)
  │    └─ deadline >= scheduleLeadDays away → create SCHEDULED_TASK copy
  │         scheduledAt = deadline - scheduleLeadDays
  │         original task → DISPATCHED_GPU (indexed, done)
  │
  └─ hasActionableContent=true (no assignment, no deadline)
       → READY_FOR_GPU (execute when available)
```

**Schedule lead time**: `SCHEDULE_LEAD_DAYS = 2` (configurable per client).
Tasks with deadlines further than this are scheduled, not executed immediately.

**DISPATCHED_GPU (info_only):**
- Document indexed and structured (Graph + RAG)
- No action items or decisions
- Simple informational content
- Routine updates (status change, minor commit)

**READY_FOR_GPU (immediate):**
- Assigned to the team/organization
- Deadline too close (within schedule lead days)
- Requires user action (reply, update, review)
- Code changes or architectural decisions
- Complex analysis or investigation

**SCHEDULED_TASK (deferred):**
- Has actionable content with a future deadline
- Scheduled copy created with `scheduledAt = deadline - scheduleLeadDays`
- BackgroundEngine scheduler loop picks these up automatically

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
- **Process:** Reads READY_FOR_QUALIFICATION tasks from MongoDB, ordered by `queuePosition ASC NULLS LAST, createdAt ASC`
- **Agents:** SimpleQualifierAgent with CPU model (OLLAMA_QUALIFIER)
- **Max iterations:** 10 (for chunking loops)
- **Concurrency:** 10 parallel KB requests (matching CPU Ollama `OLLAMA_NUM_PARALLEL=10`)
- **Retry:** Operational errors (Ollama busy, timeout, 429, 503) → infinite exponential backoff 5s→10s→20s→...→5min cap. Items stay READY_FOR_QUALIFICATION with future `nextQualificationRetryAt`. Non-retriable errors → permanent ERROR state.
- **Priority:** Items with explicit `queuePosition` are processed first (set via UI reorder controls)

### Scheduler Loop (Task Dispatch)

- **Interval:** 60 seconds
- **Advance dispatch:** 10 minutes before `scheduledAt`
- **One-shot tasks:** Transitions `NEW → READY_FOR_QUALIFICATION`, clears `scheduledAt`
- **Recurring tasks (cron):** Creates execution copy → `READY_FOR_QUALIFICATION`, updates original with next `scheduledAt` via `CronExpression.next()`
- **Invalid cron:** Falls back to one-shot behavior (deletes original after creating execution copy)

### Execution Loop (GPU)

- **Trigger:** Runs ONLY when idle (no user requests for 30s)
- **Agent:** Python Orchestrator (LangGraph) with GPU model (OLLAMA_PRIMARY)
- **Max iterations:** 500 (configurable via application.yml)
- **Preemption:** Immediately interrupted by user requests
- **Dual-queue:** Status emissions include both FOREGROUND and BACKGROUND pending items
- **Atomic claim:** Uses MongoDB `findAndModify` (READY_FOR_GPU → DISPATCHED_GPU) to prevent duplicate execution
- **Stale recovery:** On pod startup, tasks stuck in DISPATCHED_GPU/QUALIFYING for >10min are reset

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
READY_FOR_QUALIFICATION → QUALIFYING (atomic findAndModify) → DONE or READY_FOR_GPU
    ↓
READY_FOR_GPU → DISPATCHED_GPU (atomic findAndModify) → PYTHON_ORCHESTRATING → COMPLETED
                    │                     │
                    │                     └── new messages arrived? → READY_FOR_GPU (auto-requeue)
                    └── interrupted → USER_TASK → user responds → READY_FOR_GPU (loop)

Scheduled tasks:
NEW (scheduledAt set) → scheduler loop dispatches when scheduledAt <= now + 10min
    ├── one-shot: NEW → READY_FOR_QUALIFICATION (scheduledAt cleared)
    └── recurring (cron): original stays NEW (scheduledAt = next cron run),
                          execution copy → READY_FOR_QUALIFICATION
```

### K8s Resilience

- **Deployment strategy:** `Recreate` — old pod is stopped before new pod starts (no overlap)
- **Atomic task claiming:** MongoDB `findAndModify` ensures only one instance processes each task
- **Stale task recovery:** On startup, BackgroundEngine resets tasks stuck in transient states (DISPATCHED_GPU, QUALIFYING) for >10 minutes back to their retryable state
- **Single GPU constraint:** Recreate strategy + atomic claims guarantee no duplicate GPU execution

For Python orchestrator task flow see [orchestrator-final-spec.md § 9](orchestrator-final-spec.md#9-async-dispatch--result-polling-architektura).

---

## Ollama Router Architecture (Priority-Based GPU/CPU Routing)

All services call a single endpoint – the **Ollama Router** (:11430) – which transparently proxies to GPU or CPU Ollama based on priority and model availability.

```
┌─────────────────────────────────────────────────────────────────────────┐
│               NAS Server (2×24 cores, 200GB RAM, P40 GPU)              │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Ollama Router (:11430)   Python FastAPI                        │   │
│  │  • Priority routing (P0 critical → P3 background)               │   │
│  │  • Model set swapping (orchestrator ↔ background)               │   │
│  │  • Preemption (background → CPU on orchestrator request)        │   │
│  │  • Multi-GPU pool (scalable to N GPU backends)                  │   │
│  │  • Announce/release protocol for orchestrator                   │   │
│  └──────┬───────────────────────────────────┬──────────────────────┘   │
│         │                                   │                          │
│  ┌──────▼──────────────────────┐  ┌────────▼─────────────────────────┐ │
│  │  GPU Instance (:11434)      │  │  CPU Instance (:11435)           │ │
│  │  P40 24GB VRAM              │  │  Fallback for preempted work     │ │
│  │                             │  │                                   │ │
│  │  Set A (orchestrator):      │  │  OLLAMA_NUM_PARALLEL=10          │ │
│  │    qwen3-coder-tool:30b     │  │  OLLAMA_NUM_THREADS=18           │ │
│  │    (~20GB, NUM_PARALLEL=2)  │  │  OLLAMA_MAX_LOADED_MODELS=3      │ │
│  │                             │  │  qwen2.5:7b + 14b + embed:8b     │ │
│  │  Set B (background):       │  │                                   │ │
│  │    qwen2.5:7b + 14b +      │  │  Always loaded, always available  │ │
│  │    qwen3-embedding:8b       │  │  as fallback when GPU busy        │ │
│  │    (~20GB, coexists)        │  │                                   │ │
│  └──────────────────────────────┘  └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### Priority Levels

| Priority | Source | Behavior |
|----------|--------|----------|
| P0 CRITICAL | Orchestrator + user chat | Preempts background, always GPU, model swap immediate |
| P1 CODING | OpenHands/Aider | GPU preferred, waits for current request |
| P2 VLM | Image description (KB) | GPU-only capability, queued |
| P3 BACKGROUND | KB ingest, qualifier, embedding | GPU when free, CPU fallback |

> Priority is detected from the model name in the request: `qwen3-coder-tool:30b` → P0, `qwen2.5:*` / `qwen3-embedding:*` → P3.

### Model Sets (alternating on GPU)

| Set | Models | VRAM | Active when |
|-----|--------|------|-------------|
| A – Orchestrator | qwen3-coder-tool:30b | ~20GB | Orchestrator/chat active |
| B – Background | qwen2.5:7b + qwen2.5:14b + qwen3-embedding:8b | ~20GB | Orchestrator idle |

### Orchestrator Pre-announcement Protocol

```
Orchestrator ──POST /router/announce──► Router: preempt bg → unload → load orch model
                                        ◄── {status: "ready"}
Orchestrator ──POST /api/chat──────────► Router: proxy to GPU (model ready)
Orchestrator ──POST /router/release───► Router: load background models on GPU
```

Auto-release: if orchestrator is idle for 5 min → router auto-releases GPU and loads background set.

### Multi-GPU Support

Router manages a pool of GPU backends (`GPU_BACKENDS` JSON env var). With 2+ GPUs:
- Orchestrator gets dedicated GPU#0, background runs on GPU#1 simultaneously
- No preemption or model swapping needed
- Adding a GPU = config change only, no code change

### Key Configuration

**GPU Instance (:11434):**
```bash
OLLAMA_HOST=0.0.0.0:11434
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
OLLAMA_MAX_LOADED_MODELS=4      # Allow bg set (3 models) coexistence
OLLAMA_NUM_PARALLEL=2
OLLAMA_KEEP_ALIVE=5m
CUDA_VISIBLE_DEVICES=0
OLLAMA_NUM_GPU=999
```

**CPU Instance (:11435) – fallback:**
```bash
OLLAMA_HOST=0.0.0.0:11435
OLLAMA_NUM_PARALLEL=10
OLLAMA_NUM_THREADS=18
OLLAMA_MAX_LOADED_MODELS=3
OLLAMA_NUM_GPU=0
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
OLLAMA_KEEP_ALIVE=1h
```

### Endpoint Mapping (all services → router :11430)

| Service | Env Var | Value |
|---------|---------|-------|
| Kotlin server | `OLLAMA_BASE_URL` | `http://192.168.100.117:11430` |
| KB service | `OLLAMA_*_BASE_URL` | `http://192.168.100.117:11430` |
| Orchestrator | `OLLAMA_API_BASE` | `http://192.168.100.117:11430` |
| Coding engine | `ollama-base-url` | `http://192.168.100.117:11430` |

### Source Code

- Router service: `backend/service-ollama-router/`
- GPU announce/release: `backend/service-orchestrator/app/llm/gpu_router.py`
- K8s deployment: `k8s/app_ollama_router.yaml`
- ConfigMap: `k8s/configmap.yaml` → `jervis-ollama-router-config`

---

**Document Version:** 5.0
**Last Updated:** 2026-02-13
