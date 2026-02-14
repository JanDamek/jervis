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

1. [Ollama Router – Priority-Based GPU/CPU Routing](#ollama-router--priority-based-gpucpu-routing)
2. [Graph-Based Routing Architecture](#graph-based-routing-architecture)
3. [Background Engine & Task Processing](#background-engine--task-processing)
4. [Multi-Agent Delegation System Data Models](#multi-agent-delegation-system-data-models)

---

## Ollama Router – Priority-Based GPU/CPU Routing

### Overview

**All LLM requests** in the system (Orchestrator, KB, Correction Agent) now route through **Ollama Router** (port 11430), which intelligently distributes requests between GPU and CPU backends based on priority and availability.

### Architecture

```
┌──────────────────────────────────────────────────────────┐
│                 LLM Request Sources                      │
│                                                          │
│  • Python Orchestrator (reasoning, planning, response)  │
│  • Knowledge Base (RAG, embeddings, graph prep)         │
│  • Correction Agent (transcript corrections)            │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │   Ollama Router      │  Port: 11430
              │   (Priority Proxy)   │  Host: 192.168.100.117
              │                      │
              │  Request Analysis:   │
              │  • Check priority    │
              │  • Check GPU state   │
              │  • Route to backend  │
              └──────┬───────────────┘
                     │
       ┌─────────────┴────────────────┐
       │                              │
       ▼                              ▼
┌──────────────┐            ┌──────────────────┐
│ GPU Backend  │            │  CPU Backend     │
│ (port 11434) │            │  (port 11435)    │
│              │            │                  │
│ • P40 24GB   │            │ • 200GB RAM      │
│ • Fast       │            │ • Slow           │
│ • Limited    │            │ • Unlimited cap. │
└──────────────┘            └──────────────────┘
```

### Priority Levels

| Priority | Source | GPU Behavior | Preemption |
|----------|--------|--------------|------------|
| **100** | Orchestrator (via `X-Ollama-Priority: 100`) | Reserved slot for 30min | Cannot be preempted |
| **50** | Background tasks | Uses GPU when idle | Preempted by priority 100 |
| **Auto** | Standard requests (no header) | Routes to GPU if free | Fallback to CPU |

### Request Flow Example

```python
# 1. Orchestrator makes LLM request
response = await llm_provider.completion(
    messages=[...],
    tier=ModelTier.LOCAL_STANDARD,
)
# Internally calls: http://192.168.100.117:11430/api/generate
# With header: X-Ollama-Priority: 100

# 2. Ollama Router receives request
#    → Checks priority header (100 = Orchestrator)
#    → Reserves GPU slot (30min reservation)
#    → Routes to GPU backend (http://127.0.0.1:11434)

# 3. KB makes embedding request
embedding = await ollama.embed(
    model="nomic-embed-text",
    input="Sample text",
)
# Calls: http://192.168.100.117:11430/api/embed
# No priority header → Auto routing

# 4. Ollama Router receives embedding request
#    → No priority header → Auto mode
#    → If GPU idle → route to GPU (fast)
#    → If GPU busy with orchestrator → route to CPU (slower but works)
```

### Configuration (All Services)

| Service | Environment Variable | Value |
|---------|---------------------|-------|
| **Orchestrator** | `OLLAMA_API_BASE` | `http://192.168.100.117:11430` |
| **KB (read)** | `OLLAMA_BASE_URL` | `http://192.168.100.117:11430` |
| | `OLLAMA_EMBEDDING_BASE_URL` | `http://192.168.100.117:11430` |
| | `OLLAMA_INGEST_BASE_URL` | `http://192.168.100.117:11430` |
| **KB (write)** | Same as KB read | |

**Key point:** All services transparently use Ollama Router. No code changes needed – just updated environment variables.

### Benefits

1. **Guaranteed GPU for Orchestrator** - User-facing requests never wait
2. **Automatic fallback** - Background tasks use CPU when GPU busy
3. **Model management** - Router auto-loads/unloads models by priority
4. **Transparent** - Services don't know about routing logic
5. **Metrics** - Prometheus metrics for monitoring GPU utilization

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
        │ • Legacy graph: 4 task categories (ADVICE,       │
        │   SINGLE_TASK, EPIC, GENERATIVE)                 │
        │ • Delegation graph: 7-node multi-agent system    │
        │   (19 specialist agents, feature-flagged)        │
        │ • Hierarchical context: step→goal→epic           │
        │ • MongoDB context store (orchestrator_context)   │
        │ • Session Memory + Procedural Memory             │
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
- **Stale recovery:** On pod startup, BACKGROUND tasks stuck in DISPATCHED_GPU/QUALIFYING for >10min are reset (FOREGROUND DISPATCHED_GPU tasks are completed, not stuck)

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
- **Stale task recovery:** On startup, BackgroundEngine resets BACKGROUND tasks stuck in transient states (DISPATCHED_GPU, QUALIFYING) for >10 minutes back to their retryable state. FOREGROUND tasks in DISPATCHED_GPU are preserved (completed chat tasks, not stuck).
- **Single GPU constraint:** Recreate strategy + atomic claims guarantee no duplicate GPU execution

### Workspace Retry with Exponential Backoff

When `initializeProjectWorkspace()` fails (CLONE_FAILED), the project is NOT retried immediately.
Instead, a periodic retry loop (`runWorkspaceRetryLoop()`, 60s interval) checks for CLONE_FAILED projects
whose backoff has elapsed:

- **Backoff schedule:** 5min → 15min → 30min → 60min → 5min cap (wraps around)
- **Fields:** `ProjectDocument.workspaceRetryCount`, `nextWorkspaceRetryAt`, `lastWorkspaceError`
- **Manual retry:** UI "Zkusit znovu" button calls `IProjectService.retryWorkspace()` → resets all retry fields
- **UI banner:** `WorkspaceBanner` composable shows CLONING (info) or CLONE_FAILED (error + retry button)

### Orchestrator Dispatch Backoff

When Python orchestrator dispatch fails (unavailable, busy), the task gets exponential backoff
instead of fixed 15s retry:

- **Backoff schedule:** 5s → 15s → 30s → 60s → 5min cap
- **Fields:** `TaskDocument.dispatchRetryCount`, `nextDispatchRetryAt`
- **Task picking:** `getNextForegroundTask()`/`getNextBackgroundTask()` skip tasks where `nextDispatchRetryAt > now`
- **Reset:** On successful dispatch (PYTHON_ORCHESTRATING), `dispatchRetryCount` resets to 0

### Circuit Breaker for Orchestrator Health

`PythonOrchestratorClient` includes an in-memory circuit breaker for health checks:

- **States:** CLOSED (normal) → OPEN (fast-fail after 5 consecutive failures) → HALF_OPEN (probe after 30s)
- **When OPEN:** `isHealthy()` returns false immediately without HTTP call
- **UI indicator:** `OrchestratorHealthBanner` shows warning when circuit breaker is OPEN
- **Queue status metadata:** `orchestratorHealthy` field pushed via existing QUEUE_STATUS stream

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

## Multi-Agent Delegation System Data Models

The Python Orchestrator uses a multi-agent delegation architecture where a top-level orchestrator decomposes tasks and delegates them to specialist agents. This section documents the data structures that power the delegation graph, agent communication, and execution tracking.

> **Related docs:**
> - [orchestrator-detailed.md](orchestrator-detailed.md) – Full technical description of all nodes, LLM, K8s Jobs, communication, state, approval flow
> - [orchestrator-final-spec.md](orchestrator-final-spec.md) – Async dispatch, approval flow, concurrency

### Domain & Status Enums

```python
class DomainType(str, Enum):
    CODE, DEVOPS, PROJECT_MANAGEMENT, COMMUNICATION, LEGAL,
    FINANCIAL, ADMINISTRATIVE, PERSONAL, SECURITY, RESEARCH, LEARNING

class DelegationStatus(str, Enum):
    PENDING, RUNNING, COMPLETED, FAILED, INTERRUPTED
```

`DomainType` classifies the problem domain so the orchestrator can select the correct specialist agent tree. `DelegationStatus` tracks each delegation through its lifecycle.

### DelegationMessage

The core unit of work passed from a parent agent to a child agent:

```python
class DelegationMessage(BaseModel):
    delegation_id: str
    parent_delegation_id: str | None
    depth: int  # 0=orchestrator, 1-4=sub-agents
    agent_name: str
    task_summary: str
    context: str  # token-budgeted
    constraints: list[str]
    expected_output: str
    response_language: str  # ISO 639-1
    client_id: str
    project_id: str | None
    group_id: str | None
```

- `depth` limits recursion (max 4 levels of sub-delegation).
- `context` is trimmed to fit the target agent's token budget.
- `response_language` ensures the final user-facing output matches the detected input language.

### AgentOutput

Structured response returned by every agent upon completion:

```python
class AgentOutput(BaseModel):
    delegation_id: str
    agent_name: str
    success: bool
    result: str  # Full compact response (not truncated)
    structured_data: dict
    artifacts: list[str]
    changed_files: list[str]
    sub_delegations: list[str]
    confidence: float  # 0.0-1.0
    needs_verification: bool
```

- `result` contains the complete compact answer (never truncated).
- `confidence` and `needs_verification` enable the parent agent to decide whether to accept, retry, or escalate.

### ExecutionPlan

Produced by the orchestrator to describe how delegations should be scheduled:

```python
class ExecutionPlan(BaseModel):
    delegations: list[DelegationMessage]
    parallel_groups: list[list[str]]  # Groups of delegation_ids for parallel execution
    domain: DomainType
```

`parallel_groups` defines which delegations can run concurrently vs. sequentially.

### SessionEntry (Session Memory)

Per-client/project memory entries stored in MongoDB:

```python
class SessionEntry(BaseModel):
    timestamp: str
    source: str  # "chat" | "background" | "orchestrator_decision"
    summary: str  # max 200 chars
    details: dict | None
    task_id: str | None
```

### Procedure Models (Learned Workflows)

```python
class ProcedureStep(BaseModel):
    agent: str
    action: str
    parameters: dict

class ProcedureNode(BaseModel):
    trigger_pattern: str
    procedure_steps: list[ProcedureStep]
    success_rate: float
    last_used: str | None
    usage_count: int
    source: str  # "learned" | "user_defined"
    client_id: str
```

Procedures are repeatable multi-step workflows that the system learns from successful delegation sequences or that users define explicitly.

### AgentCapability

Describes what each specialist agent can do:

```python
class AgentCapability(BaseModel):
    name: str
    description: str
    domains: list[DomainType]
    can_sub_delegate: bool
    max_depth: int
    tool_names: list[str]
```

### DelegationMetrics

Per-delegation performance tracking:

```python
class DelegationMetrics(BaseModel):
    delegation_id: str
    agent_name: str
    start_time: str | None
    end_time: str | None
    token_count: int
    llm_calls: int
    sub_delegation_count: int
    success: bool
```

### OrchestratorState Fields (Delegation-Related)

The LangGraph `OrchestratorState` includes these delegation-specific fields:

```python
execution_plan: dict | None
delegation_states: dict
active_delegation_id: str | None
completed_delegations: list
delegation_results: dict
response_language: str
domain: str | None
session_memory: list
_delegation_outputs: list
```

These fields are managed by the orchestrator graph nodes (planner, delegator, aggregator) throughout the execution lifecycle.

### MongoDB Collections (Delegation System)

| Collection | Purpose | TTL | Limit |
|------------|---------|-----|-------|
| `session_memory` | Per-client/project memory (SessionEntry docs) | 7 days | max 50 entries per client/project |
| `delegation_metrics` | Per-agent delegation metrics (DelegationMetrics docs) | 90 days | — |

### Agent Communication Protocol

All agents respond with a structured text format that is parsed into `AgentOutput`:

```
STATUS: 1 (success) | 0 (failure) | P (partial)
RESULT: <complete compact answer>
ARTIFACTS: <files, commits>
ISSUES: <problems, blockers>
CONFIDENCE: 0.0-1.0
NEEDS_VERIFICATION: true/false
```

The parent agent (or orchestrator aggregator node) parses this format and uses the `CONFIDENCE` and `NEEDS_VERIFICATION` fields to decide whether to accept the result, request a retry, or escalate to a different agent.

---

## Multi-Agent Delegation System

### Overview

The Python Orchestrator supports two execution paths controlled by feature flags:

1. **Legacy graph (default):** 14-node graph for 4 task categories (ADVICE, SINGLE_TASK, EPIC, GENERATIVE)
2. **Delegation graph (opt-in):** 7-node graph for multi-agent orchestration with 19 specialist agents

### Delegation Graph Flow

```
intake → evidence_pack → plan_delegations → execute_delegation(s) → synthesize → finalize → END
```

**plan_delegations:** LLM selects agents from AgentRegistry and builds ExecutionPlan (DAG)
**execute_delegation:** Dispatches to agents, supports parallel groups via DAGExecutor
**synthesize:** Merges results, RAG cross-check, translates to response language

### Agent Tiers

| Tier | Agents | Domains |
|------|--------|---------|
| 1 — Core | Coding, Git, CodeReview, Test, Research | Code, testing, KB/web search |
| 2 — DevOps & PM | IssueTracker, Wiki, Documentation, DevOps, ProjectManagement, Security | Tracking, docs, CI/CD, security |
| 3 — Communication | Communication, Email, Calendar, Administrative | Messaging, scheduling, admin |
| 4 — Business | Legal, Financial, Personal, Learning | Legal, financial, personal |

### Memory Architecture

| Layer | Storage | TTL |
|-------|---------|-----|
| Working Memory | LangGraph state | Per-orchestration |
| Session Memory | MongoDB `session_memory` | 7 days |
| Semantic Memory | KB (Weaviate + ArangoDB) | Permanent |
| Procedural Memory | ArangoDB `ProcedureNode` | Permanent (usage-decay) |

### Feature Flags

All default to `False` (opt-in):
- `use_delegation_graph` — Switch to 7-node delegation graph
- `use_specialist_agents` — Use specialist agents instead of LegacyAgent
- `use_dag_execution` — Enable parallel delegation execution
- `use_procedural_memory` — Enable learning from successful orchestrations

---

**Document Version:** 6.0
**Last Updated:** 2026-02-13
