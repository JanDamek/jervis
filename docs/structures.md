# Data Processing Pipeline & Routing

**Status:** Production Documentation (2026-02-18)
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

**All LLM requests** in the system (Orchestrator, KB, Correction Agent) route through **Ollama Router** (port 11430), which uses a **two-tier request queue** to distribute requests between GPU and CPU backends based on priority and availability.

**Router ALWAYS accepts requests.** Never returns 503/reject. Each request is queued and dispatched when a backend slot becomes available.

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
              │   (Queue + Proxy)    │  Host: 192.168.100.117
              │                      │
              │  ┌────────────────┐  │
              │  │ CRITICAL queue │  │  Unlimited, GPU-only
              │  │ (priority 0)  │  │  Preempts NORMAL
              │  ├────────────────┤  │
              │  │ NORMAL queue   │  │  Max 10, GPU+CPU
              │  │ (priority 1)  │  │  Back-pressure at limit
              │  └────────────────┘  │
              │                      │
              │  Dispatcher:         │
              │  • CRITICAL first    │
              │  • Max 2 per backend │
              │  • Least-busy GPU    │
              └──────┬───────────────┘
                     │
       ┌─────────────┼────────────────┐
       │             │                │
       ▼             ▼                ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│ GPU Backend 1│ │ GPU Backend 2│ │  CPU Backend     │
│ (P40 24GB)   │ │ (P40 24GB)   │ │  (200GB RAM)     │
│ Max 2 slots  │ │ Max 2 slots  │ │  Max 2 slots     │
│ CRIT+NORMAL  │ │ CRIT+NORMAL  │ │  NORMAL only     │
└──────────────┘ └──────────────┘ └──────────────────┘
```

### Request Queue

Two-tier queue with backend-aware dispatch (`app/request_queue.py`):

- **CRITICAL queue**: Unlimited — chat, foreground, interactive (orchestrator). Always GPU, never CPU. Preempts NORMAL if all GPU slots busy.
- **NORMAL queue**: Bounded (max 10) — background, KB ingest, qualification. GPU preferred, CPU fallback for small models. Returns 429 back-pressure when full.
- **Dispatch**: Fast-path (immediate) if slot available, otherwise queued. Background dispatcher assigns queued requests to freed slots. CRITICAL always dispatched first.
- **Concurrency**: Max 2 concurrent requests per backend (Ollama handles 2 parallel well; more degrades all).
- **Client disconnect**: Monitored via `cancel_event` — request dequeued or proxy cancelled on disconnect.

### Priority Levels

| Priority | Value | Source | Routing | Preemption |
|----------|-------|--------|---------|------------|
| **CRITICAL** | 0 | Orchestrator FOREGROUND (via `X-Ollama-Priority: 0`) | GPU only, auto-reserves GPU | Cannot be preempted, preempts NORMAL |
| **NORMAL** | 1 | Everything else (no header) | GPU preferred, CPU fallback | Preempted by CRITICAL |

### GPU Reservations

When a CRITICAL request is dispatched to a GPU, the router automatically creates a reservation for that GPU. Reservations prevent NORMAL requests from using the GPU while the orchestrator session is active.

- **Auto-created**: On first CRITICAL dispatch to a GPU
- **Refreshed**: On each subsequent CRITICAL dispatch to the same GPU
- **Idle timeout**: Auto-released after 60s of no CRITICAL activity (configurable)
- **Absolute timeout**: Safety net at 600s (configurable)
- **On release**: Background model set is loaded onto the freed GPU after a 5s delay

### Request Flow

```python
# 1. Orchestrator makes LLM request (FOREGROUND)
# → X-Ollama-Priority: 0 header → CRITICAL queue
# → Immediate dispatch if GPU slot available (fast path)
# → Otherwise queued, NORMAL preempted if needed
# → GPU auto-reserved for orchestrator session

# 2. KB makes embedding request (no priority header)
# → NORMAL queue
# → GPU if slot available and no CRITICAL reservation blocks it
# → CPU fallback for small models (<20GB VRAM estimate)
# → Queued if all backends busy

# 3. Backend finishes a request
# → Dispatcher wakes up, checks queues (CRITICAL first)
# → Assigns next request to freed slot
```

### Configuration (All Services)

| Service | Environment Variable | Value |
|---------|---------------------|-------|
| **Orchestrator** | `OLLAMA_API_BASE` | `http://192.168.100.117:11430` |
| **KB (read)** | `OLLAMA_BASE_URL` | `http://192.168.100.117:11430` |
| | `OLLAMA_EMBEDDING_BASE_URL` | `http://192.168.100.117:11430` |
| | `OLLAMA_INGEST_BASE_URL` | `http://192.168.100.117:11430` |
| **KB (write)** | Same as KB read | |

### Router Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `max_concurrent_per_backend` | 2 | Max parallel requests per Ollama instance |
| `normal_queue_max` | 10 | NORMAL queue limit (429 when full) |
| `orchestrator_idle_timeout_s` | 60 | Auto-release GPU reservation after idle |
| `orchestrator_reservation_timeout_s` | 600 | Absolute reservation timeout (safety net) |
| `max_request_timeout_s` | 300 | Cancel zombie requests after this |
| `background_load_delay_s` | 5 | Delay before loading bg models after release |

**Key point:** All services transparently use Ollama Router. No code changes needed – just updated environment variables.

### VRAM Management

Bigger model = higher VRAM priority. Only **embedding** models co-locate alongside :30b (small, won't impact perf). KB extraction uses :30b (same model as orchestrator).

```
GPU state (consolidated – always loaded, keep_alive="-1"):
  qwen3-coder-tool:30b  (~25GB)  → GPU (all LLM tasks: orchestrator, KB, ingest)
  qwen3-embedding:8b    (~5GB)   → GPU (alongside, ~0.3s)

Total capacity: 2 GPU × 2 = 4 GPU slots for all LLM work
No model swapping needed – 30b handles everything.
```

### Caller Concurrency

Callers send requests freely — **router queue manages backend load**. Callers should NOT self-limit with tight semaphores or sequential processing.

| Caller | Concurrency | Timeout | Notes |
|--------|-------------|---------|-------|
| **Orchestrator** (provider.py) | Semaphore(6) | 300-1200s per tier | Safety limit only; router manages actual concurrency |
| **KB Graph** (graph_service.py) | `gather` + Semaphore(4) | 900s per LLM call | Parallel chunk extraction, 4 concurrent |
| **KB RAG** (rag_service.py) | Semaphore(5) | 3600s HTTP | Embedding-specific, OK as-is |
| **Correction** (agent.py) | `gather` + Semaphore(4) | 3600s token | Parallel chunk correction, 4 concurrent |

### Key Files

| File | Purpose |
|------|---------|
| `app/router_core.py` | OllamaRouter — entry point, reservation management, watchdogs |
| `app/request_queue.py` | RequestQueue — two-tier queue, dispatcher, slot finding, execution |
| `app/gpu_state.py` | GpuPool, GpuBackend — GPU state tracking, model load/unload |
| `app/proxy.py` | HTTP proxy — streaming and non-streaming with cancellation |
| `app/config.py` | Settings via environment variables |
| `app/models.py` | Priority, TrackedRequest, model sets |
| `app/main.py` | FastAPI endpoints |

---

## Meeting Transcript Correction Pipeline

### Pipeline Flow

```
RECORDING → UPLOADED → TRANSCRIBING → TRANSCRIBED → INDEXED (raw text)
  → (after qualification, qualified=true)
  → CORRECTING → CORRECTED (or CORRECTION_REVIEW) → re-indexed
```

### Context-Aware Correction Agent

The correction agent (`backend/service-correction/app/agent.py`) processes transcripts sequentially with cumulative context:

1. **Load correction rules** from KB (client/project-specific)
2. **Load project context** from KB (people, technologies, terminology)
3. **First pass**: Identify meeting phases, speakers, topics (LLM analysis)
4. **Sequential chunk correction**: Each chunk gets context from previous corrections
5. **Interactive questions**: Unknown terms generate questions for user review

### Key Design Decisions

- **Sequential, not parallel**: Chunks must be processed in order — each chunk needs context from previous corrections for consistency
- **Correction after qualification**: Pipeline indexes raw transcript first, then corrects after client/project is known (provides domain context)
- **Cumulative running context**: Previous corrections (name spellings, terms) are passed to subsequent chunks
- **Retry on connection errors**: 2× with exponential backoff (2-4s) for router restarts

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-correction/app/agent.py` | CorrectionAgent — KB context, phase analysis, sequential correction |
| `backend/server/.../meeting/MeetingContinuousIndexer.kt` | Pipeline orchestration (index raw → correct qualified → re-index) |
| `backend/server/.../meeting/TranscriptCorrectionService.kt` | State transitions, error recovery |
| `backend/server/.../entity/meeting/MeetingDocument.kt` | Meeting entity with `qualified` flag |

---

## Graph-Based Routing Architecture

### Problem Solved

**Original architecture:** Auto-indexed everything directly into RAG without structuring, causing context overflow for large documents and inefficient use of expensive GPU models.

**Solution:** Two-stage architecture with CPU-based qualification (structuring) and GPU-based execution (analysis/actions).

### Archived Client = No Activity

When `ClientDocument.archived = true`, the entire pipeline is blocked for that client:

| Stage | Mechanism |
|-------|-----------|
| **CentralPoller** | `findByArchivedFalseAndConnectionIdsContaining()` — archived clients excluded from polling, no new tasks created |
| **Pipeline tasks** | `TaskService.markArchivedClientTasksAsDone()` — bulk DB update marks READY_FOR_QUALIFICATION/QUALIFYING/READY_FOR_GPU as DONE (runs on startup + every 5 min) |
| **Scheduler** | `clientRepository.getById()` check before dispatch — archived client's scheduled tasks stay in NEW, resume when unarchived |
| **Idle review** | Client archived check before creating IDLE_REVIEW task |
| **Running tasks** | PYTHON_ORCHESTRATING tasks finish normally — no new tasks follow |

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

**TaskTypeEnum values** (each carries a `sourceKey` used as wire-format identifier for KB):

| Enum value | `sourceKey` | Description |
|------------|-------------|-------------|
| `EMAIL_PROCESSING` | `email` | Email content from IMAP/POP3 |
| `WIKI_PROCESSING` | `confluence` | Confluence pages |
| `BUGTRACKER_PROCESSING` | `jira` | Jira/GitHub/GitLab issues |
| `GIT_PROCESSING` | `git` | Git commits and diffs |
| `USER_INPUT_PROCESSING` | `chat` | User chat messages |
| `USER_TASK` | `user_task` | User-created tasks |
| `SCHEDULED_TASK` | `scheduled` | Cron-scheduled tasks |
| `LINK_PROCESSING` | `link` | URLs extracted from other documents |
| `MEETING_PROCESSING` | `meeting` | Transcribed meeting recordings |
| `IDLE_REVIEW` | `idle_review` | System-generated proactive review task |

The `sourceKey` is sent to KB via multipart form field `sourceType` and stored in graph metadata.
Python KB service uses matching `SourceType(str, Enum)` in `app/api/models.py`.

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

**KB Graph Extraction Budget (same pattern as chat/orchestrator context management):**

- RAG embedding indexes ALL content (no limit) — cheap, fast, GPU-routed
- LLM graph extraction capped at `MAX_EXTRACTION_CHUNKS` (30) per document
- Large documents: selects representative chunks (beginning + sampled middle + end)
- All LLM calls use explicit `num_ctx=INGEST_CONTEXT_WINDOW` (32 768) to prevent Ollama's small default (often 2048)
- Per-call timeout (`LLM_CALL_TIMEOUT=180s`) prevents hangs blocking the async callback
- See `docs/knowledge-base.md` § "Context Window Management" for full settings table

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
    val sourceType: TaskTypeEnum?,       // wire value via .sourceKey (email, jira, git, ...)
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

#### Intelligent Task Routing (Async Fire-and-Forget)

The qualifier dispatches tasks to KB via `POST /ingest/full/async` (fire-and-forget, HTTP 202).
KB processes in background and calls `/internal/kb-done` with routing hints when finished.
`KbResultRouter` (server-side callback handler) receives `FullIngestResult` and makes routing decisions.

**Async flow:**
1. `SimpleQualifierAgent.dispatch()` — extracts text, loads attachments, submits to KB
2. KB returns HTTP 202 immediately — server qualification worker moves to next task
3. KB processes: attachments → RAG → LLM summary (parallel) → graph extraction (queued)
4. Progress events pushed via `POST /internal/kb-progress` (real-time UI updates)
5. On completion: KB POSTs `FullIngestResult` to `POST /internal/kb-done`
6. `KbResultRouter.routeTask()` — routing decision based on KB analysis result

**Progress steps (pushed from KB via callback):**
1. `start` — processing begins
2. `attachments` — attachment files processed (count metadata)
3. `content_ready` — combined content hashed (content_length, hash)
4. `hash_match` — content unchanged, RAG skipped (idempotent re-ingest)
   OR `purge` — content changed, old chunks deleted
5. `rag_start` + `llm_start` — RAG ingest + summary generation launched (parallel)
6. `rag_done` — RAG chunks stored (chunks count)
7. `llm_done` — LLM analysis complete (summary, entities, actionability, urgency)
8. `summary_done` — full metadata available

**Server timestamps:** Each progress event includes `epochMs` in metadata
(set by `NotificationRpcImpl` from server `Instant.now().toEpochMilli()`). The UI
uses these server-side timestamps for step timing display instead of client-side
`Clock.System`.

**Decision tree:**

```
KB ingest_full() returns routing hints (hasActionableContent, suggestedActions, ...)
  │
  ├─ Step 1: hasActionableContent=false
  │    → DONE (info_only — indexed, no action needed, terminal)
  │
  ├─ Step 2: No COMPLEX_ACTIONS in suggestedActions
  │    → handleSimpleAction():
  │       ├─ reply_email / answer_question → creates USER_TASK
  │       ├─ schedule_meeting → creates scheduled reminder (if deadline available)
  │       └─ acknowledge / forward_info → done (indexed only)
  │    → DONE (indexed + action handled locally, terminal)
  │
  ├─ Step 3: isAssignedToMe=true AND hasActionableContent=true
  │    → READY_FOR_GPU (immediate, high priority)
  │
  ├─ Step 4: hasFutureDeadline=true AND hasActionableContent=true
  │    ├─ deadline < scheduleLeadDays away → READY_FOR_GPU (too close, do now)
  │    └─ deadline >= scheduleLeadDays away → create SCHEDULED_TASK copy
  │         scheduledAt = deadline - scheduleLeadDays
  │         original task → DONE (indexed, terminal)
  │
  └─ Step 5: Complex actions (suggestedActions ∩ COMPLEX_ACTIONS ≠ ∅)
       → READY_FOR_GPU (needs orchestrator: decompose_issue, analyze_code,
                        create_application, review_code, design_architecture)
```

**Note:** No age-based filter — the LLM (`_generate_summary()`) decides actionability even for old content (forgotten tasks, open issues, etc.)

**Constants:**
- `SCHEDULE_LEAD_DAYS = 2` (configurable per client) — deadline scheduling threshold
- `COMPLEX_ACTIONS` = {decompose_issue, analyze_code, create_application, review_code, design_architecture}

**DONE (info_only or simple action handled — TERMINAL):**
- Document indexed and structured (Graph + RAG)
- No action items OR simple action handled by qualifier itself
- Simple informational content
- Routine updates (status change, minor commit)
- Never reset on restart — terminal state, unlike DISPATCHED_GPU

**READY_FOR_GPU (immediate orchestrator execution):**
- Assigned to the team/organization
- Deadline too close (within schedule lead days)
- Complex actions requiring orchestrator (coding, architecture, decomposition)
- Requires user action that can't be handled by simple agent

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

### Qualification Loop (CPU) — Fire-and-Forget Dispatch

- **Interval:** 30 seconds
- **Process:** Reads READY_FOR_QUALIFICATION tasks from MongoDB, ordered by `queuePosition ASC NULLS LAST, createdAt ASC`
- **Agents:** SimpleQualifierAgent dispatches to KB microservice (fire-and-forget)
- **Concurrency:** 1 (dispatch is fast — Tika extraction + HTTP POST, not blocking on KB)
- **Dispatch flow:** `setToQualifying()` (atomic claim) → `SimpleQualifierAgent.dispatch()` (text extraction, attachment loading, HTTP POST to `/ingest/full/async`) → returns immediately. Task stays in QUALIFYING until KB calls back.
- **Retry:** If KB is unreachable or rejects the request → return to queue with backoff. KB handles its own internal retry (Ollama busy, timeouts). When KB permanently fails, it calls `/internal/kb-done` with `status="error"` → server marks task as ERROR. Recovery: stuck QUALIFYING tasks (>10min without KB callback) are reset to READY_FOR_QUALIFICATION.
- **Priority:** Items with explicit `queuePosition` are processed first (set via UI reorder controls)
- **Completion callback:** KB POSTs to `/internal/kb-done` with `FullIngestResult` → `KbResultRouter.routeTask()` handles routing (DONE / READY_FOR_GPU / scheduled). Routing logic lives in `KbResultRouter`, not in the qualification loop.
- **Live progress:** KB pushes progress events via `POST /internal/kb-progress` → Kotlin handler saves to DB + emits to WebSocket (real-time). Pre-KB steps (agent_start, text_extracted, kb_accepted) emitted by `SimpleQualifierAgent.dispatch()`.
- **Persistent history:** Each progress step saved to `TaskDocument.qualificationSteps` via MongoDB `$push`. `qualificationStartedAt` set atomically in `setToQualifying()`.
- **UI:** `MainViewModel.qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>>` → `IndexingQueueScreen` shows live step/message per item in "KB zpracování" section.
- **Indexing Queue UI data source:** "KB zpracování" and "KB fronta" sections display data from the **KB write service SQLite extraction queue** (not MongoDB server tasks). `IndexingQueueRpcImpl` calls `KnowledgeServiceRestClient.getExtractionQueue()` → `GET /api/v1/queue` on KB write service.
- **Backend pagination:** `getPendingBackgroundTasksPaginated(limit, offset)` with DB skip/limit.

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
- **Stale recovery:** On pod startup, BACKGROUND tasks stuck in DISPATCHED_GPU/QUALIFYING for >10min are reset (FOREGROUND DISPATCHED_GPU tasks are completed, not stuck). DONE tasks are terminal — never reset. Migration: old BACKGROUND DISPATCHED_GPU tasks without orchestratorThreadId are migrated to DONE.

### KB Queue Count Fix (2026-02-23)

**Problem:** UI always showed "KB fronta 199" because:
1. Kotlin fetches max 200 items from Python KB queue (`limit=200`)
2. 1 item is `in_progress` and filtered out as matching QUALIFYING
3. 200 - 1 = 199

**Solution:** `IndexingQueueRpcImpl.collectPipelineTasks()` now uses `kbStats.pending` (real `COUNT(*)` from SQLite) for `kbWaitingTotalCount` when no search/client filter is active. With filters, falls back to `filteredKbWaiting.size` (correct for filtered subsets).

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
                    │                     │                    │
                    │                     │                    └── coding agent dispatched →
                    │                     │                        WAITING_FOR_AGENT → (watcher resumes) →
                    │                     │                        PYTHON_ORCHESTRATING (loop)
                    │                     └── new messages arrived? → READY_FOR_GPU (auto-requeue)
                    └── interrupted → USER_TASK → user responds → READY_FOR_GPU (loop)

Scheduled tasks:
NEW (scheduledAt set) → scheduler loop dispatches when scheduledAt <= now + 10min
    ├── one-shot: NEW → READY_FOR_QUALIFICATION (scheduledAt cleared)
    └── recurring (cron): original stays NEW (scheduledAt = next cron run),
                          execution copy → READY_FOR_QUALIFICATION

Idle review:
(no active tasks + brain configured) → IDLE_REVIEW task → READY_FOR_GPU → PYTHON_ORCHESTRATING → DONE
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

### Idle Review Loop (Brain)

- **Interval:** Configurable via `BackgroundProperties.idleReviewInterval` (default 30 min)
- **Enabled:** `BackgroundProperties.idleReviewEnabled` (default true)
- **Preconditions:** Brain configured (SystemConfig), no active tasks, no existing IDLE_REVIEW task
- **Creates:** `IDLE_REVIEW` task with state `READY_FOR_GPU` (skips qualification)
- **Orchestrator prompt:** Review open issues, check deadlines, update Confluence summaries
- **Task type:** `TaskTypeEnum.IDLE_REVIEW`
- **Client resolution:** Uses JERVIS Internal project's client ID

### Cross-Project Aggregation (Qualifier → Brain)

After successful KB ingest with `hasActionableContent = true`, `SimpleQualifierAgent` writes findings to brain Jira:

- **Deduplication:** JQL search for `labels = "corr:{correlationId}"` before creating
- **Labels:** `auto-ingest`, source type, `corr:{correlationId}`
- **Non-critical:** Failure logged but does not block qualification or routing
- **Brain service:** Uses `BrainWriteService.createIssue()` (reads SystemConfig for connection)

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
┌─────────────────────────────────────────────────────────────────────────────────┐
│  Ollama Router (K8s pod, :11430)   Python FastAPI                              │
│  • Priority routing (CRITICAL / NORMAL)                                         │
│  • VRAM priority (bigger model = higher VRAM priority)                          │
│  • Multi-GPU pool with per-GPU reservations                                     │
│  • Preemption (background → CPU on orchestrator request)                       │
│  • Auto-reservation (60s idle timeout, no announce/release API)                │
│  └──────┬──────────────────────────┬──────────────────────┬────────────────────│
│         │                          │                      │                    │
└─────────┼──────────────────────────┼──────────────────────┼────────────────────┘
          │                          │                      │
   ┌──────▼──────────────────┐ ┌─────▼────────────────┐ ┌──▼───────────────────────┐
   │ GPU_BACKENDS[0]         │ │ GPU_BACKENDS[1]       │ │ CPU_BACKEND_URL          │
   │ P40 24GB VRAM           │ │ P40 24GB VRAM         │ │ Fallback for preempted   │
   │                         │ │                       │ │                          │
   │ 30b + embedding:        │ │ 30b + embedding:      │ │ OLLAMA_NUM_PARALLEL=10   │
   │   qwen3-coder-tool:30b  │ │   qwen3-coder-tool:30b│ │ OLLAMA_NUM_THREADS=18    │
   │   qwen3-embedding:8b    │ │   qwen3-embedding:8b  │ │ OLLAMA_MAX_LOADED_MODELS=3│
   │                         │ │                       │ │ embed:8b (fallback only) │
   │ Concurrent CRITICAL #1  │ │ Concurrent CRITICAL #2│ │                          │
   │ or NORMAL when free     │ │ or NORMAL when free   │ │ Always available fallback│
   └──────────────────────────┘ └───────────────────────┘ └──────────────────────────┘
```

### Priority Levels (2 levels)

| Priority | Value | Source | Behavior |
|----------|-------|--------|----------|
| CRITICAL | 0 | Orchestrator FOREGROUND, jervis_mcp | Preempts non-critical, always GPU, auto-reserves |
| NORMAL | 1 | Everything else (correction, KB simple ingest, background tasks) | GPU when free, CPU fallback |

> Priority is set via `X-Ollama-Priority: 0` header for CRITICAL. No header = NORMAL (router default). Model name no longer determines priority.
>
> **Orchestrator processing_mode**: FOREGROUND tasks send `X-Ollama-Priority: 0` on all sub-calls (KB, tools). BACKGROUND tasks send no header (NORMAL).

### Model Co-location on GPU

KB extraction uses :30b (same model as orchestrator). CRITICAL extraction shares the GPU model — no swap needed. NORMAL extraction also uses :30b but routes to CPU when GPU is reserved.

| Model | VRAM Est. | Location | Purpose |
|-------|-----------|----------|---------|
| qwen3-coder-tool:30b | ~25GB | GPU (VRAM priority) | All LLM tasks (orchestrator, KB extraction, ingest simple+complex) |
| qwen3-embedding:8b | ~5GB | GPU (alongside 30b) | KB embeddings |
| **GPU Total** | **~30GB** | Some CPU offload | Acceptable performance, no model swapping |

### Auto-Reservation Protocol (no announce/release API)

GPU reservation is fully automatic — the router self-manages based on CRITICAL request activity:

```
CRITICAL request arrives → Router auto-reserves GPU, loads :30b, resets 60s timer
More CRITICAL requests  → Router routes to reserved GPU, resets 60s timer each time
60s without CRITICAL    → Watchdog auto-releases reservation, loads background set
```

No orchestrator announce/release calls needed. The router tracks `last_critical_activity` per GPU and the watchdog runs every 15s to check for idle reservations (60s timeout, 10min absolute max).

### Multi-GPU Routing (Per-GPU Reservations)

Router manages a pool of GPU backends (`GPU_BACKENDS` JSON env var). Reservations are **per-GPU**, supporting multiple concurrent CRITICAL sessions.

**Current setup (2× P40, configured via K8s ConfigMap):**
```
p40-1 (GPU_BACKENDS[0]):  :30b reserved for CRITICAL #1
p40-2 (GPU_BACKENDS[1]):  :30b reserved for CRITICAL #2 or NORMAL
CPU   (CPU_BACKEND_URL):  fallback for overflow
```
- Single CRITICAL → auto-reserves one GPU, other GPU serves NORMAL
- Two concurrent CRITICALs → each GPU serves one CRITICAL session
- All GPUs reserved → NORMAL to CPU
- NORMAL → prefer unreserved GPU, then CPU

Adding/removing a GPU = config change only (`GPU_BACKENDS` env var), no code change.

### Routing Algorithm with VRAM Priority

The router's `_do_route()` function routes requests based on model size priority:

**Step 1-4:** Check CRITICAL priority, find GPU with model, check free VRAM (standard routing).

**Step 5: VRAM priority model management**

```python
if requested_vram > current_max_vram:
    # Bigger model arriving → VRAM priority
    gpu.loading_in_progress = True  # block other requests during swap
    unload_all(gpu)                 # waits for active requests first
    load_model(gpu, big_model)      # load biggest first
    for prev in previous_models:    # reload previous alongside
        load_model(gpu, prev)       # (sorted by size desc)
    gpu.loading_in_progress = False
else:
    # Smaller model → load alongside existing (Ollama CPU offload)
    load_model(gpu, model)
```

**Key behaviors:**
- `loading_in_progress` flag: during model swap, all find methods skip this GPU → other requests go to CPU
- `unload_all()` waits up to 60s for active requests to complete before unloading
- After swap, all models co-locate on GPU (Ollama handles CPU offload for overflow layers)
- No model thrashing: smaller models never unload bigger ones

**Implementation:** `backend/service-ollama-router/app/router_core.py:_do_route()`, `backend/service-ollama-router/app/gpu_state.py`

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
- Priority headers: `backend/service-orchestrator/app/graph/nodes/_helpers.py` (`priority_headers()`)
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
| Chat History | MongoDB `chat_messages` + `ChatSummaryDocument` | Permanent (compressed) |
| Task Outcomes | KB via `task-outcome:{taskId}` sourceUrn | Permanent |
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

## Autonomous Pipeline Components (EPIC 2-5, 7-10, 14)

### Enhanced Qualifier Output (EPIC 2-S1)

The qualifier now produces structured fields for pipeline routing:

```
FullIngestResult (KB service output)
├── actionType: String?      → CODE_FIX, CODE_REVIEW, RESPOND_EMAIL, etc.
├── estimatedComplexity: String?  → TRIVIAL, SIMPLE, MEDIUM, COMPLEX
├── suggestedAgent: String?  → CODING, ORCHESTRATOR, NONE
├── affectedFiles: List<String>
└── relatedKbNodes: List<String>
```

DTOs: `shared/common-dto/.../pipeline/PipelineDtos.kt`

### Auto-Task Creation (EPIC 2-S2)

`AutoTaskCreationService` creates tasks from qualifier findings:
- `CODE_FIX + SIMPLE` → BACKGROUND task (auto-dispatch)
- `CODE_FIX + COMPLEX` → USER_TASK (needs plan approval)
- `RESPOND_EMAIL` → USER_TASK (draft for approval)
- `INVESTIGATE` → BACKGROUND task
- Deduplication via `correlationId`

Source: `backend/server/.../service/background/AutoTaskCreationService.kt`

### Priority-Based Scheduling (EPIC 2-S3)

Tasks ordered by `priorityScore DESC, createdAt ASC` instead of FIFO.
`TaskPriorityCalculator` assigns 0-100 scores based on urgency, deadline, security keywords.

Source: `backend/server/.../service/background/TaskPriorityCalculator.kt`

### Planning Phase (EPIC 2-S4)

Background handler now runs an LLM planning phase before the agentic loop:
1. Analyze task + guidelines → structured JSON plan
2. Plan injected into conversation context
3. Guides agentic loop execution

Source: `backend/service-orchestrator/app/background/handler.py`

### Code Review Pipeline (EPIC 3)

After coding agent dispatch, runs 2-phase review:
1. **Static analysis**: Forbidden patterns, credentials scan, file restrictions
2. **LLM review**: Independent reviewer (doesn't see coding agent reasoning)
3. Verdict: APPROVE → continue, REQUEST_CHANGES → re-dispatch, REJECT → USER_TASK

Source: `backend/service-orchestrator/app/review/review_engine.py`

### Universal Approval Gate (EPIC 4)

Every write action passes through `ApprovalGate.evaluate()`:
- Checks guidelines approval rules (per-action auto-approve settings)
- CRITICAL risk → always NEEDS_APPROVAL
- DEPLOY / KB_DELETE → always NEEDS_APPROVAL
- Wired into `execute_tool()` in Python executor

Sources:
- `backend/service-orchestrator/app/review/approval_gate.py`
- `backend/service-orchestrator/app/tools/executor.py` (approval gate integration)

### Action Execution Engine (EPIC 5)

`ActionExecutorService` in Kotlin server routes approved actions:
- Evaluates approval via `GuidelinesService.getMergedGuidelines()`
- AUTO_APPROVED → dispatch to backend service
- NEEDS_APPROVAL → emit `ApprovalRequired` event, create USER_TASK
- DENIED → reject and log

Sources:
- `backend/server/.../service/action/ActionExecutorService.kt`
- `shared/common-dto/.../pipeline/ApprovalActionDtos.kt`

### Anti-Hallucination Guard (EPIC 14)

Post-processing pipeline for fact-checking LLM responses:
- Extract claims (file paths, URLs, code references, API endpoints)
- Verify against KB and git workspace
- Status: VERIFIED / UNVERIFIED / CONTRADICTED
- Overall confidence score
- **Contradiction detector** in KB write path — prevents conflicting info from accumulating

Sources:
- `backend/service-orchestrator/app/guard/fact_checker.py`
- `backend/service-orchestrator/app/guard/contradiction_detector.py`

### Code Review Re-dispatch Loop (EPIC 3-S2)

When code review returns REQUEST_CHANGES, background handler runs a re-dispatch loop (max 2 rounds):
1. Inject review feedback into coding agent context
2. Run short agentic sub-loop (max 3 iterations)
3. Re-run code review on updated code
4. If 2nd round still fails → escalate to USER_TASK

Source: `backend/service-orchestrator/app/background/handler.py`

### Batch Approval & Analytics (EPIC 4-S4/S5)

- `executeBatch()` — groups requests by action type, evaluates once per type
- `recordApprovalDecision()` / `getApprovalStats()` — in-memory approval statistics
- `shouldSuggestAutoApprove()` — suggests auto-approve when ≥10 approvals with 0 denials

Source: `backend/server/.../service/action/ActionExecutorService.kt`

### Action Memory (EPIC 9-S4)

Records completed actions to KB as `action_log` category:
- Background task completions, coding dispatches, code reviews, deployments
- `query_action_log` chat tool for "what did you do last week?" queries
- Enables learning from past actions

Sources:
- `backend/service-orchestrator/app/memory/action_log.py`
- Chat tool: `query_action_log` in `app/chat/tools.py`

### Dynamic Filtering Chat Tools (EPIC 10-S2)

3 chat tools for managing filtering rules via conversation:
- `set_filter_rule` — create filter (e.g., "ignoruj emaily od noreply@")
- `list_filter_rules` — show active rules
- `remove_filter_rule` — delete rule by ID
- Intent patterns: filtr, ignoruj, pravidlo, blokuj, etc.
- FILTERING tool category with dedicated intent classifier

Sources:
- Chat tools: `app/chat/tools.py` (TOOL_SET_FILTER_RULE, etc.)
- Handlers: `app/chat/handler_tools.py`
- Kotlin API: `app/tools/kotlin_client.py` (set_filter_rule, list_filter_rules, remove_filter_rule)
- Internal API: `backend/server/.../rpc/internal/InternalFilterRulesRouting.kt`

### Foundation DTOs (EPICs 7-13, 16-17)

| EPIC | DTO Package | Key Types |
|------|------------|-----------|
| 7 KB Maintenance | `dto.maintenance` | IdleTaskType, VulnerabilityFinding, KbConsistencyFinding |
| 8 Deadline | `dto.deadline` | DeadlineItem, DeadlineUrgency, DeadlinePreparationPlan |
| 9 Chat Intelligence | `dto.chat` | ConversationTopic, TopicSummary, ActionLogEntry |
| 10 Filtering | `dto.filtering` | FilteringRule, FilterAction, FilterConditionType |
| 11-12 Integrations | `dto.integration` | ExternalChatMessage, CalendarEvent, AvailabilityInfo |
| 13 Self-Evolution | `dto.selfevolution` | PromptSection, LearnedBehavior, UserCorrection |
| 14 Anti-Hallucination | `dto.guard` | FactClaim, SourceAttribution, ResponseConfidence |
| 16 Brain Workflow | `dto.brain` | BrainIssueType, DailyReport |
| 17 Environment | `dto.environment` | EnvironmentAgentRequest, DeploymentValidationResult |

### Services

| Service | EPIC | Source |
|---------|------|--------|
| IdleTaskRegistry | 7-S1 | `backend/server/.../service/maintenance/IdleTaskRegistry.kt` |
| VulnerabilityScannerService | 7-S2 | `backend/server/.../service/maintenance/VulnerabilityScannerService.kt` |
| KbConsistencyCheckerService | 7-S3 | `backend/server/.../service/maintenance/KbConsistencyCheckerService.kt` |
| LearningEngineService | 7-S4 | `backend/server/.../service/maintenance/LearningEngineService.kt` |
| DocFreshnessService | 7-S5 | `backend/server/.../service/maintenance/DocFreshnessService.kt` |
| DeadlineTrackerService | 8-S1/S2 | `backend/server/.../service/deadline/DeadlineTrackerService.kt` |
| ProactivePreparationService | 8-S3 | `backend/server/.../service/deadline/ProactivePreparationService.kt` |
| FilteringRulesService | 10-S1 | `backend/server/.../service/filtering/FilteringRulesService.kt` |
| TopicTracker | 9-S1 | `backend/service-orchestrator/app/chat/topic_tracker.py` |
| MemoryConsolidation | 9-S2 | `backend/service-orchestrator/app/memory/consolidation.py` |
| IntentDecomposer | 9-S3 | `backend/service-orchestrator/app/chat/intent_decomposer.py` |
| SourceAttribution | 14-S2 | `backend/service-orchestrator/app/chat/source_attribution.py` |
| ApprovalQueueDocument | 4-S3 | `backend/server/.../entity/ApprovalQueueDocument.kt` |
| ApprovalStatisticsDocument | 4-S5 | `backend/server/.../entity/ApprovalStatisticsDocument.kt` |
| ChatContinuousIndexer | 11-S4 | `backend/server/.../integration/chat/ChatContinuousIndexer.kt` |
| ChatReplyService | 11-S5 | `backend/server/.../integration/chat/ChatReplyService.kt` |
| CalendarService | 12-S1–S5 | `backend/server/.../service/calendar/CalendarService.kt` |
| CalendarIntegration | 12-S2/S5 | `backend/service-orchestrator/app/calendar/calendar_integration.py` |
| PromptEvolutionService | 13-S1–S4 | `backend/server/.../service/selfevolution/PromptEvolutionService.kt` |
| BehaviorLearning | 13-S2 | `backend/service-orchestrator/app/selfevolution/behavior_learning.py` |
| UserCorrections | 13-S3 | `backend/service-orchestrator/app/selfevolution/user_corrections.py` |
| BrainWorkflowService | 16-S1/S2 | `backend/server/.../service/brain/BrainWorkflowService.kt` |
| EnvironmentAgentService | 17-S1–S3 | `backend/server/.../service/environment/EnvironmentAgentService.kt` |

---

**Document Version:** 12.0
**Last Updated:** 2026-02-26
