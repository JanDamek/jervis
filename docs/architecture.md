# Architecture - Complete System Overview

**Status:** Production Documentation (2026-02-25)
**Purpose:** Comprehensive architecture guide for all major components and frameworks

---

## Table of Contents

1. [Framework Overview](#framework-overview)
2. [Workspace & Directory Architecture](#workspace--directory-architecture)
3. [GPU Routing & Ollama Router](#gpu-routing--ollama-router)
4. [Kotlin RPC (kRPC) Architecture](#kotlin-rpc-krpc-architecture)
5. [Email Intelligence & Multi-Client Qualification](#email-intelligence--multi-client-qualification)
6. [Polling & Indexing Pipeline](#polling--indexing-pipeline)
6. [Knowledge Graph Design](#knowledge-graph-design)
7. [Vision Processing Pipeline](#vision-processing-pipeline)
8. [Transcript Correction Pipeline](#transcript-correction-pipeline)
9. [Smart Model Selector](#smart-model-selector)
10. [Security Architecture](#security-architecture)
11. [Resilience Patterns](#resilience-patterns)
12. [Coding Agents (Claude + KILO)](#coding-agents)
13. [Unified Agent (Python)](#unified-agent-python)
14. [Dual-Queue System & Inline Message Delivery](#dual-queue-system--inline-message-delivery)
15. [Notification System](#notification-system)
16. [Foreground Chat (ChatSession)](#foreground-chat-chatsession)
17. [Guidelines Engine](#guidelines-engine)
18. [Chat Router](#chat-router)
19. [Hierarchical Task System](#hierarchical-task-system)
20. [Unified Chat Stream](#unified-chat-stream)
21. [Watch Apps (watchOS + Wear OS)](#watch-apps-watchos--wear-os)
22. [TTS Service (Piper)](#tts-service-piper)
23. [Thought Map (Navigation Layer)](#thought-map-navigation-layer)

---

## Framework Overview

The Jervis system is built on several key architectural patterns:

- **Unified Agent (LangGraph)**: ONE agent for all interactions — chat (foreground) and background tasks. Paměťový graf (Memory Graph) + Myšlenkový graf (Thinking Graph). See [graph-agent-architecture.md](graph-agent-architecture.md)
- **TaskQualificationService**: Dispatches tasks to KB microservice for indexing. After KB callback, saves kbSummary/kbEntities/kbActionable to TaskDocument and routes directly to QUEUED or DONE.
- **Kotlin RPC (kRPC)**: Type-safe, cross-platform messaging framework for client-server communication
- **3-Stage Polling Pipeline**: Polling → Indexing → KB Processing → QUEUED/DONE
- **Knowledge Graph (ArangoDB)**: Centralized structured relationships between all entities
- **Vision Processing**: Two-stage vision analysis for document understanding

---

## Workspace & Directory Architecture

### Fundamental Principle

**DirectoryStructureService is the SINGLE SOURCE OF TRUTH for all file system paths.**

- NO hardcoded paths anywhere in the application
- ALL path resolution goes through DirectoryStructureService
- Future refactoring = change only DirectoryStructureService

### Workspace Structure

```
{data}/clients/{clientId}/projects/{projectId}/
├── git/
│   └── {resourceId}/          ← AGENT/ORCHESTRATOR WORKSPACE (main working directory)
│       ├── .git/
│       ├── src/
│       └── ... (full repo checkout)
├── git-indexing/               ← INDEXING TEMPORARY WORKSPACE (new)
│   └── {resourceId}/
│       └── ... (checkout branches/commits for indexing)
├── uploads/
├── audio/
├── documents/
└── meetings/
```

### Critical Distinction

**`git/{resourceId}/` - Agent/Orchestrator Workspace**
- Single clone of repository at default branch
- Orchestrator and agents work ONLY in this directory
- MUST remain stable - no branch/commit changes during agent operation
- Used by: Python orchestrator tools (git_status, read_file, execute_command, etc.)

**`git-indexing/{resourceId}/` - Indexing Temporary Workspace**
- Separate clone where indexing can freely checkout branches/commits
- Used ONLY by background indexing to analyze code
- Can be reset/recreated at any time
- Indexing NEVER modifies agent workspace

### Why Separation?

**Problem:** If indexing and agents share workspace:
- Indexing checks out branch A → agent sees wrong code
- Agent working on files → indexing checkout conflicts
- Race conditions between concurrent operations

**Solution:** Two independent clones:
- Agent workspace stays at HEAD of default branch
- Indexing workspace can freely navigate history

### Implementation Rules

1. **DirectoryStructureService manages ALL paths:**
   ```kotlin
   fun projectGitDir(clientId, projectId): Path           // Agent workspace
   fun projectGitIndexingDir(clientId, projectId): Path   // Indexing workspace
   ```

2. **GitRepositoryService (indexing):**
   - Uses `projectGitIndexingDir()` for analysis
   - Can checkout any branch/commit
   - Never touches agent workspace

3. **BackgroundEngine (agent workspace):**
   - Manages `projectGitDir()` clone lifecycle via `initializeProjectWorkspace()`
   - Tracks workspace status in DB (`WorkspaceStatus`: CLONING, READY, CLONE_FAILED_*)
   - Listens to `ProjectWorkspaceInitEvent` for on-demand initialization
   - Used by orchestrator pre-dispatch validation

4. **Python Orchestrator Tools:**
   - All git/FS/terminal tools work in `projectGitDir()` only
   - Receive workspace path from Kotlin server
   - Never construct paths themselves

### Startup Flow

1. **DirectoryStructureService** creates directory structure
2. **BackgroundEngine.initializeAllProjectWorkspaces()** (background, async):
   - For each **active** project with REPOSITORY resources (`findByActiveTrue()`):
     - `null` status → trigger clone to `git/{resourceId}/`
     - `READY` → **verify `.git` exists on disk** (files may be gone after pod restart/PVC loss); if missing → reset status to null and re-clone
     - `CLONING` → skip (in progress)
     - `CLONE_FAILED_AUTH`/`CLONE_FAILED_NOT_FOUND` → skip (needs user fix)
     - `CLONE_FAILED_NETWORK`/`CLONE_FAILED_OTHER` → respect backoff, retry when elapsed
     - `NOT_NEEDED` → skip
   - Updates `workspaceStatus` field in ProjectDocument
3. **GitRepositoryService.syncAllOnStartup()** (indexing only):
   - Clone to `git-indexing/{resourceId}/` for KB indexing
   - Index commits, branches, files
   - Does NOT touch agent workspaces (managed by BackgroundEngine)

### Pre-Dispatch Validation

Before orchestrator dispatch, `AgentOrchestratorService` checks workspace status:
```kotlin
val workspace = project.workspaceStatus
if (workspace != WorkspaceStatus.READY) {
    // Return user-friendly message based on status
}
```

---

## GPU Routing & Ollama Router

### Overview

**Ollama Router** is a transparent proxy service that routes LLM requests across GPU backends (p40-1: LLM 30b, p40-2: embedding + extraction 8b/14b + VLM + whisper) based on priority, capability, and `GPU_MODEL_SETS`. No CPU backend — all inference on GPU only.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     All LLM Requests                             │
│  (Orchestrator, KB, Correction Agent)                           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
                ┌───────────────────────┐
                │   Ollama Router       │
                │   (port 11430)        │
                │                       │
                │ • Priority routing    │
                │ • Capability routing  │
                │ • Per-type concurrency│
                │ • Request queuing     │
                └───────────┬───────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
    ┌─────────────────────┐  ┌──────────────────────────────┐
    │ p40-1 (P40 24GB)    │  │ p40-2 (P40 24GB)             │
    │                     │  │                              │
    │ qwen3-coder-tool:30b│  │ Permanent (22.5GB):          │
    │ (18.5GB)            │  │  qwen3-embedding:8b (5.5GB)  │
    │                     │  │  qwen3:8b (6.0GB)            │
    │ Orchestrator, chat, │  │  qwen3:14b (11.0GB)          │
    │ coding              │  │ On-demand:                    │
    │                     │  │  qwen3-vl-tool (8.8GB)       │
    │                     │  │  + Whisper GPU (3-6GB)        │
    └─────────────────────┘  └──────────────────────────────┘
```

### Priority Levels (2 levels)

| Priority | Value | Header | Source | Behavior |
|----------|-------|--------|--------|----------|
| CRITICAL | 0 | `X-Ollama-Priority: 0` | Orchestrator FOREGROUND, jervis_mcp | Always GPU, auto-reserves, preempts NORMAL |
| NORMAL | 1 | No header (default) | Correction, KB ingest, background tasks | GPU when free, waits in queue |

- Priority set via `X-Ollama-Priority` header. No header = NORMAL.
- Orchestrator `processing_mode`: FOREGROUND sends `X-Ollama-Priority: 0` on all sub-calls (KB, tools). BACKGROUND sends no header.

### Auto-Reservation Protocol

GPU reservation is fully automatic — no announce/release API:

```
CRITICAL request arrives → Router auto-reserves GPU, loads :30b, resets 60s timer
More CRITICAL requests  → Routes to reserved GPU, resets 60s timer each time
60s without CRITICAL    → Watchdog auto-releases, loads background model set
Next CRITICAL request   → Re-reserves GPU automatically
```

Watchdog runs every 15s, checks `last_critical_activity` per GPU. Limits: 60s idle timeout, 10min absolute max.

### Request Flow

```python
# All services call Ollama Router at :11430
OLLAMA_API_BASE = "http://jervis-ollama-router:11430"

# CRITICAL: header present → GPU (auto-reserve)
headers = {"X-Ollama-Priority": "0"}  # FOREGROUND tasks

# NORMAL: no header → GPU (waits in queue, never 429)
headers = {}  # BACKGROUND tasks, correction, KB ingest
```

### Configuration

All services use Ollama Router (K8s service `jervis-ollama-router:11430`):

- **Orchestrator**: `OLLAMA_API_BASE=http://jervis-ollama-router:11430`
- **KB (read/write)**: `OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_BASE_URL`, `OLLAMA_INGEST_BASE_URL` all → `http://jervis-ollama-router:11430`
- **Correction**: `OLLAMA_BASE_URL=http://jervis-ollama-router:11430`

### Key Features

- **Transparent proxy** - Services call router like standard Ollama
- **2-level priority** - CRITICAL gets guaranteed GPU, NORMAL waits in unlimited queue
- **Auto-reservation** - GPU reserved/released automatically based on CRITICAL activity
- **Model management** - Auto-loads/unloads model sets per GPU_MODEL_SETS
- **Per-type concurrency** - embedding concurrent=5 on p40-2, LLM serial=1 per GPU
- **Capability routing** - vision capability routes to qwen3-vl-tool on p40-2, extraction to qwen3:8b
- **GPU error retry** - on model load failure or connect error, retries with backoff (5s, 15s, 30s, 60s) — NEVER returns 503 to client

### Deployment

- K8s deployment: `k8s/app_ollama_router.yaml`
- ConfigMap: `k8s/configmap.yaml` (`jervis-config` — unified Python ConfigMap)
- Build script: `k8s/build_ollama_router.sh`
- ClusterIP service (no hostNetwork, no hostPort)

---

## Kotlin RPC (kRPC) Architecture

### Overview

The Jervis system uses Kotlin RPC (kRPC) for type-safe, cross-platform communication between UI and backend server.

### Communication Contract

- **UI ↔ Server**: ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Email Intelligence & Multi-Client Qualification

### Overview
Email processing uses a fully LLM-driven qualification pipeline — no hardcoded content-type
handlers. The qualification agent (LLM with CORE tools) analyzes ALL content types generically
and decides what to do based on KB conventions, cross-source matching, and reasoning.

```
Email → KB Indexing → ClientResolver → QualificationAgent (LLM + tools)
```

### Design Principle

**NO content-type-specific code.** The LLM model decides what type of content it is
(invoice, job offer, medical report, contract, anything) and how to handle it.
Business rules (e.g., "newsletters = auto-DONE", "invoices from X = urgent") are stored
in KB as conventions, not hardcoded in code.

### Components

| File | Purpose |
|------|---------|
| `backend/server/.../email/ClientResolver.kt` | Multi-step client resolution (sender mapping → domain mapping → thread history → fallback) |
| `backend/service-orchestrator/app/unified/qualification_handler.py` | Generic LLM qualification agent — analyzes any content type, uses KB conventions and tools |

### Qualification Flow
1. KB indexes email content (text extraction, entity recognition, summary)
2. Kotlin dispatches to Python `/qualify` endpoint with KB results
3. Qualification agent checks KB conventions first (`kb_search "conventions rules"`)
4. Agent performs cross-source matching (find related KB entities)
5. Agent decides: DONE / QUEUED / URGENT_ALERT / CONSOLIDATE + priority score
6. Agent calls back to Kotlin `/internal/qualification-done` with decision

### Client Resolution Pipeline
1. **Sender mapping** (exact + pattern): `ConnectionDocument.senderClientMappings`
2. **Domain mapping**: `ConnectionDocument.domainClientMappings`
3. **Thread history**: same thread → same client
4. **Fallback**: connection's default client

### UI
Connection edit dialog shows sender/domain → client ID mapping editor for email connections.

---

## Urgency / Deadline Scheduling

**Module:** `com.jervis.urgency` (backend/server) + `backend/service-ollama-router` + orchestrator `graph/nodes`.
**Canonical SSOT:** KB `agent://claude-code/task-routing-unified-design`.

### Prime directive — deadline is the ONLY urgency signal crossing service boundaries

A single `deadline: Instant?` field on `TaskDocument` drives urgency end-to-end.
There is **no** `Speed`/`Priority`/`Bucket` enum travelling through DTOs, RPC,
or HTTP. The router derives a private `_Bucket` (REALTIME / URGENT / NORMAL /
BATCH) from `(deadline - now, priority)` at decision time — that enum never
leaves `router_core.py`.

Changing `task.deadline` (e.g. via `bump_task_deadline` tool) takes effect on
the next router call with zero cache invalidation — the bucket is recomputed.

### TaskDocument urgency fields

- `deadline: Instant?` — absolute response-by time. Null = no urgency pressure (BATCH).
- `userPresence: String?` — observed presence at creation (ACTIVE_CONVERSATION / LIKELY_WAITING / RECENTLY_ACTIVE / AWAY / OFFLINE / UNKNOWN).
- `capability: String` — router queue + model selection (chat | thinking | coding | extraction | embedding | visual). Default "chat".
- `tier: String?` — snapshot of max cloud tier at creation (NONE | FREE | PAID | PREMIUM). Null = resolve from clientId.
- `minModelSize: Int` — minimum local model size in billions (0 | 14 | 30 | 120). 0 = any.

### Layers

1. **Inbound handlers** (`SlackContinuousIndexer`, `TeamsContinuousIndexer`, `DiscordContinuousIndexer`) — call `StructuralUrgencyDetector.decide(signal, config, presence)` and write `deadline + userPresence` onto the new TaskDocument. No cloud/local decision here.
2. **StructuralUrgencyDetector** — pure function mapping inbound structural signal (DM / @mention / reply-to-my-thread) → deadline derived from `UrgencyConfigDocument.fastPath*` fields, multiplied by the `presenceFactor` for the observed presence. Ambiguous messages return `deadline = null`.
3. **PresenceCache** — in-memory per-platform store; TTL from `presenceTtlSeconds`. Real platform subscriptions (Slack Events, Graph `/me/presence`, Discord gateway) are a separate workstream — current impl returns `UNKNOWN` on miss.
4. **Scheduler** — `TaskService.getNextBackgroundTask()` → `TaskRepository.findByProcessingModeAndStateOrderByDeadlineAscPriorityScoreDescCreatedAtAsc`. BACKGROUND tasks served nearest-deadline first, null deadlines sort last, `priorityScore DESC` breaks ties. **No watchdog / priority-bump background loop — sort key does the work.**
5. **Sidebar / pending-tasks list** — `TaskService.getPendingBackgroundTasksPaginated` + `PendingTaskService.listPagedPendingTasks` both use `$ifNull`-coerced aggregation (missing deadline → far-future) so ASC sort puts due tasks first and tasks without deadline at the bottom.
6. **Orchestrator dispatch** (Kotlin → Python) — `OrchestrateRequestDto` carries `deadline_iso`, `priority`, `capability`, `tier`, `min_model_size` onto `CodingTask`.
7. **Graph nodes** (`_helpers.py::llm_with_cloud_fallback`) — forward `deadline_iso` + `priority` + `min_model_size` to `route_request()`.
8. **Router unified entrypoint** — `/api/chat`, `/api/generate`, `/api/embed` + headers `X-Deadline-Iso`, `X-Capability`, `X-Client-Id`, `X-Min-Model-Size`, `X-Priority`. The router runs `decide_and_dispatch` in one pass (no `/route-decision` → `/api/*` round-trip). `X-Priority: CASCADE` on `/api/generate` selects the latency-optimized cascade path; `/api/cascade` is deprecated.

### Router decision rules (applied in order)

```
tier == NONE                 → local only
capability == embedding      → local only (data locality)
capability == vlm + GPU blocked (whisper / loading / in-flight) → cloud preferred
bucket == REALTIME           → cloud PREMIUM/PAID, else local with CASCADE preempt
bucket == URGENT             → cloud preferred, local fallback
bucket == NORMAL             → local if GPU free + ctx ≤ 48k, cloud on busy/oversize
bucket == BATCH              → local only; cloud only when no local model fits the capability
```

### Expired deadlines, config RPC

- **Expired deadlines** — the task still wins scheduling race (earliest deadline) and runs; orchestrator logs a warning. No separate cleanup job.
- **Config RPC** — `IUrgencyConfigRpc` (`getUrgencyConfig` / `updateUrgencyConfig` / `getUserPresence`) — consumed by UI Settings tab "Urgency & Deadliny" and by orchestrator tools `get_urgency_config` / `update_urgency_config` / `bump_task_deadline` / `get_user_presence`.

Superseded briefs (kept for history): `agent://claude-code/urgency-deadline-presence-design`, `agent://claude-code/router-escalation-unification-brief`.

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

#### Resource Filtering (Project-level)

Polling uses `project.resources` to determine which external resources belong to each project:

- **Project with resources:** Only polls resources matching `connectionId + capability + resourceIdentifier`
- **Project without resources:** Skips polling (does NOT index everything)
- **Client-level:** Skips if any project already claims resources for that connection+capability
- **Processing order:** Projects first, then clients (prevents duplicate indexing)

This ensures e.g. `JanDamek/jervis` issues are indexed under the correct project, not under a different client's project that shares the same GitHub connection.

#### MR/PR Indexing (MergeRequestContinuousIndexer)

Separate polling loop (120s interval) for merge request / pull request discovery:

1. **Discovery:** Iterates all **active** projects with REPOSITORY resources (`findByActiveTrue()`) → calls GitLab `listOpenMergeRequests` / GitHub `listOpenPullRequests` → deduplicates against `merge_requests` MongoDB collection → saves NEW documents
   - **Filters:** Skips draft MRs/PRs (not ready for review), skips `jervis/*` branches (handled by AgentTaskWatcher to avoid review loops)
   - **Author extraction:** Extracts author name/username from API response (GitLab `author.name`, GitHub `user.login`)
2. **Task creation (15s):** Picks up NEW MR documents → creates `TaskTypeEnum.SYSTEM` task in QUEUED state with sourceUrn scheme `merge-request` (bypasses KB indexation — MR content IS the task) → marks REVIEW_DISPATCHED
3. **Orchestrator:** Graph agent picks up task, reasons about code review scope, can delegate to coding agent or use tools directly

**Key files:** `MergeRequestContinuousIndexer.kt`, `MergeRequestDocument.kt`, `MergeRequestRepository.kt`

**Two MR review paths:**
- **External MRs** (human-created): MergeRequestContinuousIndexer → graph agent → review
- **Jervis MRs** (coding agent): AgentTaskWatcher → `code_review_handler.py` → review coding agent K8s Job

#### Email Thread Consolidation

Email indexer (`EmailContinuousIndexer`) is thread-aware — consolidates related emails into a single task:

1. **SENT folder polling:** `ImapPollingHandler` auto-includes SENT folder alongside INBOX for full conversation context
2. **Thread detection:** RFC 2822 `In-Reply-To`/`References` headers → `EmailMessageIndexDocument.computeThreadId()`
3. **Direction detection:** Folder name → `EmailDirection.SENT` vs `RECEIVED`
4. **Consolidation logic (`EmailThreadService.analyzeThread()`):**
   - SENT email → KB only (attachments indexed), no task created
   - Incoming + user already replied → auto-resolve existing USER_TASK → DONE
   - Incoming + existing task for thread → update content + bump `lastActivityAt`
   - Incoming + new conversation → create task with `topicId = "email-thread:<threadId>"`
5. **topicId:** General-purpose grouping field on `TaskDocument` — works across all sources (email, MR, Slack, Teams). Format: `email-thread:<id>`, `mr:<projectId>:<mrId>`, `slack:<channelId>:<threadTs>`

**Key files:** `EmailThreadService.kt`, `EmailContinuousIndexer.kt`, `EmailPollingHandlerBase.kt`, `ImapPollingHandler.kt`

#### "K reakci" Priority Sorting

USER_TASK list ("K reakci") sorts by priority instead of creation time:

```
Sort: priorityScore DESC → lastActivityAt DESC → createdAt ASC
```

- `priorityScore` (0-100) set on all USER_TASK creation paths: `AutoTaskCreationService`, `/internal/kb-done` handler, `UserTaskService.failAndEscalateToUserTask()` (default 60 for escalated)
- `lastActivityAt` bumped when new thread messages arrive
- DB index: `{'type': 1, 'state': 1, 'priorityScore': -1, 'lastActivityAt': -1}`

### Initial Sync s Pagination

---

## Knowledge Graph Design

> Full KB reference: [knowledge-base.md](knowledge-base.md). Data processing pipeline: [structures.md](structures.md).

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

## KB Service Concurrency Model

### Problem

The KB service (`service-knowledgebase`) uses synchronous Weaviate, ArangoDB, and Ollama embedding
clients inside `async def` FastAPI handlers. A single long ingest operation (30-180s) blocks the
event loop and starves all other requests (queries, list, retrieve).

### Solution: Horizontal Scaling + `asyncio.to_thread()`

The KB service is deployed as a **single K8s Deployment** with multiple replicas (3 pods).
All pods expose all endpoints (read + write). K8s service load-balancing distributes requests
across pods naturally.

**Deployment:** `jervis-knowledgebase` with `replicas: 3`
**Service:** `jervis-knowledgebase:8080` (round-robin to all 3 pods)
**Endpoints:** All pods expose both read (retrieve, traverse, graph/*, alias/resolve, chunks/*)
and write (ingest, crawl, purge, alias/register, alias/merge)

**Callers:**
- Orchestrator, MCP server, Kotlin indexers, Kotlin retrieve operations → all use `jervis-knowledgebase:8080`
- K8s automatically distributes load across 3 pods

**Build script:** `k8s/build_kb.sh` builds Docker image and deploys to K8s with 3 replicas.

### Reranker (TEI)

Cross-encoder reranking for improved retrieval precision.

**Deployment:** `jervis-reranker` — HuggingFace Text Embeddings Inference (TEI), Rust binary
**Model:** `BAAI/bge-reranker-v2-m3` (568M params, multilingual, runs on CPU)
**Service:** `jervis-reranker:8080` — `/rerank` endpoint
**K8s manifest:** `k8s/app_reranker.yaml`

**Pipeline integration:** After hybrid retrieval (RAG + graph + entity + RRF), top-50 candidates
are sent to TEI for cross-encoder reranking. Returns top-10 with re-scored relevance.
Graceful degradation: if TEI unavailable, retrieval falls back to pre-rerank order.

### Additional: `asyncio.to_thread()` + Batch Embeddings + Workers

Three changes make the service non-blocking:

1. **`asyncio.to_thread()` wrapping** — All blocking Weaviate and ArangoDB calls are wrapped in
   `asyncio.to_thread()`, which runs them in Python's default thread pool. The event loop stays
   free to serve queries while ingest runs in a background thread.

2. **Batch embeddings** — `RagService.ingest()` uses `embed_documents([chunks])` instead of
   per-chunk `embed_query(chunk)` loops. This is a single Ollama call for all chunks instead of N calls.

3. **4 uvicorn workers** — Increased from 2 to 4 workers for better concurrency across processes.

### What stays async (not wrapped)

- LLM calls (`await self.llm.ainvoke()`) — already async via LangChain
- `AliasRegistry` — already async
- Progress callbacks — fire-and-forget, fine as-is

### Thread safety

Weaviate and python-arango clients are thread-safe for read operations. Write operations
(batch insert, update) are isolated per-request. The thread pool size is adjustable via
`loop.set_default_executor()` if needed.

### Key files

| File | Change |
|------|--------|
| `backend/service-knowledgebase/app/services/rag_service.py` | `asyncio.to_thread()` around all Weaviate + embedding calls, batch `embed_documents()` |
| `backend/service-knowledgebase/app/services/graph_service.py` | `asyncio.to_thread()` around all ArangoDB calls |
| `backend/service-knowledgebase/app/services/knowledge_service.py` | `asyncio.to_thread()` around ArangoDB crawl-URL tracking |
| `backend/service-knowledgebase/Dockerfile` | uvicorn workers 2 → 4 |

---

## Vision Processing Pipeline

### Problem Statement

**Problem**: Traditional text extraction is blind - extracts text, but doesn't see **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

**Solution**: VLM-first extraction pipeline using **Qwen3-VL** (vision model) via DocumentExtractor, with pymupdf/python-docx for structured docs.

### Vision Architecture

### Vision Integration

- **Vision as a pipeline stage**: Separate processing step in indexing pipeline
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

---

## Whisper Transcription Pipeline

### Overview

Audio recordings are transcribed using **faster-whisper** (CTranslate2-optimized OpenAI Whisper)
with **pyannote-audio 4.x** diarization that produces 256-dim speaker embeddings for auto-identification.
Two deployment modes are supported, configurable via UI (**Settings → Whisper → Režim nasazení**):

1. **K8S_JOB** (default): Runs as short-lived K8s Jobs in-cluster (or Python subprocess in local dev).
   Requires sufficient cluster RAM for the chosen model. Uses shared PVC for audio/result files.

2. **REST_REMOTE**: Calls a persistent Whisper REST server on a dedicated machine via HTTP.
   Audio is uploaded as multipart/form-data, result returned as JSON. No PVC sharing needed.
   Ideal when K8s cluster lacks RAM but a separate machine has plenty.

Settings are stored in MongoDB (`whisper_settings` collection, singleton document).

### Deployment Mode Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Deployment mode | `K8S_JOB` | `K8S_JOB` = K8s Jobs in cluster, `REST_REMOTE` = remote REST server |
| REST remote URL | `http://192.168.100.117:8786` | URL of the Whisper REST service (only used in REST_REMOTE mode) |

### Configurable Parameters (UI)

| Parameter | Default | Description |
|-----------|---------|-------------|
| Model | `base` | tiny, base, small, medium, large-v3 |
| Task | `transcribe` | transcribe (keep language) or translate (to English) |
| Language | auto-detect | ISO 639-1 code (cs, en, de...) |
| Beam size | 5 | 1-10, higher = more accurate but slower |
| VAD filter | true | Silero VAD skips silence — significant speedup |
| Word timestamps | false | Per-word timing in segments |
| Initial prompt | auto | Auto-populated from KB correction rules (per client/project) |
| Condition on previous | true | Use previous segment as context |
| No-speech threshold | 0.6 | Skip segments above this silence probability |
| Max parallel jobs | 3 | Concurrent K8s Whisper Jobs / REST requests |
| Timeout multiplier | 3 | Timeout = audio_duration × multiplier |
| Min timeout | 600s | Minimum job timeout |

### K8s Job Resource Limits (K8S_JOB mode only)

Resource limits are set dynamically based on model size via `WhisperJobRunner.resourcesForModel()`:

| Model | Memory Request | Memory Limit |
|-------|---------------|--------------|
| tiny, base | 512Mi | 2Gi |
| small | 1Gi | 3Gi |
| medium | 2Gi | 6Gi |
| large-v3 | 4Gi | 12Gi |

CPU: 500m request / 2 cores limit for all models. The `large-v3` model (used by retranscription)
requires ~10GB RAM; the previous hardcoded 2Gi limit caused OOM kills (exit=137).

All models are pre-downloaded in the Docker image (no HuggingFace download at runtime).

### REST Remote Mode

When `deploymentMode = REST_REMOTE`:
- `WhisperJobRunner` sends the audio file to the remote Whisper REST service via `WhisperRestClient`
- Audio is uploaded as HTTP multipart (`POST /transcribe`) with options as a JSON form field
- The REST server runs `whisper_runner.py` in a background thread and returns an **SSE stream**:
  - `event: progress` — periodic updates: `{"percent": 45.2, "segments_done": 128, "elapsed_seconds": 340, "last_segment_text": "..."}`
  - `event: result` — final transcription JSON (same format as whisper_runner.py output)
  - `event: error` — error details if transcription fails
- `WhisperRestClient` reads the SSE stream, emits progress via `NotificationRpcImpl` (same as K8s mode)
- No PVC, no K8s Job — progress and result come via SSE stream, no HTTP timeout risk
- Health check available at `GET /health`

**Docker image:** `jervis-whisper-rest` (built via `k8s/build_whisper_rest.sh`)
**Dockerfile:** `backend/service-whisper/Dockerfile.rest`
**Port:** 8786 (configurable via `WHISPER_REST_PORT` env var)

### Progress Tracking

**K8S_JOB mode:** The Whisper container writes a progress file on PVC (`meeting_{id}_progress.json`) updated every 5 seconds:
```json
{"percent": 45.2, "segments_done": 128, "elapsed_seconds": 340, "updated_at": 1738000000.0}
```
The server-side `WhisperJobRunner` reads this file during K8s Job polling (every 10s) and emits
`MeetingTranscriptionProgress` events via `NotificationRpcImpl` for real-time UI updates.

**REST_REMOTE mode:** The REST server uses an in-memory thread-safe queue (no file I/O) to stream
SSE `progress` events every ~3 seconds, including `last_segment_text` for live transcription preview.
`WhisperRestClient` reads these events and calls `buildProgressCallback()` which emits
`MeetingTranscriptionProgress` notifications (with `lastSegmentText`) — UI progress works
identically in both modes, with the REST mode additionally showing the last transcribed text.

State transitions (TRANSCRIBING → TRANSCRIBED/FAILED, CORRECTING → CORRECTED, etc.) emit
`MeetingStateChanged` events so the meeting list/detail view updates without polling.

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-whisper/whisper_runner.py` | Python entry point — faster-whisper with progress tracking |
| `backend/service-whisper/whisper_rest_server.py` | FastAPI REST wrapper — diarization + 256-dim speaker embeddings (REST_REMOTE mode) |
| `backend/service-whisper/entrypoint-whisper-job.sh` | K8s Job entrypoint — env parsing, error handling |
| `backend/service-whisper/Dockerfile` | Docker image for K8s Job mode |
| `backend/service-whisper/Dockerfile.rest` | Docker image for REST server mode (FastAPI + uvicorn) |
| `backend/server/.../meeting/WhisperJobRunner.kt` | Orchestration — routes to K8s Job, REST, or local subprocess |
| `backend/server/.../meeting/WhisperRestClient.kt` | Ktor HTTP client for REST_REMOTE mode |
| `backend/server/.../meeting/MeetingTranscriptionService.kt` | High-level transcription API + speaker auto-matching (cosine similarity) |
| `backend/server/.../meeting/MeetingContinuousIndexer.kt` | 4 pipelines: transcribe → correct → index → purge |
| `shared/common-api/.../service/IWhisperSettingsService.kt` | kRPC interface |
| `shared/common-dto/.../dto/whisper/WhisperSettingsDtos.kt` | Settings DTOs + enums (incl. WhisperDeploymentMode) |
| `shared/ui-common/.../settings/sections/WhisperSettings.kt` | Settings UI composable |
| `k8s/build_whisper.sh` | Build script for K8s Job image |
| `k8s/build_whisper_rest.sh` | Build script for REST server image |

---

## Transcript Correction Pipeline

### Overview

After Whisper produces a raw transcript, an LLM-based correction pipeline fixes speech-to-text errors
using per-client/project correction rules stored in KB (Weaviate) as chunks with `kind="transcript_correction"`.

The correction agent lives in the Python orchestrator service (`service-orchestrator/app/whisper/correction_agent.py`)
to share Ollama GPU access. The Kotlin server delegates to it via REST.

### State Machine

```
RECORDING → UPLOADING → UPLOADED → TRANSCRIBING → TRANSCRIBED → CORRECTING → CORRECTED → INDEXED
                                                       ↑              │    ↑         │
                                                       │              │    │         │
                                                       │       CORRECTION_REVIEW    │
                                                       │       (questions pending)   │
                                                       │         │    │              │
                                                       │         │    └──── FAILED ──┘
                                                       │         │
                                        all known ─────┘         └─── any "Nevím" → CORRECTING
                                        (KB rules + re-correct)       (retranscribe + targeted)
```

### Correction Flow

1. `MeetingContinuousIndexer` picks up TRANSCRIBED meetings
2. `TranscriptCorrectionService.correct()` sets state to CORRECTING
3. Delegates to Python orchestrator via `PythonOrchestratorClient.correctTranscript()`
4. Python `CorrectionAgent` loads per-client/project correction rules from KB (Weaviate)
5. Transcript segments chunked (20/chunk) and sent to Ollama GPU (`qwen3-coder-tool:30b`, configurable via `DEFAULT_CORRECTION_MODEL`)
6. **Streaming + token timeout**: Ollama called with `stream: True`, responses processed as NDJSON lines. Each token must arrive within `TOKEN_TIMEOUT_SECONDS` (300s orchestrator / 3600s correction) — if not, `TokenTimeoutError` is raised (read timeout on LLM stream, separate from task-level stuck detection)
7. **Intra-chunk progress**: Every ~10s during streaming, progress is emitted to Kotlin server with token count, enabling smooth UI progress within each chunk
8. System prompt: meaning-first approach — read full context, phonetic reasoning for garbled Czech, apply correction rules
9. LLM returns corrections + optional questions when uncertain about proper nouns/terminology
10. If questions exist: state → CORRECTION_REVIEW (best-effort corrections + questions stored)
11. If no questions: state → CORRECTED
12. User answers questions in UI:
    - **All answers known** → saved as KB correction rules → state reset to TRANSCRIBED → full re-correction with new rules
    - **Any "Nevím" (unknown) answers** → retranscribe + targeted correction flow (see below)
13. Downstream indexing picks up CORRECTED meetings for KB ingestion

### "Nevím" Re-transcription + Targeted Correction

When user answers "Nevím" (I don't know) to correction questions, the system re-transcribes unclear audio:

1. Known answers are saved as KB rules (same as before)
2. State → CORRECTING
3. Audio ranges ±10s around "Nevím" segments are extracted via ffmpeg (in Whisper container)
4. Extracted audio re-transcribed with Whisper **large-v3, beam_size=10** (best CPU accuracy)
5. Result segments merged: user corrections + new Whisper text + untouched segments
6. Merged segments sent to Python `CorrectionAgent.correct_targeted()` — only retranscribed segments go through LLM
7. State → CORRECTED (or CORRECTION_REVIEW if agent has new questions)

**Whisper retranscription settings** (overrides global settings for maximum accuracy):

| Setting | Value | Why |
|---------|-------|-----|
| model | large-v3 | Best accuracy |
| beam_size | 10 | Maximum search breadth |
| vad_filter | true | Skip silence |
| condition_on_previous_text | true | Use context |
| no_speech_threshold | 0.3 | Lower = fewer skipped segments |

**Error handling**: Connection errors reset to CORRECTION_REVIEW (preserves questions for retry). Other errors → FAILED.

### Liveness & Recovery

- **Timestamp-based stuck detection (Pipeline 5)**: `MeetingContinuousIndexer` checks `stateChangedAt` on CORRECTING meetings. If stuck for longer than `STUCK_CORRECTING_THRESHOLD_MINUTES` (15 min), the meeting is reset to TRANSCRIBED (auto-retry), not FAILED. No in-memory tracker needed — detection is purely DB-based
- **Connection-error recovery**: If `TranscriptCorrectionService.correct()` fails with `ConnectException` or `IOException` (Connection refused/reset), the meeting is reset to TRANSCRIBED for automatic retry instead of being marked as FAILED
- **No hard timeouts**: All LLM operations use streaming with token-arrival-based liveness detection — never a fixed timeout

### Correction Rules Management

- **Storage**: KB (Weaviate) chunks with `kind="transcript_correction"`, per-client/project
- **RPC interface**: `ITranscriptCorrectionService` in `shared/common-api/` — `submitCorrection()`, `listCorrections()`, `deleteCorrection()`
- **Categories**: person_name, company_name, department, terminology, abbreviation, general
- **UI**: `CorrectionsScreen` composable accessible from MeetingDetailView (book icon)
- **Interactive**: `CorrectionQuestionsCard` in MeetingDetailView shows agent questions when state == CORRECTION_REVIEW

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/whisper/correction_agent.py` | Python correction agent — KB loading, LLM calls, interactive questions, targeted correction |
| `backend/service-orchestrator/app/main.py` | Python endpoints incl. `/correction/correct-targeted` |
| `backend/server/.../meeting/TranscriptCorrectionService.kt` | Kotlin delegation to Python orchestrator, question handling, retranscribe+correct flow |
| `backend/server/.../meeting/WhisperJobRunner.kt` | Whisper orchestration (K8s Job / REST / local) — includes `retranscribe()` for audio extraction + high-accuracy re-transcription |
| `backend/service-whisper/whisper_runner.py` | Whisper transcription script — supports `extraction_ranges` for partial re-transcription |
| `backend/server/.../agent/PythonOrchestratorClient.kt` | REST client for Python correction endpoints incl. `correctTargeted()` |
| `shared/common-api/.../service/ITranscriptCorrectionService.kt` | RPC interface for correction CRUD |
| `shared/common-dto/.../dto/meeting/MeetingDtos.kt` | `MeetingStateEnum` (incl. CORRECTION_REVIEW), `CorrectionQuestionDto`, `CorrectionAnswerDto` |
| `shared/ui-common/.../meeting/CorrectionsScreen.kt` | Corrections management UI |
| `shared/ui-common/.../meeting/CorrectionViewModel.kt` | Corrections UI state management |
| `backend/server/.../meeting/MeetingContinuousIndexer.kt` | Pipeline 5 stuck detection via `stateChangedAt` timestamp (STUCK_CORRECTING_THRESHOLD_MINUTES = 15) |

---

## Meeting Helper (Real-Time Assistance)

Real-time translation, answer suggestions, and Q&A prediction pushed to a separate device (iPhone) or shown in-app during active recording. Requires live assist (real-time Whisper transcription) to be active. Works identically on Desktop, Android, and iOS.

### Architecture

```
Desktop/Mobile (recording)             iPhone (helper device)
    |                                       |
    | audio chunks                          | WebSocket / RPC events
    v                                       | /ws/meeting-helper/{meetingId}
Kotlin Server                               |
    | POST /api/v1/voice/session/chunk      |
    | → Whisper GPU (VD)                    |
    | → transcript text                     |
    |                                       |
    | POST /meeting-helper/chunk            |
    v                                       |
Python Orchestrator                         |
    | live_helper.py                        |
    | → Translation (LLM, every chunk)     |
    | → Answer suggestions (LLM, 30s)      |
    | → Q&A prediction (LLM, 45s)          |
    |                                       |
    | POST /internal/meeting-helper/push    |
    v                                       |
Kotlin Server ─── WebSocket push ───────────┘
    └── JervisEvent.MeetingHelperMessage ──→ RPC event stream (desktop/mobile)
```

### Dual Delivery

Helper messages are delivered via two channels simultaneously:
1. **WebSocket** (`/ws/meeting-helper/{meetingId}`) — for dedicated helper devices (iPhone, iPad)
2. **RPC Event Stream** (`JervisEvent.MeetingHelperMessage`) — for in-app display on desktop/mobile via `MeetingViewModel.handleHelperMessage()` → `MeetingHelperView` shown below `RecordingBar`

### Components

| File | Role |
|------|------|
| `backend/service-orchestrator/app/meeting/live_helper.py` | Core pipeline: rolling context window, parallel LLM translation + suggestions + Q&A prediction |
| `backend/service-orchestrator/app/meeting/routes.py` | FastAPI routes: start/stop/chunk processing |
| `backend/server/.../meeting/MeetingHelperService.kt` | Session management, WebSocket connection registry, message broadcast + RPC event emission |
| `backend/server/.../meeting/MeetingHelperRouting.kt` | WebSocket endpoint for devices, internal REST for Python callbacks |
| `shared/common-dto/.../meeting/MeetingHelperDtos.kt` | HelperMessageDto, HelperSessionStartDto, DeviceInfoDto |
| `shared/common-dto/.../events/JervisEvent.kt` | MeetingHelperMessage event for RPC delivery |
| `shared/common-api/.../meeting/IMeetingHelperService.kt` | RPC interface: startHelper, stopHelper, listHelperDevices |
| `shared/ui-common/.../meeting/MeetingHelperView.kt` | Compose UI: color-coded messages, auto-scroll, copy-to-clipboard |
| `shared/ui-common/.../meeting/MeetingViewModel.kt` | Handles MeetingHelperMessage events, manages helper state flows |
| `shared/ui-common/.../meeting/RecordingSetupDialog.kt` | Device picker when Meeting Helper is enabled |

### WebSocket Protocol (Server → Device)

```json
{"type": "TRANSLATION", "text": "...", "fromLang": "en", "toLang": "cs", "timestamp": "..."}
{"type": "SUGGESTION", "text": "Suggest saying: ...", "context": "...", "timestamp": "..."}
{"type": "QUESTION_PREDICT", "text": "They might ask: ...", "timestamp": "..."}
{"type": "STATUS", "text": "Asistence zahájena", "timestamp": "..."}
```

### Device Registry

Devices register via `IDeviceTokenService.registerToken()` with extended fields: `deviceName`, `deviceType` (IPHONE, IPAD, WATCH, etc.), `capabilities` (e.g., "helper"). The Meeting Helper lists devices with helper capability or iPhone/iPad type as targets.

---

## Financial Module

### Overview

Financial tracking module with auto-matching of invoices to payments. Financial data is created via orchestrator tools (not hardcoded handlers) — the LLM agent uses `record_payment`, `list_invoices` etc. tools when processing any content it identifies as financial.

```
Any content → Qualification Agent (LLM) → decides financial action
                                              │
           ┌──────────────────────────────────┤
           ▼                                   ▼
    Orchestrator tools                    Manual via UI
  (record_payment, etc.)              (FinanceScreen in Compose)
           │
           ▼
    FinancialService → Auto-Match (VS or amount+counterparty)
```

### Components

| Component | File | Purpose |
|-----------|------|---------|
| `FinancialDocument` | `backend/server/.../finance/FinancialDocument.kt` | MongoDB document — invoices, payments, expenses |
| `ContractDocument` | `backend/server/.../finance/ContractDocument.kt` | MongoDB document — contracts with rates |
| `FinancialService` | `backend/server/.../finance/FinancialService.kt` | Auto-matching, overdue detection, summary |
| `FinancialRpcImpl` | `backend/server/.../finance/FinancialRpcImpl.kt` | kRPC implementation |
| `InternalFinanceRouting` | `backend/server/.../rpc/internal/InternalFinanceRouting.kt` | REST API for Python orchestrator |
| `FinanceScreen` | `shared/ui-common/.../screens/finance/FinanceScreen.kt` | Compose UI — summary, records, contracts |
| Chat tools | `handler_tools.py` | `finance_summary`, `list_invoices`, `record_payment`, `list_contracts` |

### Auto-Matching Logic

1. **By Variable Symbol (VS):** Exact match of incoming payment VS against existing invoice VS
2. **By Amount + Counterparty:** Fuzzy match — same amount within tolerance and counterparty name contains match
3. On match: both documents get status `MATCHED` with cross-reference via `matchedDocumentId`

### Financial Types

- `INVOICE_IN` — received invoice (expense)
- `INVOICE_OUT` — issued invoice (income)
- `PAYMENT` — payment received or sent
- `EXPENSE` — direct expense
- `RECEIPT` — income receipt

### Overdue Detection

`detectOverdueInvoices()` finds all `NEW` invoices with `dueDate` before today and updates their status to `OVERDUE`.

---

## Time & Capacity Management

### Overview

Time tracking and capacity management module. Logs work hours per client, calculates weekly capacity from active contracts, provides summary reports.

### Components

| Component | File | Purpose |
|-----------|------|---------|
| `TimeEntryDocument` | `backend/server/.../timetracking/TimeEntryDocument.kt` | MongoDB document for time entries |
| `TimeTrackingService` | `backend/server/.../timetracking/TimeTrackingService.kt` | Time logging, summary, capacity calculation |
| `TimeTrackingRpcImpl` | `backend/server/.../timetracking/TimeTrackingRpcImpl.kt` | kRPC implementation |
| `InternalTimeTrackingRouting` | `backend/server/.../rpc/internal/InternalTimeTrackingRouting.kt` | REST API for Python orchestrator |
| `TimeTrackingScreen` | `shared/ui-common/.../screens/finance/TimeTrackingScreen.kt` | Compose UI — today, summary, capacity |
| Chat tools | `handler_tools.py` | `log_time`, `check_capacity`, `time_summary` |

### Time Sources

- `MANUAL` — user logs via chat or UI
- `MEETING` — auto-logged from meeting duration when meeting is indexed with a known clientId (`MeetingContinuousIndexer.autoLogMeetingTime()`)
- `TASK` — auto-logged from task completion
- `CALENDAR` — auto-logged from calendar events

### Auto-Logging: Meetings

When `MeetingContinuousIndexer` completes indexing a meeting (TRANSCRIBED → INDEXED), if the meeting has a `clientId` and `durationSeconds > 3 minutes`, it automatically creates a time entry:
- Source: `TimeSource.MEETING`
- Hours: `durationSeconds / 3600` (rounded to 2 decimals)
- Date: meeting start date (Europe/Prague timezone)
- Description: meeting title or meeting ID suffix

### Capacity Model

Capacity is calculated from active contracts:
- Monthly contracts = 40h/week (full-time)
- Daily contracts = normalized to weekly hours
- Hourly contracts = no committed hours (on-demand)

Available hours = 40h/week - sum(committed hours from contracts)

---

## Proactive Communication

### Overview

Proactive system that generates briefings, alerts, and summaries on schedule. Triggered by Python orchestrator's scheduled tasks, results pushed as BACKGROUND chat messages and push notifications.

### Scheduled Tasks

| Task | Schedule | Endpoint |
|------|----------|----------|
| Morning briefing | Daily 7:00 | `POST /internal/proactive/morning-briefing` |
| Invoice overdue check | Daily 9:00 | `POST /internal/proactive/overdue-check` |
| Weekly summary | Monday 8:00 | `POST /internal/proactive/weekly-summary` |

### Components

| Component | File | Purpose |
|-----------|------|---------|
| `ProactiveScheduler` | `backend/server/.../proactive/ProactiveScheduler.kt` | Generates briefings, checks overdue, sends alerts |
| `InternalProactiveRouting` | `backend/server/.../rpc/internal/InternalProactiveRouting.kt` | REST API for triggering from orchestrator |
| Proactive routes | `backend/service-orchestrator/app/proactive/routes.py` | FastAPI router for scheduled triggers |
| Proactive scheduler | `backend/service-orchestrator/app/proactive/scheduler.py` | Asyncio cron loop — fires triggers at configured times (Europe/Prague TZ) |

### Scheduling Architecture

The scheduler runs as an asyncio background task in the Python orchestrator (started in FastAPI lifespan). It calculates the next trigger time, sleeps until then, and POSTs to the local proactive FastAPI routes, which forward to Kotlin's ProactiveScheduler via internal REST API. No external cron or APScheduler dependency needed.

### VIP Sender Detection

When an email arrives, the qualification handler checks KB for VIP sender conventions. If sender is VIP, an immediate push notification is sent via APNs/FCM.

---

## Active Opportunity Search

### Overview

The qualification agent evaluates any incoming content it identifies as a work opportunity
(job offers, freelance inquiries, project proposals) using KB data — user skill profile,
rate expectations, and available capacity. No hardcoded scoring — the LLM agent uses
KB conventions and tools to analyze and score opportunities.

### How It Works

1. Qualification agent identifies content as a potential opportunity
2. Agent searches KB for user skill profile (`kb_search "user-skill-profile"`)
3. Agent checks capacity via `check_capacity` tool
4. Agent evaluates match, rate, and availability
5. Agent creates USER_TASK with analysis and recommendation

### KB Data Required

- **User skill profile** (stored in KB): skills, domains, roles, languages, min rates
- **Active contracts** (stored in KB + FinancialModule): current commitments
- **Capacity data** (from TimeTrackingService): available hours

---

## GPU Model Routing

### Overview

GPU routing uses **capability-based routing** with the Ollama Router as the central routing service. The orchestrator/chat declares a **capability** (thinking, coding, chat, embedding, visual, extraction) and the router decides local GPU vs cloud. Fixed `num_ctx` per GPU prevents costly model reloads.

### Architecture

```
Orchestrator → "capability=chat, max_tier=FREE, tokens=5000"
    → Router /route-decision
        → GPU free → {"target":"local", "model":"qwen3-coder-tool:30b", "api_base":"..."}
        → GPU busy + FREE → {"target":"openrouter", "model":"qwen/qwen3-30b-a3b:free"}
        → GPU busy + NONE → {"target":"local", ...}  (wait in queue)
Orchestrator → litellm → target backend
```

### Per-GPU Model Sets

Each GPU has its own set of models (configured via `GPU_MODEL_SETS` env var):

```
GPU_MODEL_SETS: '{"p40-1":["qwen3-coder-tool:30b"],"p40-2":["qwen3-embedding:8b","qwen3:8b","qwen3:14b","qwen3-vl-tool:latest"]}'
```

| GPU | Models | num_ctx | Role |
|-----|--------|---------|------|
| GPU1 (P40) | `qwen3-coder-tool:30b` (18.5GB) | 48,000 | Orchestrator, chat, coding — sole LLM GPU |
| GPU2 (P40) | `qwen3-embedding:8b` (5.5GB) + `qwen3:8b` (6.0GB) + `qwen3:14b` (11.0GB) | 64,000 | Extraction + embedding (22.5GB permanent) |

- **Embedding → GPU2 only** — p40-1 doesn't have embedding model
- **Extraction → GPU2 only** — `qwen3:8b` (lightweight) and `qwen3:14b` (complex) permanent on p40-2
- **VLM → GPU2 only** — on-demand swap, temporarily replaces permanent models
- **Per-type concurrency**: embedding concurrent=5, LLM serial=1 per GPU
- **No CPU Ollama** — all LLM/embedding on GPU only
- Default for new clients: `maxOpenRouterTier = FREE`

### Capability Model Catalog

```python
LOCAL_MODEL_CAPABILITIES = {
    "qwen3-coder-tool:30b": ["thinking", "coding", "chat"],
    "qwen3-embedding:8b": ["embedding"],
    "qwen3:8b": ["extraction"],
    "qwen3:14b": ["extraction"],
    "qwen3-vl-tool:latest": ["visual"],
}
```

- **extraction** capability: routes to `qwen3:8b` on p40-2 (orchestrator qualification, KB link relevance)
- KB service calls `qwen3:8b` (simple) and `qwen3:14b` (complex) directly by model name — no capability routing

### Routing Logic (`/route-decision`)

The router decides based on capability, max_tier, estimated_tokens, and GPU state:

1. **max_tier = NONE**: Always local (wait for GPU)
2. **Context > 48k**: Route to cloud model with enough context
3. **GPU free**: Use local GPU
4. **GPU busy + OpenRouter enabled**: Route to cloud (no waiting)
5. **GPU busy + no cloud**: Local (wait in queue)

### CloudModelPolicy Hierarchy

Policy resolution: **project → group → client → default (FREE)**

- Project can override group/client policy
- Group can override client policy (new)
- Resolved via `CloudModelPolicyResolver` in Kotlin server
- All 4 tiers available: NONE, FREE, PAID (Haiku/GPT-4o-mini), PREMIUM (Sonnet/o3-mini)

### Background vs Foreground

- **Foreground (chat)**: CRITICAL priority, capability-based routing via `/route-decision`, can use OpenRouter
- **Background**: Always local GPU, no routing decision, no OpenRouter — waits in queue

### Gemini (Direct Orchestrator Call)

Gemini (1M context) is **NOT** in the routing queues. The orchestrator calls it directly via litellm (`CLOUD_LARGE_CONTEXT` tier) only when:
1. Context exceeds the max capacity of all available models
2. Used for context reduction — splitting huge documents into smaller scope chunks
3. Orchestrator stores chunks in scope, then processes each chunk via normal routing

### GPU Performance Profile (Tesla P40, qwen3-coder-tool:30b Q4_K_M)

Benchmarked 2026-03-02 on both P40 GPUs. Key findings:

| Metric | Value | Notes |
|--------|-------|-------|
| Prompt processing (warm) | 800–1,500 tok/s | KV cache already allocated |
| Prompt processing (cold) | 260–530 tok/s | After num_ctx change / reload |
| Token generation (1k ctx) | ~52 tok/s | |
| Token generation (8k ctx) | ~45 tok/s | |
| Token generation (16k ctx) | ~41 tok/s | |
| Token generation (32k ctx) | ~33 tok/s | |
| Token generation (48k ctx) | ~29 tok/s | |
| Model load (p40-1, 251GB RAM) | ~14s | Cached in page cache |
| Model load (p40-2, 8GB RAM) | ~200-260s | Full disk I/O, model > RAM |

**Critical rules:**
- **NEVER change num_ctx between requests** — causes 2-5× slowdown (Ollama restarts runner)
- **NEVER unload permanent models on p40-2** — reload takes >200s (3 permanent models = 22.5GB)
- **Fixed num_ctx per GPU**: p40-1=48k, p40-2=64k
- Both GPUs perform identically once warm (±5% variance)
- Concurrent execution works — no interference between GPUs

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
- **backend/common-services**: Shared library (RPC interfaces, rate limiting, HTTP helpers, DTOs)
- **backend/service-***: Ktor microservices (github, gitlab, atlassian, joern, whisper, aider, coding-engine, junie, claude)
- **shared/common-dto**: Data transfer objects
- **shared/common-api**: `@HttpExchange` contracts
- **shared/domain**: Pure domain types
- **shared/ui-common**: Compose Multiplatform UI screens (ViewModels decomposed by domain — see below)
- **apps/desktop**: Primary desktop application
- **apps/mobile**: iOS/Android port from desktop

### Shared Infrastructure (`backend/common-services`)

- **`com.jervis.common.http`**: Typed exception hierarchy (`ProviderApiException`), response validation (`checkProviderResponse()`), pagination helpers (Link header, offset-based)
- **`com.jervis.common.ratelimit`**: `DomainRateLimiter` (per-domain sliding window), `ProviderRateLimits` (centralized configs for GitHub/GitLab/Atlassian), `UrlUtils`
- **`com.jervis.common.client`**: kRPC service interfaces (`IBugTrackerClient`, `IRepositoryClient`, `IWikiClient`, `IProviderService`)

### UI ViewModel Architecture (`shared/ui-common`)

`MainViewModel` is a thin coordinator (~450 lines) that owns client/project selection
and delegates domain logic to specialized sub-ViewModels:

| ViewModel | Package | Responsibility |
|-----------|---------|---------------|
| `MainViewModel` | `ui/` | Client/project selection, event routing, workspace status |
| `ChatViewModel` | `ui/chat/` | Chat messages, streaming, history, attachments, pending retry |
| `QueueViewModel` | `ui/queue/` | Orchestrator queue, task history, progress tracking |
| `EnvironmentViewModel` | `ui/environment/` | Environment panel, polling, status, selection tracking, chat context |
| `NotificationViewModel` | `ui/notification/` | User tasks, approve/deny/reply |
| `ConnectionViewModel` | `ui/` | Connection state, offline detection |

**Wiring:** Sub-ViewModels receive `StateFlow<String?>` for `selectedClientId`/`selectedProjectId`
(read-only). Cross-cutting concerns use callbacks (`onScopeChange`, `onError`, `onChatProgressUpdate`).
`MainViewModel.handleGlobalEvent()` routes kRPC events to the appropriate sub-ViewModel.

### Communication Patterns

- **UI ↔ Server**: ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Benefits of Architecture

1. **Cost efficiency**: Expensive GPU models only when necessary
2. **Scalability**: Parallel CPU indexing, orchestrator execution on idle
3. **Explainability**: Evidence links for all relationships
4. **Flexibility**: Schema-less graph for new entity types
5. **Performance**: Hybrid search combining semantic + structured
6. **Type safety**: Compile-time checks prevent runtime errors
7. **Fail-fast design**: Errors propagate, no silent failures
8. **Multi-tenancy**: Per-client isolation in all storage layers
9. **Resilient retries**: Exponential backoff with DB-persisted state (survives pod restarts)

---

## Resilience Patterns

### Circuit Breaker (Orchestrator)

`PythonOrchestratorClient.CircuitBreaker` protects health checks and dispatch calls:

| State | Behavior |
|-------|----------|
| CLOSED | Normal operation, track consecutive failures |
| OPEN | After 5 failures → fast-fail (no HTTP call) for 30s |
| HALF_OPEN | After 30s in OPEN → allow 1 probe. Success → CLOSED, Failure → OPEN |

- `isHealthy()` and `orchestrateStream()` record success/failure
- HTTP 429 (busy) is NOT recorded as failure (orchestrator is healthy, just saturated)

### Workspace Recovery (Exponential Backoff)

`WorkspaceStatus` classifies clone failures into 4 categories:

| Status | Retryable | User Action |
|--------|-----------|-------------|
| `CLONE_FAILED_AUTH` | No | Fix credentials in connection settings |
| `CLONE_FAILED_NOT_FOUND` | No | Fix repository URL |
| `CLONE_FAILED_NETWORK` | Yes | Auto-retry with backoff |
| `CLONE_FAILED_OTHER` | Yes | Auto-retry with backoff |

Backoff schedule (retryable only): 1min → 2min → 4min → 8min → ... → 1h cap.

- **Startup**: READY workspaces verified on disk (`.git` exists); if missing → reset to null and re-clone. Non-retryable failures skipped; retryable failures respect existing backoff.
- **Periodic loop** (`runWorkspaceRetryLoop`, 60s): picks up retryable failures whose backoff elapsed
- **User save** (`ProjectService.saveProject`): always publishes `ProjectWorkspaceInitEvent` for projects with REPOSITORY resources (except when status is CLONING). For CLONE_FAILED_* statuses, resets retry state before re-triggering. For READY workspaces, triggers git fetch refresh.
- **State in DB**: `workspaceRetryCount`, `nextWorkspaceRetryAt`, `lastWorkspaceError` on `ProjectDocument`

### Task Dispatch Throttling (Exponential Backoff)

When orchestrator dispatch fails (busy/error), tasks get DB-persisted backoff:

- Fields: `dispatchRetryCount`, `nextDispatchRetryAt` on `TaskDocument`
- Backoff: 5s → 15s → 30s → 60s → 5min cap
- `getNextForegroundTask()`/`getNextBackgroundTask()` skip tasks in backoff
- Successful dispatch resets retry state to 0

### Offline Mode (Client-Side)

The Compose UI supports offline operation — the app renders immediately without waiting for server connection.

**Key components:**
- `OfflineException` (`shared/domain/.../di/OfflineException.kt`): thrown when RPC called while offline
- `OfflineDataCache` (expect/actual, 3 platforms): persists clients + projects for offline display
- `RecordingSessionStorage` (expect/actual, 3 platforms): persists recording session state (replaces legacy `RecordingState` and `OfflineMeeting` models)
- `RecordingUploadService` (`shared/ui-common/.../meeting/RecordingUploadService.kt`): unified local-first recording upload (replaces `OfflineMeetingSyncService`)

**Recording architecture (local-first):**
- All recordings are local-first: audio chunks are always saved to disk first
- `RecordingUploadService` uploads chunks asynchronously in background while recording continues
- On stop, recording is finalized only after all chunks have been uploaded
- Works seamlessly both online and offline — when offline, chunks accumulate on disk and upload when connection is restored

**Server-side meeting dedup:**
- `MeetingDocument` has a `deviceSessionId` field (unique per recording session)
- Compound index on `(clientId, meetingType, state, deleted)` prevents duplicate meetings
- `MeetingRepository.findActiveByClientIdAndMeetingType()` and `findOverlapping()` queries support dedup logic
- `MeetingCreateDto` includes `deviceSessionId` for server-side correlation
- `MeetingDto` includes `mergeSuggestion` field (populated after classify/update for potential merge candidates)

**Behavior:**
- `JervisApp.kt` creates repository eagerly (lambda-based, not blocking on connect)
- Desktop `ConnectionManager.repository` is non-nullable val
- No blocking overlay on disconnect — replaced by "Offline" chip in `PersistentTopBar`
- `ConnectionViewModel.isOffline: StateFlow<Boolean>` derives from connection state
- Chat input disabled when offline; meeting recording works offline (chunks saved to disk, uploaded when connected)

### Ad-hoc Recording (Quick Record from Top Bar)

One-tap recording from `PersistentTopBar` — no dialog, no client/project selection.

**Key changes:**
- `MeetingDocument.clientId` is nullable (`ClientId?`) — null means unclassified
- `MeetingDto.clientId` and `MeetingCreateDto.clientId` are nullable (`String?`); `MeetingCreateDto` also carries `deviceSessionId`
- `MeetingDto.mergeSuggestion` — populated after classify/update when server detects potential merge candidates
- `MeetingDocument.deviceSessionId` — unique per recording session, used for server-side dedup
- `MeetingTypeEnum.AD_HOC` — new enum value for quick recordings
- `PersistentTopBar` has a mic button (🎙) that calls `MeetingViewModel.startQuickRecording()` — records with `clientId=null, meetingType=AD_HOC`
- Stop button (⏹) replaces mic button during recording
- Unclassified meetings directory: `{workspaceRoot}/unclassified/meetings/`

**Classification flow:**
- `IMeetingService.classifyMeeting(MeetingClassifyDto)` — assigns clientId/projectId/title/type, moves audio file to correct directory
- `IMeetingService.listUnclassifiedMeetings()` — returns meetings with null clientId
- `MeetingsScreen` shows "Neklasifikované nahrávky" section with "Klasifikovat" button
- `ClassifyMeetingDialog` — radio buttons for client, project, title field, meeting type chips

**Pipeline behavior with null clientId:**
- Transcription and correction run normally (don't need clientId)
- KB indexing (CORRECTED → INDEXED) is **skipped** until classified — meeting stays in CORRECTED state
- After classification, the indexer picks up the meeting on next cycle

---

## Coding Agents

Jervis dispatches coding work to external agents running as ephemeral K8s Jobs. The CodingAgent (Python) coordinates workspace preparation, job dispatch, and result collection.

### Agent Overview

| Agent | Image | Purpose | Provider | Cost |
|-------|-------|---------|----------|------|
| **Claude** | `jervis-claude` | Complex coding — architecture, multi-file refactoring, reasoning | Anthropic (claude-sonnet-4) | Paid |
| **KILO** | `jervis-kilo` | Simple tasks — bug fixes, small features, formatting | OpenRouter (free models) | Free |

### Agent Selection (`select_agent()` in `_helpers.py`)

Automatic routing based on task complexity and project rules:

- **KILO** auto-selected when ALL conditions met:
  - Task complexity is `SIMPLE`
  - Project `max_openrouter_tier` is `NONE` or `FREE` (no paid cloud budget)
- **Claude** auto-selected for: `MEDIUM`/`COMPLEX`/`CRITICAL` complexity, or paid-tier projects
- **Explicit preference**: User can force `claude` or `kilo` via `agent_preference` field
- **Fallback**: KILO failure → caller retries with `preference="claude"`

### KILO Agent (`service-kilo`)
- **Container**: Python 3.12 + aider-chat + Node.js 20
- **LLM**: OpenRouter free models (`qwen/qwen3-coder:free`, fallback: `nvidia/llama-3.3-nemotron-super-49b-v1:free`)
- **Config**: `k8s/configmap.yaml` → `coding-tools.kilo`
- **Build**: `k8s/build_kilo.sh`
- **Entrypoint**: Shared `entrypoint-job.sh` with `AGENT_TYPE=kilo`

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

### Coding Agent → MR/PR → Code Review Pipeline

After a coding agent K8s Job completes successfully:

```
Coding agent (K8s Job) → commit + push → result.json with branch →
AgentTaskWatcher detects completion →
  ├─ CODING → PROCESSING → DONE (two-step via agent-completed + report_status_change)
  ├─ Server creates MR/PR (GitHub PR / GitLab MR)
  └─ Code review dispatched (async, non-blocking)

Code Review (Coding Agent K8s Job — NOT orchestrator LLM):
  Orchestrator: KB prefetch + static analysis → dispatch review agent job →
  Review Agent (Claude SDK): reads diff, KB search, web search, file analysis →
  Structured verdict → Posted as MR/PR comment →
    ├─ APPROVE → User merges manually (NEVER auto-merge)
    ├─ REQUEST_CHANGES (BLOCKERs only) → new fix coding task (max 2 rounds)
    └─ After max rounds → Escalation comment, user decides
```

#### Implementation Status

| Component | File | Purpose | Status |
|-----------|------|---------|--------|
| Result with branch | `entrypoint-job.sh`, `claude_sdk_runner.py` | result.json includes `branch` field | ✅ Done |
| MR/PR creation | `InternalMergeRequestRouting.kt` | `POST /internal/tasks/{id}/create-merge-request` — resolves provider | ✅ Done |
| MR comment posting | `InternalMergeRequestRouting.kt` | `POST /internal/tasks/{id}/post-mr-comment` — posts review to MR/PR | ✅ Done |
| MR/PR Diff API | `InternalMergeRequestRouting.kt` | `GET /internal/tasks/{id}/merge-request-diff` — fetch diff without workspace | ✅ Done |
| Code review handler | `app/review/code_review_handler.py` | Prepare context + dispatch review agent K8s Job | ✅ Done |
| Review engine | `app/review/review_engine.py` | Static analysis (forbidden patterns, credentials) | ✅ Done |
| Review as Coding Agent | `handler.py` + `workspace_manager.py` | Review runs as Claude SDK K8s Job, NOT local LLM | ✅ Done |
| Review KB Prefetch | `code_review_handler.py` | KB search before dispatch (Jira, meetings, architecture) | ✅ Done |
| KB Freshness | `rag_service.py`, `graph_service.py` | `observedAt` in search results — agent checks staleness | ✅ Done |
| Review Outcome → KB | `agent_task_watcher.py` | Store review findings via `kb_store(kind="finding")` | ✅ Done |
| Review CLAUDE.md | `workspace_manager.py` | Review-specific instructions (read-only, JSON verdict output) | ✅ Done |
| Claude SDK review mode | `claude_sdk_runner.py` | Read-only tools (no Write/Edit), fewer turns | ✅ Done |
| Task watcher integration | `app/agent_task_watcher.py` | Creates MR + dispatches review + handles review completion | ✅ Done |
| Python client methods | `app/tools/kotlin_client.py` | `create_merge_request()`, `post_mr_comment()`, `get_merge_request_diff()` | ✅ Done |
| Fix task round tracking | `agent_task_watcher.py` | sourceUrn `code-review-fix:{id}` + round parsing | ✅ Done |

**Review agent (Claude SDK K8s Job) responsibilities:**
1. Read diff from `.jervis/diff.txt` + pre-fetched KB context from `.jervis/review-kb-context.md`
2. KB search via MCP: Jira issues, meeting discussions, chat decisions, architecture notes
3. Verify stale KB data (observedAt > 7 days) against web search
4. Read full files (not just diff) for context understanding
5. Output structured JSON verdict (APPROVE / REQUEST_CHANGES / REJECT)

**Orchestrator responsibilities (before dispatch):**
1. Extract diff from MR/PR API (fallback: workspace git diff)
2. Run static analysis (forbidden patterns, credentials, forbidden files)
3. Pre-fetch KB context (task-related search, 8 results, 6k chars)
4. Write diff + KB context to workspace `.jervis/` files
5. Dispatch coding agent K8s Job with `review_mode=True`

#### Direct Coding Task Flow

Tasks with `sourceUrn="chat:coding-agent"` follow a special two-step completion:

```
AgentTaskWatcher._poll_once():
  1. Detect job completion (succeeded/failed)
  2. POST /internal/tasks/{id}/agent-completed  → CODING → PROCESSING
  3. POST /orchestrate/v2/report-status-change  → PROCESSING → DONE
  4. If success + result.branch:
     a. Create MR/PR via kotlin_client.create_merge_request()
     b. asyncio.create_task(run_code_review(...))  # non-blocking — dispatches review K8s Job
  5. Update memory graph TASK_REF vertex → COMPLETED
```

Review tasks (`sourceUrn="code-review:{originalTaskId}"`):
- Dispatched by `run_code_review()` via `/internal/dispatch-coding-agent`
- Routed by `handler.py` to `_run_coding_agent_background()` with `review_mode=True`
- `workspace_manager` generates review-specific CLAUDE.md (read-only instructions, JSON verdict)
- `claude_sdk_runner` detects review mode from `task.json` → read-only tools, no Write/Edit
- On completion → `_handle_review_completed()`: parse JSON → post MR comment → fix task if BLOCKERs

Fix tasks (`sourceUrn="code-review-fix:{originalTaskId}"`):
- Do NOT create new MR (branch + MR already exist)
- Parse review round from task content: `"## Code Review Fix (Round N)"`
- Reuse existing `mergeRequestUrl` from task document

#### KB Integration in Code Review

**Search (gathering context):**
- Pre-fetch: orchestrator runs 3+ KB queries before review dispatch
  - Task name/description → Jira issues, requirements, acceptance criteria
  - Changed files → file-specific architecture notes, previous changes
  - Bug/error keywords → meeting discussions, chat decisions
- Agent: Claude SDK uses MCP `kb_search` for deep dives during review
- Sources tracked: `kb_sources_used` in review result

**Store (updating state):**
- Review outcome stored in KB via `kb_store(kind="finding")` after review completes
- Enables future reviews to reference: "Similar issue was found in MR #42, fix was..."
- `sourceUrn="code-review:{taskId}"` for provenance

**Provider support:** GitHub (PR) and GitLab (MR). Provider auto-detected from `ConnectionDocument.provider` via project's REPOSITORY resource.

**Review scope (BLOCKERs only):**
1. Guidelines compliance (project, client, global)
2. Correctness — does the fix solve the problem completely?
3. No regressions — doesn't break existing code
4. Scope adherence — agent fixed ONLY what was in the task
5. Critical safety — SQL injection, race conditions, data loss

**Non-blocking items (INFO/MINOR only):**
- Style preferences (unless guidelines explicitly require it)
- "Better alternatives" that aren't wrong
- Missing tests (unless task required them)
- Refactoring outside task scope

**Feedback loop protection:** Max 2 rounds of review→fix. Only BLOCKER issues trigger new coding round.

**CLAUDE.md template** (`workspace_manager.py`): Push is allowed, merge/force-push forbidden. GPG signing is pre-configured by entrypoint.

---

## Unified Agent (Python)

> **SSOT:** [graph-agent-architecture.md](graph-agent-architecture.md)

### Overview

The Unified Agent (`backend/service-orchestrator/`) is a FastAPI service using LangGraph
that handles ALL interactions — foreground chat AND background tasks. Runs as a separate
K8s Deployment, communicates with Kotlin server via REST + SSE.

**Key principle**: ONE agent for everything. Chat messages create REQUEST vertices in the
Paměťový graf (Memory Graph). Background tasks create Myšlenkové grafy (Thinking Graphs).
Qualifier creates INCOMING vertices. All share the same agentic loop (`vertex_executor.py`).

### Architecture

```
┌─────────────────┐        ┌──────────────────────────────────────────┐
│   UI (Compose)  │        │   Python Unified Agent (FastAPI)         │
│                 │ kRPC   │                                          │
│ ChatViewModel   │◄──────►│  POST /chat ──► sse_handler.py           │
│ subscribeTo-    │  SSE   │    ├── chat_router.py → route to vertex  │
│ ChatEvents()    │        │    ├── vertex_executor.py (agentic loop) │
│                 │        │    └── ChatStreamEvent SSE stream        │
└────────┬────────┘        │                                          │
         │                 │  POST /orchestrate/v2 ──► background     │
┌────────▼────────┐        │    ├── langgraph_runner.py               │
│  Kotlin Server  │ REST   │    ├── vertex_executor.py (shared)       │
│  (Spring Boot)  │◄──────►│    └── callback to Kotlin                │
│                 │        │                                          │
│  ChatService    │        │  Paměťový graf (RAM singleton)            │
│  PythonChat-    │        │  AgentStore (persistence.py)             │
│  Client (SSE)   │        └──────────────────────────────────────────┘
│  BackgroundEng. │
└─────────────────┘
```

**Communication model**: Push-based (Python → Kotlin) with safety-net polling.
- **Foreground chat**: SSE stream (Python → Kotlin → UI)
- **Background tasks**: Push callbacks (`orchestrator-progress`, `orchestrator-status`)
- **Safety net**: BackgroundEngine polls every 60s
- **Stuck detection**: Timestamp-based, 15 min without progress = stuck

### State Persistence

- **TaskDocument** (Kotlin/MongoDB): SSOT for task lifecycle, USER_TASK state
- **AgentGraph** (Python/MongoDB `task_graphs`): Graph structure — vertices, edges, status
- **AgentStore** (Python RAM): In-memory singleton for Paměťový graf, periodic DB flush (30s), 3-tier lifecycle (RAM 24h → MongoDB archive 7d → KB permanent), per-client cleanup + hierarchy GC
- **master_graph_archive** (MongoDB): Tier 2 archive — per-vertex documents with 7d TTL, text-searchable
- **Per-vertex state**: `agent_messages` + `agent_iteration` on GraphVertex for resume

### Chat Context Persistence

**Foreground chat:** Python `ChatContextAssembler` reads MongoDB directly (motor) to build LLM context.
Messages keyed by `conversationId` (= `ChatSessionDocument._id`).

**Three layers:**
1. **Recent messages** (verbatim): Last 20 `ChatMessageDocument` records
2. **Rolling summaries** (compressed): `ChatSummaryDocument` collection
3. **Paměťový graf summary**: Injected into every system prompt (~2000 tokens)

**MongoDB collections:**
- `chat_messages` — individual messages (`conversationId` field)
- `chat_summaries` — compressed summary blocks
- `chat_sessions` — session lifecycle (one active per user)
- `task_graphs` — AgentGraph persistence (Paměťový graf + Myšlenkové grafy)

### Task State Machine

```
QUEUED → PROCESSING → done → DONE
                    │         → error → ERROR
                    └── ASK_USER vertex → BLOCKED → user answers via chat → graph continues
```

### Approval Flow

**Foreground (chat):** SSE-based — `approval_request` event → UI dialog → `POST /chat/approve`
**Background:** ASK_USER vertex → BLOCKED status → user answers via chat → `answer_blocked_vertex` tool

### Concurrency Control

Only **one background orchestration at a time**. Foreground chat runs independently (SSE stream).
- **Kotlin**: `countByState(PROCESSING) > 0` → skip background dispatch
- **Foreground preempts background**: CRITICAL priority on GPU queue

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/main.py` | FastAPI endpoints (/chat, /orchestrate/v2, /chat/approve, /chat/stop) |
| `backend/service-orchestrator/app/agent/vertex_executor.py` | Unified agentic loop (LLM + tools) |
| `backend/service-orchestrator/app/agent/chat_router.py` | Chat message → vertex routing |
| `backend/service-orchestrator/app/agent/sse_handler.py` | SSE adapter for foreground chat |
| `backend/service-orchestrator/app/agent/langgraph_runner.py` | LangGraph execution for background tasks |
| `backend/service-orchestrator/app/agent/models.py` | AgentGraph, GraphVertex, enums |
| `backend/service-orchestrator/app/agent/persistence.py` | AgentStore — RAM cache + DB flush |
| `backend/service-orchestrator/app/agent/tool_sets.py` | Per-vertex-type tool sets + request_tools |
| `backend/service-orchestrator/app/agent/graph.py` | Graph operations, hierarchy, vertex creation |
| `backend/service-orchestrator/app/config.py` | Configuration (MongoDB URL, K8s, LLM providers) |
| `backend/server/.../BackgroundEngine.kt` | Safety-net polling, stuck detection, foreground preemption |
| `backend/server/.../OrchestratorStatusHandler.kt` | Task state transitions (push-based from Python) |
| `backend/service-orchestrator/app/tools/kotlin_client.py` | Push client: progress + status callbacks to Kotlin |
| `backend/service-orchestrator/app/llm/provider.py` | LLM provider with capability-based routing |
---

## Dual-Queue System & Inline Message Delivery

### Dual-Queue Architecture

Tasks are divided into two processing queues based on their `processingMode`:

| Queue | Mode | Purpose | Examples |
|-------|------|---------|----------|
| **Frontend** | `FOREGROUND` | User-initiated tasks, higher priority | Chat messages, user tasks |
| **Backend** | `BACKGROUND` | System/indexing tasks, lower priority | Email processing, wiki indexing, git processing |

The UI (`AgentWorkloadScreen`) displays both queues separately and allows:
- **Reordering** tasks within a queue (up/down)
- **Moving** tasks between queues (e.g., promote a background task to foreground)

#### Queue Management RPC

Three new RPC methods on `IAgentOrchestratorService`:

| Method | Purpose |
|--------|---------|
| `getPendingTasks()` | Returns all pending items from both queues with `taskId`, `processingMode`, `queuePosition` |
| `reorderTask(taskId, newPosition)` | Changes a task's position within its current queue |
| `moveTask(taskId, targetProcessingMode)` | Moves a task between FOREGROUND and BACKGROUND queues |

Backend services: `TaskService.getPendingBackgroundTasks()`, `reorderTaskInQueue()`, `moveTaskToQueue()`.
Repository: `TaskRepository` has a dedicated query for background queue items.

#### Queue Status Emissions

`BackgroundEngine` emits queue status updates via kRPC stream that include items from both FOREGROUND and BACKGROUND queues. Each `PendingQueueItem` carries `taskId` and `processingMode` so the UI can partition them into separate lists.

### Inline Message Delivery During Orchestration

When a user sends a message to a project whose task is currently in `PROCESSING` state, the message cannot be processed immediately (the orchestrator is busy). Instead of dropping or blocking the message:

1. The message is saved to `ChatMessageDocument` (MongoDB) as usual -- it is persisted
2. `TaskDocument` has an `orchestrationStartedAt: Instant?` field set when orchestration begins
3. When orchestration completes ("done"), `BackgroundEngine` checks if any new USER messages arrived after `orchestrationStartedAt` (via `ChatMessageRepository.countByTimestamp`)
4. If new messages found: the task is auto-requeued to `QUEUED`, so the agent re-processes with full context including the new messages
5. If no new messages: normal completion flow continues

```
User sends message while PROCESSING
    │
    ├── Message saved to ChatMessageDocument (persisted)
    │
    └── Orchestration completes ("done")
         │
         ├── New USER messages after orchestrationStartedAt?
         │   YES → auto-requeue to QUEUED (re-process with new context)
         │   NO  → normal completion (DONE or DELETE)
         │
         └── TaskDocument.orchestrationStartedAt reset
```

### Key Files

| File | Purpose |
|------|---------|
| `TaskDocument.kt` | `orchestrationStartedAt` field for tracking orchestration start time |
| `AgentOrchestratorService.kt` | Sets `orchestrationStartedAt` on dispatch |
| `AgentOrchestratorRpcImpl.kt` | PROCESSING handling, 3 new queue RPC methods |
| `BackgroundEngine.kt` | Auto-requeue logic, dual-queue status emissions |
| `ChatMessageRepository.kt` | Count messages by timestamp query |
| `TaskService.kt` | `getPendingBackgroundTasks()`, `reorderTaskInQueue()`, `moveTaskToQueue()` |
| `TaskRepository.kt` | Background queue query |
| `PendingTasksDto.kt` | Shared DTO for queue items |
| `AgentActivityEntry.kt` | Enhanced `PendingQueueItem` with `taskId`, `processingMode`, `queuePosition` |
| `QueueViewModel.kt` | Dual-queue state (`foregroundQueue`, `backgroundQueue`), action methods |
| `AgentWorkloadScreen.kt` | Dual-queue UI with reorder/move controls |

---

## Environment Definitions

### Overview

Environment definitions describe K8s namespace configurations for testing and debugging.
An environment contains infrastructure components (PostgreSQL, Redis, etc.) and project
references, with property mappings connecting them.

### Data Model

```
EnvironmentDocument (MongoDB: environments)
├── clientId: ClientId
├── groupId: ProjectGroupId?     ← Scoped to group (optional)
├── projectId: ProjectId?        ← Scoped to project (optional)
├── tier: EnvironmentTier        ← DEV, STAGING, PROD
├── namespace: String            ← K8s namespace
├── components: List<EnvironmentComponent>
│   ├── type: ComponentType      ← POSTGRESQL, REDIS, PROJECT, etc.
│   ├── image: String?           ← Docker image (infra) or null (project)
│   ├── ports, envVars, autoStart, startOrder
│   ├── sourceRepo, sourceBranch, dockerfilePath  ← Build pipeline (git→build→deploy)
│   ├── deploymentYaml, serviceYaml              ← Stored K8s manifests for recreate
│   ├── configMapData: Map<String, String>       ← Complex config files
│   └── componentState: ComponentState           ← PENDING, DEPLOYING, RUNNING, ERROR, STOPPED
├── componentLinks: List<ComponentLink>
│   ├── sourceComponentId → targetComponentId
├── propertyMappings: List<PropertyMapping>
│   ├── projectComponentId, propertyName, targetComponentId, valueTemplate
├── agentInstructions: String?
├── state: EnvironmentState      ← PENDING, CREATING, RUNNING, etc.
└── yamlManifests: Map<String, String>  ← Stored YAML for namespace recreate from DB
```

### Inheritance (Client → Group → Project)

- Environment at **client level** applies to all groups and projects
- Environment at **group level** overrides/extends for that group's projects
- Environment at **project level** is most specific
- Resolution: query most specific first (project → group → client)

### Environment Lifecycle (Auto-Provision + Auto-Stop)

Environments are automatically provisioned when a coding task starts and
stopped when it finishes. The user can override auto-stop via chat.

**On task dispatch (Kotlin `AgentOrchestratorService.dispatchBackgroundV6`):**

1. Resolves environment via `EnvironmentService.resolveEnvironmentForProject()`
2. If environment is **PENDING** or **STOPPED** → auto-provisions via `EnvironmentK8sService`
3. Passes `environment` JSON + `environmentId` to Python orchestrator

**During task (Python respond node):**

4. User can say "nech prostředí běžet" → `environment_keep_running(enabled=true)` tool
5. Sets `keep_environment_running = True` in LangGraph state

**On task completion or error (dual safety-net):**

6. **Python finalize node**: If `keep_environment_running` is false → calls `POST /internal/environments/{id}/stop`
7. **Kotlin `OrchestratorStatusHandler.handleDone`**: If `keepEnvironmentRunning` is false → calls `deprovisionEnvironment()` (safety net)
8. **Kotlin `OrchestratorStatusHandler.handleError`**: Always calls `autoStopEnvironment()` — don't waste cluster resources on errored tasks; user can re-provision via UI/chat if debugging is needed

### Agent Environment Context

When a coding task is dispatched to the Python orchestrator:

1. `AgentOrchestratorService` resolves environment via `EnvironmentService.resolveEnvironmentForProject()`
2. `EnvironmentMapper.toAgentContextJson()` converts to `JsonObject`
3. Passed in `OrchestrateRequestDto.environment` + `environmentId` fields
4. Python orchestrator stores in LangGraph state as `environment` dict + `environment_id`
5. `workspace_manager.prepare_workspace()` writes:
   - `.jervis/environment.json` – raw JSON for programmatic access
   - `.jervis/environment.md` – human-readable markdown with connection strings, credentials, how-to-run
   - `.env` / `.env.{component}` – resolved env vars for `source .env` usage
6. `CLAUDE.md` includes environment section with:
   - Infrastructure endpoints (host:port) and connection strings
   - Default credentials (DEV only)
   - Project components with ENV vars
   - How to run instructions (install deps, source .env, start app)
   - Environment workflow (use .env, don't build Docker, check infra health)
   - Agent instructions
   - Component topology

### Typical Agent Workflow (Create → Deploy → Use)

```
1. environment_create(client_id, name, ...)     → PENDING
2. environment_add_component(id, "postgresql", "POSTGRESQL")
3. environment_add_component(id, "my-app", "PROJECT")
4. environment_auto_suggest_mappings(id)        → auto-creates SPRING_DATASOURCE_URL, etc.
5. environment_deploy(id)                       → RUNNING, values resolved
6. Coding agent gets CLAUDE.md + .env with resolved connection strings
```

### UI → Chat Context Bridge

`EnvironmentViewModel` tracks which environment the user is currently inspecting:
- `resolvedEnvId` — auto-detected from selected project (server-side resolution)
- `selectedEnvironmentId` — user-expanded environment in the sidebar panel
- `activeEnvironmentId` — resolved OR selected (priority: resolved > selected)
- `EnvironmentPanel` shows "Chat kontext: ..." indicator so user sees what the agent knows
- `PropertyMappingsTab` in Environment Manager allows managing property mappings with auto-suggest from `PROPERTY_MAPPING_TEMPLATES`

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../environment/EnvironmentDocument.kt` | MongoDB document + embedded types |
| `backend/server/.../environment/EnvironmentService.kt` | CRUD + resolve inheritance |
| `backend/server/.../environment/EnvironmentK8sService.kt` | K8s namespace provisioning |
| `backend/server/.../environment/ComponentDefaults.kt` | Default Docker images per type |
| `backend/server/.../environment/EnvironmentMapper.kt` | Document ↔ DTO + toAgentContextJson() |
| `shared/common-dto/.../dto/environment/EnvironmentDtos.kt` | Cross-platform DTOs |

### Environment MCP Integration (Runtime K8s Access for Agents)

Coding agents (Claude, OpenHands, Junie) can inspect and manage the K8s environment
associated with their project via the unified `jervis-mcp` HTTP server (port 8100).

**Architecture:**

```
Agent (Claude Code)
  └─ MCP HTTP: jervis-mcp:8100/mcp (FastMCP, Bearer token + OAuth 2.1 auth)
       ├─ KB tools → httpx → jervis-knowledgebase:8080
       ├─ Environment tools → httpx → Kotlin server :5500/internal/environment/{ns}/*
       ├─ MongoDB tools → Motor → MongoDB
       └─ Orchestrator tools → httpx → jervis-orchestrator:8090
            └─ EnvironmentResourceService → fabric8 K8s client → K8s API
```

**Single HTTP MCP server** (`service-mcp`) exposes all tools over Streamable HTTP.
Agents connect via HTTP instead of stdio subprocesses — smaller Docker images, one server for all tools.

**Environment CRUD Tools (MCP + Orchestrator DevOps agent):**

| Tool | Purpose |
|------|---------|
| `environment_list(client_id?)` | List environments (optionally by client) |
| `environment_get(environment_id)` | Get environment detail (components, links, state) |
| `environment_create(client_id, name, ...)` | Create environment definition (PENDING state) |
| `environment_add_component(environment_id, name, type, ...)` | Add infra or project component |
| `environment_configure(environment_id, component_name, ...)` | Update component config |
| `environment_deploy(environment_id)` | Provision K8s namespace + deploy components |
| `environment_stop(environment_id)` | Deprovision (stop deployments, keep DB definition) |
| `environment_status(environment_id)` | Per-component readiness and replica status |
| `environment_sync(environment_id)` | Re-apply manifests from DB to running K8s |
| `environment_add_property_mapping(environment_id, ...)` | Add env var mapping from infra → project |
| `environment_auto_suggest_mappings(environment_id)` | Auto-generate mappings for all PROJECT×INFRA pairs |
| `environment_clone(environment_id, new_name, ...)` | Clone environment to new scope/tier |
| `environment_keep_running(enabled)` | Override auto-stop — keep env running for user testing |
| `environment_delete(environment_id)` | Delete environment + namespace |
| `environment_discover_namespace(namespace, client_id, ...)` | Discover existing K8s namespace → create Jervis environment from running resources |
| `environment_replicate(environment_id, new_name, ...)` | Clone environment + deploy to new namespace in one step |
| `environment_sync_from_k8s(environment_id)` | Sync K8s state back to Jervis (K8s is source of truth) |

**K8s Resource Inspection Tools (namespace as parameter):**

| Tool | Purpose |
|------|---------|
| `list_namespace_resources(namespace, resource_type)` | List pods/deployments/services/secrets |
| `get_pod_logs(namespace, pod_name, tail_lines)` | Read recent pod logs (max 1000 lines) |
| `get_deployment_status(namespace, name)` | Deployment health, conditions, recent events |
| `scale_deployment(namespace, name, replicas)` | Scale deployment up/down (0-10 replicas) |
| `restart_deployment(namespace, name)` | Trigger rolling restart |
| `get_namespace_status(namespace)` | Overall namespace health (pod counts, crashing pods) |

**Design Philosophy: Chat-First, CLI-Based Operations**
- UI (ComponentsTab, EnvironmentManagerScreen) is for **configuration and monitoring only**
- Operational actions (deploy, stop, sync) flow through: chat → orchestrator → internal REST
- **Data operations (DB import, seed data):** Agent connects to K8s services directly via DNS
  (`psql -h postgres.env-ns.svc.cluster.local -f /path/to/dump.sql`) — no pod exec needed
- Files come from chat attachments → stored via `DirectoryStructureService` → agent uses CLI tools
- Environment context in `CLAUDE.md` provides K8s DNS hostnames, ports, ENV vars for direct connectivity

**Tool Loading Strategy:**
- CRUD tools are in `ENVIRONMENT_TOOLS` list + `DEVOPS_AGENT_TOOLS` (NOT in ALL_RESPOND_TOOLS_FULL)
- Chat respond node does NOT load environment tools (saves ~40k context tokens)
- DevOps agent gets them via delegation when user requests environment management
- MCP server always exposes all tools (external agents decide what to use)

**Internal REST Endpoints (KtorRpcServer):**

```
# Environment CRUD (InternalEnvironmentRouting.kt)
GET    /internal/environments?clientId=...
GET    /internal/environments/{id}
POST   /internal/environments                     → CreateEnvironmentRequest
DELETE /internal/environments/{id}
POST   /internal/environments/{id}/components     → AddComponentRequest
PUT    /internal/environments/{id}/components/{name} → ConfigureComponentRequest
POST   /internal/environments/{id}/property-mappings → AddPropertyMappingRequest
POST   /internal/environments/{id}/property-mappings/auto-suggest
POST   /internal/environments/{id}/deploy
POST   /internal/environments/{id}/stop
POST   /internal/environments/{id}/sync
POST   /internal/environments/{id}/clone           → CloneEnvironmentRequest
GET    /internal/environments/{id}/status
GET    /internal/environments/templates
POST   /internal/environments/discover              → DiscoverNamespaceRequest
POST   /internal/environments/{id}/replicate        → ReplicateEnvironmentRequest
POST   /internal/environments/{id}/sync-from-k8s    → (no body) K8s→Jervis sync

# K8s resource inspection (existing)
GET  /internal/environment/{ns}/resources?type=pods|deployments|services|all
GET  /internal/environment/{ns}/pods/{name}/logs?tail=100
GET  /internal/environment/{ns}/deployments/{name}
POST /internal/environment/{ns}/deployments/{name}/scale  → {"replicas": N}
POST /internal/environment/{ns}/deployments/{name}/restart
GET  /internal/environment/{ns}/status
```

**Security:**
- All endpoints validate namespace has `managed-by=jervis-server` label (prevents access to non-Jervis namespaces)
- Secrets: only names returned, NEVER values
- Replica scaling capped at 0-10
- ClusterRole `jervis-server-environment-role` grants cross-namespace K8s access (incl. RBAC roles/rolebindings management)
- Coding agent ServiceAccount `jervis-coding-agent` (jervis namespace) with per-environment-namespace Role+RoleBinding (created by `ensureAgentRbac()`)
- Coding agents get full kubectl access ONLY to their assigned namespace(s) via `KUBE_NAMESPACES` env var
- MCP server dual-mode auth: legacy Bearer tokens (`MCP_API_TOKENS`) + OAuth 2.1 with Google IdP (for Claude.ai / iOS connectors)
- OAuth whitelist: only configured Google accounts (`OAUTH_ALLOWED_EMAILS`) can obtain tokens
- OAuth flow: Google login → email verification → Jervis access token (1h) + refresh token (30d)
- OAuth endpoints: `/.well-known/oauth-authorization-server`, `/oauth/register`, `/oauth/authorize`, `/oauth/callback`, `/oauth/token`

**Workspace Integration:**
- `workspace_manager.py` writes `.claude/mcp.json` with HTTP MCP server URL
- `CLAUDE.md` includes tool descriptions, namespace hint, connection strings, credentials, how-to-run
- `.env` / `.env.{component}` files with resolved property mappings for `source .env` usage
- Namespace passed as tool parameter (not env var)
- **kubectl access**: When environment is available, CLAUDE.md includes kubectl instructions with assigned namespace(s)
- Coding agent pod uses ServiceAccount `jervis-coding-agent` with per-namespace RBAC
- `KUBE_NAMESPACES` env var on pod spec tells agent which namespaces it may access
- Agent instructed to call `environment_sync_from_k8s` after kubectl changes to update Jervis DB

**K8s Namespace Discovery + Sync-back (K8s = Source of Truth):**
- `discoverFromNamespace()` reads existing K8s deployments/services/configmaps → creates EnvironmentDocument
- Component type inferred from Docker image name (postgres→POSTGRESQL, mongo→MONGODB, redis→REDIS, etc.)
- `syncFromK8s()` re-reads K8s state, updates existing components, adds new ones, marks missing as STOPPED
- `environment_replicate()` = clone + deploy to new namespace in one atomic step

**Key Files:**

| File | Purpose |
|------|---------|
| `backend/service-mcp/app/main.py` | Unified HTTP MCP server (KB + env CRUD + resource inspection + mongo + orchestrator + OAuth 2.1) |
| `backend/service-mcp/app/oauth_provider.py` | OAuth 2.1 authorization server (DCR, Google IdP, token issuance) |
| `backend/server/.../environment/EnvironmentResourceService.kt` | K8s resource inspection via fabric8 |
| `backend/server/.../environment/EnvironmentK8sService.kt` | Namespace/deployment/service lifecycle |
| `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` | Internal REST endpoints for environment CRUD |
| `backend/server/.../rpc/KtorRpcServer.kt` | Internal REST endpoints routing |
| `backend/service-orchestrator/app/tools/definitions.py` | Tool definitions (ENVIRONMENT_TOOLS, DEVOPS_AGENT_TOOLS) |
| `backend/service-orchestrator/app/tools/executor.py` | Tool execution (environment_* handlers) |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | MCP config injection |
| `k8s/orchestrator-rbac.yaml` | ClusterRole for cross-namespace access + ServiceAccount jervis-coding-agent |

---

## Project Groups

### Overview

Project Groups provide logical grouping of projects within a client. Groups enable:
- Shared resources and connections (inherited from client, extended at group level)
- KB cross-visibility (projects in same group share knowledge base data)
- Environment inheritance (group-level environments apply to all projects)

### Data Model

```
ProjectGroupDocument (MongoDB: project_groups)
├── clientId: ClientId
├── name: String (unique)
├── description: String?
├── connectionCapabilities: List<ProjectConnectionCapability>
├── resources: List<ProjectResource>
└── resourceLinks: List<ResourceLink>

ProjectDocument.groupId: ProjectGroupId?  ← null = ungrouped
ProjectDocument.active: Boolean = true    ← false = closed project
```

### Project Active/Closed Status

Projects can be marked as active or closed via `ProjectDocument.active` (default `true`). Closed projects (`active = false`) are excluded from all background processing:

- **BackgroundEngine**: `findByActiveTrue()` — idle task generation skips closed projects
- **GitRepositoryService**: `findByActiveTrue()` — git sync skips closed projects
- **MergeRequestContinuousIndexer**: `findByActiveTrue()` — MR/PR polling skips closed projects
- **CentralPoller**: `findByResourcesConnectionIdAndActiveTrue()` — resource polling skips closed projects

**UI**: `ProjectEditForm.kt` shows "Aktivní projekt" checkbox. `ProjectsSettings.kt` displays "(uzavřeno)" label next to closed projects.

**DTO**: `ProjectDto.active: Boolean = true` — propagated across KMP platforms.

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../projectgroup/ProjectGroupDocument.kt` | MongoDB document |
| `backend/server/.../projectgroup/ProjectGroupService.kt` | CRUD |
| `backend/server/.../projectgroup/ProjectGroupMapper.kt` | Document ↔ DTO |
| `shared/common-dto/.../dto/ProjectGroupDto.kt` | Cross-platform DTO |

---

## Notification System

Hybrid push/local notification architecture for real-time user alerts.

### Delivery Channels

| Channel | When Used | Capabilities |
|---------|-----------|-------------|
| **kRPC WebSocket** | App running (foreground/background) | Immediate delivery, all event types |
| **FCM Data Messages** | App killed or not connected (Android) | Remote push via Firebase |
| **APNs HTTP/2** | App killed or not connected (iOS) | Native Apple push via Pushy library |
| **Desktop OS** | macOS/Windows/Linux, app running | osascript / SystemTray / notify-send |
| **In-app Dialog** | Approval events, any platform | Approve/Deny buttons with reason input |

### Notification Types

| Type | Event | Urgency | Actions |
|------|-------|---------|---------|
| **Approval** | `UserTaskCreated(isApproval=true)` | HIGH | Approve / Deny (with reason) |
| **User Task** | `UserTaskCreated(isApproval=false)` | NORMAL | Tap to open app |
| **Error** | `ErrorNotification` | NORMAL | Informational |
| **Meeting State** | `MeetingStateChanged` | NORMAL | UI updates meeting list/detail in real-time |
| **Transcription Progress** | `MeetingTranscriptionProgress` | NORMAL | UI shows Whisper progress % + last segment text |
| **Orchestrator Progress** | `OrchestratorTaskProgress` | NORMAL | UI shows node/goal/step progress |
| **Orchestrator Status** | `OrchestratorTaskStatusChange` | NORMAL | UI shows done/error/interrupted |
| **Memory Graph Changed** | `MemoryGraphChanged` | NORMAL | UI refreshes Paměťový graf panel (500ms debounce) |
| **Qualification Progress** | `QualificationProgress` | NORMAL | UI shows qualification step progress |

### Event Flow

```
Python Orchestrator → node transition
  → POST /internal/orchestrator-progress
    → KtorRpcServer → update stateChangedAt timestamp on TaskDocument
    → NotificationRpcImpl.emitOrchestratorTaskProgress() [kRPC stream]
    → MainViewModel.handleGlobalEvent() → QueueViewModel.handleOrchestratorProgress()

Python Orchestrator → completion/error/interrupt
  → POST /internal/orchestrator-status
    → KtorRpcServer → OrchestratorStatusHandler.handleStatusChange()
    → NotificationRpcImpl.emitOrchestratorTaskStatusChange() [kRPC stream]
    → MainViewModel.handleGlobalEvent() → QueueViewModel.handleOrchestratorStatusChange()

Python Orchestrator → vertex status change (memory graph)
  → POST /internal/memory-graph-changed
    → KtorRpcServer → NotificationRpcImpl.emitMemoryGraphChanged() [broadcast ALL clients]
    → MainViewModel.handleGlobalEvent() → ChatViewModel.loadMemoryGraph() [500ms debounce]

Python Orchestrator → interrupt (approval required)
  → OrchestratorStatusHandler → UserTaskService.failAndEscalateToUserTask()
    → NotificationRpcImpl.emitUserTaskCreated() [kRPC stream]
    → FcmPushService.sendPushNotification() [FCM → Android]
    → ApnsPushService.sendPushNotification() [APNs HTTP/2 → iOS]
  → MainViewModel.handleGlobalEvent() → NotificationViewModel.handleUserTaskCreated()
    → UserTaskNotificationDialog (approval/clarification)
```

### Ephemeral Prompts — Pass-Through Q&A (no TaskDocument)

Pod agents (O365 browser, WhatsApp browser, meeting bot) sometimes need a
direct Yes/No answer from the user that is **not work Jervis owns**. The
classic example is the off-hours `auth_request` push: Teams pod idled for
hours, its session cookie expired, it wants the user to confirm before
re-logging in. That question has:

- no history worth keeping (it's not a conversation)
- no KB content worth indexing (there are no entities / topics)
- no priority or classification to determine (the pod already decided it
  needs to ask)
- no follow-up work Jervis would schedule after the answer (the pod takes
  over again)

Routing such a prompt through the full TaskDocument lifecycle is harmful —
it fires the qualifier, runs KB ingest, shows up in the sidebar "K
vyřízení" list as a first-class task, and persists in history forever.

The ephemeral-prompt path bypasses all of that:

```
pod agent (Teams) → notify(kind="auth_request", connectionId=X)
  → ServerO365SessionGrpcImpl: kind=="auth_request" short-circuit
    → EphemeralPromptRegistry.register(kind, clientId, connectionId)
      → NotificationRpcImpl.emitUserTaskCreated(
          taskId = prompt.id,
          ephemeralPromptId = prompt.id,
          ephemeralPromptKind = "auth_request",
          isApproval = true,
          ...)
    → FCM / APNs push (data.type = "ephemeral_prompt")
  → NotificationViewModel.handleUserTaskCreated
    → UserTaskNotificationDialog → AuthRequestContent (Povolit / Odmítnout)
  → user taps "Povolit"
    → NotificationViewModel.approveTask(promptId)
      → detects ephemeralPromptId → IUserTaskService.answerEphemeralPrompt
        → UserTaskRpcImpl: EphemeralPromptRegistry.consume(promptId)
          → kind=="auth_request" → ConnectionApprovalService.approveRelogin
            → O365BrowserPoolGrpcClient.pushInstruction → pod resumes login
          → NotificationRpcImpl.emitUserTaskCancelled (clears dialog on other devices)
```

Key differences vs. a regular USER_TASK (`urgent_message`,
`meeting_invite`, `error`):

| aspect | ephemeral prompt | TaskDocument-backed USER_TASK |
|--------|------------------|-------------------------------|
| persistence | in-memory only (15-min TTL) | MongoDB `taskDocuments` |
| sidebar | never appears | shows up as "K vyřízení" card |
| KB ingest | no | yes (indexing pipeline) |
| qualifier | no | yes (needsQualification flag) |
| history | none | ChatMessageDocument log |
| answer RPC | `answerEphemeralPrompt` | `sendToAgent` |
| survives server restart | no (pod re-asks) | yes |

Scope (2026-04-22): only `auth_request` uses the ephemeral path. `mfa`
already had a push-only short-circuit (pod self-heals on the phone, no
server-side answer needed). `urgent_message`, `meeting_invite`, `error`
keep the TaskDocument path because Jervis genuinely owns follow-up work
(reply history, recording dispatch, retry/escalation). Future candidates
(e.g. `meeting_alone_check`) can be added by extending the switch in
`ServerO365SessionGrpcImpl.notify` and adding the corresponding branch in
`UserTaskRpcImpl.answerEphemeralPrompt`.

### Cross-Platform Architecture

```
expect class PlatformNotificationManager
├── jvmMain:    macOS osascript, Windows SystemTray, Linux notify-send
├── androidMain: NotificationCompat + BroadcastReceiver + action buttons
└── iosMain:    UNUserNotificationCenter + UNNotificationAction

expect object PushTokenRegistrar
├── registerTokenIfNeeded()  — upload OS push handle, dedup per token
├── announceContext(clientId) — rewire device→client binding on each reconnect
├── androidMain: FCM token → registerToken(platform="android")
├── iosMain:    IosTokenHolder.apnsToken → registerToken(platform="ios")
└── jvmMain:    desktop registry row (no push handle) on Windows/Linux;
                macOS routes through the apps/macApp native wrapper for APNs
                (Swift host registers, forwards token via IPC to JVM)

NotificationActionChannel (MutableSharedFlow)
├── Android: NotificationActionReceiver → emits
├── iOS:     NotificationDelegate.swift → NotificationBridge.kt → emits
└── NotificationViewModel: collects → approveTask/denyTask/replyToTask
```

### Key Files

| File | Purpose |
|------|---------|
| `shared/common-dto/.../events/JervisEvent.kt` | Event model with approval + ephemeral prompt metadata |
| `shared/ui-common/.../notification/PlatformNotificationManager.kt` | expect class |
| `shared/ui-common/.../notification/NotificationActionChannel.kt` | Cross-platform action callback |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | In-app approve/deny dialog (incl. `AuthRequestContent` for ephemeral prompts) |
| `backend/server/.../infrastructure/notification/EphemeralPromptRegistry.kt` | In-memory store for pass-through Q&A prompts (15-min TTL) |
| `backend/server/.../connection/ConnectionApprovalService.kt` | Shared approveRelogin dispatcher — used by both MCP gRPC impl and ephemeral-prompt answer path |
| `backend/server/.../infrastructure/notification/FcmPushService.kt` | Firebase Cloud Messaging sender (Android) |
| `backend/server/.../infrastructure/notification/ApnsPushService.kt` | APNs HTTP/2 push sender (iOS, Pushy) |
| `backend/server/.../preferences/DeviceTokenDocument.kt` | Device token storage (platform: android/ios) |
| `shared/common-api/.../IDeviceTokenService.kt` | Token registration RPC |
| `shared/ui-common/.../notification/IosTokenHolder.kt` | APNs token holder (Swift → Kotlin bridge) |
| `shared/ui-common/.../notification/PushTokenRegistrar.kt` | expect/actual with split `registerTokenIfNeeded` + `announceContext` |
| `apps/macApp/` | *(planned)* Native Swift/SwiftUI host for macOS. Registers APNs in `AppDelegate.didRegisterForRemoteNotificationsWithDeviceToken` and forwards the token + incoming notifications to the JVM Compose Desktop via IPC (Unix socket). JVM desktop keeps running on Windows/Linux unmodified. |

### Device registration — two RPCs, two concerns

The RPC surface separates the **OS-level push handle** from the **device →
client binding**, because they change at very different rates:

```
IDeviceTokenService.registerToken(DeviceTokenDto{deviceId, token, platform, …})
  — called once per token lifetime (first acquisition + rotation).
  — backend upserts by deviceId; clientId on the row is left untouched.

IDeviceTokenService.setActiveContext(DeviceContextDto{deviceId, clientId})
  — called on every (clientId, RpcConnected) pair.
  — refreshes lastSeen + clientId so push routing and the registered event
    flow target the correct recipient after each reconnect.
  — rejects unknown deviceIds with a clear message (token must register first).
```

Client side (`PushTokenRegistrar`) enforces ordering: `registerTokenIfNeeded`
runs before `announceContext` in the same coroutine fed by
`combine(_selectedClientId, connectionManager.state)` + `distinctUntilChanged`.
Reconnects and client switches each trigger a fresh `announceContext`; the
token upload is a no-op unless the token itself rotated.

### macOS background push — native wrapper (planned)

Compose Desktop runs as a JVM process on macOS and cannot register APNs
directly — APNs is gated by bundle signing + `aps-environment` entitlement
on an AppKit-hosted binary. The planned approach mirrors `apps/iosApp`:

- `apps/macApp/` — standalone SwiftUI host, bundle id `com.jervis.macApp`,
  signed with the Developer ID + APS entitlement.
- `AppDelegate` registers APNs, captures the token in
  `didRegisterForRemoteNotificationsWithDeviceToken`, and relays push
  payloads from `didReceiveRemoteNotification`.
- Swift host spawns the Compose Desktop JVM as a child and opens a Unix
  socket for token handoff + notification events. JVM side
  (`PushTokenRegistrar.jvm.kt`, macOS branch) reads from the socket and
  feeds the existing `registerTokenIfNeeded` / `announceContext` flow.
- Windows/Linux JVM builds stay unchanged — they keep the registry-only
  behavior (empty token) and rely on kRPC event streams while the app is
  in foreground.

See KB: `agent://claude-code/device-token-split-and-macos-push`.

---

## K8s Deployment Rules

### Image Tagging: Always `latest`

**All Docker images MUST use the `:latest` tag.** No versioned tags (commit hashes, timestamps).

- Build scripts (`k8s/build_*.sh`) build and push only `:latest`
- K8s Deployments reference `image: registry.damek-soft.eu/jandamek/<service>:latest`
- `imagePullPolicy: Always` on all containers — K8s pulls fresh image on every pod start
- `revisionHistoryLimit: 2` on all Deployments — prevents old ReplicaSet buildup

### Deployment Flow

```
build_*.sh:
  1. docker build -t <registry>/<service>:latest
  2. docker push <registry>/<service>:latest
  3. kubectl apply -f app_<service>.yaml     (ensures YAML changes propagate)
  4. kubectl rollout restart deployment/...   (forces new pod with fresh pull)
  5. kubectl rollout status deployment/...    (waits for healthy rollout)
```

### Why `latest` Only

- PoC stage — no rollback requirements, no multi-version deployments
- Simpler build scripts, no tag management overhead
- `rollout restart` + `Always` pull policy guarantees fresh image every deploy
- Old ReplicaSets cleaned up automatically (`revisionHistoryLimit: 2`)

### Build Scripts Reference

| Script | Service | Type |
|--------|---------|------|
| `build_server.sh` | jervis-server | Gradle + Docker + K8s |
| `build_orchestrator.sh` | jervis-orchestrator | Docker + K8s |
| `build_kb.sh` | jervis-knowledgebase (read+write) | Docker + K8s |
| `build_ollama_router.sh` | jervis-ollama-router | Docker + K8s |
| `build_correction.sh` | jervis-correction | Docker + K8s |
| `build_mcp.sh` | jervis-mcp | Docker + K8s |
| `build_service.sh` | Generic (provider services) | Gradle + Docker + K8s |
| `build_tts.sh` | jervis-tts | Docker + K8s |
| `build_image.sh` | Generic (Job-only images) | Docker only (no K8s Deployment) |

### ConfigMaps & Secrets

Exactly **2 ConfigMaps** in `k8s/configmap.yaml`:

| ConfigMap | Scope | Injection | Content |
|-----------|-------|-----------|---------|
| `jervis-config` | ALL Python microservices | `envFrom` in every Python deployment YAML | Unified env vars |
| `jervis-server-config` | Kotlin server only | Mounted as file (`application.yml`) | Spring Boot config |

**`jervis-config` design:**
- Shared values defined ONCE: `MONGODB_HOST`, `OLLAMA_ROUTER_URL`, `KOTLIN_SERVER_URL`, etc.
- Service-specific values use prefixes: `O365_POOL_*`, `MCP_*`, `ORCHESTRATOR_*`, etc.
- Every Python deployment YAML includes `envFrom: [{configMapRef: {name: jervis-config}}]`
- Python services use pydantic-settings with `Field(validation_alias="PREFIX_FIELD")` for prefixed vars and read shared fields directly (no prefix)
- All settings classes have `model_config = {"extra": "ignore"}` to silently ignore env vars from other services

**Secrets** (`jervis-secrets` K8s Secret):
- Contains `MONGODB_PASSWORD`, `ARANGO_PASSWORD`, API keys, etc.
- Injected individually via `env[].valueFrom.secretKeyRef` in deployment YAMLs (not `envFrom`)

---

## Timezone Management

### Core Rules

- **All backend services run in UTC** — enforced via `TZ=UTC` env var in K8s deployments and `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` for JVM
- **Storage is always UTC** — MongoDB `Instant`, ArangoDB timestamps, Python `datetime.now(timezone.utc)`
- **Client timezone flows from device** — sent in every chat request as `clientTimezone` (e.g., `Europe/Prague`)
- **Last-known timezone** — persisted to GLOBAL `AgentPreference` (key `timezone`) on each chat request, used as fallback by scheduler/calendar when no direct client connection
- **Client apps** display times in the device's system timezone via `TimeZone.currentSystemDefault()`

### LLM Awareness

The system prompt includes both UTC and user local time:
```
## Current time
- UTC: 2026-03-30 14:00 UTC
- User local: 2026-03-30 15:00 (Europe/Prague)
When the user asks about time, always answer in their local timezone.
```

### Scheduled Tasks — Two Timezone Modes

| Type | `followUserTimezone` | Timezone source | Use case |
|------|---------------------|----------------|----------|
| **Automated/cron** | `false` | `cronTimezone` on TaskDocument (immutable, set at creation) | Reports, deploys, recurring jobs |
| **Personal reminder** | `true` | User's CURRENT timezone (from preference, updated on each chat) | "Remind me at 16:00", "5 min before call" |

**How it works:**

1. **Automated tasks** — cron expression `0 9 * * MON` + `cronTimezone=Europe/Prague` → always fires at 9:00 CET/CEST, regardless of where user travels. DST handled by Java `ZoneId`.

2. **Personal reminders** — `scheduledLocalTime=2026-03-31T16:00` + `followUserTimezone=true`. Scheduler recalculates `scheduledAt` (UTC Instant) every loop iteration using user's current timezone. User flies to London → preference updates to `Europe/London` → reminder fires at 16:00 GMT, not 16:00 CET.

**TaskDocument fields:**
```kotlin
val cronTimezone: String?          // Fixed tz for cron (e.g. "Europe/Prague")
val followUserTimezone: Boolean    // true = personal reminder, false = fixed
val scheduledLocalTime: String?    // ISO LocalDateTime (e.g. "2026-03-31T16:00")
```

### Python Services

- **Always** use `datetime.now(timezone.utc)` — never `datetime.now()` or `datetime.utcnow()`
- `from datetime import datetime, timezone` at top of file

### Kotlin Server

- Use `preferenceService.getUserTimezone()` for user-facing time calculations
- Use `Instant.now()` for internal storage (always UTC)
- Never use `ZoneId.systemDefault()` for user-facing operations

---

## KB-Done Callback & Task Routing

### Overview

After KB ingestion completes, the `/internal/kb-done` callback handler saves KB analysis results
(kbSummary, kbEntities, kbActionable) directly to the TaskDocument and makes routing decisions.
The orchestrator then classifies the task type as its first step when picking up QUEUED tasks.

**Pipeline:** NEW → INDEXING → (KB callback) → QUEUED or DONE

**Routing decisions (in `/internal/kb-done` handler):**
- Not actionable / filtered → **DONE** (terminal)
- Simple action (reply_email, schedule_meeting) → **DONE** (with USER_TASK)
- Actionable content → **QUEUED** (orchestrator classifies on pickup)

---

## Foreground Chat (ChatSession)

### Overview

Foreground chat uses the **unified agent** — same `vertex_executor.py` agentic loop as background tasks.
The user chats with Jervis like iMessage/WhatsApp — one global conversation (not per client/project).
Jervis acts as a personal assistant. Each chat message creates a REQUEST vertex in the Paměťový graf.

**Both foreground and background use the same agentic loop** (`vertex_executor.py`):
- Foreground: SSE streaming via `sse_handler.py`, `chat_router.py` routes to correct vertex
- Background: LangGraph StateGraph via `langgraph_runner.py`, delegates to `vertex_executor.py`

### Architecture

```
UI (Compose) ──kRPC──► Kotlin ChatRpcImpl ──HTTP SSE──► Python /chat endpoint
                           │                                    │
                    subscribeToChatEvents()              handle_chat(request)
                    sendMessage()                        ├── register foreground (preempt background)
                    getChatHistory()                     ├── load context (MongoDB motor)
                    archiveSession()                     ├── agentic loop (LLM + tools, max 15)
                           │                             ├── save assistant message
                    SharedFlow<ChatResponseDto>          ├── fire-and-forget compression
                    (replay=0, capacity=200)             └── release foreground
                           │
                    ┌──────▼──────┐
                    │ ChatService │ (Spring @Service)
                    │  - session lifecycle
                    │  - save user message
                    │  - forward to PythonChatClient
                    │  - getHistory (pagination)
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ PythonChat  │ (Ktor HTTP SSE)
                    │  Client     │
                    │  - POST /chat
                    │  - manual SSE line parsing
                    │  - Flow<ChatStreamEvent>
                    └─────────────┘
```

### Data Model

**MongoDB Collections:**

| Collection | Document | Purpose |
|------------|----------|---------|
| `chat_sessions` | `ChatSessionDocument` | Session lifecycle (one active per user, archivable) |
| `chat_messages` | `ChatMessageDocument` | Messages with `conversationId` (= session._id) + `sequence` |
| `chat_summaries` | `ChatSummaryDocument` | LLM-compressed blocks (20 msgs each) |

**ChatSessionDocument:**
```kotlin
data class ChatSessionDocument(
    @Id val id: ObjectId = ObjectId(),
    val userId: String = "jan",
    var title: String? = null,
    var archived: Boolean = false,
    val createdAt: Instant = Instant.now(),
    var lastMessageAt: Instant = Instant.now(),
)
```

**Note:** `ChatMessageDocument.conversationId` replaces the old `taskId` field.
Messages are keyed by `(conversationId, sequence)` for pagination.

### SSE Event Types

Python streams these event types to Kotlin via SSE:

| Type | Description | UI Mapping |
|------|-------------|------------|
| `token` | Streaming response chunk (~40 chars) | `STREAMING_TOKEN` — accumulated in buffer |
| `tool_call` | Tool invocation started | `EXECUTING` — progress indicator |
| `tool_result` | Tool returned result | `EXECUTING` — progress indicator |
| `thinking` | Progress event before each tool call (Czech description) | `PLANNING` — progress indicator |
| `done` | Agentic loop completed | `FINAL` — clear progress, show response |
| `error` | Error occurred (includes partial content from tool_summaries) | `ERROR` — show error message |

**Fake token streaming:** The LLM is called in non-streaming mode (litellm can't reliably stream tool_calls for Ollama). The final response is chunked into ~40-character pieces and emitted as `token` events with small delays for progressive UI rendering.

**Thinking events:** Before each tool call, a `thinking` event is emitted with a Czech human-readable description (e.g., "Hledám v knowledge base: project architecture"). Generated by `_describe_tool_call()` helper.

### LLM Configuration

Chat LLM calls are configured as follows:

- **Priority**: `X-Ollama-Priority: 0` (CRITICAL) — preempts background/indexing tasks in ollama-router queue
- **Context estimation**: Dynamic — `message_tokens + tools_tokens + output_tokens` (same pattern as orchestrator respond node)
- **Tools**: 26 tools (~4000 tokens in JSON) → tier typically `LOCAL_STANDARD` (32k context)
- **Timeout**: `LLM_TIMEOUT_SECONDS` (300s) via `asyncio.wait_for()` on blocking LLM call
- **GPU speed tiers**: ≤48k context = full P40 GPU speed (~30 tok/s); >48k spills to CPU RAM (~7-12 tok/s); handles up to ~250k
- **Blocking mode**: Tool calls use `litellm.acompletion()` (non-streaming) because litellm can't reliably stream tool_calls for Ollama

### System Prompt & Runtime Context

The system prompt is enriched with live runtime data on each chat request:

**Runtime context** (`RuntimeContext` dataclass, loaded via `_load_runtime_context()`):
- **Clients & projects**: Full list with IDs and names (cached 5 min TTL)
- **Pending user tasks**: Count + top N tasks with titles and questions
- **Unclassified meetings**: Count of ad-hoc recordings awaiting classification

**Scope resolution** (embedded in prompt rules):
- UI-selected client/project used as default scope
- User mentions resolved to IDs from the clients list
- Scope changes announced explicitly ("Přepínám na...")
- Ambiguous scope triggers clarification question

Data fetched from Kotlin internal API (`/internal/clients-projects`, `/internal/pending-user-tasks/summary`, `/internal/unclassified-meetings/count`).

### Foreground Graph Decomposition

Complex multi-entity queries (e.g. "compare Rust, Go and TypeScript") are automatically decomposed into parallel graph vertices for faster, focused processing.

**Detection** (`chat_decomposer.py`):
- Quick LLM classification call (LOCAL_COMPACT) decides if query needs decomposition
- Triggers for: named entities to compare, discovery+research (incl. vendor/contractor/supplier discovery in Czech — "najdi firmy", "vyhledej dodavatele"), multi-aspect queries
- Conversation context included to detect follow-ups (which are NOT decomposed)
- Safe attribute access (`getattr`) on response objects — some OpenRouter models (e.g. Step-3.5-flash) return non-standard Choice objects missing `finish_reason`

**Graph structure:**
```
ROOT (COMPLETED placeholder — never executes)
├── INVESTIGATOR "Rust" (READY) ──┐
├── INVESTIGATOR "Go" (READY)   ──┤── DEPENDENCY ──► SYNTHESIS (PENDING → READY → COMPLETED)
└── INVESTIGATOR "TypeScript" (READY) ──┘
```

**Execution optimizations vs. background graphs:**
- **Minimal tools**: Investigators get only `kb_search`, `web_search`, `web_fetch` (3 tools vs 36+ in background). No persistence tools to prevent infinite store loops.
- **Focused prompt**: Research-only system prompt, instructs model to produce TEXT after 2-3 tool calls
- **Tight iteration limits**: Max 10 iterations (vs 100 background), stagnation threshold 3 (vs 5)
- **Summary truncation**: Vertex results capped at 3000 chars for edge payloads to prevent context explosion in synthesis
- **Synthesis vertex**: Type SYNTHESIS with NO tools — just combines upstream text (streaming mode)
- **Root placeholder**: Set to COMPLETED immediately, never resumes (prevents double processing)

**Result flow**: `node_synthesize()` prioritizes SYNTHESIS vertex result over root vertex, avoiding redundant LLM synthesis calls.

### Stop & Disconnect Handling

Two mechanisms for stopping an active chat:

1. **Explicit stop** (`POST /chat/stop`): User presses Stop button → Kotlin `PythonChatClient.stopChat(sessionId)` → sets `asyncio.Event` in `_active_chat_stops` dict → handler checks event at start of each iteration → emits partial content + `done` event

2. **SSE disconnect**: Kotlin `PythonChatClient` closes chat SSE connection → `request.is_disconnected()` detected → same interrupt flow

Both mechanisms save accumulated `tool_summaries` as partial content before stopping.

### Error Recovery

When the LLM call fails mid-loop (timeout, connection error):
- `tool_summaries` list accumulates human-readable summaries of all completed tool calls
- On error: partial content is constructed from summaries + error message
- Partial content saved to MongoDB as assistant message (prevents context loss)
- `error` SSE event includes the partial content for UI display

### Foreground Preemption & Smart Routing

Chat routing uses capability-based route decision (`/route-decision` on Ollama Router):

1. **`max_tier == NONE`** → always local GPU (CRITICAL priority, preempts background)
2. **GPU free** → local GPU (no cost, no preemption needed)
3. **GPU busy + OpenRouter allowed** → cloud (background keeps GPU undisturbed)
4. **GPU busy + no cloud model** → local GPU (waits in queue)

`max_openrouter_tier` is resolved hierarchically: project → group → client → default (`FREE`).

Preemption is **deferred to after route decision** — only triggered when route=local:

```kotlin
// BackgroundEngine.kt
private val activeForegroundChats = AtomicInteger(0)

fun registerForegroundChatStart() { activeForegroundChats.incrementAndGet() }
fun registerForegroundChatEnd() { activeForegroundChats.updateAndGet { if (it > 0) it - 1 else 0 } }
fun isForegroundChatActive(): Boolean = activeForegroundChats.get() > 0
```

Python calls `register_foreground_start()` only on first agentic iteration AND only when `route.target == "local"`.
`register_foreground_end()` always called in `finally` block (counter underflow protected).
BackgroundEngine skips `getNextBackgroundTask()` when `isForegroundChatActive()`.

### Long Message Processing — Pravidla

**KRITICKÉ PRAVIDLO: NIKDY neořezávat zprávy (pre-trim). Veškerý obsah musí být zpracován.**

Ořezávání (truncation) zpráv je nepřípustné. Pokud zpráva nemůže být zpracována v kontextovém okně:

1. **Sumarizovat** — LLM vytvoří strukturovaný souhrn zachovávající VŠECHNY požadavky a detaily
2. **Background task** — Pro zprávy s desítkami/stovkami úkolů: vytvořit background task, který zpracuje vše postupně
3. **Uložit do KB** — Originální zpráva se uloží do KB a agent se k ní může kdykoli vrátit přes `kb_search`

**Co je přípustné zkrátit:**
- UI log/progress info — zobrazit "Zpracovávám..." s indikátorem, ne celý obsah v UI

**Co NIKDY neořezávat:**
- Scope, kontext, výsledky tool calls, uživatelské požadavky
- Agent musí mít vždy možnost vrátit se ke kterémukoliv výsledku
- KB read je relativně rychlé — není třeba cachovat, agent se může zeptat znova

**Správný flow pro dlouhé zprávy:**
```
Zpráva > 16k chars → sumarizovat (LOCAL_FAST, ~5s)
  ├── Sumarizace OK → agentic loop na souhrnu, originál v KB
  └── Sumarizace FAIL → navrhnout background task (NIKDY neořezávat!)
Zpráva > 50 požadavků → automaticky background task
Zpráva < 16k chars → normální agentic loop
```

### Content Reducer (`app/memory/content_reducer.py`)

Centrální modul pro **LLM-based content reduction**. All hard-coded `[:N]` truncation has been REMOVED from the codebase. Content reduction is done exclusively via LLM summarization or whole-message removal.

**Tři funkce:**

| Funkce | Typ | Použití |
|--------|-----|---------|
| `reduce_for_prompt(content, token_budget, purpose, state=)` | async | LLM prompt composition — když content přesáhne token budget |
| `reduce_messages_for_prompt(messages, token_budget, state=)` | async | Batch message building (newest-first, per-msg reduction) |
| `trim_for_display(content, max_chars)` | sync | **POUZE** display/logging (error msgs, UI progress, debug logs) |

**Reduction flow:**
```
content ≤ budget → return as-is (fast path, žádné LLM volání)
content > budget && ≤ 24k tokens → single-pass LOCAL_COMPACT LLM reduction
content > 24k tokens → multi-pass chunked reduction
  └── state provided? → llm_with_cloud_fallback (auto-escalace na Gemini/OpenRouter)
  └── no state? → LOCAL_COMPACT only
LLM reduction fails → return full content (NIKDY neořezávat!)
```

**`trim_for_display` je přípustné POUZE pro:**
- Error messages v logách (`logger.warning("... %s", trim_for_display(err, 200))`)
- UI progress indikátory (`summarize_for_progress()`)
- Debug logging (`summary[:80]` v context_store)

**`trim_for_display` NIKDY pro:**
- Data storage (key_facts, affair messages, KB writes)
- LLM prompt building (context_switch, composer, consolidation)
- Agent output extraction (retention_policy facts)

### Agent Tools

> Tool definitions are in `agent/tool_sets.py`. See [graph-agent-architecture.md](graph-agent-architecture.md) for full tool list.

REQUEST vertex gets base tools (always available) + `request_tools` meta-tool for on-demand categories
(calendar, email, slack, settings, project_management, environment, git, code, meetings, guidelines, queue).
The `settings` category provides MongoDB self-management tools (`mongo_list_collections`, `mongo_get_document`,
`mongo_update_document`) with auto cache invalidation after writes.

**Chat-specific Kotlin internal endpoints:**

| Tool | Endpoint | Purpose |
|------|----------|---------|
| `create_background_task` | `POST /internal/create-background-task` | Create task for background processing |
| `dispatch_coding_agent` | `POST /internal/dispatch-coding-agent` | Dispatch coding agent |
| `search_tasks` | `GET /internal/tasks/search` | Search all tasks |
| `respond_to_user_task` | `POST /internal/respond-to-user-task` | Respond to approval/clarification |
| `classify_meeting` | `POST /internal/classify-meeting` | Classify ad-hoc meeting |
| MCP bridge | `POST /internal/mcp/{tool_name}` | Calendar/Gmail/Slack proxy |
| Cache invalidation | `POST /internal/cache/invalidate` | Invalidate Kotlin caches after MongoDB write |

### Internal API Routing (Ktor Modules)

Chat-specific Kotlin internal endpoints are organized as **Ktor routing modules** (not in KtorRpcServer directly — SOLID/SRP):

| Module | File | Endpoints |
|--------|------|-----------|
| `installInternalChatContextApi()` | `rpc/internal/InternalChatContextRouting.kt` | `/internal/clients-projects`, `/internal/pending-user-tasks/summary`, `/internal/unclassified-meetings/count` |
| `installInternalTaskApi()` | `rpc/internal/InternalTaskApiRouting.kt` | `/internal/tasks/{id}/status`, `/internal/tasks/search`, `/internal/tasks/recent` |
| `installInternalCacheApi()` | `rpc/internal/InternalCacheRouting.kt` | `/internal/cache/invalidate` |

Installed in `KtorRpcServer` routing block via extension functions on `Routing`. Dependencies injected as function parameters (clientService, projectService, taskRepository, userTaskService, guidelinesService).

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../chat/ChatService.kt` | Session management + message coordination |
| `backend/server/.../chat/PythonChatClient.kt` | Ktor HTTP SSE client for Python /chat + stopChat() |
| `backend/server/.../chat/ChatStreamEvent.kt` | SSE event data class |
| `backend/server/.../chat/ChatRpcImpl.kt` | kRPC bridge: UI ↔ ChatService ↔ Python |
| `backend/server/.../rpc/internal/InternalChatContextRouting.kt` | Ktor routing: clients-projects, pending tasks, meetings count |
| `backend/server/.../rpc/internal/InternalTaskApiRouting.kt` | Ktor routing: task status, search, recent tasks |
| `backend/server/.../rpc/internal/InternalCacheRouting.kt` | Ktor routing: cache invalidation after MongoDB writes |
| `shared/common-api/.../service/IChatService.kt` | kRPC interface (subscribeToChatEvents, sendMessage, getChatHistory, archiveSession). `getChatHistory` has `excludeBackground: Boolean = true` for filtering |
| `backend/server/.../chat/ChatSessionDocument.kt` | MongoDB session entity |
| `backend/server/.../chat/ChatSessionRepository.kt` | Spring Data repo |
| `backend/service-orchestrator/app/agent/sse_handler.py` | Chat entry-point: route → vertex_executor → SSE stream |
| `backend/service-orchestrator/app/agent/chat_router.py` | Message → vertex routing (new/resume/answer/direct) |
| `backend/service-orchestrator/app/agent/vertex_executor.py` | Unified agentic loop (shared with background) |
| `backend/service-orchestrator/app/chat/handler_tools.py` | Tool execution handlers |
| `backend/service-orchestrator/app/chat/handler_streaming.py` | LLM calls, token streaming, message saving |
| `backend/service-orchestrator/app/chat/models.py` | ChatRequest, ChatStreamEvent models |
| `backend/service-orchestrator/app/chat/context.py` | ChatContextAssembler (MongoDB motor) |
| `backend/service-orchestrator/app/chat/system_prompt.py` | System prompt builder + RuntimeContext |
| `backend/service-orchestrator/app/tools/ollama_parsing.py` | Shared Ollama JSON tool-call parsing |
| `backend/service-orchestrator/app/tools/kotlin_client.py` | Python HTTP client for Kotlin internal API |
| `backend/service-orchestrator/app/main.py` | FastAPI endpoints incl. /chat, /chat/stop |

### Two-Tier Tool Management

Free OpenRouter models handle fewer tools reliably. Chat uses a two-tier system:

- **10 initial core tools** always sent: `kb_search`, `web_search`, `web_fetch`, `store_knowledge`, `dispatch_coding_agent`, `create_background_task`, `respond_to_user_task`, `check_task_graph`, `answer_blocked_vertex`, and `request_tools` (meta-tool)
- **`request_tools(category)` meta-tool** expands the tool set on demand across 6 categories: `planning` (6 tools), `task_mgmt` (5), `meetings` (4), `memory` (6), `filtering` (3), `admin` (4)

Handler intercepts `request_tools` calls, appends category tools to `selected_tools` (deduplicates), and returns confirmation. Source: `app/chat/tools.py` (`CHAT_INITIAL_TOOLS`, `ToolCategory`, `TOOL_CATEGORIES`).

### Anti-Hallucination Guard

Three-layer defense against hallucinated facts:

1. **Drift guard** (`app/chat/drift.py`): Multi-signal detection (duplicate calls, tool spam, alternating pairs, domain drift, excessive tools). On trigger, injects system message forcing evidence-only response with source URLs. Workflow chains (e.g., `{search, memory, task}`) are exempt from domain drift detection.

2. **Active fact-checking** (`app/guard/fact_checker.py`): Post-response extraction of verifiable claims (file paths, URLs, code refs, phone numbers, ratings, prices) and verification against KB (code_search) and collected web evidence. Produces `FactCheckResult` with overall confidence score and per-claim `VERIFIED`/`UNVERIFIED`/`CONTRADICTED` status. Confidence badge (`high`/`medium`/`low`) sent in SSE `done` metadata.

3. **Source attribution** (`app/chat/source_attribution.py`): `SourceTracker` per request collects KB sources (from `kb_search` results) and web evidence (from `web_search`/`web_fetch` results). Top 5 sources attached to message metadata. Web evidence text passed to fact-checker for real-world entity verification.

**System prompt** enforces per-entity verification workflow: for each real-world entity, require separate `web_search` → `web_fetch` → include only facts from `web_fetch` with source URL.

### Background Message Filtering

Background task results (BACKGROUND_RESULT) are hidden from chat by default to prevent flooding (can be 400+ messages). Three FilterChips in the chat UI control visibility:

- **"Chat"** (default ON): shows regular chat messages (USER_MESSAGE, PROGRESS, FINAL, ERROR)
- **"Tasky"** (default OFF): shows all BACKGROUND_RESULT messages from the current session
- **"K reakci (N)"** (default OFF): shows backgrounds needing user reaction (N = global USER_TASK count)

**Architecture:** Server always loads with `excludeBackground=true` (DB-level filtering via `ChatMessageRepository.findByConversationIdAndRoleNotOrderByIdDesc`). Live background messages arrive via SSE push and are added to `_chatMessages`. Filtering is pure client-side via Compose `remember()` — no server reload on toggle. `ChatHistoryDto` carries `backgroundMessageCount` and `userTaskCount` for chip labels. The dock badge (macOS) sums USER_TASK count across all clients, while the "K reakci" chip shows the same global count from `ChatRpcImpl.taskRepository.countByState(USER_TASK)`.

### Migration from Old Chat Flow

The old foreground chat flow (`IAgentOrchestratorService.subscribeToChat/sendMessage/getChatHistory`) has been removed. Key changes:

- **Removed from `IAgentOrchestratorService`**: `subscribeToChat()`, `sendMessage(ChatRequestDto)`, `getChatHistory(clientId, projectId, ...)`
- **Removed from `AgentOrchestratorRpcImpl`**: `chatStreams`, `emitToChatStream()`, `emitProgress()`, `saveAndMapAttachment()`, and all old chat method implementations
- **`BackgroundEngine`**: `onProgress` callback simplified to logging only (no longer emits to dead SharedFlow)
- **`OrchestratorStatusHandler`**: Removed `emitToChatStream()` calls in handleInterrupted/handleDone/handleError (message persistence kept)
- **`/internal/streaming-token`**: Now a no-op endpoint (returns ok but doesn't relay tokens)
- **`IAgentOrchestratorService`** retains: queue management (`subscribeToQueueStatus`, `getPendingTasks`, `reorderTask`, `moveTask`, `cancelOrchestration`) and task history (`getTaskHistory`)

---

## Chat Router

**Status:** Replaces old Intent Router (deleted).

Chat messages are routed to vertices in the Paměťový graf via `agent/chat_router.py`:

1. `context_task_id` set → `answer_ask_user` (resume blocked vertex)
2. Greeting pattern → `direct_response` (fast LLM, no tools)
3. RUNNING/BLOCKED vertex for client/project → `resume_vertex`
4. Default → `new_vertex` (create REQUEST vertex, run agentic loop)

> See [graph-agent-architecture.md](graph-agent-architecture.md) for details.

---

## Hierarchical Task System

### Overview

Tasks can form parent-child hierarchies for work plan decomposition. Two mechanisms exist:

**Iterative Chat Planning** (`update_work_plan_draft` + `finalize_work_plan` tools):
Agent builds a draft plan incrementally through dialogue with the user. Draft is stored in an affair's `key_facts["__plan_draft__"]` via the memory system (LQM). The plan can be parked ("dej to bokem") and resumed later. When the user approves, `finalize_work_plan` converts the draft into real tasks via `create_work_plan` API.

- `app/chat/work_plan_draft.py` — DraftPlan model, markdown renderer, serialization
- `app/chat/tools.py` — Tool definitions for both planning tools
- `app/chat/handler_tools.py` — Tool handlers (affair-based storage)
- `app/chat/prompts/complex.py` — Iterative planning system prompt
- `app/chat/system_prompt.py` — Active plan injection into system prompt
- UI: `WORK_PLAN_UPDATE` event type → `WorkPlanCard` composable in `ChatMessageDisplay.kt`

**Direct Work Plan** (`create_work_plan` tool):
Creates a root task (BLOCKED state) with child tasks organized in phases with dependency tracking. Used for immediate task creation without iterative planning.

### Task States for Hierarchy

| State | Purpose |
|-------|---------|
| `BLOCKED` | Waiting for dependency tasks (`blockedByTaskIds`) to complete; also used for root tasks undergoing decomposition |

### TaskDocument Hierarchy Fields

```kotlin
val parentTaskId: TaskId? = null,      // Parent task for child tasks
val blockedByTaskIds: List<TaskId> = emptyList(), // Dependencies
val phase: String? = null,             // Phase name (e.g., "architecture")
val orderInPhase: Int = 0,             // Ordering within phase
```

### WorkPlanExecutor

New loop in `BackgroundEngine` (15s interval) that:
1. Finds BLOCKED tasks → checks if ALL `blockedByTaskIds` have state DONE → unblocks to INDEXING
2. Finds BLOCKED root tasks (with children) → if all children DONE → root.state = DONE with summary
3. If any child ERROR → root task escalated to USER_TASK for user attention

Existing loops (execution, indexing) are unaffected — they never see BLOCKED tasks.

### Kotlin Endpoint

`POST /internal/tasks/create-work-plan` — accepts phases with tasks and dependencies, creates root (BLOCKED) + children (BLOCKED/INDEXING).

---

## Unified Chat Stream

### New Message Roles

| Role | Purpose | UI Styling |
|------|---------|------------|
| `BACKGROUND` | Background task result pushed to chat | surfaceVariant, checkmark/error icon, collapsible |
| `ALERT` | Urgent notification pushed to chat | errorContainer border, warning icon, always visible |

### Push Mechanism

```kotlin
// ChatRpcImpl
fun isUserOnline(): Boolean = chatEventStream.subscriptionCount.value > 0
suspend fun pushBackgroundResult(taskTitle, summary, success, taskId, metadata)
suspend fun pushUrgentAlert(sourceUrn, summary, suggestedAction)
```

**OrchestratorStatusHandler** pushes BACKGROUND_RESULT on task done/error (when user is online), including `taskId` in metadata.
The `/internal/kb-done` handler pushes URGENT_ALERT for urgent KB results.

### Interactive Background Results

Background task results in chat include a "Reagovat" button (visible when `taskId` is in metadata).
Clicking sets `contextTaskId` on ChatViewModel — the next user message is sent with `contextTaskId`
so the agent knows which background task the user is responding to.

Flow: `pushBackgroundResult(taskId=...)` → UI card with "Reagovat" → `replyToTask(taskId)` →
`sendMessage(contextTaskId=taskId)` → Python agent receives context about which task to follow up on.

### LLM Context Integration

`ChatContextAssembler` maps BACKGROUND/ALERT roles to `"system"` for LLM with `[Background]`/`[Urgent Alert]` prefixes, so Jervis sees background results and alerts in conversation context.

---

## Guidelines Engine

### Overview

Hierarchical rules engine that provides configurable guidelines at three scope levels: **Global → Client → Project**. Lower scopes override/extend higher ones via deep merge.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Settings UI                               │
│  (GuidelinesSettings.kt — three-tab scope selector)         │
└────────────────────────────┬────────────────────────────────┘
                             │ kRPC
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                 Kotlin Server                                │
│                                                             │
│  IGuidelinesService → GuidelinesRpcImpl                     │
│        → GuidelinesService (5-min cache)                    │
│        → GuidelinesRepository (MongoDB: guidelines)         │
│                                                             │
│  Internal REST API:                                         │
│    GET  /internal/guidelines/merged?clientId=&projectId=    │
│    GET  /internal/guidelines?scope=&clientId=&projectId=    │
│    POST /internal/guidelines                                │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────┐
│              Python Orchestrator                             │
│                                                             │
│  GuidelinesResolver (context/guidelines_resolver.py)        │
│    → resolve_guidelines() — cached merged load              │
│    → format_guidelines_for_prompt() — system prompt section │
│    → format_guidelines_for_coding_agent() — CLAUDE.md       │
│                                                             │
│  Injection Points:                                          │
│    1. Foreground chat system prompt (RuntimeContext)         │
│    2. Background task system prompt (handler.py)            │
│    3. Delegation planner (plan_delegations.py)              │
│    4. Specialist agent agentic loop (BaseAgent)             │
│                                                             │
│  Chat Tools:                                                │
│    get_guidelines / update_guideline                        │
└─────────────────────────────────────────────────────────────┘
```

### Scope Resolution

```
PROJECT guidelines  (most specific, clientId + projectId)
    ↑ overrides
CLIENT guidelines   (clientId, no projectId)
    ↑ overrides
GLOBAL guidelines   (no clientId, no projectId)
```

### Categories

Six categories, each with typed rules: `coding`, `git`, `review`, `communication`, `approval`, `general`.

### Merge Rules

- **Lists** (forbiddenPatterns, protectedBranches): concatenated from all scopes
- **Nullable scalars** (maxFileLines, commitMessageTemplate): lower scope wins
- **Booleans** (mustHaveTests, requireJiraReference): OR — if any scope enables, it's enabled
- **Maps** (namingConventions, languageSpecific): merged, lower scope keys win

### Caching

- **Kotlin server**: `GuidelinesService` uses `ConcurrentHashMap` with 5-min TTL
- **Python orchestrator**: `guidelines_resolver._guidelines_cache` with configurable TTL (`ORCHESTRATOR_GUIDELINES_CACHE_TTL`, default 300s)
- Cache invalidation: on any write via `updateGuidelines()` or `update_guideline` tool

### Delegation Integration

When the multi-agent delegation system is enabled (`use_delegation_graph=true`):
1. **plan_delegations** node resolves guidelines and includes them in the LLM planning prompt
2. Guidelines constraints (forbidden patterns, forbidden files, test requirements) are extracted and propagated to each `DelegationMessage.constraints`
3. **BaseAgent._agentic_loop** enriches the specialist agent's system prompt with formatted guidelines
4. All injection is non-blocking — guideline resolution failures are logged at debug level and do not block execution

---

## O365 Gateway

### Overview

Multi-tenant Microsoft 365 integration via browser-session relay. Three services:

1. **`jervis-o365-gateway`** (Kotlin/Ktor) — Stateless API. Receives tool calls from MCP/orchestrator, fetches Bearer tokens from browser pods, calls Microsoft Graph API.
2. **`jervis-browser-{connectionId}`** (Python/FastAPI/Playwright) — Dynamic Deployment per connection. Each O365/Teams connection gets its own isolated pod with dedicated PVC for browser profiles.
3. **`jervis-vnc-router`** (OpenResty/nginx+Lua) — Centralized VNC proxy. Parses connectionId from token/cookie, routes to correct browser pod.

### Architecture Flow

```
MCP Tool Call → O365 Gateway → Browser Pod (per connection) → Graph API

UI → Server → BrowserPodManager → K8s API
                                    ↓
                            Deployment: jervis-browser-{connId}
                            Service:    jervis-browser-{connId}
                            PVC:        jervis-browser-{connId}-data

VNC Access:
  User → jervis-vnc.damek-soft.eu → Ingress → VNC Router (nginx+Lua)
           ↓ parses connId from token/cookie
         http://jervis-browser-{connId}:8090 → noVNC
```

### Key Design Decisions

- **Dynamic pod per connection**: Each connection gets isolated Deployment+Service+PVC. Crash of one connection doesn't affect others. Server creates/deletes pods via Fabric8 K8s client (`BrowserPodManager`).
- **CONNECTION_ID via env**: Pod receives only `CONNECTION_ID` — reads credentials from MongoDB on init. Credentials never in env/ConfigMap.
- **Self-restore from PVC**: Pod saves `init-config.json` to PVC after successful init. On cluster restart, pod self-restores without server intervention.
- **VNC Router**: Centralized nginx+Lua proxy. Token format `{connId}_{randomHex}` — router parses connId, proxies to correct pod. Handles HTTP + WebSocket (noVNC).
- **Raw HTTP over Graph SDK**: Uses Ktor HTTP client for Graph API calls instead of the heavy Graph SDK. Simpler, fewer dependencies.
- **Browser-based token relay**: For tenants that don't allow OAuth app registration in Azure AD. Playwright intercepts `Authorization: Bearer` headers from `graph.microsoft.com` requests.
- **Per-client rate limiting**: 4 req/s safety margin under Graph API's 5 req/s Teams limit.
- **Persistent browser profiles**: Cookies and local storage persisted to per-pod PVC (5Gi), surviving pod restarts.

### VLM Screen Scraping (Conditional Access Fallback)

When Graph API tokens cannot be captured (Conditional Access blocks Graph API but allows web UI), the browser pool falls back to **VLM screen scraping**:

```
Browser Pool (Playwright, headed mode with Xvfb)
  → Periodic screenshots of Teams/Outlook/Calendar tabs
  → VLM analysis (Qwen3-VL via ollama-router)
  → Extracted messages → MongoDB (o365_scrape_messages, state=NEW)
  → Kotlin O365PollingHandler reads NEW, creates TeamsMessageIndexDocument
  → Marks as PROCESSED (or SKIPPED if resource filter doesn't match)
```

**Session lifecycle notifications** (Python → Kotlin push):
- Browser pool detects MFA requirement or session expiry
- `kotlin_callback.py` POSTs to `/internal/o365/session-event`
- Kotlin creates USER_TASK with VNC link + sends FCM/APNs push

**Push-based capability discovery**:
- After browser session login, tab_manager opens tabs for each requested service (chat/email/calendar)
- Tabs that redirect to marketing/login pages are detected as unavailable and closed
- `tab_manager.get_available_capabilities()` returns only capabilities for successfully opened tabs
- Python POSTs discovered capabilities to `POST /internal/o365/capabilities-discovered`
- Kotlin server updates `connection.availableCapabilities` and transitions state `DISCOVERING → VALID`
- Connection state flow: `NEW → (login) → DISCOVERING → (capabilities callback) → VALID`
- UI shows "Zjišťuji dostupné služby..." spinner during DISCOVERING state
- `listO365Resources()` guards on `availableCapabilities` — returns empty for undiscovered capabilities
- `POST /scrape/{client_id}/discover` accepts `{"capability": "CHAT_READ"}` body — returns `service_unavailable: true` for missing tabs

**Persistent resource discovery**:
- VLM scraping auto-discovers chats/channels/teams from sidebar
- Stored in `o365_discovered_resources` collection (upsert by connectionId + externalId)
- Settings UI reads from DB (cached 1h) instead of on-demand browser pool calls

**Historical backfill**:
- `POST /scrape/{client_id}/backfill` — navigates to channel, scrolls up, screenshots + VLM extraction
- Messages stored in `o365_scrape_messages` with standard NEW state for polling pipeline

**MongoDB collections** (written by Python, read by Kotlin):
- `o365_scrape_messages` — individual extracted messages (state: NEW → PROCESSED/SKIPPED)
- `o365_scrape_results` — latest VLM analysis per tab (used by screen_scraper caching)
- `o365_discovered_resources` — persistent resource inventory for settings UI

### MCP Tools

| Tool | Graph API Endpoint | Phase |
|------|--------------------|-------|
| `o365_teams_list_chats` | `GET /me/chats` | 1 |
| `o365_teams_read_chat` | `GET /me/chats/{id}/messages` | 1 |
| `o365_teams_send_message` | `POST /me/chats/{id}/messages` | 1 |
| `o365_teams_list_teams` | `GET /me/joinedTeams` | 1 |
| `o365_teams_list_channels` | `GET /teams/{id}/channels` | 1 |
| `o365_teams_read_channel` | `GET /teams/{id}/channels/{id}/messages` | 1 |
| `o365_teams_send_channel_message` | `POST /teams/{id}/channels/{id}/messages` | 1 |
| `o365_session_status` | Browser pool session query | 1 |
| `o365_mail_list` | `GET /me/mailFolders/{folder}/messages` | 2 |
| `o365_mail_read` | `GET /me/messages/{id}` | 2 |
| `o365_mail_send` | `POST /me/sendMail` | 2 |
| `o365_calendar_events` | `GET /me/events` or `/me/calendarView` | 3 |
| `o365_calendar_create` | `POST /me/events` | 3 |
| `o365_files_list` | `GET /me/drive/root/children` | 4 |
| `o365_files_download` | `GET /me/drive/items/{id}` | 4 |
| `o365_files_search` | `GET /me/drive/root/search(q='...')` | 4 |

### Deployment

- Gateway: `Deployment` (stateless, 256-512Mi)
- Browser Pods: Dynamic `Deployment` per connection + `PVC` 5Gi (managed by `BrowserPodManager`, label `managed-by=jervis-browser-pod`)
- VNC Router: `Deployment` (stateless, 64-256Mi, OpenResty nginx+Lua)

## Chat Platform Integrations (Teams, Slack, Discord)

Three chat platforms share the same polling→indexing→task pipeline architecture:

```
CentralPoller
  → PollingHandler (per platform)
    → Fetches messages from external API
    → Saves to MongoDB as NEW state
  → ContinuousIndexer (per platform)
    → Reads NEW documents from MongoDB
    → Creates tasks (CHAT_PROCESSING / SLACK_PROCESSING / DISCORD_PROCESSING)
    → Marks as INDEXED
```

### Platform-Specific Handlers

| Platform | PollingHandler | API | Auth | Resource Key |
|----------|---------------|-----|------|-------------|
| Microsoft Teams | `O365PollingHandler` | Triple-mode: Graph API (OAuth2) / O365 Gateway (browser token) / VLM scrape (MongoDB) | OAuth2 / Browser session (token) / Browser session (VLM) | `teamId/channelId` or `chatName` |
| WhatsApp | `WhatsAppPollingHandler` | VLM scrape (MongoDB) from WhatsApp Web browser session | Browser session (QR code) | `chatName` |
| Slack | `SlackPollingHandler` | Slack Web API (`conversations.list`, `conversations.history`) | Bot Token (`xoxb-...`) via BEARER | `channelId` |
| Discord | `DiscordPollingHandler` | Discord REST API v10 (`/guilds`, `/channels`, `/messages`) | Bot Token via BEARER (`Bot` prefix) | `guildId/channelId` |

### Continuous Indexers

After the 2026-04-11 refactor every indexer creates `TaskTypeEnum.SYSTEM`
tasks; source identity (teams/whatsapp/slack/discord/…) is encoded in
`SourceUrn.scheme()`, NOT in the task type. See
[structures.md § TaskTypeEnum](structures.md#tasktypeenum--3-pipeline-categories-post-2026-04-11-refactor).

| Platform | Indexer | sourceUrn scheme | Topic ID Format |
|----------|---------|------------------|-----------------|
| Teams | `TeamsContinuousIndexer` | `teams` | `teams-channel:{teamId}/{channelId}` or `teams-chat:{chatId}` |
| WhatsApp | `WhatsAppContinuousIndexer` | `whatsapp` | `whatsapp-chat:{chatName}` |
| Slack | `SlackContinuousIndexer` | `slack` | `slack-channel:{channelId}` |
| Discord | `DiscordContinuousIndexer` | `discord` | `discord-channel:{guildId}/{channelId}` |

### Resource Routing (same hierarchy as email/git)

- **Client has "all"** → everything indexes to client level
- **Project claims specific channels** → those channels index to project, rest to client
- **Chats (Teams 1:1/group)** → always client level (not channel-specific)

### SourceUrn Factories

- `SourceUrn.teams(connectionId, messageId, channelId?, chatId?)`
- `SourceUrn.whatsapp(connectionId, messageId, chatName?)`
- `SourceUrn.slack(connectionId, messageId, channelId)`
- `SourceUrn.discord(connectionId, messageId, channelId, guildId?)`

### Key Files

| File | Description |
|------|-------------|
| `backend/server/.../teams/O365PollingHandler.kt` | Teams polling via O365 Gateway |
| `backend/server/.../slack/SlackPollingHandler.kt` | Slack polling via Web API |
| `backend/server/.../discord/DiscordPollingHandler.kt` | Discord polling via REST API |
| `backend/server/.../teams/TeamsContinuousIndexer.kt` | Teams NEW→INDEXED pipeline |
| `backend/server/.../slack/SlackContinuousIndexer.kt` | Slack NEW→INDEXED pipeline |
| `backend/server/.../discord/DiscordContinuousIndexer.kt` | Discord NEW→INDEXED pipeline |
| `backend/server/.../whatsapp/WhatsAppPollingHandler.kt` | WhatsApp polling — reads VLM-scraped messages from MongoDB |
| `backend/server/.../whatsapp/WhatsAppContinuousIndexer.kt` | WhatsApp NEW→INDEXED pipeline |
| `backend/server/.../whatsapp/WhatsAppMessageIndexDocument.kt` | WhatsApp message tracking in MongoDB |
| `backend/server/.../whatsapp/WhatsAppScrapeMessageDocument.kt` | VLM-scraped WhatsApp message entity |
| `backend/server/.../rpc/internal/InternalWhatsAppSessionRouting.kt` | WhatsApp session callback API (session events + capabilities) |
| `backend/service-whatsapp-browser/` | Python WhatsApp Web browser session (Playwright + VLM scraping) |
| `backend/server/.../teams/TeamsMessageIndexDocument.kt` | Teams message tracking in MongoDB |
| `backend/server/.../slack/SlackMessageIndexDocument.kt` | Slack message tracking in MongoDB |
| `backend/server/.../discord/DiscordMessageIndexDocument.kt` | Discord message tracking in MongoDB |
| `backend/server/.../teams/O365ScrapeMessageDocument.kt` | VLM-scraped message entity (maps to `o365_scrape_messages`) |
| `backend/server/.../teams/O365DiscoveredResourceDocument.kt` | Persistent discovered O365 resources |
| `backend/server/.../teams/O365ScrapeMessageRepository.kt` | Scrape message queries (by connectionId + state) |
| `backend/server/.../teams/O365DiscoveredResourceRepository.kt` | Discovered resource queries |
| `backend/server/.../rpc/internal/InternalO365SessionRouting.kt` | Session callback API (MFA/expiry → USER_TASK + push) + capabilities discovery endpoint |
| `backend/service-o365-browser-pool/app/kotlin_callback.py` | Python→Kotlin session state + capabilities discovery notification |
| `backend/service-o365-browser-pool/app/scrape_storage.py` | MongoDB storage for scrape messages + discovered resources |
| `backend/server/.../chat/ChatReplyService.kt` | Outbound message sending (stubs, EPIC 11-S5) |

---

## Watch Apps (watchOS + Wear OS)

### Overview

Companion watch apps for ad-hoc audio recording and voice chat commands. Both platforms send audio data to the paired phone, which handles upload via `RecordingUploadService`.

### watchOS App (`apps/watchApp/`)

SwiftUI watchOS app with two main actions:

- **Ad-hoc Recording** — starts recording on the watch, streams audio chunks to iPhone via WatchConnectivity
- **Chat Voice Command** — records a voice command and sends it to the phone for chat submission

**Phone-side integration:** `WatchSessionManager` (iOS) receives `WCSessionDelegate` data transfers and feeds audio chunks into `RecordingUploadService` for server upload.

### Wear OS App (`apps/wearApp/`)

Compose for Wear OS app (registered in `settings.gradle.kts` as `:apps:wearApp`):

- **Recording Screen** — ad-hoc recording with start/stop controls
- **Chat Screen** — voice command recording for chat

Uses **DataLayer API** (`Wearable.getDataClient()`) for phone communication. The phone-side `DataLayerListenerService` receives audio data and delegates to `RecordingUploadService`.

### Communication Flow

```
Watch (audio capture)
  → WatchConnectivity (iOS) / DataLayer API (Android)
    → Phone receives audio chunks
      → RecordingUploadService → server upload
```

### Voice Assistant Integration (Siri + Google Assistant)

All platforms support voice assistant activation for hands-free interaction:

#### Apple (iOS + watchOS) — App Intents + Shortcuts

Uses `AppShortcutsProvider` (iOS 16+ / watchOS 9+) for automatic Siri phrase registration:

- **"Siri, Jervis [dotaz]"** — sends text query to `/api/v1/chat/siri`, returns spoken response
- **"Siri, nahravej s Jervisem"** — opens app in recording mode
- **"Siri, Jervis chat"** (watchOS) — opens chat view on watch

Files:
- `apps/iosApp/iosApp/JervisIntents.swift` — `AskJervisIntent`, `StartRecordingIntent`, `JervisShortcutsProvider`
- `apps/iosApp/iosApp/JervisApiClient.swift` — HTTP client for Siri → backend
- `apps/watchApp/JervisWatch/JervisIntents.swift` — watchOS intents (includes `StartWatchChatIntent`)
- `apps/watchApp/JervisWatch/WatchJervisApiClient.swift` — watchOS HTTP client

#### Android (phone + Wear OS) — App Actions

Uses `shortcuts.xml` for Google Assistant integration:

- **"Hey Google, ask Jervis [query]"** — launches `VoiceQueryActivity`, sends query, speaks response via TTS
- **"Hey Google, open Jervis"** — opens main app

Files:
- `apps/mobile/src/androidMain/res/xml/actions.xml` — App Actions capability definitions
- `apps/mobile/src/androidMain/kotlin/.../VoiceQueryActivity.kt` — transparent Activity for voice queries
- `apps/wearApp/src/main/res/xml/actions.xml` — Wear OS App Actions
- `apps/wearApp/src/main/kotlin/.../VoiceQueryActivity.kt` — Wear OS voice query handler

#### Backend Endpoint

`POST /api/v1/chat/siri` — public REST endpoint for voice assistant queries.

- Accepts: `{ "query": "...", "source": "siri|google_assistant|...", "clientId?": "...", "projectId?": "..." }`
- Returns: `{ "response": "...", "taskId": "...", "state": "DONE|PROCESSING|..." }`
- Creates a task (QUEUED, skip KB indexing), polls up to 25s for completion
- File: `backend/server/src/main/kotlin/com/jervis/rpc/VoiceChatRouting.kt`

---

## TTS Service (Piper)

### Overview

Text-to-speech service using **Piper TTS** (fast, CPU-only neural TTS). Deployed as a FastAPI microservice.

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/tts` | POST | Returns complete WAV audio for input text |
| `/tts/stream` | POST | Returns chunked WAV audio (streaming) |

### Deployment

- **Service:** `backend/service-tts/` (FastAPI + Piper)
- **CPU-only** — no GPU required
- **K8s Deployment** — standard pod, built via `k8s/build_tts.sh`

### Client Integration

TTS playback from chat bubbles uses SSE streaming (`POST /api/v1/tts/stream`) via the
platform-specific `postSseStream()` function (`shared/domain/.../di/SseClient.kt`).
Each sentence is synthesized and streamed as a separate `tts_audio` SSE event, so
playback starts immediately without waiting for the full text.

**SSE platform implementations:**
- **JVM/Android** (`SseClient.jvm.kt`, `SseClient.android.kt`): Ktor CIO engine — `bodyAsChannel().readUTF8Line()` works natively
- **iOS** (`SseClient.ios.kt`): Native `NSURLSession` with `NSURLSessionDataDelegate` — Darwin Ktor engine buffers the entire response (no SSE streaming), so we bypass it with direct Foundation networking

**Voice chat** uses WebSocket (`ws://server:5500/api/v1/voice/ws`) for continuous bidirectional
voice sessions. Client sends 100ms PCM chunks (binary frames), server detects speech boundaries
(EnergyVad), transcribes via Whisper GPU on VD, classifies intent, responds with text + TTS audio
(binary frames). Anti-echo via tts_playing/tts_finished control messages. All platforms:
- **JVM/Android**: Ktor CIO WebSocket client (`VoiceWebSocketClient.jvm.kt`)
- **iOS**: Native `NSURLSessionWebSocketTask` (`VoiceWebSocketClient.ios.kt`)
- **watchOS**: Native `URLSessionWebSocketTask` (`WatchVoiceSession.swift`)

Shared session logic in `VoiceSessionManager.kt` (commonMain) manages AudioRecorder + WebSocket + AudioPlayer.

---

## Thought Map (Navigation Layer)

> Full spec: [thought-map-spec.md](thought-map-spec.md). KB integration: [knowledge-base.md](knowledge-base.md#thought-map-navigation-layer).

Thought Map adds a navigation layer over the KB graph using **spreading activation** — replaces flat `kb_search` with proactive context traversal.

```
┌─────────────────────────────────────────────────────────────┐
│              ThoughtNodes + ThoughtEdges                     │
│  [Auth problem]──causes──[Deploy blocked]                   │
│       │                        │                            │
│  ThoughtAnchors           ThoughtAnchors                    │
│       │                        │                            │
│  ─────┼────────────────────────┼────────────────────────────│
│       ▼                        ▼                            │
│  KnowledgeNodes + KnowledgeEdges + Joern CPG                │
│  [AuthService.kt]──calls──[OAuth2Client.kt]                 │
└─────────────────────────────────────────────────────────────┘
```

### Collections

3 global collections with multi-tenant `clientId` filtering (same pattern as `KnowledgeNodes`):

- **ThoughtNodes** — high-level insights/decisions/problems with 384-dim embeddings
- **ThoughtEdges** — relationships between thoughts (causes, blocks, same_domain)
- **ThoughtAnchors** — edges from ThoughtNodes → KnowledgeNodes

### Chat Flow Integration

1. User message arrives → `load_runtime_context()` calls `prefetch_thought_context()`
2. KB service: embed query → top-5 entry points → AQL 1..3 hops spreading activation → top-20 results
3. Formatted context (~5000 tokens) injected into system prompt before LLM call
4. Post-response (fire-and-forget): Hebbian reinforcement + pattern-based thought extraction

### Maintenance

Background scheduler in KB service — light maintenance (decay + merge) on GPU idle, heavy (archive + Louvain hierarchy) during quiet hours (01:00–06:00).

---

## Voice Cloning TTS

### Overview

XTTS-v2 (Coqui) voice cloning pipeline that enables Jervis to respond with the user's own voice. Runs on VD GPU VM (P40).

### Pipeline

```
Meeting recordings → extract_voice_samples.py (WhisperX diarization)
    → voice samples (WAV + transcripts)
    → finetune_xtts.py (XTTS-v2 fine-tuning, Coqui trainer)
    → custom speaker model (model.pth + speaker_embedding.pt)
    → deploy to TTS service speakers directory
```

### Components

| Component | File | Purpose |
|-----------|------|---------|
| Voice Sample Extraction | `scripts/extract_voice_samples.py` | WhisperX diarization + segment extraction from meeting recordings |
| XTTS Fine-tuning | `scripts/finetune_xtts.py` | Fine-tune XTTS-v2 with extracted voice samples |
| TTS Service | `backend/service-tts/app/xtts_server.py` | XTTS-v2 TTS with multi-speaker support |

### Multi-Speaker Architecture

TTS service supports multiple speakers via the `voice` field in TTS requests:

- `POST /tts {"text": "...", "voice": "jan-damek"}` — use fine-tuned voice
- `POST /tts {"text": "...", "voice": "default"}` — use default reference voice
- `POST /tts {"text": "..."}` — auto-select default

Speaker sources (in priority order):
1. Pre-computed `.pt` embedding files in `speakers/` directory
2. WAV reference files in `speakers/` directory (computed on-demand, cached)
3. Default speaker reference (`TTS_SPEAKER_WAV` env)

### Deployment

All scripts run on VD GPU VM (never K8s CPU pods):
1. Extract samples: `python extract_voice_samples.py --input-dir /recordings --output-dir /samples`
2. Fine-tune: `python finetune_xtts.py --samples-dir /samples --output-dir /model`
3. Deploy: copy `speaker_embedding.pt` → `TTS_DATA_DIR/speakers/jan-damek.pt`
4. Hot-swap: `POST /set_speaker` or restart TTS service

### VD GPU VM port layout (ollama.lan.mazlusek.com)

Both XTTS and Whisper share the P40 and speak gRPC over h2c. Port
assignment avoids the silent bind-race seen on 2026-04-20 when both
services defaulted to 5501 and only the first-starting systemd unit
actually listened:

| Port | Service | Systemd unit | Notes |
|------|---------|--------------|-------|
| 5501 | XTTS v2 (`service-tts`) gRPC | `jervis-tts-gpu` | Deployed via `k8s/deploy_xtts_gpu.sh` |
| 5502 | Whisper (`service-whisper`) gRPC | `jervis-whisper` | Deployed via `k8s/deploy_whisper_gpu.sh` — env `WHISPER_GRPC_PORT=5502` |
| 8786 | Whisper FastAPI (legacy `/health`, `/gpu/release`) | same unit | Kept for the ollama-router GPU coordination — pod-to-pod transcription traffic is gRPC-only. |
| 11434 | Ollama | `ollama` | Raw model serving, consumed by `service-ollama-router`. |

Kotlin clients hard-code these ports (`TtsGrpcClient` → 5501,
`WhisperRestClient.parseTarget` → 5502). Adding a new VD-hosted
service means allocating the next free port (5503…) and matching the
env on both the server script and the consumer.

---

# Data Flow & Pipeline Architecture

> (Dříve docs/structures.md)

# Data Processing Pipeline & Routing

**Status:** Production Documentation (2026-02-18)
**Purpose:** Two-stage data processing architecture (CPU indexing → orchestrator execution)

> **Related docs:**
> - [knowledge-base.md](knowledge-base.md) – Knowledge Base SSOT (graph schema, RAG, ingest, normalization, indexers)
> - [kb-analysis-and-recommendations.md](kb-analysis-and-recommendations.md) – KB analýza, kritické problémy, doporučení (CZ)
> - [architecture.md](architecture.md) – System-wide architecture overview
> - [koog-audit.md](koog-audit.md) – Koog removal audit (historical)

---

## Table of Contents

1. [Ollama Router – Priority-Based GPU Routing](#ollama-router--priority-based-gpu-routing)
2. [Graph-Based Routing Architecture](#graph-based-routing-architecture)
3. [Background Engine & Task Processing](#background-engine--task-processing)
4. [Multi-Agent Delegation System Data Models](#multi-agent-delegation-system-data-models)

---

## Ollama Router – Priority-Based GPU Routing

### Overview

**All LLM requests** in the system (Orchestrator, KB, Correction Agent) route through **Ollama Router** (port 11430), which uses a **two-tier request queue** to distribute requests across GPU backends (p40-1: LLM 30b, p40-2: embedding + extraction 8b/14b + VLM + whisper) based on priority, capability, and model sets.

**Router ALWAYS accepts requests.** Never returns 503/reject. Each request is queued and dispatched when a backend slot becomes available.

### Architecture

```
┌──────────────────────────────────────────────────────────┐
│                 LLM Request Sources                      │
│                                                          │
│  • Python Orchestrator (reasoning, planning, response)  │
│  • Knowledge Base (RAG, embeddings, graph prep)         │
│  • Correction Agent (transcript corrections)            │
│  • Foreground Chat (interactive LLM)                    │
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
              │  │ NORMAL queue   │  │  Max 10, GPU-only
              │  │ (priority 1)  │  │  Back-pressure at limit
              │  └────────────────┘  │
              │                      │
              │  Dispatcher:         │
              │  • CRITICAL first    │
              │  • Max 1 per backend │
              │  • GPU_MODEL_SETS    │
              │    strict filtering  │
              └──────┬───────────────┘
                     │
       ┌─────────────┴────────────────┐
       │                              │
       ▼                              ▼
┌──────────────────────┐  ┌──────────────────────────────┐
│ p40-1 (P40 24GB)     │  │ p40-2 (P40 24GB)             │
│ Max 1 slot           │  │ Max 1 slot (per-type)        │
│ CRIT+NORMAL          │  │ embedding=5, LLM=1           │
│                      │  │                              │
│ Models:              │  │ Models (permanent 22.5GB):   │
│ qwen3-coder-tool:30b │  │ qwen3-embedding:8b (5.5GB)  │
│ (18.5GB, sole LLM)  │  │ qwen3:8b (6.0GB)            │
│                      │  │ qwen3:14b (11.0GB)          │
│                      │  │ + qwen3-vl-tool (on-demand)  │
│                      │  │ + Whisper GPU (on-demand)    │
└──────────────────────┘  └──────────────────────────────┘
```

### Request Queue

Two-tier queue with backend-aware dispatch (`app/request_queue.py`):

- **CRITICAL queue**: Unlimited — chat, foreground, interactive (orchestrator). Always GPU, never CPU. Preempts NORMAL if all GPU slots busy.
- **NORMAL queue**: Unlimited — background, KB ingest, indexing. Requests wait in queue.
- **Dispatch**: Fast-path (immediate) if slot available, otherwise queued. Background dispatcher assigns queued requests to freed slots. CRITICAL always dispatched first.
- **Concurrency**: Max 1 concurrent request per backend (serial is faster than parallel when VRAM spills to RAM).
- **Client disconnect**: Monitored via `cancel_event` — request dequeued or proxy cancelled on disconnect.

### Priority Levels

| Priority | Value | Source | Routing | Preemption |
|----------|-------|--------|---------|------------|
| **CRITICAL** | 0 | Orchestrator FOREGROUND (via `X-Ollama-Priority: 0`) | GPU only, auto-reserves GPU | Cannot be preempted, preempts NORMAL |
| **NORMAL** | 1 | Everything else (no header) | GPU, waits in queue | Preempted by CRITICAL |

### GPU Reservations

When a CRITICAL request is dispatched to a GPU, the router automatically creates a reservation for that GPU. Reservations prevent NORMAL requests from using the GPU while the orchestrator is processing.

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
# → Routed by GPU_MODEL_SETS (embedding → p40-2, LLM → p40-1)
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
| `max_concurrent_per_backend` | 1 | Max parallel requests per Ollama instance (serial is faster) |
| `normal_queue_max` | unlimited | NORMAL queue (never returns 429, requests wait) |
| `orchestrator_idle_timeout_s` | 60 | Auto-release GPU reservation after idle |
| `orchestrator_reservation_timeout_s` | 600 | Absolute reservation timeout (safety net) |
| `max_request_timeout_s` | 300 | Cancel zombie requests after this |
| `background_load_delay_s` | 5 | Delay before loading bg models after release |

**Key point:** All services transparently use Ollama Router. No code changes needed – just updated environment variables.

### VRAM Management

**p40-1**: Dedicated LLM GPU. Only `qwen3-coder-tool:30b` (18.5GB). All orchestrator, chat, coding tasks.

**p40-2**: Shared utility GPU. Four permanent models + on-demand workloads:
- `qwen3-embedding:8b` (5.5GB) — **permanent**, RAG embeddings
- `qwen3:8b` (6.0GB) — **permanent**, lightweight extraction (KB link relevance, qualification)
- `qwen3:14b` (11.0GB) — **permanent**, complex extraction (KB graph extraction, summaries)
- `qwen3-vl-tool:latest` (8.8GB) — **on-demand**, loaded when VLM request arrives
- Whisper GPU (medium ~914MB, large-v3 ~1.5GB) — **on-demand**, lazy-loaded on transcription

Per-type concurrency on p40-2: **embedding concurrent=5, LLM serial=1**. Router NEVER returns 429 — unlimited queue, requests wait.

```
p40-1 (always loaded, keep_alive="-1"):
  qwen3-coder-tool:30b  (18.5GB)  → sole LLM GPU (orchestrator, chat, coding)

p40-2 VRAM budget (24GB total):
  qwen3-embedding:8b    (5.5GB)   → permanent (RAG embeddings)
  qwen3:8b              (6.0GB)   → permanent (lightweight extraction, qualification)
  qwen3:14b             (11.0GB)  → permanent (complex extraction, summaries)
  ─────────────────────────────────
  Total permanent:       22.5GB / 24GB
  qwen3-vl-tool:latest  (8.8GB)   → on-demand swap (never concurrent with whisper)
  whisper medium         (0.9GB)   → on-demand (never concurrent with VLM)

GPU_MODEL_SETS strict filtering:
  p40-1: ["qwen3-coder-tool:30b"]
  p40-2: ["qwen3-embedding:8b", "qwen3:8b", "qwen3:14b", "qwen3-vl-tool:latest"]
  → prevents 30b from loading on p40-2
  → KB service calls qwen3:8b (simple) and qwen3:14b (complex) directly by model name
  → Orchestrator qualification uses capability="extraction" → routed to qwen3:8b
```

### p40-2 VRAM Coordination (Router as Single Authority)

Router is the **single authority** for p40-2 GPU scheduling. Whisper and VLM share p40-2 via router-managed mutual exclusion. Permanent models (embedding:8b, qwen3:8b, qwen3:14b) run permanently, never blocked.

**Whisper-Router coordination** (Kotlin server mediates):
1. Kotlin `WhisperJobRunner` calls `POST /router/whisper-acquire` (blocks until granted)
2. Router checks: no VLM active on p40-2 → grants immediately; VLM active → waits
3. Kotlin calls whisper REST `POST /transcribe` (existing SSE stream)
4. After transcription, Kotlin calls `POST /router/whisper-release`

**VLM-Whisper coordination**:
1. VLM request arrives at router for p40-2
2. Router checks whisper lock: held → waits for `whisper-release` callback
3. Once released, router calls `POST whisper:8786/gpu/release` to unload model from VRAM
4. Router loads VLM via Ollama

**Safety nets**:
- Whisper lock watchdog: auto-release after 2h (Kotlin crash safety)
- Whisper auto-unload: after 60s idle, whisper releases GPU VRAM independently
- Router unreachable fallback: Kotlin proceeds without coordination

```
Whisper transcription flow:
  1. Kotlin → POST /router/whisper-acquire → Router grants (blocks if VLM active)
  2. Kotlin → POST whisper:8786/transcribe → SSE stream
  3. whisper._acquire_gpu(): unload VLM via Ollama, load whisper model
  4. Transcribe (+ pyannote 4.x diarization → speaker embeddings)
  5. Kotlin → POST /router/whisper-release
  6. After 60s idle → whisper auto-unloads GPU

VLM request flow:
  1. Request arrives at router for qwen3-vl-tool:latest
  2. Router: whisper_gpu_held? → wait for release event
  3. Router calls POST whisper:8786/gpu/release (unload model)
  4. Router calls gpu_pool.load_model(p40-2, "qwen3-vl-tool:latest")
```

### Caller Concurrency

Callers send requests freely — **router queue manages backend load**. Router NEVER returns 429 — unlimited queue, requests wait. Callers should NOT self-limit with tight semaphores or sequential processing.

| Caller | Concurrency | Timeout | Notes |
|--------|-------------|---------|-------|
| **Orchestrator** (provider.py) | Semaphore(6) | 300-1200s per tier | Safety limit only; router manages actual concurrency |
| **KB Graph** (graph_service.py) | `gather` + Semaphore(4) | 900s per LLM call | Parallel chunk extraction on qwen3:14b (p40-2) |
| **KB RAG** (rag_service.py) | Semaphore(5) | 3600s HTTP | Embedding concurrent=5 on p40-2 |
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

### Whisper GPU REST Service (p40-2)

Persistent FastAPI service on `ollama.damek.local:8786`, sharing P40 GPU with Ollama.

| Setting | Value |
|---------|-------|
| **Host** | ollama.damek.local:8786 |
| **Deploy** | `k8s/deploy_whisper_gpu.sh` (systemd on GPU VM, not K8s) |
| **Device** | CUDA (int8_float32 — P40 lacks efficient float16) |
| **Models** | medium (default), large-v3 (on request) |
| **Diarization** | pyannote-audio 4.x (requires HF_TOKEN), CPU only, returns 256-dim speaker embeddings. **Pipeline preloaded at startup** when HF_TOKEN is set. |
| **Idle timeout** | 60s → auto-unloads whisper GPU model (diarization pipeline stays on CPU) |
| **Startup** | Whisper model lazy-loaded on first request; pyannote pipeline preloaded |

#### When diarization runs

Diarization is **only** requested by `MeetingTranscriptionService.transcribe()` (full meeting, finished recording). All other Whisper callers pass `diarize=false`:

| Caller | `diarize` | Why |
|--------|-----------|-----|
| `MeetingTranscriptionService.transcribe` | **true** (default) | Full meeting needs speaker labels |
| `MeetingLiveUrgencyProbe` (tail probes) | false | Only needs text for keyword detection |
| `WhisperTranscriptionClient.retranscribe` | false | Short slice ("Nevím" fix), speakers irrelevant |
| `VoiceWebSocketHandler` (watch voice chat) | false (omitted) | Single-speaker, speakers irrelevant |
| `VoiceChatRouting` (chat voice) | false (omitted) | Single-speaker |

#### SSE robustness (critical)

Long pyannote runs (5–10 min on 20-min audio) produce no native progress events, which previously caused NAT/ingress to drop the SSE connection. Server now:
- Cleans up `work_dir` from the **worker thread**, not the SSE generator's `finally` — a dropped connection no longer deletes the audio while diarization is still using it.
- Emits a 5s heartbeat into `progress_queue` from inside `run_diarization` so the SSE keeps flowing during the silent pyannote phase.
- `EventSourceResponse(ping=5)`.
- Defensive `os.path.exists(audio_path)` early-abort with a clear log line.

Key files:

| File | Purpose |
|------|---------|
| `backend/service-whisper/whisper_rest_server.py` | REST server — GPU coordination, lazy-load, auto-unload, diarization heartbeat |
| `backend/service-whisper/whisper_runner.py` | Whisper transcription engine (range extraction, segment mapping) |
| `k8s/deploy_whisper_gpu.sh` | SSH deployment to GPU VM (systemd service) |
| `backend/server/.../meeting/WhisperTranscriptionClient.kt` | Kotlin client — `transcribe(..., diarize)` parameter, default from `whisperProperties.diarize` |

---

## Meeting Transcript Correction Pipeline

### Pipeline Flow

```
RECORDING → UPLOADED → TRANSCRIBING → TRANSCRIBED → INDEXED (raw text)
  → (after indexing, qualified=true)
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
- **Correction after indexing**: Pipeline indexes raw transcript first, then corrects after client/project is known (provides domain context)
- **Cumulative running context**: Previous corrections (name spellings, terms) are passed to subsequent chunks
- **Retry on connection errors**: 2× with exponential backoff (2-4s) for router restarts

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-correction/app/agent.py` | CorrectionAgent — KB context, phase analysis, sequential correction |
| `backend/server/.../meeting/MeetingContinuousIndexer.kt` | Pipeline orchestration (index raw → correct qualified → re-index) |
| `backend/server/.../meeting/TranscriptCorrectionService.kt` | State transitions, error recovery |
| `backend/server/.../meeting/MeetingDocument.kt` | Meeting entity with `qualified` flag |

---

## Speaker Auto-Identification via Embeddings

### Overview

Pyannote 4.x diarization produces 256-dimensional speaker embeddings alongside the standard `SPEAKER_XX` labels. These embeddings enable automatic speaker identification across meetings by comparing new embeddings against known speaker profiles using cosine similarity.

### Data Flow

```
Meeting 1 (new speakers):
  Whisper+Pyannote → SPEAKER_00, SPEAKER_01 + embeddings (256-dim each)
  → Result JSON: speaker_embeddings: {"SPEAKER_00": [0.12, ...], "SPEAKER_01": [-0.05, ...]}
  → WhisperJobRunner parses → MeetingDocument.speakerEmbeddings
  → User assigns SPEAKER_00 = "Martin" in UI
  → System saves Martin's embedding to SpeakerDocument.voiceEmbedding

Meeting 2 (auto-match):
  Whisper+Pyannote → SPEAKER_00, SPEAKER_01 + embeddings
  → MeetingTranscriptionService.autoMatchSpeakers() runs after transcription
  → Cosine similarity against all known speakers (threshold ≥ 0.70 for auto-mapping)
  → Auto-maps: SPEAKER_01 = Martin (89%), SPEAKER_00 = unknown
  → UI shows auto-match confidence badge (threshold ≥ 0.50 for display)
```

### Thresholds

| Threshold | Value | Purpose |
|-----------|-------|---------|
| Auto-match (mapping) | 0.70 | Minimum cosine similarity to auto-assign speaker |
| Display confidence | 0.50 | Minimum to show auto-match suggestion in UI |

### Storage

- `MeetingDocument.speakerEmbeddings: Map<String, List<Float>>?` -- per-meeting embeddings from pyannote
- `SpeakerDocument.voiceEmbedding: List<Float>?` -- per-speaker profile voice fingerprint (256-dim)

### DTOs

- `AutoSpeakerMatchDto(speakerId, speakerName, confidence)` -- auto-match result per diarization label
- `SpeakerEmbeddingDto(speakerId, embedding)` -- for setting voice embedding on speaker profile
- `SpeakerDto.hasVoiceprint: Boolean` -- indicates if speaker has a stored voice embedding

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-whisper/whisper_rest_server.py` | Returns `speaker_embeddings` in result JSON |
| `backend/server/.../meeting/WhisperJobRunner.kt` | Parses `speaker_embeddings` from whisper result |
| `backend/server/.../meeting/MeetingTranscriptionService.kt` | Auto-matching after transcription (cosine similarity) |
| `backend/server/.../meeting/MeetingDocument.kt` | Stores `speakerEmbeddings` per meeting |
| `backend/server/.../meeting/SpeakerDocument.kt` | Stores `voiceEmbedding` per speaker profile |
| `backend/server/.../meeting/MeetingRpcImpl.kt` | Builds `autoSpeakerMapping` for UI |
| `backend/server/.../meeting/SpeakerRpcImpl.kt` | `setVoiceEmbedding` endpoint |
| `shared/ui-common/.../meeting/SpeakerAssignmentPanel.kt` | Shows auto-match confidence, saves embedding on confirm |
| `shared/common-dto/.../meeting/SpeakerDtos.kt` | `AutoSpeakerMatchDto`, `SpeakerEmbeddingDto`, `hasVoiceprint` |

---

## Graph-Based Routing Architecture

### Problem Solved

**Original architecture:** Auto-indexed everything directly into RAG without structuring, causing context overflow for large documents and inefficient use of expensive GPU models.

**Solution:** Two-stage architecture with CPU-based indexing (structuring) and orchestrator-based execution (analysis/actions).

### Archived Client = No Activity

When `ClientDocument.archived = true`, the entire pipeline is blocked for that client:

| Stage | Mechanism |
|-------|-----------|
| **CentralPoller** | `findByArchivedFalseAndConnectionIdsContaining()` — archived clients excluded from polling, no new tasks created |
| **Pipeline tasks** | `TaskService.markArchivedClientTasksAsDone()` — bulk DB update marks INDEXING/QUEUED as DONE (runs on startup + every 5 min) |
| **Scheduler** | `clientRepository.getById()` check before dispatch — archived client's scheduled tasks stay in NEW, resume when unarchived |
| **Idle review** | Client archived check before creating IDLE_REVIEW task |
| **Running tasks** | PROCESSING tasks finish normally — no new tasks follow |

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
│ BackgroundEngine - Indexing Loop                 │
│ • Runs continuously (30s interval)              │
│ • Processes tasks               │
└─────────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────────┐
│ KB Indexing — TaskQualificationService           │
│ • Dispatches to KB microservice for indexing     │
│ • Atomic claim via indexingClaimedAt             │
│ • Task stays in INDEXING during KB processing    │
└──────────────────────────────────────────────────┘
        ↓ (KB callback /internal/kb-done)
    ┌───┴────────┐
    ↓            ↓
┌────────┐ ┌──────────────────────────────────────┐
│  DONE  │ │ QUEUED (actionable content)           │
│        │ │ • kbSummary/kbEntities/kbActionable   │
│        │ │   saved on TaskDocument               │
│        │ │ • Orchestrator classifies on pickup   │
└────────┘ └──────────────────────────────────────┘
                    ↓
        ┌──────────────────────────────────────────────┐
        │ BackgroundEngine - Execution Loop (Orchestr.) │
        │ • Runs ONLY when idle (no user requests)     │
        │ • Preemption: interrupted by user            │
        └──────────────────────────────────────────────┘
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
- **Exception: BugTrackerContinuousIndexer** — also calls `IBugTrackerClient.getComments()` to fetch issue comments from GitHub/GitLab (Jira comments come from issue response)
- **States:** NEW (from API) → INDEXING (processing) → INDEXED (task created)
- **INDEXED = "content passed to Jervis as pending task", NOT "already in RAG/Graph"!**
- **@Mention detection:** BugTrackerContinuousIndexer checks comment bodies for `@selfUsername` (from connection self-identity). If found → `TaskDocument.mentionsJervis=true` → priority score 80 in TaskPriorityCalculator → overrides IGNORE/not-actionable in kb-done callback

For indexer details see [knowledge-base.md § Continuous Indexers](knowledge-base.md#continuous-indexers).

### TaskTypeEnum — 3 pipeline categories (post-2026-04-11 refactor)

> **CRITICAL — DO NOT regress to per-source enum values.** Source identity
> (email vs jira vs whatsapp vs …) lives in `SourceUrn`, NOT in
> `TaskTypeEnum`. The previous 15-value enum was a misclass that forced
> indexers/qualifier/router to dispatch on the wrong axis.

| Enum value | Description |
|------------|-------------|
| `INSTANT` | Interactive chat input — user is in front of the screen waiting for the response. FOREGROUND processing. |
| `SCHEDULED` | Time-triggered work — cron expression or `scheduledAt`. Fires on schedule, then runs as autonomous SYSTEM-style work. |
| `SYSTEM` | Autonomous background pipeline — every indexer (email/jira/git/wiki/teams/slack/discord/whatsapp/calendar/meeting/link), idle review, qualifier output. May escalate to `state=USER_TASK` when it needs the user. |

**Source identification — SourceUrn is the single source of truth.**

`SourceUrn` is an inline value class (`backend/common-services/.../common/types/SourceUrn.kt`)
that encodes provider + structured key-value metadata in one URN string,
e.g. `email::conn:abc123,msgId:42` or `whatsapp::conn:xyz,msgId:wa_5,chat:Katenka`.

Three derivation methods replace all previous TaskTypeEnum-based dispatch:

| Method | Returns | Used for |
|--------|---------|----------|
| `scheme()` | `String` (e.g. `"email"`, `"whatsapp"`) | Branching on source-specific behaviour in indexers/qualifier |
| `kbSourceType()` | `String` matching KB Python `SourceType` enum value | Wire format sent to KB ingest endpoint |
| `uiLabel()` | Czech UI label (`"Email"`, `"WhatsApp"`, `"Schůzka"`, …) | Queue display, K reakci, task lists |

**KB Python `SourceType` stays at 15 values** (`backend/service-knowledgebase/app/api/models.py`)
because it carries credibility-tier weights and graph-relationship semantics. The Kotlin side
sends a String derived from `SourceUrn.kbSourceType()`; the KB enum is the receiver.

**USER_TASK is exclusively a `TaskStateEnum` value, never a TaskTypeEnum value.**
When a task needs user attention, the existing task transitions to
`state=USER_TASK` — no wrapper task is created. This is enforced by
`UserTaskService.failAndEscalateToUserTask()`. K reakci queries the DB
by `state=USER_TASK` only, type-agnostic.

**Sub-task hierarchy fields** on `TaskDocument`:
- `parentTaskId` — parent task in a decomposition tree
- `blockedByTaskIds` — list of tasks that must complete before this one is unblocked
- `phase`, `orderInPhase` — work-plan ordering inside a parent
- `state=BLOCKED` — parent waiting for children to finish

### Re-entrant qualifier (Phase 3, post-2026-04-11)

The qualifier no longer runs **once** per task — it runs every time the task's
context changes. The flow:

1. **Indexer creates task** with `state=INDEXING` → `TaskQualificationService`
   submits to KB `/ingest/full/async`.
2. **KB callback** `/internal/kb-done` does cheap routing (filtering rules,
   `hasActionableContent`) and dispatches actionable tasks to Python `/qualify`.
3. **Python `/qualify`** (`unified/qualification_handler.py`) reasons with KB
   tools and posts back via `/internal/qualification-done` with one of six
   decisions:
   - `DONE` — terminal, no action
   - `QUEUED` — orchestrator picks it up (default)
   - `URGENT_ALERT` — push alert + QUEUED
   - `CONSOLIDATE` — merge into existing topic task, this one → DONE
   - `ESCALATE` — needs user judgment → `state=USER_TASK` with
     `pendingUserQuestion` + `userQuestionContext`. **NEVER creates a wrapper
     task** — the original task transitions in place.
   - `DECOMPOSE` — qualifier returns 2–6 `sub_tasks`. Kotlin creates child
     `TaskDocument`s with `parentTaskId` set, parent → `state=BLOCKED` with
     `blockedByTaskIds` populated. Children inherit parent's `correlationId`,
     `sourceUrn`, `clientId`, `projectId`.

**Re-entrant triggers** (set `needsQualification=true` so the
`RequalificationLoop` picks the task up again):

- **Parent unblock**: when a child reaches DONE,
  `TaskService.updateState()` calls `unblockChildrenOfParent(parentId)`.
  If all `blockedByTaskIds` are DONE, the parent transitions
  `BLOCKED → NEW` and is flagged for re-qualification — the qualifier sees
  the children's results and decides the next step.
- **User response**: when the user replies to a USER_TASK via
  `UserTaskRpcImpl.respondToTask()`, the task moves back to NEW with
  `needsQualification=true` and the user's reply appended to `content`.
  The qualifier re-reasons with the new info.
- **External force**: `TaskService.markNeedsQualification(taskId)` is the
  programmatic API for any future trigger (topic-fan-out, schedule, etc.).

**Background workers** (in `BackgroundEngine`):
- `runQualificationLoop()` — CPU indexing dispatcher (the one that pushes
  INDEXING tasks to KB; legacy name).
- `runRequalificationLoop()` — Phase 3 re-entrant qualifier loop. Every
  `waitInterval` it scans `findByNeedsQualificationTrueOrderByCreatedAtAsc()`
  and dispatches each task to Python `/qualify`. The flag is cleared by the
  `/internal/qualification-done` callback.

**Anti-patterns**:
- Don't dispatch to `/qualify` directly from a state transition — set
  `needsQualification=true` and let the loop pick it up. Keeps the qualifier
  rate-limited and idempotent.
- Don't create wrapper tasks for ESCALATE — use
  `TaskService.transitionToUserTask(task, question, context)`.
- Don't manually populate `blockedByTaskIds` outside of
  `TaskService.decomposeTask(parent, subTasks)`.

### Graph-based task relationships (Phase 5, post-2026-04-12)

Tasks are independent work items (one concern = one task). Relationships
between tasks live in the KB graph (ArangoDB) via shared entities —
person nodes, topic nodes, project nodes. **NOT** in TaskDocument fields,
**NOT** as chains, **NOT** as parent-child hierarchy.

**`TaskDocument.summary: String?`** — short 2-3 sentence overview set by
the qualifier after processing. Used for:
- Graph edge annotations
- Sidebar card text (instead of truncated content)
- LLM context: related tasks sent as summaries (50 tokens each)
- Model requests full content via `get_task_detail` only when needed

**Qualifier step "search related tasks"**: before deciding, qualifier
searches KB with extracted entity names → finds SUMMARIES of related
tasks → can decide CONSOLIDATE (merge into existing), REOPEN (reopen
a DONE task because new evidence arrived), or LINK (new task + graph
edge to related).

**UI "🔗 Související úlohy" section** in task brief: shows related tasks
found via shared entities, each with state badge + [Zobrazit] navigate
button + [Otevřít znovu] for DONE tasks.

**`IPendingTaskService.listRelatedTasks(taskId)`**: searches for tasks
sharing entities with the given task via content regex matching on
extracted kbEntities. Returns max 10 results with summaries.

**Anti-patterns:**
- Mega-task per person (doesn't scale, loses structure)
- Linear task chains (reality is a graph, not a line)
- Parent/child for lateral relationships (that's for decomposition)
- Sending full content of 20 related tasks to LLM (send summaries)

### Conventions system (Phase 5, post-2026-04-12)

User-defined rules for how JERVIS should handle specific content. Stored
in KB as chunks with `kind="user_knowledge_convention"` (created via
`store_knowledge(category="convention")`). The qualifier loads them via
`kb_search(kinds=["user_knowledge_convention"])` before every routing
decision.

**Storage:** KB (Weaviate RAG + ArangoDB graph), NOT a separate MongoDB
collection. Reason: semantic search handles synonyms ("průzkum" matches
"survey", "dotazník"), pre-filtered by `clientId` + `kind` in Weaviate,
top-10 relevance-ranked for the specific task content.

**Write path:** User says "od teď průzkumy zavírej" in chat → JERVIS
calls `store_knowledge(subject="Pravidlo: průzkumy = zavírat",
content="...", category="convention")` → KB stores with
`kind="user_knowledge_convention"`.

**Read path:** Qualifier runs on new task → calls
`kb_search(query="pravidla konvence {source} {client}", kinds=["user_knowledge_convention"])`
→ gets top-10 semantically relevant conventions → passes to LLM as context
→ LLM applies matching rules.

**Maintenance:** JERVIS periodically (idle review) checks old conventions.
"Máš 5 pravidel z března — platí ještě?" On conflict between rules, qualifier
escalates to user: "Mám protichůdná pravidla: X říká urgent, Y říká ignore."

**Anti-patterns:**
- Don't store conventions in a separate MongoDB collection — KB semantic
  search is the right tool for natural-language rule matching
- Don't use regex/keyword matching — embedding similarity handles synonyms
- Don't send all conventions to the LLM — pre-filter by kind + client, then
  top-10 by relevance to the specific task content

### Chat Task Sidebar (Phase 5, post-2026-04-12)

The primary task interface is the **chat sidebar** in
`shared/ui-common/.../ui/chat/ChatTaskSidebar.kt`. It shows all active
tasks grouped into **collapsible sections** by pipeline stage:

| Section | States | Default |
|---------|--------|---------|
| K vyrizeni | USER_TASK | expanded |
| JERVIS pracuje | PROCESSING, CODING | expanded |
| Ve fronte | QUEUED | expanded |
| Nove | NEW, INDEXING | expanded |
| Ceka na podulohy | BLOCKED | collapsed |
| Chyby | ERROR | collapsed |

**Push-only rendering (guideline #9):** the sidebar subscribes to
`IPendingTaskService.subscribeSidebar(clientId, showDone)` — a kRPC
`Flow<SidebarSnapshot>` fed by a per-scope `MutableSharedFlow(replay=1)`
in `SidebarStreamService`. Server writes invalidate the flow via
`TaskSaveEventListener` (Mongo `AfterSaveEvent`) + explicit calls from
`TaskService.updateState`/`updateStateAndContent`/`markAsError`. UI just
`collectAsState()`s the snapshot in `ChatViewModel.sidebarSnapshot`. No
pull, no refresh button, no `TASK_LIST_CHANGED` event→reload.

**Task brief title resolution** (in `ChatViewModel.switchToTaskConversation`):
`summary` > `taskName` (if not "Unnamed Task") > first line of content > `sourceLabel`

**Filter chips hidden in task view:** When `activeChatTaskName != null`
(user drilled into a task), Chat/Tasky/K reakci filter chips and tier
toggle are hidden — they control main chat, not task conversations.

**Layout:**
- Expanded (>=600dp): `JHorizontalSplitLayout` — sidebar left (22%) + chat right
- Compact (<600dp): `ModalNavigationDrawer` with floating list button

### Tasks UI (Phase 4, post-2026-04-11)

The user-facing task screen lives in
`shared/ui-common/.../ui/PendingTasksScreen.kt` (entry name kept for nav
compatibility — display title is "Ulohy"). It is the **single
state-agnostic task list** for everything in the `tasks` collection. Any
attempt to add a second task screen for "USER_TASK only" or "INDEXING
only" should be redirected here with a state filter.

**Filter axes** (all DB-side, never client-side):
- `state` — `NEW`, `INDEXING`, `QUEUED`, `PROCESSING`, `CODING`,
  `USER_TASK`, `BLOCKED`, `DONE`, `ERROR`
- `taskType` — `INSTANT` / `SCHEDULED` / `SYSTEM` (post-Phase-1)
- `sourceScheme` — SourceUrn prefix (`email`, `whatsapp`, `jira`,
  `meeting`, …). Implemented as a regex match `^scheme::` on the
  stored `sourceUrn` string.

**Backend**: `IPendingTaskService.listTasksPaged(taskType, state,
clientId, sourceScheme, parentTaskId, textQuery, page, pageSize)` —
DB-side pagination, sort by `createdAt DESC`. Companion endpoints:
- `getById(id)` — single task for the detail panel
- `listChildren(parentTaskId)` — sub-tasks ordered by phase + orderInPhase

**`PendingTaskDto` carries the Phase 4 display fields**:
- `taskName` — display name from TaskDocument
- `sourceLabel` — Czech UI label, derived from `SourceUrn.uiLabel()` server-side
- `sourceScheme` — URN scheme prefix for client-side filter chips
- `pendingUserQuestion` — populated when state=USER_TASK
- `needsQualification` — Phase 3 re-entrant flag for the "Re-kvalifikace" badge
- `parentTaskId`, `childCount`, `completedChildCount`, `phase` — sub-task hierarchy

**Anti-patterns**:
- Adding a `getTaskTypeLabel()` switch in UI code — use `task.sourceLabel`
- Filtering tasks client-side (load-then-filter) — extend
  `listTasksPaged` with the new filter axis instead
- Hardcoding the source list in the UI dropdown — keep it in sync with
  `SourceUrn.uiLabel()` / `kbSourceType()`

#### 1.1 Attachment Extraction Pipeline

**Email attachments** are processed through a dual-path pipeline:

**Path A — Direct KB Registration (existing, preserved):**
```
EmailPollingHandler.storeAttachmentBinary() → kb-documents/{clientId}/
  → EmailContinuousIndexer.indexEmailAttachments()
  → AttachmentKbIndexingService.registerPreStoredAttachment()
  → Python KB service (DocumentExtractor/VLM → RAG + Graph)
```

**Path B — Attachment Relevance Assessment:**
```
EmailContinuousIndexer → AttachmentExtractionService.createExtractsForAttachments()
  → MongoDB: attachment_extracts (PENDING)
  → async: AttachmentExtractionService.processPendingExtracts()
    → Python KB /documents/extract-text (VLM-first for images, pymupdf/python-docx for docs)
  → MongoDB: attachment_extracts (SUCCESS, extractedText populated)
  → LLM scores relevance 0.0–1.0
    → score >= 0.7 → register with KB
```

**Extraction strategy:**
- **Images (PNG/JPG):** VLM-first (`qwen3-vl-tool:latest` on p40-2)
- **Scanned PDFs:** pymupdf text + VLM for pages with images/scans (hybrid)
- **Structured docs:** DOCX (python-docx), XLSX (openpyxl)
- **Plain text:** Direct decode (no extraction needed)

**MongoDB: `attachment_extracts`** — tracks extraction and relevance:
- `taskId`, `filename`, `mimeType`, `filePath`
- `extractedText`, `extractionStatus` (PENDING/SUCCESS/FAILED), `extractionMethod`
- `relevanceScore` (0.0–1.0), `relevanceReason`
- `kbUploaded`, `kbDocId`

#### 1.2 Link Handling Flow - SEPARATE PENDING TASKS

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

**2. KB service processes EMAIL task (DATA_PROCESSING):**
- Indexes email content into RAG/Graph
- Creates Email vertex
- Creates Person vertices (from, to, cc)
- Creates Graph edges: Email→Person (sender), Person→Email (recipient)
- Notes in metadata: "3 links will be processed separately"
- Routing: DONE (email indexed, links waiting in queue)

**3. KB service processes LINK task (LINK_PROCESSING) - SEPARATELY:**
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

#### 2. KB Indexing Tools

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
    routing: String, // "DONE" or "QUEUED"
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
    val routingDecision: String,         // DONE / QUEUED
    val routingReason: String,
    val sourceType: String?,             // KB source type via SourceUrn.kbSourceType() — "email", "jira", "git", ...
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
- **Indexing loop (CPU):** runs continuously, 30s interval
- **Execution loop (orchestrator):** runs ONLY when idle (no user requests)

**Preemption guarantees:** User requests ALWAYS have priority over background tasks

#### Intelligent Task Routing (Async Fire-and-Forget)

`TaskQualificationService` dispatches tasks to KB via `POST /ingest/full/async` (fire-and-forget, HTTP 202).
KB processes in background and calls `/internal/kb-done` with routing hints when finished.
The `/internal/kb-done` callback handler saves KB results to the TaskDocument and routes the task.

**Async flow:**
1. `TaskQualificationService.dispatch()` — extracts text, loads attachments, submits to KB
2. KB returns HTTP 202 immediately — server indexing worker moves to next task
3. KB processes: attachments → RAG → LLM summary (parallel) → graph extraction (queued)
4. Progress events pushed via `POST /internal/kb-progress` (real-time UI updates)
5. On completion: KB POSTs `FullIngestResult` to `POST /internal/kb-done`
6. `/internal/kb-done` handler saves kbSummary/kbEntities/kbActionable to TaskDocument, applies filters, routes to DONE or QUEUED
   - **Mention override:** If `task.mentionsJervis=true`, IGNORE filter and not-actionable routing are bypassed → always QUEUED

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
  ├─ Step 0: mentionsJervis=true (from @mention in issue/MR comments)
  │    → QUEUED (priority 80, overrides ALL below — direct mention always actioned)
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
  │    → QUEUED (immediate, high priority)
  │
  ├─ Step 4: hasFutureDeadline=true AND hasActionableContent=true
  │    ├─ deadline < scheduleLeadDays away → QUEUED (too close, do now)
  │    └─ deadline >= scheduleLeadDays away → create SCHEDULED task copy
  │         (TaskTypeEnum.SCHEDULED, scheduledAt = deadline - scheduleLeadDays)
  │         original task → DONE (indexed, terminal)
  │
  └─ Step 5: ALL remaining actionable content
       → QUEUED (orchestrator will classify task type on pickup)
```

**Note:** No age-based filter — the LLM (`_generate_summary()`) decides actionability even for old content (forgotten tasks, open issues, etc.)

**Constants:**
- `SCHEDULE_LEAD_DAYS = 2` (configurable per client) — deadline scheduling threshold
- `COMPLEX_ACTIONS` = {decompose_issue, analyze_code, create_application, review_code, design_architecture}

**DONE (info_only or simple action handled — TERMINAL):**
- Document indexed and structured (Graph + RAG)
- No action items OR simple action handled locally
- Simple informational content
- Routine updates (status change, minor commit)
- Never reset on restart — terminal state

**QUEUED (immediate orchestrator execution):**
- Assigned to the team/organization
- Deadline too close (within schedule lead days)
- Complex actions requiring orchestrator (coding, architecture, decomposition)
- Requires user action that can't be handled by simple agent

**SCHEDULED (deferred):**
- Has actionable content with a future deadline
- Copy created with `type=TaskTypeEnum.SCHEDULED`, `scheduledAt = deadline - scheduleLeadDays`
- BackgroundEngine scheduler loop picks these up automatically

### Benefits

1. **Cost efficiency:** Expensive GPU models only when necessary
2. **No context overflow:** Chunking solves large documents
3. **Bi-directional navigation:** Graph (structured) ↔ RAG (semantic)
4. **Efficient context passing:** TaskMemory eliminates redundant work
5. **User priority:** Preemption ensures immediate response
6. **Scalability:** CPU indexing can run in parallel on multiple tasks

---

## Background Engine & Task Processing

### Indexing Loop (CPU) — Fire-and-Forget Dispatch

- **Interval:** 30 seconds
- **Process:** Reads INDEXING tasks from MongoDB, ordered by `queuePosition ASC NULLS LAST, createdAt ASC`
- **Dispatch:** `TaskQualificationService` dispatches to KB microservice (fire-and-forget)
- **Concurrency:** 1 (dispatch is fast — content already cleaned, just HTTP POST, not blocking on KB)
- **Text extraction:** All HTML/XML content is cleaned at `TaskService.createTask()` time via `DocumentExtractionClient` → Python `jervis-document-extraction` microservice (BeautifulSoup for HTML, python-docx, pymupdf for binaries). No local Jsoup/Tika — single extraction point, fail-fast.
- **Dispatch flow:** `claimForIndexing()` (atomic claim via `indexingClaimedAt`, state stays INDEXING) → `TaskQualificationService.processOne()` (task.content already clean, attachment loading, HTTP POST to `/ingest/full/async`) → returns immediately. Task stays in INDEXING until KB calls back.
- **Retry:** If KB is unreachable or rejects the request → `returnToQueue()` unsets `indexingClaimedAt` with backoff. KB handles its own internal retry (Ollama busy, timeouts). When KB permanently fails, it calls `/internal/kb-done` with `status="error"` → server marks task as ERROR. Recovery: stuck INDEXING tasks with `indexingClaimedAt > 10min` → unset `indexingClaimedAt` (re-dispatch).
- **Priority:** Items with explicit `queuePosition` are processed first (set via UI reorder controls)
- **Completion callback:** KB POSTs to `/internal/kb-done` with `FullIngestResult` → handler saves kbSummary/kbEntities/kbActionable to TaskDocument, applies filters, routes:
  - Not actionable / filtered → **DONE** (terminal)
  - Simple action (reply_email, schedule_meeting) → **DONE** (with USER_TASK)
  - ALL actionable content → **QUEUED** (orchestrator classifies task type on pickup)
- **Live progress:** KB pushes progress events via `POST /internal/kb-progress` → Kotlin handler saves to DB + emits to WebSocket (real-time). Pre-KB steps (agent_start, text_extracted, kb_accepted) emitted by `TaskQualificationService.dispatch()`.
- **Persistent history:** Each progress step saved to `TaskDocument.qualificationSteps` via MongoDB `$push`. `qualificationStartedAt` set atomically in `claimForIndexing()`.
- **UI:** `MainViewModel.qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>>` → `IndexingQueueScreen` shows live step/message per item in "KB zpracování" section.
- **Indexing Queue UI data source:** "KB zpracování" and "KB fronta" sections display data from the **KB write service SQLite extraction queue** (not MongoDB server tasks). `IndexingQueueRpcImpl` calls `KnowledgeServiceRestClient.getExtractionQueue()` → `GET /api/v1/queue` on KB write service.
- **Backend pagination:** `getPendingBackgroundTasksPaginated(limit, offset)` with DB skip/limit.

### Scheduler Loop (Task Dispatch)

- **Interval:** 60 seconds
- **Advance dispatch:** 10 minutes before `scheduledAt`
- **One-shot tasks:** Transitions `NEW → INDEXING`, clears `scheduledAt`
- **Recurring tasks (cron):** Creates execution copy → `INDEXING`, updates original with next `scheduledAt` via `CronExpression.next()`
- **Invalid cron:** Falls back to one-shot behavior (deletes original after creating execution copy)

### Execution Loop (Orchestrator) — Three-Tier Priority

- **Priority order:** FOREGROUND > BACKGROUND > IDLE
- **FOREGROUND (chat):** Highest priority, processed first by `queuePosition ASC`
- **BACKGROUND (user-scheduled):** Processed when no FOREGROUND tasks, by `priorityScore DESC, createdAt ASC`
- **IDLE (system idle work):** Lowest priority, processed only when no FG/BG tasks and no active chat
- **Preemption:** FOREGROUND preempts both BACKGROUND and IDLE; BACKGROUND preempts IDLE; IDLE never preempts
- **Agent:** Python Orchestrator (LangGraph) with GPU model (OLLAMA_PRIMARY)
- **Atomic claim:** Uses MongoDB `findAndModify` (QUEUED → PROCESSING) to prevent duplicate execution
- **Stale recovery:** On pod startup, BACKGROUND and IDLE tasks stuck in PROCESSING for >10min are reset (FOREGROUND completed tasks are not stuck). DONE tasks are terminal — never reset.

### KB Queue Count Fix (2026-02-23)

**Problem:** UI always showed "KB fronta 199" because:
1. Kotlin fetches max 200 items from Python KB queue (`limit=200`)
2. 1 item is `in_progress` and filtered out
3. 200 - 1 = 199

**Solution:** `IndexingQueueRpcImpl.collectPipelineTasks()` now uses `kbStats.pending` (real `COUNT(*)` from SQLite) for `kbWaitingTotalCount` when no search/client filter is active. With filters, falls back to `filteredKbWaiting.size` (correct for filtered subsets).

### Auto-Requeue on Inline Messages

When orchestration is dispatched, `TaskDocument.orchestrationStartedAt` is set to the current timestamp.
On completion ("done"), `BackgroundEngine` checks for new USER messages that arrived during orchestration:

```
orchestrationStartedAt = Instant.now()  ← set on dispatch

... orchestration runs (PROCESSING) ...

onComplete("done"):
  newMessageCount = chatMessageRepository.countAfterTimestamp(
      projectId, orchestrationStartedAt
  )
  if (newMessageCount > 0):
      task.state = QUEUED              ← auto-requeue
      task.orchestrationStartedAt = null
      // Agent will re-process with full context including new messages
  else:
      // Normal completion flow (DONE or DELETE)
```

This ensures that messages sent while the agent is busy are not lost -- the task is automatically
re-processed with the full conversation context once the current orchestration finishes.

### Task States Flow

```
NEW (from API) → INDEXING (processing)
    ↓
INDEXING → claimForIndexing() (atomic, stays INDEXING) → KB callback (/internal/kb-done):
    ├─ not actionable → DONE
    ├─ simple action → DONE (+ USER_TASK)
    └─ actionable → QUEUED (kbSummary/kbEntities/kbActionable saved on TaskDocument)
    ↓
QUEUED → PROCESSING (atomic findAndModify, orchestrator classifies task) → DONE
                    │                     │                    │
                    │                     │                    └── coding agent dispatched →
                    │                     │                        CODING → (watcher resumes) →
                    │                     │                        PROCESSING (loop)
                    │                     └── new messages arrived? → QUEUED (auto-requeue)
                    └── interrupted → USER_TASK → user responds → QUEUED (loop)

Scheduled tasks:
NEW (scheduledAt set) → scheduler loop dispatches when scheduledAt <= now + 10min
    ├── one-shot: NEW → INDEXING (scheduledAt cleared)
    └── recurring (cron): original stays NEW (scheduledAt = next cron run),
                          execution copy → INDEXING

Idle work (ProcessingMode.IDLE):
(no FG/BG tasks + brain configured) → IDLE_REVIEW task (mode=IDLE) → QUEUED → PROCESSING → DONE
Max ONE idle task at a time. Automatically preempted when any FG/BG work arrives.
```

### K8s Resilience

- **Deployment strategy:** `Recreate` — old pod is stopped before new pod starts (no overlap)
- **Atomic task claiming:** MongoDB `findAndModify` ensures only one instance processes each task
- **Stale task recovery:** On startup, BackgroundEngine resets BACKGROUND tasks stuck in PROCESSING for >10 minutes back to QUEUED. INDEXING with `indexingClaimedAt > 10min` → unset `indexingClaimedAt` (KB callback never arrived). FOREGROUND completed tasks are preserved (not stuck).
- **Single GPU constraint:** Recreate strategy + atomic claims guarantee no duplicate GPU execution

### Workspace Retry with Exponential Backoff

When `initializeProjectWorkspace()` fails (CLONE_FAILED), the project is NOT retried immediately.
Instead, a periodic retry loop (`runWorkspaceRetryLoop()`, 60s interval) checks for CLONE_FAILED projects
whose backoff has elapsed:

- **Backoff schedule:** 5min → 15min → 30min → 60min → 5min cap (wraps around)
- **Fields:** `ProjectDocument.workspaceRetryCount`, `nextWorkspaceRetryAt`, `lastWorkspaceError`
- **Manual retry:** UI "Zkusit znovu" button calls `IProjectService.retryWorkspace()` → resets all retry fields
- **UI banner:** `WorkspaceBanner` composable shows CLONING (info) or CLONE_FAILED (error + retry button)

### Unified Idle Work Loop

- **Interval:** Configurable via `BackgroundProperties.idleReviewInterval` (default 30 min)
- **Enabled:** `BackgroundProperties.idleReviewEnabled` (default true)
- **Preconditions:** No active FG/BG tasks, no existing idle-review task
- **ProcessingMode:** `IDLE` (lowest priority — preempted by both FOREGROUND and BACKGROUND)
- **Creates:** At most ONE idle-review task at a time with `ProcessingMode.IDLE`
- **Task selection:** `IdleTaskRegistry` returns highest-priority due check (priority-ordered, interval-based)
- **Lifecycle:** Task created → QUEUED → executed → DONE (deleted) → next iteration picks next due check
- **Task type:** `TaskTypeEnum.SYSTEM` (post-2026-04-11; idle-review identity is encoded in `sourceUrn` with scheme `idle-review`)
- **Client resolution:** Uses JERVIS Internal project's client ID
- **Deadline scan:** Also uses `ProcessingMode.IDLE` (periodic via scheduler loop, every 5 min)
- **GPU idle callback:** `onGpuIdle()` immediately creates idle task when GPU has been idle ≥5 min

### Orchestrator Dispatch Backoff

When Python orchestrator dispatch fails (unavailable, busy), the task gets exponential backoff
instead of fixed 15s retry:

- **Backoff schedule:** 5s → 15s → 30s → 60s → 5min cap
- **Fields:** `TaskDocument.dispatchRetryCount`, `nextDispatchRetryAt`
- **Task picking:** `getNextForegroundTask()`/`getNextBackgroundTask()` skip tasks where `nextDispatchRetryAt > now`
- **Reset:** On successful dispatch (PROCESSING), `dispatchRetryCount` resets to 0

### Circuit Breaker for Orchestrator Health

`PythonOrchestratorClient` includes an in-memory circuit breaker for health checks:

- **States:** CLOSED (normal) → OPEN (fast-fail after 5 consecutive failures) → HALF_OPEN (probe after 30s)
- **When OPEN:** `isHealthy()` returns false immediately without HTTP call
- **UI indicator:** `OrchestratorHealthBanner` shows warning when circuit breaker is OPEN
- **Queue status metadata:** `orchestratorHealthy` field pushed via existing QUEUE_STATUS stream

For Python orchestrator task flow see [orchestrator-final-spec.md § 9](orchestrator-final-spec.md#9-async-dispatch--result-polling-architektura).

---

## UI Data Streams — Push-Only Server Architecture

**Rule #9 in `docs/guidelines.md`. UI pattern SSOT in `docs/ui-design.md` §12.**

Every live UI surface subscribes to a kRPC `Flow<Snapshot>`. The server owns the
invalidation and pushes already-rendered snapshots. UI never pulls.

### Architecture

```
┌────────── Write sources ──────────┐
│  Repository.save()                │
│  Repository.delete()              │    emit(snapshot)
│  Mongo change streams             │  ─────────────┐
│  Orchestrator progress callbacks  │                │
│  Kb progress callbacks            │                ▼
└───────────────────────────────────┘   ┌──────────────────────────┐
                                         │  Per-scope StreamService │
                                         │  MutableSharedFlow<Snap> │
                                         │  (replay = 1)            │
                                         └──────────┬───────────────┘
                                                    │ Flow<Snap>  (kRPC over WS)
                                                    ▼
                                         ┌──────────────────────────┐
                                         │   UI ViewModel.collect{} │
                                         │   → StateFlow → Compose  │
                                         └──────────────────────────┘
```

### Stream registry

| Scope key | Service | Snapshot | Invalidated by |
|---|---|---|---|
| `sidebar/{clientId\|""}` | `SidebarStreamService` | `SidebarSnapshot` | `TaskRepository.save/delete`, markDone, reopen |
| `task/{taskId}` | `TaskStreamService` | `TaskSnapshot` | task save, progress event, related-task save |
| `chat/{clientId}/{projectId}/{filter}` | `ChatStreamService` | `ChatHistoryDto` | `ChatMessageRepository.save`, task markDone/reopen |
| `conversation/{taskId}` | `ChatStreamService` | `ConversationSnapshot` | chat message save, progress event |
| `queue` | `QueueStreamService` | `QueueSnapshot` | task state transitions (QUEUED ↔ PROCESSING ↔ DONE) |
| `meeting/{meetingId}` | `MeetingStreamService` | `MeetingSnapshot` | meeting save, transcription progress, correction progress |
| `userTasks/{clientId\|""}` | `UserTaskStreamService` | `List<UserTaskListItemDto>` | `UserTaskService.*` |

### Invalidation rules

1. **Write-path ownership.** Only the service that owns the write invalidates.
   `TaskService.markDone()` calls `sidebarStreamService.invalidate(task.clientId)`
   and `taskStreamService.invalidate(task.id)` and, if the task is linked to a
   main chat conversation, `chatStreamService.invalidate(scope)`.
2. **Cross-scope fan-out.** A write affecting the global sidebar also emits to
   the client-scoped flow (and vice versa). `StreamService.invalidate` resolves
   all affected scope keys in one call.
3. **Coalescing.** `MutableSharedFlow(extraBufferCapacity = 8)` — bursts of
   writes collapse on the consumer side (slow subscriber sees latest, not every
   intermediate). Do NOT rely on seeing every individual emit.
4. **Lazy snapshot compute.** Snapshot is built only when a subscriber is
   attached. `onSubscription { if (replayCache.isEmpty()) emit(build()) }`. No
   work happens for scopes no one watches.
5. **No user ACL in snapshot path.** Snapshot is built for `(clientId,
   projectId)` scope — if the UI switches scope, it opens a new stream.

### Filter as stream parameter

A filter (`ChatFilter`, `TaskTypeFilter`) is part of the stream method
signature. The UI emits a new `clientId`/`filter` value → `collectLatest`
cancels the old stream and opens a new one. The server computes the snapshot
for the new filter and emits immediately. No `reloadForCurrentFilter()` logic
in UI code.

### Writes never return snapshots

Write RPCs (`markDone`, `reopen`, `sendMessage`, `cancel`, `dismiss`,
`approveApproval`, …) return only an `Ack` or the updated domain DTO. They
NEVER return a list/history. UI consumers see the change via the subscription
they already hold — there is no post-write refresh.

### Reconnect

`RpcConnectionManager.generation: StateFlow<Int>` bumps on every successful
reconnect. `JervisRepository.streamingCall` wraps a collector so each
generation restart re-subscribes to the stream. With `replay = 1` on the
server, the UI receives the current snapshot on the new socket without any
manual "refresh after reconnect" code.

### What stays unary

- `listDoneTasksPaged`, `getMeetingTranscript`, file download — historical reads
- `markDone`, `reopen`, settings CRUD, etc. — writes
- `getById` is allowed ONLY when the result is used as a one-off input for a
  write op; for live rendering, use `subscribeTask(id)` instead

### Legacy patterns removed

- `IPendingTaskService.listTasksPaged(state = …)` for the live sidebar —
  replaced by `subscribeSidebar`
- `IChatService.getChatHistory(...)` for the main chat live view — replaced by
  `subscribeChatHistory`
- `INotificationService.subscribeToEvents` → UI handler calling `getXxx()` —
  event signals stay, but they no longer trigger pulls. Data lives on its own
  stream
- `_sidebarRefreshTrigger`, `refreshTrigger: Int`, `LaunchedEffect(refreshTrigger)`
- `JRefreshButton` on chat/sidebar/queue/meeting/userTasks views (still OK in
  Settings CRUD)
- `getById(taskId)` in `ChatViewModel.switchToTaskConversation` — replaced by
  `subscribeTask(taskId)` (fixes stale `_activeChatTaskState` bug)
- `15s polling cycle` comment and any implicit poll loop

---

## Ollama Router Architecture (Priority-Based GPU Routing)

All services call a single endpoint – the **Ollama Router** (:11430) – which routes to GPU backends (p40-1: LLM 30b, p40-2: embedding + extraction 8b/14b + VLM + whisper) based on priority, capability, and `GPU_MODEL_SETS`.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  Ollama Router (K8s pod, :11430)   Python FastAPI                              │
│  • Priority routing (CRITICAL / NORMAL)                                         │
│  • Per-type concurrency (embedding=5, LLM=1)                                   │
│  • Unlimited queue — NEVER returns 429, requests wait                          │
│  • GPU_MODEL_SETS routing (p40-1=[30b], p40-2=[embed,8b,14b,vl-tool])         │
│  • Auto-reservation (60s idle timeout, no announce/release API)                │
│  └──────┬──────────────────────────┬───────────────────────────────────────────│
│         │                          │                                           │
└─────────┼──────────────────────────┼───────────────────────────────────────────┘
          │                          │
   ┌──────▼──────────────────┐ ┌─────▼──────────────────────────────┐
   │ GPU_BACKENDS[0] (p40-1) │ │ GPU_BACKENDS[1] (p40-2)            │
   │ P40 24GB VRAM           │ │ P40 24GB VRAM                      │
   │                         │ │                                    │
   │ qwen3-coder-tool:30b    │ │ Permanent (22.5GB):               │
   │ (18.5GB, sole LLM)     │ │   qwen3-embedding:8b  (5.5GB)     │
   │                         │ │   qwen3:8b            (6.0GB)     │
   │ Orchestrator, chat,     │ │   qwen3:14b           (11.0GB)    │
   │ coding                  │ │ On-demand swap:                    │
   │                         │ │   qwen3-vl-tool       (8.8GB)     │
   │                         │ │   + Whisper GPU        (3-6GB)     │
   └──────────────────────────┘ └────────────────────────────────────┘
```

### Priority Levels (2 levels)

| Priority | Value | Source | Behavior |
|----------|-------|--------|----------|
| CRITICAL | 0 | Orchestrator FOREGROUND, jervis_mcp | Preempts non-critical, always GPU, auto-reserves |
| NORMAL | 1 | Everything else (correction, KB simple ingest, background tasks) | GPU, waits in unlimited queue |

> Priority is set via `X-Ollama-Priority: 0` header for CRITICAL. No header = NORMAL (router default). Model name no longer determines priority.
>
> **Orchestrator processing_mode**: FOREGROUND tasks send `X-Ollama-Priority: 0` on all sub-calls (KB, tools). BACKGROUND and IDLE tasks send no header (NORMAL).

### Model Co-location on GPU

**p40-1**: Dedicated to orchestrator/chat/coding — only `qwen3-coder-tool:30b` (18.5GB).

**p40-2**: Dedicated to extraction + embedding — three permanent models (22.5GB) + on-demand VLM/whisper. KB extraction uses `qwen3:8b` (simple) and `qwen3:14b` (complex) directly by model name. Orchestrator qualification uses `capability="extraction"` routed to `qwen3:8b`.

| Model | VRAM Est. | Location | Purpose |
|-------|-----------|----------|---------|
| qwen3-coder-tool:30b | 18.5GB | p40-1 (permanent) | Orchestrator, chat, coding |
| qwen3-embedding:8b | 5.5GB | p40-2 (permanent) | RAG embeddings |
| qwen3:8b | 6.0GB | p40-2 (permanent) | Lightweight extraction (KB link relevance, qualification) |
| qwen3:14b | 11.0GB | p40-2 (permanent) | Complex extraction (KB graph extraction, summaries) |
| qwen3-vl-tool:latest | 8.8GB | p40-2 (on-demand) | VLM image description |
| Whisper GPU | 3-6GB | p40-2 (on-demand) | Transcription |

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
p40-1 (GPU_BACKENDS[0]):  :30b — orchestrator, chat, coding (CRITICAL + NORMAL)
p40-2 (GPU_BACKENDS[1]):  embedding:8b + qwen3:8b + qwen3:14b + vl-tool — extraction, embedding, VLM
```
- p40-1: sole LLM GPU for orchestrator/chat/coding
- p40-2: dedicated extraction + embedding GPU (per-type concurrency: embedding=5, LLM=1)
- CRITICAL requests auto-reserve p40-1, p40-2 handles extraction/embedding independently

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
OLLAMA_MAX_LOADED_MODELS=5      # Allow all p40-2 models (embed + 8b + 14b + vl-tool)
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

## ~~Multi-Agent Delegation System Data Models~~ (REMOVED)

> **REMOVED:** The multi-agent delegation system (19 specialist agents, DelegationMessage, ExecutionPlan, etc.)
> has been replaced by the Unified Agent architecture. See [graph-agent-architecture.md](graph-agent-architecture.md).
>
> All models below (DomainType, DelegationStatus, DelegationMessage, AgentOutput, ExecutionPlan,
> AgentCapability, DelegationMetrics, SessionEntry, ProcedureNode) are no longer used.

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
- `context` is passed in full to the target agent (no truncation — routing handles context limits).
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

## ~~Multi-Agent Delegation System~~ (REMOVED)

> **REMOVED:** Replaced by Unified Agent. See [graph-agent-architecture.md](graph-agent-architecture.md).

---

## Agent — Task Decomposition via Vertex/Edge DAG

> **SSOT:** [graph-agent-architecture.md](graph-agent-architecture.md) — full architecture including Paměťový/Myšlenkový graf, unified agent, Phase 4.

### Overview

The Agent uses a dynamic vertex/edge DAG for task decomposition. Uses **LangGraph** for execution, with **AgentGraph** as the planning structure. Each vertex type has a distinct **responsibility** — determining its system prompt, default tool set, and behavior.

**Key principles:**
- **Input → vertices → edges → result**: a request is decomposed into vertices, each further decomposable
- **Responsibility-based types**: each vertex type (planner, investigator, executor, validator, reviewer) gets tools matching its role
- **Context accumulation**: each edge carries `summary` + `full context` (searchable). After 10 vertices, target has 10 contexts
- **Fan-in**: if 10 edges converge into a vertex, it receives 10 summaries + 10 full contexts
- **Dynamic tool requests**: vertices get default tools but can request additional categories at runtime via `request_tools`
- **LangGraph execution**: LangGraph handles checkpointing, interrupt/resume, and execution flow. TaskGraph teaches it HOW to think

### Data Model

**Source:** `backend/service-orchestrator/app/agent/models.py`

```python
class VertexType(str, Enum):
    ROOT = "root"               # Initial request — decomposes into sub-vertices
    PLANNER = "planner"         # Plans approach / breaks down further
    INVESTIGATOR = "investigator"  # Researches context (KB, web, code search)
    EXECUTOR = "executor"       # Performs concrete work (coding, tracker ops)
    VALIDATOR = "validator"     # Verifies results (tests, checks, lint)
    REVIEWER = "reviewer"       # Reviews quality (code review, output review)
    SYNTHESIS = "synthesis"     # Combines results from upstream vertices
    GATE = "gate"               # Decision / approval point
    TASK = "task"               # Legacy alias for EXECUTOR
    DECOMPOSE = "decompose"     # Alias for PLANNER
    SETUP = "setup"             # Project scaffolding, repo creation
    ASK_USER = "ask_user"       # Blocked — needs user input
    REQUEST = "request"         # Chat message → agent execution → response
    TASK_REF = "task_ref"       # Reference to Myšlenkový graf
    INCOMING = "incoming"       # Qualified item from indexation
    CLIENT = "client"           # Client hierarchy node
    PROJECT = "project"         # Project hierarchy node

class VertexStatus(str, Enum):
    PENDING = "pending"         # Waiting for incoming edges
    READY = "ready"             # All incoming edges satisfied
    RUNNING = "running"         # Currently processing
    COMPLETED = "completed"     # Done
    FAILED = "failed"
    BLOCKED = "blocked"         # Waiting for external input (ASK_USER)
    SKIPPED = "skipped"         # Unreachable (upstream failed)

class EdgeType(str, Enum):
    DEPENDENCY = "dependency"       # B depends on A's result
    DECOMPOSITION = "decomposition" # Parent → child breakdown
    SEQUENCE = "sequence"           # Strict ordering
```

### EdgePayload — what flows through edges

```python
class EdgePayload(BaseModel):
    source_vertex_id: str
    source_vertex_title: str
    summary: str                # Concise result summary
    context: str                # Full context (searchable at target)
```

When a vertex completes, its outgoing edges get filled with `EdgePayload(summary, context)`. The target vertex becomes READY only when ALL incoming edges have payloads.

### GraphVertex

```python
class GraphVertex(BaseModel):
    id: str
    title: str
    description: str
    vertex_type: VertexType
    status: VertexStatus

    agent_name: str | None          # Which agent handles this
    input_request: str              # What to solve
    incoming_context: list[EdgePayload]  # Accumulated from incoming edges

    # Output (filled after execution)
    result: str                     # Full result
    result_summary: str             # Summary for outgoing edges
    local_context: str              # Full context (searchable downstream)

    # Hierarchy
    parent_id: str | None           # Parent vertex (decomposition tree)
    depth: int                      # Decomposition depth

    # Per-vertex state (Phase 4 — unified agent)
    agent_messages: list[dict]      # LLM message history for resume
    agent_iteration: int            # How many iterations completed
```

### AgentGraph

```python
class GraphType(str, Enum):
    MEMORY_GRAPH = "memory_graph"       # Global Paměťový graf (one per user)
    THINKING_GRAPH = "thinking_graph"   # Myšlenkový graf (per-task decomposition)

class AgentGraph(BaseModel):
    id: str
    task_id: str
    client_id: str
    project_id: str | None
    graph_type: GraphType           # MEMORY_GRAPH or THINKING_GRAPH

    root_vertex_id: str
    vertices: dict[str, GraphVertex]
    edges: list[GraphEdge]
    status: GraphStatus             # BUILDING → READY → EXECUTING → COMPLETED
```

### Graph Operations

**Source:** `backend/service-orchestrator/app/agent/graph.py`

| Operation | Description |
|-----------|-------------|
| `create_agent_graph()` | Create graph with root vertex |
| `add_vertex()` | Add vertex (auto-calculates depth from parent) |
| `add_edge()` | Add directed edge, recalculate target readiness |
| `get_ready_vertices()` | Vertices where all incoming edges have payloads |
| `accumulate_context()` | Gather all incoming EdgePayloads for a vertex |
| `start_vertex()` | Mark RUNNING, populate incoming_context |
| `complete_vertex()` | Mark COMPLETED, fill outgoing edge payloads, update downstream readiness |
| `fail_vertex()` | Mark FAILED, propagate SKIPPED to unreachable downstream |
| `topological_order()` | Kahn's algorithm for execution ordering |
| `has_cycle()` | Cycle detection |
| `get_final_result()` | Compose result from terminal vertices |

### Context Flow Example

```
Request: "Review code and deploy to staging"

[ROOT] ─decompose→ [v1: Code Review] ─dependency→ [v3: Deploy to Staging]
                    [v2: Run Tests]   ─dependency→ [v3: Deploy to Staging]

v1 completes → edge to v3 gets payload(summary="review OK", context="full review details...")
v2 completes → edge to v3 gets payload(summary="tests pass", context="full test output...")
v3 becomes READY (both incoming edges have payloads)
v3.incoming_context = [
    EdgePayload(summary="review OK", context="..."),
    EdgePayload(summary="tests pass", context="..."),
]
v3 executes with access to both upstream contexts
```

### MongoDB Persistence

**Source:** `backend/service-orchestrator/app/agent/persistence.py`

| Collection | Key | TTL |
|------------|-----|-----|
| `task_graphs` | `task_id` (unique) | 30 days |

Supports atomic vertex status updates via MongoDB dot notation without rewriting the entire document.

### Progress Reporting

**Source:** `backend/service-orchestrator/app/agent/progress.py`

Uses existing `kotlin_client.report_progress()` API with delegation fields (`delegation_id`, `delegation_agent`, `delegation_depth`) to communicate graph execution state.

### LangGraph Execution

**Source:** `backend/service-orchestrator/app/agent/langgraph_runner.py`

LangGraph StateGraph flow:
```
decompose → select_next → dispatch_vertex → select_next → ... → synthesize → END
```

- `node_decompose` — calls LLM decomposer, creates AgentGraph in state
- `node_select_next` — finds next READY vertex (all incoming edges have payloads)
- `node_dispatch_vertex` — runs the agentic tool loop for the vertex (type determines system prompt + tools)
- `node_synthesize` — composes final result from completed vertices

**Agentic tool loop** (per vertex, max 6 iterations):
1. Load default tools for vertex type via `get_default_tools()`
2. Call LLM with tools
3. If tool calls → execute them → append results → repeat
4. If `request_tools` meta-tool → add requested categories to tool set
5. If text (no tool calls) → that's the final result

### Default Tool Sets per Vertex Type

**Source:** `backend/service-orchestrator/app/agent/tool_sets.py`

| Vertex Type | Default Tools |
|-------------|--------------|
| PLANNER/DECOMPOSE | KB search, memory recall, repo info, structure, tech stack, KB stats, request_tools |
| INVESTIGATOR | Above + web search, file listing, commits, branches, indexed items |
| EXECUTOR/TASK | KB search, web search, files, repo, dispatch coding agent, KB write, memory, scheduling |
| VALIDATOR | KB search, files, repo, branches, commits |
| REVIEWER | KB search, files, repo, branches, commits, tech stack |
| SYNTHESIS | KB search, memory recall, KB write, memory store |
| GATE | KB search, memory recall, request_tools |

Any vertex with `request_tools` can dynamically add tool categories: `kb`, `web`, `git`, `code`, `memory`, `scheduling`, `all`.

### Recursive Decomposition

PLANNER/DECOMPOSE vertices don't execute via the agentic tool loop. Instead, `node_dispatch_vertex` calls `decompose_vertex()` which creates new sub-vertices + edges in the graph. These children are picked up by subsequent `select_next` cycles.

**Limits:** `MAX_DECOMPOSE_DEPTH=8`, `MAX_TOTAL_VERTICES=200`. When limits are hit, PLANNER is auto-converted to EXECUTOR and handled by the agentic loop.

**Fallback:** If recursive decomposition fails, vertex is converted to EXECUTOR.

### ArangoDB Artifact Graph — Impact Analysis

**Source:** `backend/service-orchestrator/app/agent/artifact_graph.py`

ArangoDB-backed graph that tracks ALL entities Jervis manages — not just code. Entities include code artifacts (from Joern CPG via KB), documents, meetings, people, test plans, etc.

**Collections:**

| Collection | Type | Purpose |
|------------|------|---------|
| `graph_artifacts` | Vertex | Entities: classes, files, documents, meetings, people, etc. |
| `artifact_deps` | Edge | Structural/organizational dependencies between entities |
| `task_artifact_links` | Edge | Which TaskGraph vertex touches which entity |

**Entity types (ArtifactKind):**
- Code: module, file, class, function, api, db_table, config, test, schema, component, service
- Docs: document, spec, test_plan, test_scenario, release_note, report
- Organization: person, team, role
- PM: milestone, task, meeting, decision, training, budget
- Infra: environment, pipeline, resource

**Impact flow:**
1. Vertex completes → LLM extracts touched entities from result
2. Entities + dependencies persisted in ArangoDB
3. For each modifying touch → AQL graph traversal (INBOUND) finds affected entities
4. Check which OTHER planned vertices touch affected entities
5. If found → create VALIDATOR vertex (injected into graph, blocks affected vertices)
6. Detect conflicts (two vertices modifying same entity)

**Source:** `backend/service-orchestrator/app/agent/impact.py`

Code artifacts link to existing KnowledgeNodes (Joern CPG) via `kb_node_key` — no duplication.

### Key Files

| File | Purpose |
|------|---------|
| `app/agent/models.py` | GraphVertex, GraphEdge, EdgePayload, TaskGraph, enums |
| `app/agent/graph.py` | Graph operations (topological sort, context accumulation, readiness) |
| `app/agent/persistence.py` | MongoDB save/load, atomic updates |
| `app/agent/progress.py` | Progress reporting to Kotlin server |
| `app/agent/decomposer.py` | LLM-driven decomposition (root + recursive, depth 8) |
| `app/agent/validation.py` | Structural validation (cycles, limits, orphans, fan-in/out) |
| `app/agent/langgraph_runner.py` | LangGraph execution: StateGraph, agentic tool loop, entry point |
| `app/agent/tool_sets.py` | Default tool sets per vertex type, dynamic `request_tools` meta-tool |
| `app/agent/artifact_graph.py` | ArangoDB artifact/entity graph — impact analysis, conflict detection |
| `app/agent/impact.py` | Impact propagation: extract touched entities, traverse deps, create validators |

---

## Autonomous Pipeline Components (EPIC 2-5, 7-10, 14)

### Enhanced KB Output (EPIC 2-S1)

The KB ingest now produces structured fields for pipeline routing:

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

`AutoTaskCreationService` creates tasks from KB findings:
- `CODE_FIX + SIMPLE` → BACKGROUND task (auto-dispatch)
- `CODE_FIX + COMPLEX` → USER_TASK (needs plan approval)
- `RESPOND_EMAIL` → USER_TASK (draft for approval)
- `INVESTIGATE` → BACKGROUND task
- Deduplication via `correlationId`

Source: `backend/server/.../task/AutoTaskCreationService.kt`

### Priority-Based Scheduling (EPIC 2-S3)

Tasks ordered by `priorityScore DESC, createdAt ASC` instead of FIFO.
`TaskPriorityCalculator` assigns 0-100 scores based on urgency, deadline, security keywords.

Source: `backend/server/.../task/TaskPriorityCalculator.kt`

### Planning Phase (EPIC 2-S4)

Background handler now runs an LLM planning phase before the agentic loop:
1. Analyze task + guidelines → structured JSON plan
2. Plan injected into conversation context
3. Guides agentic loop execution

Source: `backend/service-orchestrator/app/background/handler.py`

### Code Review Pipeline (EPIC 3)

> **SSOT:** `docs/architecture.md` § "Coding Agent → MR/PR → Code Review Pipeline"

**Two triggers for code review:**

1. **Jervis coding agent MRs** — triggered by `AgentTaskWatcher` after coding job success
2. **External MRs** — triggered by `MergeRequestContinuousIndexer` polling GitLab/GitHub

#### Path A: Jervis-created MRs (AgentTaskWatcher)

**Phase 1 — Orchestrator (preparation):**
1. Extract diff from workspace (`git diff`) or MR/PR API
2. Static analysis: forbidden patterns, credentials scan, forbidden file changes
3. KB prefetch: Jira issues, meeting discussions, chat decisions, architecture notes
4. Dispatch review agent K8s Job with context

**Phase 2 — Review Agent (Claude SDK, K8s Job):**
1. Reads diff, instructions, pre-fetched KB context
2. Deep KB search via MCP (`kb_search`) for additional context
3. Web search (`web_search`) to verify stale best practices
4. Full file analysis (not just diff) for context
5. Structured verdict: APPROVE / REQUEST_CHANGES / REJECT

**Verdict routing:**
- APPROVE → MR comment posted, user merges manually
- REQUEST_CHANGES (BLOCKERs) → new fix coding task dispatched (max 2 rounds)
- After max rounds → escalation comment on MR

#### Path B: External MRs (MergeRequestContinuousIndexer)

1. **Discovery (120s):** Poll GitLab/GitHub for open MRs/PRs on all projects with REPOSITORY resources
   - Filters: skip drafts, skip `jervis/*` branches (avoid review loops)
   - Saves new MRs as `MergeRequestDocument(state=NEW)` in MongoDB
2. **Task creation (15s):** Pick up NEW MRs → create `TaskTypeEnum.SYSTEM` task in QUEUED state with MR metadata
   - Bypasses KB indexation — MR content IS the review task
   - sourceUrn: `merge-request::proj:{projectId},provider:{gitlab|github},mr:{mrId}`
3. **Graph agent:** Picks up task, reasons about review scope, uses tools (kb_search, web_search)

**KB integration:**
- **Search**: 3+ KB queries before dispatch (task, files, topics) + agent's own MCP searches
- **Store**: Review outcome → `kb_store(kind="finding", sourceUrn="code-review:{taskId}")` for future reference

Sources:
- `backend/service-orchestrator/app/review/code_review_handler.py` (orchestration — Path A)
- `backend/service-orchestrator/app/review/review_engine.py` (static analysis)
- `backend/service-orchestrator/app/agent_task_watcher.py` (trigger + MR creation — Path A)
- `backend/server/.../git/indexing/MergeRequestContinuousIndexer.kt` (polling + task creation — Path B)
- `backend/server/.../rpc/internal/InternalMergeRequestRouting.kt` (MR/PR API)

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
- `backend/server/.../agent/ActionExecutorService.kt`
- `shared/common-dto/.../pipeline/ApprovalActionDtos.kt`

### Meeting Attend Approval (Etapa 1)

Per-meeting approval flow for online meetings (Teams, Meet, Zoom). Read-only first
version: Jervis NEVER joins, sends messages, or speaks — even disclaimer text is
forbidden in v1. Approval is per individual occurrence; no series-wide consent.

Lifecycle (one CALENDAR_PROCESSING task = one meeting instance):

1. **Indexing.** `CalendarContinuousIndexer.indexEvent()` upserts a `TaskDocument`
   per calendar event keyed by `correlationId`. For online meetings it sets
   `meetingMetadata` and `scheduledAt = startTime`. On etag mismatch (event
   rescheduled), all fields are overwritten and the task is reset to `NEW`.
   Cancelled events transition the task to `DONE`.

2. **Polling loop.** `MeetingAttendApprovalService` runs a dedicated 60s loop
   (separate from `BackgroundEngine.runSchedulerLoop` because that one only
   dispatches `TaskTypeEnum.SCHEDULED` cron/scheduledAt tasks). Each tick:
   - Picks tasks with `meetingMetadata != null`, `state=NEW`, and `scheduledAt ≤ now + prerollMinutes`.
     (After Phase 1 the discriminator is `meetingMetadata`, NOT a task type — calendar identity is in `sourceUrn` with scheme `calendar`.)
   - First touch (preroll): creates `ApprovalQueueDocument(PENDING)`, emits push
     (FCM + APNs broadcast → multi-device "first wins"), and writes an ALERT
     chat bubble in the meeting task's own conversation
     (`conversationId = task.id.value`) with `metadata.needsReaction="true"`.
   - At-start fallback: still-`PENDING` approvals fire a second push + bubble
     when `now ≥ startTime`, deduped via `lastActivityAt`.
   - Past `endTime` with no decision → queue entry → `EXPIRED`, task → `DONE`.

3. **Resolution.** User taps Approve / Deny in the in-app dialog or chat bubble.
   `UserTaskRpcImpl.sendToAgent` intercepts `CALENDAR_PROCESSING` tasks with
   `meetingMetadata` and calls `MeetingAttendApprovalService.handleApprovalResponse`,
   which flips queue status, writes a USER decision message, and cancels the
   notification across devices. Approved tasks stay `NEW` (recording pipeline,
   etapa 2A/2B, will pick them up); denied tasks → `DONE`.

4. **External entry points.** MCP exposes `meetings_upcoming`,
   `meeting_attend_approve`, `meeting_attend_deny`, `meeting_attend_status`.
   Approve/deny tools route through `/internal/meetings/attend/{approve|deny}`
   so they share exactly the same code path as in-app taps — there is no
   separate fast path.

5. **Client/project routing.** `ClientDocument.defaultProjectId` is the fallback
   for items polled at client level (calendars, mailboxes). Calendar polling
   handlers set `projectId` from this field when the event itself doesn't
   resolve to a project. Example: a Guru web meeting under client `mazlusek`
   lands in project `příprava` because `mazlusek.defaultProjectId = příprava`.

Configuration (in `k8s/configmap.yaml` under `jervis.meeting-attend`):
- `enabled: true` — master switch
- `preroll-minutes: 10` — how early before `startTime` to ask
- `poll-interval-seconds: 60` — loop tick rate

Calendar polling sources (per-provider, etag upsert + `defaultProjectId` fallback):
- **Google Calendar**: `backend/server/.../google/GoogleWorkspacePollingHandler.kt#pollCalendar`
- **O365 / Outlook**: `backend/server/.../teams/O365CalendarPoller.kt` (dual mode:
  Graph API direct via OAuth2 bearer token, or via the o365-gateway browser
  pool when `authType=NONE` and `o365ClientId` is set). Wired into
  `O365PollingHandler` after chats/channels — single Spring component owns the
  `MICROSOFT_TEAMS` provider slot, so calendar polling rides the same cycle as
  chats. `CalendarEventIndexDocument.provider = MICROSOFT_OUTLOOK` for events
  produced here. Etag = `@odata.etag` from Microsoft Graph; on mismatch the
  document is overwritten and reset to `state=NEW` so the indexer re-emits the
  task update.

Sources:
- `backend/server/.../meeting/MeetingAttendApprovalService.kt`
- `backend/server/.../task/UserTaskRpcImpl.kt` (intercept in `sendToAgent`)
- `backend/server/.../calendar/CalendarContinuousIndexer.kt`
- `backend/server/.../task/MeetingMetadata.kt`
- `backend/server/.../rpc/internal/InternalMeetingAttendRouting.kt`
- `backend/server/.../teams/O365CalendarPoller.kt` + `O365PollingHandler.kt`
- `backend/service-o365-gateway/.../GraphApiModels.kt` (`@odata.etag` on `GraphEvent`)
- `backend/service-mcp/app/main.py` (`meetings_upcoming`, `meeting_attend_*`)
- `shared/common-dto/.../pipeline/ApprovalActionDtos.kt` (`MEETING_ATTEND`)

### Meeting Recording Dispatch (Etapa 2A — desktop loopback)

Once a `CALENDAR_PROCESSING` task is `APPROVED` (queue status), recording
dispatch takes over. Read-only first version: ONLY captures audio that is
already playing on the user's device — Jervis still does not auto-join.

`MeetingRecordingDispatcher` (`backend/server/.../meeting/`) runs a 15 s
@PostConstruct loop that calls
`TaskRepository.findCalendarTasksReadyForRecordingDispatch(now)` — a `@Query`
matching CALENDAR_PROCESSING tasks with `meetingMetadata.startTime ≤ now ≤
endTime` and `meetingMetadata.recordingDispatchedAt IS NULL`. For each match:

1. Look up `ApprovalQueueDocument.findByTaskId(...)` — skip unless `status =
   APPROVED`. Approval is the **gate**; the dispatcher is intentionally
   independent of the approval prompt loop so the two never deadlock.
2. Emit `JervisEvent.MeetingRecordingTrigger(taskId, clientId, projectId,
   title, startTime, endTime, provider, joinUrl)` via
   `NotificationRpcImpl.emitEvent(clientId, ...)`.
3. Mark `meetingMetadata.recordingDispatchedAt = now` so the task is never
   picked up again. Acts as a dedupe lock.

The desktop client (`apps/desktop/.../ConnectionState.handleEvent`) hands
the trigger to `DesktopMeetingRecorder.handleEvent`, which:

- Calls `MeetingRpc.startRecording(MeetingCreateDto)` to allocate a
  `MeetingDocument`. The RPC dedupes by `(clientId, meetingType)`, so a
  duplicate trigger after a process crash cannot create two recordings.
- Spawns `ffmpeg` with an OS-specific loopback input — `avfoundation
  :BlackHole 2ch` on macOS, `wasapi loopback` on Windows, `pulse
  default.monitor` on Linux. The device name is overridable via the
  `audio.loopback.device` agent preference.
- Reads raw 16 kHz / 16-bit / mono signed-LE PCM from ffmpeg's stdout in
  160 000-byte frames (≈5 s of audio), base64-encodes each frame, and
  POSTs it as `AudioChunkDto(meetingId, chunkIndex, data, mimeType=audio/pcm)`
  via `MeetingRpc.uploadAudioChunk`. The server appends to the existing WAV
  file and updates the header on `finalizeRecording`.
- A watchdog stops the process at the meeting's `endTime`, then calls
  `MeetingRpc.finalizeRecording(MeetingFinalizeDto(meetingId, durationSeconds,
  meetingType))`. Stop is also reachable via `JervisEvent.MeetingRecordingStop`
  for "user revoked approval" / "session cancelled".

`MeetingMetadata` carries two new fields: `recordingDispatchedAt` (the
dedupe lock above) and `recordingMeetingId` (set later when the desktop
reports back its `MeetingDocument.id` — used to link transcript artefacts
to the original calendar task).

Sources:
- `backend/server/.../meeting/MeetingRecordingDispatcher.kt`
- `backend/server/.../task/TaskRepository.kt`
  (`findCalendarTasksReadyForRecordingDispatch`)
- `backend/server/.../task/MeetingMetadata.kt` (`recordingDispatchedAt`,
  `recordingMeetingId`)
- `apps/desktop/.../meeting/DesktopMeetingRecorder.kt`
- `apps/desktop/.../ConnectionState.kt` (event dispatch)
- `shared/common-dto/.../events/JervisEvent.kt`
  (`MeetingRecordingTrigger`, `MeetingRecordingStop`)

### Meeting Attender Pod (Etapa 2B — headless fallback)

When the user is not at their desktop but has approved Jervis to attend a
meeting on their behalf, the same `MeetingRecordingTrigger` is delivered
to the `jervis-meeting-attender` K8s pod (`backend/service-meeting-attender/`).
The pod is intentionally **stateless**: no MongoDB, no scheduler, no
discovery loop. It exposes `POST /attend`, `POST /stop`, and `GET /sessions`,
and it acts ONLY when the Kotlin server tells it to.

Per session the pod:

- Loads a per-pod PulseAudio null-sink (`jervis-sink`) so audio playback
  can be recorded without depending on host audio devices.
- Spawns Xvfb + Chromium under Playwright (Teams web refuses true headless
  for guest joins). Microphone permission is **denied**, the meeting URL is
  loaded, audio plays into the null sink.
- Spawns `ffmpeg -f pulse -i jervis-sink.monitor` reading raw 16 kHz / 16-bit /
  mono PCM from stdout in 160 000-byte frames, then base64-encodes each
  frame and POSTs it to the server's
  `/internal/meeting/upload-chunk` HTTP bridge (mirroring the desktop's
  kRPC `uploadAudioChunk` path). `MeetingDocument` is created up-front via
  `/internal/meeting/start-recording` and finalized via
  `/internal/meeting/finalize-recording` when the watchdog hits `endTime`.

Read-only v1 invariants are enforced in code: `permissions=[]` in
Playwright denies mic and camera; the page interaction layer is empty
(only `goto`); no chat function is imported. The pod is deployed via
`k8s/build_meeting_attender.sh` and `k8s/app_meeting_attender.yaml` —
single replica, `Recreate` strategy because in-flight ffmpeg pumps cannot
survive a rolling restart.

Sources:
- `backend/service-meeting-attender/app/main.py`
- `backend/service-meeting-attender/Dockerfile`,
  `entrypoint.sh`, `requirements.txt`
- `k8s/app_meeting_attender.yaml`, `k8s/build_meeting_attender.sh`

### Meeting Urgency Detector (Etapa 3 — live nudge)

`MeetingUrgencyDetector` is a Spring `@Component` invoked from
`WhisperTranscriptionClient.buildProgressCallback` immediately after the
existing `emitMeetingTranscriptionProgress` call. It runs a regex-only
fast path on the latest transcribed segment text and looks for three
signals:

| Kind | Detector | Helper message type |
|------|----------|---------------------|
| `NAME_MENTION` | `(jervis|@jervis|jandamek|jan damek)` | `SUGGESTION` |
| `DIRECT_QUESTION` | `?` AND a 2nd-person Czech/English verb form (`-íš`, `-ete`, `you`) | `QUESTION_PREDICT` |
| `DECISION_REQUIRED` | `musíme rozhodnout`, `schválíš`, `need (your )?approval`, `sign-off`, ... | `SUGGESTION` |

On a match it pushes a `HelperMessageDto` into `MeetingHelperService.pushMessage`,
which propagates via `JervisEvent.MeetingHelperMessage` (RPC event stream)
and any registered helper WebSocket sessions. A 30 s per-meeting cooldown
prevents notification floods. The detector is wrapped in `runCatching`
so it can never block the transcription progress hot path. Anything that
needs an LLM (semantic intent, summary) stays in the offline correction
pipeline; this detector exists only to "tap the user on the shoulder
when their name was just said".

Read-only v1 invariants: the detector only emits to the **user's** device.
It never replies into the meeting chat and never persists transcript text
(the existing meeting pipeline already does that).

Sources:
- `backend/server/.../meeting/MeetingUrgencyDetector.kt`
- `backend/server/.../meeting/WhisperTranscriptionClient.kt#buildProgressCallback`
- `backend/server/.../meeting/MeetingHelperService.kt#pushMessage`

### Meeting Recording End-to-End Wiring (Etapa 2C — gap closure)

The pieces above (dispatcher → desktop / pod → Whisper → urgency detector)
only become a working pipeline once they are joined up. The closure layer
adds five thin pieces of glue:

1. **Dispatch routing.** `MeetingRecordingDispatcher` no longer pushes to
   the desktop unconditionally. It now asks
   `NotificationRpcImpl.hasActiveSubscribers(clientId)` whether the client
   has at least one live event-stream collector. If yes → push the
   `MeetingRecordingTrigger` event (desktop loopback path). If no → call
   the headless attender pod via `MeetingAttenderClient.attend(trigger)`,
   which posts to `POST /attend` on the K8s `service-meeting-attender`. The
   `recordingDispatchedAt` dedupe lock is only set when the chosen path
   reports success — failures leave the field `null` so the next 15 s cycle
   retries.

2. **HTTP bridge for the pod.**
   `installInternalMeetingRecordingBridgeApi(meetingRpcImpl)` exposes
   `/internal/meeting/{start-recording, upload-chunk, finalize-recording}`
   and delegates straight to `MeetingRpcImpl`. The `start-recording` body
   accepts an optional `taskId` so the bridge calls
   `meetingRpcImpl.linkMeetingToTask(taskId, meeting.id)` on the pod's
   behalf — no kRPC stubs in Python.

3. **Cross-link `MeetingMetadata.recordingMeetingId`.** A new
   `IMeetingService.linkMeetingToTask(taskId, meetingId)` is implemented in
   `MeetingRpcImpl` (looks up the task, copies its `meetingMetadata` with
   the new `recordingMeetingId`, idempotent on duplicate calls). Both the
   desktop recorder and the bridge endpoint call it right after their
   `startRecording`. This is the single edge that lets every later stage
   resolve "which calendar task produced this meeting" without parsing
   `deviceSessionId` strings.

4. **Stop on deny-after-approve.** `MeetingAttendApprovalService` now
   inspects `task.meetingMetadata?.recordingDispatchedAt`. If it's non-null
   when the user denies, the service fans out a stop signal on **both**
   channels — `JervisEvent.MeetingRecordingStop` (desktop path, drives
   `DesktopMeetingRecorder.stopRecording`) and `MeetingAttenderClient.stop`
   (pod path) — because the original dispatch routing decision is not
   recorded anywhere. Each channel no-ops on unknown taskId so the fan-out
   is safe.

5. **Audio loopback device preference (machine-local).** Loopback device
   names (`BlackHole 2ch`, WASAPI device strings, PulseAudio monitor source)
   are inherently machine-specific, not client-specific, so they live in a
   new `DesktopLocalSettings` class on the desktop side, backed by
   `java.util.prefs.Preferences` with a `JERVIS_AUDIO_LOOPBACK_DEVICE` env
   override. `ConnectionState` passes `localSettings::getAudioLoopbackDevice`
   into `DesktopMeetingRecorder` and the desktop `File → Audio Loopback
   Device…` menu item opens a Swing input dialog that writes through the
   same class.

### Mid-Recording Live Urgency Probe (Etapa 3 — live transcription)

The original Etapa 3 only fires the urgency detector during the
`finalizeRecording → transcribe` post-meeting pipeline, which means the
detector cannot tap the user on the shoulder *during* the meeting itself —
exactly when it would be useful. `MeetingLiveUrgencyProbe` closes that gap.

It is a Spring `@Service` (`@Order(13)`) that starts a single
`@PostConstruct` coroutine loop with a 45 s tick. Each tick:

1. Loads all `MeetingDocument`s in state `RECORDING` or `UPLOADING`.
2. For each one, compares the live WAV file size against an in-memory
   `probedOffsets` map. If at least 20 s of new audio (≥ 640 000 raw PCM
   bytes for 16 kHz / 16-bit / mono) has accumulated since the previous
   probe, the tail is read off disk into a temp file with a freshly built
   44-byte WAV header (helper `buildWavHeader(dataLen)`).
3. The temp file is sent to `WhisperTranscriptionClient.transcribe(path,
   meetingId = null, ...)` — passing `null` meetingId is the explicit "no
   state mutation" mode of the existing client, so the live probe never
   touches `MeetingDocument.state`, `transcriptText` or
   `transcriptSegments`. Those remain owned by the post-meeting
   `MeetingContinuousIndexer.transcribeContinuously` pipeline.
4. Every returned `WhisperSegment` is fed to
   `MeetingUrgencyDetector.analyzeSegment(meetingId, clientId, text)`. The
   detector's existing 30 s per-meeting cooldown prevents double-firing on
   the boundary between the live probe and the post-meeting transcription.
5. The temp file is deleted, the offset is advanced to the current file
   size, and the next tick begins.

The probe shares GPU budget with the main transcription pipeline through a
local `Mutex` so two Whisper calls can never overlap inside this service.
Cross-service contention with `MeetingContinuousIndexer.transcribeContinuously`
is acceptable because the live probe runs much less frequently and the
Whisper REST endpoint queues internally.

Sources:
- `backend/server/.../meeting/MeetingRecordingDispatcher.kt` (routing + retry)
- `backend/server/.../meeting/MeetingAttenderClient.kt` (HTTP client to pod)
- `backend/server/.../rpc/internal/InternalMeetingRecordingBridgeRouting.kt`
  (3-endpoint bridge → `MeetingRpcImpl`)
- `backend/server/.../meeting/MeetingRpcImpl.kt#linkMeetingToTask`
- `backend/server/.../meeting/MeetingAttendApprovalService.kt` (deny stop fan-out)
- `backend/server/.../meeting/MeetingLiveUrgencyProbe.kt`
- `apps/desktop/.../DesktopLocalSettings.kt` (java.util.prefs-backed)
- `apps/desktop/.../ConnectionState.kt` (provider wiring)
- `apps/desktop/.../Main.kt` (File menu dialog)
- `backend/service-meeting-attender/app/main.py` (sends `taskId` to bridge)

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

When code review returns REQUEST_CHANGES with BLOCKER issues:

1. `code_review_handler.py` creates fix task via `POST /internal/dispatch-coding-agent`
   - `sourceUrn="code-review-fix:{originalTaskId}"` — identifies as fix task
   - `reviewRound=N+1` — preserves round counter
   - `mergeRequestUrl` — reuses existing MR (no new MR created)
2. Coding agent receives: original task + BLOCKER issues + fix instructions
3. Agent commits fix on same branch → push → `AgentTaskWatcher` detects
4. Watcher recognizes fix task → triggers review round N+1
5. Max 2 rounds — after that, escalation comment posted on MR

Round tracking: `AgentTaskWatcher` parses `"Code Review Fix (Round N)"` from task content.

Sources:
- `backend/service-orchestrator/app/review/code_review_handler.py` (`_create_fix_task()`)
- `backend/service-orchestrator/app/agent_task_watcher.py` (fix task detection, lines 183-208)

### Batch Approval & Analytics (EPIC 4-S4/S5)

- `executeBatch()` — groups requests by action type, evaluates once per type
- `recordApprovalDecision()` / `getApprovalStats()` — in-memory approval statistics
- `shouldSuggestAutoApprove()` — suggests auto-approve when ≥10 approvals with 0 denials

Source: `backend/server/.../agent/ActionExecutorService.kt`

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
- Internal API: `backend/server/.../rpc/internal/InternalFilterRulesRouting.kt` (rpc/ = bootstrap routing only)

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
| IdleTaskRegistry | 7-S1 | `backend/server/.../maintenance/IdleTaskRegistry.kt` |
| VulnerabilityScannerService | 7-S2 | REMOVED |
| KbConsistencyCheckerService | 7-S3 | REMOVED |
| LearningEngineService | 7-S4 | `backend/server/.../maintenance/LearningEngineService.kt` |
| DocFreshnessService | 7-S5 | REMOVED |
| DeadlineTrackerService | 8-S1/S2 | `backend/server/.../deadline/DeadlineTrackerService.kt` |
| ProactivePreparationService | 8-S3 | `backend/server/.../deadline/ProactivePreparationService.kt` |
| FilteringRulesService | 10-S1 | `backend/server/.../filtering/FilteringRulesService.kt` |
| TopicTracker | 9-S1 | `backend/service-orchestrator/app/chat/topic_tracker.py` |
| MemoryConsolidation | 9-S2 | `backend/service-orchestrator/app/memory/consolidation.py` |
| IntentDecomposer | 9-S3 | REMOVED — replaced by `agent/chat_router.py` |
| SourceAttribution | 14-S2 | `backend/service-orchestrator/app/chat/source_attribution.py` |
| ApprovalQueueDocument | 4-S3 | `backend/server/.../task/ApprovalQueueDocument.kt` |
| ApprovalStatisticsDocument | 4-S5 | `backend/server/.../task/ApprovalStatisticsDocument.kt` |
| TeamsContinuousIndexer | 11-S4 | `backend/server/.../teams/TeamsContinuousIndexer.kt` |
| SlackContinuousIndexer | 11-S4 | `backend/server/.../slack/SlackContinuousIndexer.kt` |
| DiscordContinuousIndexer | 11-S4 | `backend/server/.../discord/DiscordContinuousIndexer.kt` |
| O365PollingHandler | 11-S3 | `backend/server/.../teams/O365PollingHandler.kt` |
| SlackPollingHandler | 11-S2 | `backend/server/.../slack/SlackPollingHandler.kt` |
| DiscordPollingHandler | 11-S2 | `backend/server/.../discord/DiscordPollingHandler.kt` |
| ChatReplyService | 11-S5 | `backend/server/.../chat/ChatReplyService.kt` |
| CalendarService | 12-S1–S5 | `backend/server/.../calendar/CalendarService.kt` |
| CalendarIntegration | 12-S2/S5 | `backend/service-orchestrator/app/calendar/calendar_integration.py` |
| PromptEvolutionService | 13-S1–S4 | REMOVED |
| BehaviorLearning | 13-S2 | `backend/service-orchestrator/app/selfevolution/behavior_learning.py` |
| UserCorrections | 13-S3 | `backend/service-orchestrator/app/selfevolution/user_corrections.py` |
| BrainWorkflowService | 16-S1/S2 | REMOVED |
| EnvironmentAgentService | 17-S1–S3 | `backend/server/.../environment/EnvironmentAgentService.kt` |

---

**Document Version:** 12.0
**Last Updated:** 2026-02-26


---

# Knowledge Base Architecture

> (Dříve docs/knowledge-base.md)

# Knowledge Base - Implementation and Architecture

**Status:** Production Documentation (2026-02-17)
**Purpose:** Knowledge base implementation, graph design, and architecture

---

## Table of Contents

1. [Knowledge Base Architecture](#knowledge-base-architecture)
2. [Graph Design](#graph-design)
3. [Knowledge Base Implementation](#knowledge-base-implementation)
4. [Continuous Indexers](#continuous-indexers)
5. [RAG Integration](#rag-integration)
6. [Task Outcome Ingestion](#task-outcome-ingestion)
7. [KB Document Upload](#kb-document-upload)
8. [Procedural Memory (Multi-Agent System)](#procedural-memory-multi-agent-system)
9. [Session Memory (Multi-Agent System)](#session-memory-multi-agent-system)
10. [Knowledge Base Best Practices](#knowledge-base-best-practices)
11. [Monitoring & Metrics](#monitoring--metrics)
12. [Thought Map (Navigation Layer)](#thought-map-navigation-layer)

---

## Knowledge Base Architecture

### Overview

Knowledge Base is the most critical component of Jervis. Agent cannot function without quality structured data and relationships.

### Dual Storage Model

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
6. Summary Generation (synchronous LLM call)
   - _generate_summary()    // Extracts: hasActionableContent, isAssignedToMe, hasFutureDeadline,
                           //           suggestedDeadline, urgency, actionType
   ↓
IngestResult (success, summary, ingestedNodes[], hasActionableContent, actionType, ...)
```

---

## Graph Design

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

// Method (from tree-sitter, enriched by Joern CPG)
nodeKey: "method__{classQualifiedName}__{methodName}__branch__{branch}__{projectId}"
type: "method"
props: { label: String, className: String, filePath: String, branchName: String, signature: String? }
// Note: signature is populated by Joern CPG analysis (Phase 2), not tree-sitter
```

#### VCS - Version Control
```kotlin
// Repository (one per connected repository)
nodeKey: "repository__{org}_{repo}"
type: "repository"
props: { label: String, defaultBranch: String, techStack: String?, clientId: String, projectId: String }

// Git Branch (scoped to projectId — same name can exist in different projects)
nodeKey: "branch__{branchName}__{projectId}"
type: "branch"
props: { branchName: String, isDefault: Boolean, status: String, lastCommitHash: String?, fileCount: Int,
         clientId: String, projectId: String }

// Git File (scoped to branch+project — different content per branch)
nodeKey: "file__{path}__branch__{branchName}__{projectId}"
type: "file"
props: { path: String, extension: String, language: String?, branchName: String,
         clientId: String, projectId: String }

// Git Class (scoped to branch+project, extracted by tree-sitter)
nodeKey: "class__{className}__branch__{branchName}__{projectId}"
type: "class"
props: { qualifiedName: String?, filePath: String, branchName: String, visibility: String?,
         clientId: String, projectId: String }

// Git Commit (created by POST /ingest/git-commits — structured ingest, no LLM)
nodeKey: "commit::{hash}::{projectId}"
type: "commit"
props: { hash: String, message: String, author: String, date: String, branch: String,
         clientId: String, projectId: String }
```

> **Key length guard**: ArangoDB `_key` max 254 bytes. Keys exceeding 200 chars get
> a SHA-256 suffix via `_safe_arango_key()` helper in `graph_service.py`.

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

#### COMMUNICATION - Email, Teams, Slack, Discord
```kotlin
// Email
nodeKey: "email::{messageId}"
type: "email"
props: { subject: String, from: String, to: List<String>, sentAt: Instant, receivedAt: Instant, hasAttachments: Boolean }

// Teams Message
nodeKey: "teams::{connectionId}::{messageId}"
type: "teams_message"
props: { from: String, channelName: String?, teamName: String?, chatName: String?, createdAt: Instant }

// Slack Message
nodeKey: "slack::{connectionId}::{messageId}"
type: "slack_message"
props: { from: String, channelName: String, channelId: String, createdAt: Instant, threadTs: String? }

// Discord Message
nodeKey: "discord::{connectionId}::{messageId}"
type: "discord_message"
props: { from: String, channelName: String, guildName: String, guildId: String, channelId: String, createdAt: Instant }
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
// Repository structure (structural ingest — POST /ingest/git-structure)
repository --[has_branch]--> branch
branch --[contains_file]--> file
branch --[has_commit]--> commit
file --[contains]--> class
class --[has_method]--> method     // tree-sitter extracted
file --[imports]--> class           // tree-sitter import analysis

// Git commit relationships (POST /ingest/git-commits — structured, no LLM)
branch --[has_commit]--> commit
commit --[modifies]--> file
commit --[creates]--> file
commit --[deletes]--> file
commit --[parent]--> commit

// Joern CPG edges (POST /ingest/cpg — deep analysis)
method --[calls]--> method          // call graph from Joern
class --[extends]--> class          // inheritance from Joern
class --[uses_type]--> class        // field/param type references from Joern
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

---

## Knowledge Base Implementation (Python Service)

The Knowledge Base is implemented as a Python service (`service-knowledgebase`) to leverage the rich ecosystem of AI and Data tools.

### Tech Stack
*   **Language**: Python 3.11+
*   **Framework**: FastAPI
*   **RAG**: LangChain, Weaviate Client v4
*   **Graph**: Python-Arango
*   **LLM**: Direct httpx to Ollama Router (`/api/generate`) with `X-Ollama-Priority` header (replaced LangChain ChatOllama for full priority control)
*   **Embeddings**: Direct httpx to Ollama Router (`/api/embeddings`) with `X-Ollama-Priority` header

### Features
1.  **Web Crawling**: `POST /crawl` endpoint to index documentation from URLs.
2.  **File Ingestion**: `POST /ingest/file` supports PDF, DOCX, etc. using DocumentExtractor (pymupdf, python-docx, VLM).
3.  **Code Analysis**: `POST /analyze/code` integrates with Joern service (ad-hoc queries).
4.  **Image Understanding**: Automatically detects images and uses `qwen-3-vl` to generate descriptions.
5.  **Scoped Search**: Filters results by Client, Project, and Group.
6.  **Structural Code Ingest**: `POST /ingest/git-structure` — tree-sitter extracts classes, methods, imports from source code.
7.  **Deep Code Analysis**: `POST /ingest/cpg` — Joern CPG export adds semantic edges (calls, extends, uses_type).
8.  **Git Commit Ingest**: `POST /ingest/git-commits` — structured commit nodes with graph edges to branches/files + diff as RAG chunks.

### Full Ingest Endpoint (`POST /ingest/full/async`)

Called by `TaskQualificationService` for each task. Fire-and-forget: returns HTTP 202 immediately, KB processes in background.

**Async flow:**
1. Server dispatches task via `POST /ingest/full/async` with `callbackUrl` + `taskId` (fire-and-forget)
2. KB returns HTTP 202 immediately — server indexing worker is NOT blocked
3. KB processes in background:
   a. Content hashing (SHA256[:16]) for idempotent re-ingest
   b. RAG ingest (embedding + Weaviate) with `contentHash` property
   c. `_generate_summary()` — LLM call (30b model via Ollama Router → GPU-1)
   d. Graph extraction enqueued to background worker
4. Progress events pushed to `/internal/kb-progress` (real-time WebSocket updates)
5. On completion: KB POSTs `FullIngestResult` to `/internal/kb-done` callback
6. `/internal/kb-done` handler saves kbSummary/kbEntities/kbActionable to TaskDocument, applies filters, routes to DONE or QUEUED
7. On error: KB POSTs error to `/internal/kb-done` → task marked ERROR

**Legacy endpoint (`POST /ingest/full`):** Still available for backward compatibility (synchronous processing). Note: The Kotlin `ingestFullWithProgress` NDJSON streaming client method has been removed — only the async callback pattern (`POST /ingest/full/async`) is used in production.

**Contextual Prefix (Anthropic Contextual Retrieval pattern):**
- Before embedding, one LLM call per document generates a 2-3 sentence context
- Prefix prepended to every chunk: "This chunk is from [type] about [topic]..."
- Significantly improves retrieval for chunks that lose meaning in isolation
- Enabled by `CONTEXTUAL_PREFIX_ENABLED=true` (default), disabled for short content (<200 chars)
- Uses low priority (4) to avoid blocking critical GPU work

**Cross-Encoder Reranking (TEI / bge-reranker-v2-m3):**
- After hybrid retrieval, top-50 candidates are re-scored by a cross-encoder model
- TEI (HuggingFace Text Embeddings Inference) runs as separate K8s pod on CPU
- Reranker jointly attends over query+document pair → much better relevance than bi-encoder alone
- Graceful degradation: if reranker unavailable, falls back to pre-rerank order
- Config: `RERANKER_URL`, `RERANKER_TOP_K=50`, `RERANKER_FINAL_K=10`

**Content Hash Deduplication (upsert semantics):**
- SHA256 of combined content, truncated to 32 chars (128-bit collision resistance)
- Stored as `contentHash` property on Weaviate `KnowledgeChunk` objects
- On re-ingest: compare hash → same = skip, different = purge + re-ingest
- Weaviate schema auto-migrates to add `contentHash` property if missing
- **Applies to all ingest paths** — both `full-ingest` and `ingest-queue` (MCP `kb_store`)
  now perform upsert: if `sourceUrn` already has chunks, content hash is compared;
  identical content is skipped, changed content triggers purge + re-ingest

**`_generate_summary()` output fields:**
- `hasActionableContent: bool` — LLM decides if content requires action
- `suggestedActions: List[str]` — e.g., "reply_email", "decompose_issue"
- `isAssignedToMe: bool` — assignment detection
- `hasFutureDeadline: bool`, `suggestedDeadline: str` — deadline extraction
- `urgency: str` — "normal", "high", "critical"
- `summary: str` — one-line summary
- `entities: List[str]` — extracted entity references

**Context Window Management (same pattern as chat/orchestrator):**

The indexing pipeline uses the same context budgeting principle as `chat/context.py` and `graph/nodes/_helpers.py`:

| Setting | Value | Equivalent in Chat |
|---------|-------|--------------------|
| `INGEST_CONTEXT_WINDOW` | 32 768 tokens | `TOTAL_CONTEXT_WINDOW = 32_768` |
| `INGEST_PROMPT_RESERVE` | 1 500 tokens | `SYSTEM_PROMPT_RESERVE = 2_000` |
| `INGEST_RESPONSE_RESERVE` | 2 000 tokens | `RESPONSE_RESERVE = 4_000` |
| `TOKEN_ESTIMATE_RATIO` | chars / 4 | `TOKEN_ESTIMATE_RATIO = 4` |
| `MAX_EXTRACTION_CHUNKS` | 30 | `MAX_SUMMARY_BLOCKS = 15` |
| `LLM_CALL_TIMEOUT` | 180 s | (hardened per-call timeout) |

- **`num_ctx` set explicitly** on all Ollama calls (`ChatOllama` + httpx `_llm_call`). Without this, Ollama uses the model's default (often 2048 tokens), silently truncating the prompt.
- **Token-aware content truncation** in `_generate_summary()`: `max_chars = (WINDOW − PROMPT − RESPONSE) × RATIO`
- **Extraction chunk budget**: large documents (backup logs, long emails) are capped at `MAX_EXTRACTION_CHUNKS`. Representative chunks selected: beginning (headers/context) + end (conclusions) + sampled middle. RAG still indexes ALL content.
- **Per-call timeout**: `LLM_CALL_TIMEOUT` prevents indefinite hangs that block the async callback to Kotlin.

**Key files:**
- `app/core/config.py` — `INGEST_CONTEXT_WINDOW`, `MAX_EXTRACTION_CHUNKS`, `LLM_CALL_TIMEOUT`
- `app/services/knowledge_service.py` — `ingest_full()`, `_generate_summary()`, `ChatOllama(num_ctx=, timeout=)`
- `app/services/graph_service.py` — `_llm_call(num_ctx=)`, chunk budget in `ingest()`
- `app/services/rag_service.py` — `ingest()`, `count_by_source()`, `get_content_hash()`, `purge_by_source()`

### Async Write Queue with Priority

All writes (CRITICAL and NORMAL) are **fully asynchronous**. The ingest endpoint returns immediately after RAG embedding + Weaviate insert. LLM graph extraction is always queued for background processing.

```
┌─────────────────────────────────────────────────────────────────┐
│                    INGEST ENDPOINT                               │
│  1. RAG Ingest (embedding + Weaviate) — synchronous, fast       │
│  2. Enqueue LLM extraction → return immediately                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│              SQLite PRIORITY QUEUE                               │
│  ORDER BY priority ASC, created_at ASC (FIFO within priority)   │
│                                                                  │
│  CRITICAL (0) — MCP, orchestrator FOREGROUND → processed first  │
│  NORMAL   (4) — bulk indexing, git commits   → processed after  │
├──────────────────────────────────────────────────────────────────┤
│  Background worker polls queue → graph_service.ingest()          │
│  Priority header propagated to Ollama Router for GPU routing     │
└──────────────────────────────────────────────────────────────────┘
```

**HTTP concurrency control (dual semaphore):**
- Priority semaphore (5 slots) — `X-Ollama-Priority: 0` (CRITICAL)
- Normal semaphore (5 slots) — no header or `X-Ollama-Priority > 0` (NORMAL)
- CRITICAL requests never wait behind NORMAL ones at HTTP level

**Priority propagation through full stack:**
1. **HTTP level** — `acquire_write_slot_with_priority()` routes to priority or normal semaphore
2. **RAG embedding** — All embeddings run on GPU-2 (`X-Ollama-Priority` header), concurrency controlled by `MAX_CONCURRENT_EMBEDDINGS`
3. **LLM Extraction Queue** — SQLite `priority` column, dequeue `ORDER BY priority ASC, created_at ASC`
4. **Graph LLM calls** — Worker passes priority to graph service → Ollama Router `X-Ollama-Priority` header

**Effect:** Ingest endpoint returns in milliseconds (after embedding). CRITICAL extraction tasks are processed before NORMAL ones in the background queue.

**Embedding concurrency (GPU-2 sweet spot):**
- Embeddings run exclusively on GPU-2 (p40-2, `GPU_MODEL_SETS` enforcement)
- GPU-2 benchmark: sweet spot = 4-5 concurrent requests → ~8.8 RPS (medium text)
- `MAX_CONCURRENT_EMBEDDINGS` env var controls per-worker semaphore
- KB READ (4 uvicorn workers × 2 semaphore = 8 max concurrent) — near GPU sweet spot
- KB WRITE (1 uvicorn worker × 5 semaphore = 5) — optimal for single-process batch embedding
- Text length vs latency: linear above ~800 tokens (~220ms per 1k chars)

**Key files:**
- `app/main.py` — dual semaphore, `acquire_write_slot_with_priority()`
- `app/core/config.py` — `MAX_CONCURRENT_EMBEDDINGS` (default 5)
- `app/services/rag_service.py` — `_embedding_semaphore`, configurable concurrency
- `app/services/knowledge_service.py` — always enqueues, never synchronous extraction
- `app/services/graph_service.py` — `_llm_call(prompt, priority)` with httpx
- `app/services/llm_extraction_queue.py` — priority column, priority-aware dequeue
- `app/services/llm_extraction_worker.py` — passes priority to graph service

### Multi-tenant Scoping (with Project Groups)

KB data is scoped hierarchically:

| Scope | `clientId` | `projectId` | `groupId` | Visibility |
|-------|-----------|-------------|-----------|------------|
| Global | `""` | `""` | `""` | Visible everywhere |
| Client | `"X"` | `""` | `""` | Visible to client X |
| Group | `"X"` | `""` | `"G"` | Visible to all projects in group G |
| Project | `"X"` | `"Y"` | `"G"` | Visible only to project Y |

**GLOBAL keyword (MCP):** When storing via MCP `kb_store`, use `client_id="GLOBAL"` for explicit global writes (empty clientId in KB). This prevents accidental global writes when clientId is simply forgotten. Without `client_id` (and no default configured), MCP returns a descriptive error explaining the tenant hierarchy.

**Tenant validation:** KB validates hierarchy: `projectId` requires `clientId`. Error messages include actual values and hierarchy explanation (`global → client → project`).

**Group cross-visibility**: When retrieving data for a project that belongs to a group,
the filter includes: `(projectId == "" OR projectId == myProject OR groupId == myGroup)`.
This means all projects in the same group share KB data (RAG chunks and graph nodes).

Both Weaviate (RAG) and ArangoDB (Graph) store `groupId` alongside `clientId`/`projectId`.

**Re-grouping**: When a project's group assignment changes (`ProjectService.saveProject()`),
the Kotlin backend detects the `groupId` change and calls `POST /api/v1/retag-group` on the KB
service. This updates all `KnowledgeNodes` with the matching `projectId` to the new `groupId`
(or clears it if the project is removed from a group). RAG chunks inherit the new groupId
through the graph node linkage on next search.

**groupId propagation pipeline:**
```
Ingest:   TaskQualificationService → resolves project.groupId → FullIngestRequest.groupId
                                → KnowledgeServiceRestClient → KB Python /ingest
Chat:     ChatRpcImpl → resolves project.groupId → PythonChatClient.activeGroupId
                      → Python ChatRequest.active_group_id → executor.execute_tool(group_id=...)
                      → KB Python /search payload { groupId: ... }
Background: AgentOrchestratorService → resolves project.groupId → OrchestrateRequestDto.groupId
                                     → Python OrchestrateRequest.group_id → executor.execute_tool(group_id=...)
MCP:      kb_search / kb_store / kb_traverse / kb_graph_search — optional group_id parameter
```

### Integration
*   **DocumentExtractor**: VLM-first text extraction (pymupdf + qwen3-vl for images/scans).
*   **Joern Service**: Used for deep code analysis (ad-hoc queries + CPG export).
*   **Tree-sitter**: Lightweight AST parser for structural code extraction (classes, methods, imports). Bundled in KB service via `tree-sitter-languages` package.

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

### Source Credibility & Branch Scope

#### Problem: Not all information is equally trustworthy

KB ingests data from many sources: official documentation, Jira tickets, code analysis, LLM extraction, chat messages. When retrieving evidence, information from an official API reference should rank higher than a casual Slack mention. Similarly, code structure from `main` branch is canonical truth, while a feature branch may contain experimental code that never merges.

#### Solution: Source Credibility Tiers

Every chunk and graph node carries a `credibility` field — a tier indicating how trustworthy the information is:

| Tier | Weight | Description | Auto-derived from |
|------|--------|-------------|-------------------|
| `verified_fact` | 1.00 | User explicitly asserted as ground truth | MCP `kb_store` with `credibility: "verified_fact"` |
| `official_doc` | 0.95 | Official vendor/project documentation | `SourceType.LINK`, `SourceType.CONFLUENCE`, `/crawl` endpoint |
| `structured_data` | 0.85 | Authoritative system records | `SourceType.EMAIL`, `SourceType.JIRA`, `SourceType.MEETING`, git commits |
| `code_analysis` | 0.75 | Extracted from source code (branch-scoped) | `SourceType.GIT`, tree-sitter, Joern CPG |
| `llm_extracted` | 0.60 | LLM-inferred relationships and summaries | Graph extraction by LLM (capped — even if source is higher) |
| `inferred` | 0.40 | Weak signals: chat mentions, learned patterns | `SourceType.CHAT`, `SourceType.SLACK`, `SourceType.TEAMS`, `SourceType.DISCORD` |

**Auto-derivation:** When `credibility` is not explicitly set on an ingest request, it is automatically derived from `sourceType` using `SOURCE_TYPE_DEFAULT_CREDIBILITY` mapping in `models.py`.

**LLM extraction cap:** When the graph service extracts entities/relationships via LLM, the credibility is capped at `llm_extracted` — even if the source document has higher credibility (e.g., a Jira ticket is `structured_data`, but the LLM-extracted relationships from it are only `llm_extracted`).

**Explicit override:** Callers (MCP, API) can always set `credibility` explicitly to override auto-derivation. Use `verified_fact` for information the user has personally confirmed as ground truth.

#### Branch Scope & Branch Role

Code-related information is scoped to a specific git branch. The same class may exist on `main`, `develop`, and `feature/xyz` with different content.

| Field | Description |
|-------|-------------|
| `branchScope` | Which branch this info applies to (e.g., `"main"`, `"develop"`, `"feature/xyz"`) |
| `branchRole` | Role of the branch: `"default"`, `"protected"`, `"active"`, `"merged"`, `"stale"` |

**Branch role boost** — applied during retrieval as a multiplier on the credibility weight:

| Role | Boost | Rationale |
|------|-------|-----------|
| `default` | 1.00 | `main`/`master` — canonical truth |
| `protected` | 0.95 | `develop`, `release/*` — near-canonical |
| `active` | 0.75 | Active feature branches |
| `merged` | 0.50 | Merged branches — historical, may be outdated |
| `stale` | 0.30 | Abandoned branches — low trust |

**Combined scoring:** During hybrid retrieval, the final score is:
```
final_score = combined_score × credibility_weight × branch_role_boost
```
Chunks without credibility info get a conservative default weight of `0.70`.

#### Storage

Both Weaviate (RAG chunks) and ArangoDB (graph nodes/edges) store:
- `credibility` — `SourceCredibility` enum value (string)
- `branchScope` — branch name (string, empty if not branch-scoped)
- `branchRole` — branch role (string, empty if not branch-scoped)

**Key files:**
- `app/api/models.py` — `SourceCredibility`, `CREDIBILITY_WEIGHTS`, `BRANCH_ROLE_BOOST`, `SOURCE_TYPE_DEFAULT_CREDIBILITY`
- `app/services/hybrid_retriever.py` — `_apply_credibility_boost()`
- `app/services/rag_service.py` — chunk storage with credibility fields, schema migration
- `app/services/graph_service.py` — node/edge creation with credibility, LLM cap logic
- `app/services/knowledge_service.py` — auto-derivation from sourceType

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

- **Purpose:** Thread-aware email indexer — consolidates conversations, detects user replies, indexes attachments as KB documents
- **Thread Consolidation (topicId-based):**
  - SENT emails: indexed to KB only (attachments), no task created
  - Incoming + user already replied in thread: auto-resolves existing USER_TASK → DONE
  - Incoming + existing task for same thread: updates task content with thread summary, bumps `lastActivityAt`
  - Incoming + new conversation: creates new EMAIL_PROCESSING task with `topicId = "email-thread:<threadId>"`
- **Thread Detection:**
  - RFC 2822 headers: `In-Reply-To`, `References` → `EmailMessageIndexDocument.computeThreadId()`
  - SENT folder auto-indexed by `ImapPollingHandler.getFoldersToPoll()` for full conversation context
  - `EmailDirection.SENT` vs `RECEIVED` detected from IMAP folder name
- **Process:**
  1. Reads NEW emails from MongoDB
  2. Checks direction: SENT → KB only, RECEIVED → continues
  3. `EmailThreadService.analyzeThread()` → checks thread context
  4. Cleans email body (HTML → plain text)
  5. Creates/updates task based on thread state
  6. **Indexes email attachments as KB documents** (binary stored during polling)

#### Email Thread Consolidation Flow

```
CentralPoller (IMAP/POP3) — polls INBOX + SENT folder
  → EmailPollingHandlerBase.parseMessage()
    → Extracts In-Reply-To, References headers → computes threadId
    → Detects direction from folder name (SENT/RECEIVED)
  → MongoDB (NEW state with threadId, direction)

EmailContinuousIndexer
  → SENT email?
    → Yes: indexEmailAttachments() → INDEXED (no task)
  → EmailThreadService.analyzeThread()
    → Finds thread messages, checks for SENT replies, finds existing task by topicId
  → User replied externally?
    → Yes: resolveAsHandledExternally() → DONE + INDEXED
  → Existing task for thread?
    → Yes: updateThreadActivity() (append content, bump lastActivityAt) → INDEXED
  → New conversation:
    → createTask(EMAIL_PROCESSING) + setTopicId("email-thread:<threadId>")
    → indexEmailAttachments() → INDEXED
```

#### Email Attachment Flow

```
CentralPoller (IMAP/POP3)
  → EmailPollingHandlerBase.parseContent()
    → Extracts attachment binary from MIME parts
    → Stores binary to kb-documents/{uuid}_{filename}
    → Sets storagePath on EmailAttachment
  → MongoDB (NEW state with storagePath)

EmailContinuousIndexer
  → indexEmailAttachments():
    → For each attachment with storagePath:
      → AttachmentKbIndexingService.registerPreStoredAttachment()
        → Computes SHA-256 content hash
        → Calls KB service POST /documents/register
        → KB extracts text + indexes into RAG/Graph
  → Marks as INDEXED
```

### JiraContinuousIndexer (BugTrackerContinuousIndexer)

- **Purpose:** Creates BUGTRACKER_PROCESSING task from Jira issues + indexes attachments as KB documents
- **Process:**
  1. Reads NEW Jira issues from MongoDB
  2. Fetches full issue details from Jira API (including attachments)
  3. Downloads and stores attachments in `attachments/` directory
  4. Creates BUGTRACKER_PROCESSING task with attachment metadata
  5. **Indexes each attachment as a KB document**:
     - Copies binary from `attachments/` to `kb-documents/` (separate lifecycle)
     - Registers with KB service for text extraction and RAG indexing
     - SourceUrn format: `jira-attachment::conn:{id},issueKey:{key},file:{filename}`

### ConfluenceContinuousIndexer (WikiContinuousIndexer)

- **Purpose:** Creates WIKI_PROCESSING task from Confluence pages + indexes attachments as KB documents
- **Process:**
  1. Reads NEW Confluence pages from MongoDB
  2. Fetches full page details from Confluence API (including attachments)
  3. Downloads and stores attachments in `attachments/` directory
  4. Creates WIKI_PROCESSING task with attachment metadata
  5. **Indexes each attachment as a KB document**:
     - Copies binary from `attachments/` to `kb-documents/` (separate lifecycle)
     - Registers with KB service for text extraction and RAG indexing
     - SourceUrn format: `confluence-attachment::conn:{id},pageId:{id},file:{filename}`

### AttachmentKbIndexingService (shared)

- **Location:** `backend/server/.../infrastructure/indexing/AttachmentKbIndexingService.kt`
- **Purpose:** Shared service for indexing attachments from any source as KB documents
- **Methods:**
  - `indexAttachmentAsKbDocument()` — stores binary + registers (for new binary data)
  - `indexStoredAttachmentAsKbDocument()` — copies from `attachments/` to `kb-documents/` + registers
  - `registerPreStoredAttachment()` — registers already-stored file in `kb-documents/` (no copy needed)
- **Features:**
  - SHA-256 content hash for deduplication
  - MIME-type-based categorization (REPORT, TECHNICAL, OTHER)
  - Error-tolerant: individual attachment failures don't block parent entity indexing

### GitContinuousIndexer

- **Purpose:** Creates rich KB content from Git commits with three-phase indexing
- **Process (initial branch index):**
  1. **Branch Overview** — repository tech stack, file tree, README, recent changes → `GIT_PROCESSING` task
  2. **Structural Ingest** — tree-sitter extracts classes, methods, imports from source files → `POST /ingest/git-structure` → ArangoDB graph nodes (repo→branch→file→class→method)
  3. **CPG Deep Analysis** — Joern generates Code Property Graph → `POST /ingest/cpg` → semantic edges (calls, extends, uses_type)
- **Process (incremental commits):**
  1. Parses `git diff-tree --name-status` → classifies files as modified/created/deleted
  2. Sends structured data directly via `knowledgeService.ingestGitCommits()` → `POST /ingest/git-commits`
  3. KB creates commit nodes in ArangoDB with edges (`has_commit`, `modifies`, `creates`, `deletes`)
  4. Diff content indexed as RAG chunks for fulltext search
  5. **No LLM involved** — purely structural graph ingest + RAG embedding

#### Tree-sitter Pipeline (Phase 1 — fast, ~5s per 100 files)

Kotlin sends file contents to KB service → KB invokes tree-sitter → extracts classes, methods, imports.

```
GitContinuousIndexer.createStructuralIndexTask()
  → readSourceFileContents() — reads top 150 files (max 50KB each, 5MB total)
  → POST /ingest/git-structure with fileContents
  → KB service code_parser.parse_file() for each file
  → Creates: method nodes + has_method edges + imports edges
```

Supported languages: Kotlin, Java, Python, TypeScript/JavaScript, Go, Rust.

#### Joern CPG Pipeline (Phase 2 — deep, ~60-120s per project)

After structural nodes exist, dispatches Joern K8s Job for semantic analysis.

```
GitContinuousIndexer.createCpgAnalysisTask()
  → POST /ingest/cpg (clientId, projectId, branch, workspacePath)
  → KB service dispatches JoernClient.run_cpg_export()
    → K8s Job: joern → importCode → cpg-export-query.sc → .jervis/cpg-export.json
  → KB reads pruned CPG JSON
  → graph_service.ingest_cpg_export() creates edges:
    - calls: method → method (call graph)
    - extends: class → class (inheritance)
    - uses_type: class → class (type references)
```

**CPG Pruning Strategy:** Only actionable edges are imported. Skipped: AST nodes, CFG/CDG edges, reaching-def edges, ARGUMENT/RECEIVER details (too granular for agent use).

**Failure handling:** CPG analysis failure is non-fatal — the structural graph from tree-sitter remains fully usable.

#### Git Commit Ingest Pipeline (incremental — per commit)

Kotlin sends structured commit data directly to KB, bypassing the generic indexing pipeline.

```
GitContinuousIndexer.processCommit()
  → git diff-tree --name-status → classify M/A/D/R files
  → git diff <hash> → full diff text
  → knowledgeService.ingestGitCommits(GitCommitIngestRequest)
    → POST /ingest/git-commits (KB write service)
    → graph_service.ingest_git_commits():
      - Creates commit node: commit::{hash}::{projectId}
      - Edge: branch --[has_commit]--> commit
      - Edge: commit --[modifies]--> file (for each M file)
      - Edge: commit --[creates]--> file (for each A file)
      - Edge: commit --[deletes]--> file (for each D file)
      - Edge: commit --[parent]--> commit (if parent hash known)
    → rag_service.ingest() for diff text (RAG chunks for fulltext)
```

**Key files (Python):** `app/api/models.py` (GitCommitInfo, GitCommitIngestRequest), `app/services/graph_service.py` (ingest_git_commits), `app/api/routes.py` (POST /ingest/git-commits)
**Key files (Kotlin):** `GitCommitIngestRequest.kt` (DTOs), `KnowledgeServiceRestClient.kt` (REST client), `GitContinuousIndexer.kt` (caller)

### MeetingContinuousIndexer

- **Purpose:** Transcribes uploaded meeting recordings and creates MEETING_PROCESSING tasks
- **Two-stage pipeline:**
  1. **Transcription:** Polls for UPLOADED meetings → runs Whisper (K8s Job in-cluster, subprocess locally) → TRANSCRIBED
  2. **KB Indexing:** Polls for TRANSCRIBED meetings → builds markdown content (title, date, duration, type, full transcript with timestamps) → creates MEETING_PROCESSING task → INDEXED
- **State machine:** RECORDING → UPLOADED → TRANSCRIBING → TRANSCRIBED → INDEXED (or FAILED at any step)
- **sourceUrn format:** `meeting::id:{meetingId},title:{title}`

### TeamsContinuousIndexer

- **Purpose:** Indexes Microsoft Teams messages (chats and channel messages) from O365 Gateway
- **Process:**
  1. `O365PollingHandler` fetches messages via O365 Gateway REST → saves as NEW in `teams_message_index`
  2. Reads NEW documents from MongoDB (no external API calls)
  3. Creates `CHAT_PROCESSING` task with structured content (context, from, date, body, metadata)
  4. Sets `topicId`: `teams-channel:{teamId}/{channelId}` or `teams-chat:{chatId}`
  5. Marks as INDEXED
- **sourceUrn format:** `teams::conn:{id},msgId:{id},channelId:{id}` or `teams::conn:{id},msgId:{id},chatId:{id}`

### SlackContinuousIndexer

- **Purpose:** Indexes Slack channel messages fetched via Slack Web API
- **Process:**
  1. `SlackPollingHandler` fetches via `conversations.list` + `conversations.history` → saves as NEW in `slack_message_index`
  2. Reads NEW documents from MongoDB
  3. Creates `SLACK_PROCESSING` task with channel context, author, and message body
  4. Sets `topicId`: `slack-channel:{channelId}`
  5. Marks as INDEXED
- **sourceUrn format:** `slack::conn:{id},msgId:{ts},channelId:{id}`

### DiscordContinuousIndexer

- **Purpose:** Indexes Discord guild channel messages fetched via Discord REST API
- **Process:**
  1. `DiscordPollingHandler` fetches via `/guilds`, `/channels`, `/messages` → saves as NEW in `discord_message_index`
  2. Reads NEW documents from MongoDB
  3. Creates `DISCORD_PROCESSING` task with guild/channel context, author, and message body
  4. Sets `topicId`: `discord-channel:{guildId}/{channelId}`
  5. Marks as INDEXED
- **sourceUrn format:** `discord::conn:{id},msgId:{id},channelId:{id},guildId:{id}`

### MergeRequestContinuousIndexer

- **Purpose:** Polls GitLab/GitHub for open MRs/PRs and creates code review tasks for external (non-Jervis) MRs
- **Two-loop architecture:**
  1. **Discovery loop (120s):** Polls all projects with REPOSITORY resources → fetches open MRs/PRs via GitLab/GitHub API → deduplicates against MongoDB → saves NEW documents
  2. **Task creation loop (15s):** Picks up NEW MR documents → creates SCHEDULED_TASK in QUEUED state (bypasses KB indexation) → marks as REVIEW_DISPATCHED
- **Filters:** Skips draft MRs/PRs (not ready), skips `jervis/*` branches (handled by AgentTaskWatcher)
- **Author extraction:** GitLab `author.name`/`author.username`, GitHub `user.login`
- **State machine:** NEW → REVIEW_DISPATCHED → DONE (or FAILED)
- **Provider support:** GitLab (`listOpenMergeRequests`) and GitHub (`listOpenPullRequests`)
- **sourceUrn format:** `merge-request::proj:{projectId},provider:{gitlab|github},mr:{mrId}`
- **Task content:** Markdown with MR title, description, branches, URL, author, review instructions
- **Key files:** `MergeRequestContinuousIndexer.kt`, `MergeRequestDocument.kt`, `MergeRequestRepository.kt`

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
seedNodes.forEach { seed →
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

## Indexing Queue Priority & Retry

### Queue Priority / Reordering

Tasks in `INDEXING` state are processed in order: `queuePosition ASC NULLS LAST, createdAt ASC`.
This means manually prioritized items (those with a `queuePosition` set) are processed first, while others fall back to FIFO by creation time.

**RPC endpoints:**
- `reorderKbQueueItem(taskId, newPosition)` -- set explicit queue position
- `prioritizeKbQueueItem(taskId)` -- move to position 1 (front of queue)

The UI shows up/down arrows and a prioritize button on items in the "Čeká na KB" pipeline section.

### Exponential Retry for Operational Errors

When Ollama is busy or unreachable, indexing retries infinitely with DB-based exponential backoff:

```
5s → 10s → 20s → 40s → 80s → 160s → 300s (cap, retries forever at 5min)
```

**Configuration** (`QualifierProperties`):
- `initialBackoffMs = 5000` (5 seconds)
- `maxBackoffMs = 300000` (5 minutes)

**Retriable errors** (infinite retry, never marks ERROR):
- Timeout, connection, socket, network, prematurely closed
- Ollama busy, queue full, too many requests
- HTTP 429 (Too Many Requests), HTTP 503 (Service Unavailable)

**Non-retriable errors** (permanent ERROR state):
- Actual indexing/parsing errors from KB microservice

**Key invariants:**
- Items stay `INDEXING` with a future `nextQualificationRetryAt` during backoff (not marked FAILED)
- Queue releases items only on restart or crash (stale task recovery in `BackgroundEngine.resetStaleTasks()`)
- `qualificationRetries` counter tracks retry attempts (displayed in UI as "Opakuje (Nx)")

### SourceUrn Provider Dispatch

BugTracker and Wiki items use provider-specific SourceUrn factories:

| Provider | BugTracker URN | Wiki URN |
|----------|---------------|----------|
| GITHUB | `SourceUrn.githubIssue()` | — |
| GITLAB | `SourceUrn.gitlabIssue()` | — |
| JIRA / other | `SourceUrn.jira()` | — |
| CONFLUENCE / other | — | `SourceUrn.confluence()` |

This ensures correct source type display in the indexing queue UI (e.g., GitHub Issues connections show "GitHub" not "Jira").

---

## Task Outcome Ingestion

### Overview

When a task completes in the `finalize` node, the orchestrator automatically extracts structured knowledge from the completed task and ingests it into KB for long-term memory. This enables the agent to learn from past work and avoid solving the same problems repeatedly.

### Two-Level Memory Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   MEMORY LAYERS                          │
├────────────────────────┬────────────────────────────────┤
│    Local Memory        │    Knowledge Base               │
│    (Chat History)      │    (Long-term)                  │
├────────────────────────┼────────────────────────────────┤
│ • ChatMessageDocument  │ • Task outcomes                 │
│ • ChatSummaryDocument  │ • Key decisions                 │
│ • MongoDB              │ • Code patterns                 │
│ • Temporary, cheap     │ • Lessons learned               │
│ • Back-and-forth       │ • ArangoDB + Weaviate           │
│ • ✅ Already exists     │ • Permanent, expensive          │
│                        │ • ✅ Implemented                 │
└────────────────────────┴────────────────────────────────┘
```

### Significance Filter

Not all tasks are worth ingesting. The filter is deterministic (no LLM call):

| Task Category | Task Action | Ingested? |
|---------------|-------------|-----------|
| `single_task` | `code`, `tracker_ops`, `mixed` | ✅ Yes |
| `single_task` | `respond` (with step_results) | ✅ Yes |
| `single_task` | `respond` (no steps) | ❌ No |
| `epic` | any | ✅ Yes |
| `generative` | any | ✅ Yes |
| `advice` | any | ❌ No (simple Q&A) |
| any | error present | ❌ No |
| any | empty final_result | ❌ No |

### Extraction Schema

LLM extracts structured JSON from the completed task context:

```json
{
  "outcome_summary": "2-3 sentences describing what was done",
  "key_decisions": ["why solution X over Y"],
  "patterns_used": ["exponential backoff", "circuit breaker"],
  "artifacts": ["BackgroundEngine.kt", "OrchestratorCircuitBreaker.kt"],
  "lessons_learned": ["non-retryable errors should not have backoff"],
  "topics": ["workspace", "retry", "resilience"]
}
```

### Ingest Details

- **sourceUrn:** `task-outcome:{task_id}` — stable, re-runs overwrite previous entry
- **kind:** `task_outcome`
- **Priority:** `X-Ollama-Priority: 4` (background, non-urgent embedding)
- **Endpoint:** KB write service (`jervis-knowledgebase-write:8080/api/v1/ingest`)
- **Content:** Markdown document with sections: Summary, Key Decisions, Patterns, Artifacts, Lessons

### Implementation Files

- `backend/service-orchestrator/app/kb/outcome_ingest.py` — extraction + ingestion logic
- `backend/service-orchestrator/app/graph/nodes/finalize.py` — integration point (Phase 2 after summary)

### Data Flow

```
finalize()
  ├── Phase 1: Generate Czech summary for user (existing)
  │
  └── Phase 2: KB outcome ingestion (fire-and-forget)
        ├── is_significant_task() — deterministic filter
        ├── extract_outcome() — LLM LOCAL_FAST → structured JSON
        └── ingest_outcome_to_kb() — POST /api/v1/ingest
              └── Never blocks, never fails the task
```

---

## User Context Auto-Prefetch

### Overview

Agent automatically retrieves user-learned knowledge from previous conversations at the start of each new orchestration. This enables personalized responses without re-asking questions.

### Structured Categories

Knowledge stored via `store_knowledge` tool uses structured categories (kind = `user_knowledge_{category}`):

| Category | Kind | Examples |
|----------|------|----------|
| preference | `user_knowledge_preference` | Coding style, tooling, workflow preferences |
| domain | `user_knowledge_domain` | Business domain, industry, location info |
| team | `user_knowledge_team` | People, roles, processes |
| tech_stack | `user_knowledge_tech_stack` | Frameworks, libraries, patterns |
| personal | `user_knowledge_personal` | Personal info about the user |
| general | `user_knowledge_general` | Anything else |

### Auto-Prefetch Flow

1. **Intake node** calls `fetch_user_context(client_id, project_id)`
2. Function queries KB `/api/v1/chunks/by-kind` for each of the 6 categories
3. Pure Weaviate filter — **no embeddings, no GPU** — very fast (~30ms)
4. Results assembled into markdown with category headers
5. Stored in `state["user_context"]` for downstream nodes
6. **Respond node** injects into `context_parts` as "User Context (learned from previous conversations)"
7. System prompt instructs agent to use existing context and not re-store known facts

### Implementation Files

- `backend/service-orchestrator/app/kb/prefetch.py` — `fetch_user_context()`, `USER_CONTEXT_KINDS`
- `backend/service-orchestrator/app/tools/definitions.py` — `TOOL_STORE_KNOWLEDGE` with category enum
- `backend/service-orchestrator/app/tools/executor.py` — `_execute_store_knowledge()` (writes `kind=user_knowledge_{category}`)
- `backend/service-orchestrator/app/graph/nodes/intake.py` — calls `fetch_user_context()`
- `backend/service-orchestrator/app/graph/nodes/respond.py` — injects `user_context` into LLM prompt

### Data Flow

```
store_knowledge(subject="BMS", content="Brokerage Management System", category="domain")
  → KB ingest: kind=user_knowledge_domain, sourceUrn=user-knowledge:domain:BMS:{ts}

Next conversation:
  intake → fetch_user_context(client_id) → queries 6 user_knowledge_* kinds
  → state["user_context"] = "### Domain Context\n- **BMS**: Brokerage Management System\n..."
  → respond node includes in LLM context → personalized answer
```

---

## KB Document Upload

### Overview

Users can upload documents (PDF, DOCX, TXT, images, etc.) directly into the Knowledge Base.
Uploaded documents are:

1. **Archived** on the shared filesystem (`/opt/jervis/data/clients/{clientId}/kb-documents/`)
2. **Tracked** as `kb_document` nodes in ArangoDB (metadata, state, storage path)
3. **Extracted** via DocumentExtractor (pymupdf for docs, VLM for images)
4. **Indexed** into RAG (Weaviate) for semantic search
5. **Graph-linked** via LLM entity extraction (same as other KB content)

This means the KB has full overview of all uploaded documents: their metadata,
classification, extracted content, and the original file is always available for download.

### Architecture

```
┌─────────────┐     KRPC      ┌──────────────────┐     REST     ┌─────────────────┐
│  Client UI  │ ───────────►  │  KbDocumentRpc   │ ──────────►  │  KB Python Svc  │
│  (Desktop)  │               │  (Kotlin Server) │              │  (FastAPI)      │
└─────────────┘               └──────────────────┘              └─────────────────┘
                                     │                                │
                              Store file on FS              Create ArangoDB node
                              (DirectoryStructureService)   Extract text (DocumentExtractor)
                                     │                      Ingest into RAG
                                     ▼                            │
                              /opt/jervis/data/                   ▼
                              clients/{clientId}/           Weaviate + ArangoDB
                              kb-documents/
                              {uuid}_{filename}

┌─────────────┐   direct REST  ┌─────────────────┐
│  MCP Server │ ──────────────►│  KB Python Svc  │  (reads file from shared PVC)
│  (Claude)   │                └─────────────────┘
└─────────────┘
```

### Document State Machine

```
UPLOADED → EXTRACTED → INDEXED
    │           │
    └───────────┴──→ FAILED
```

- **UPLOADED**: File stored on FS, graph node created, awaiting text extraction
- **EXTRACTED**: Text extracted via DocumentExtractor (pymupdf/VLM), ready for RAG ingest
- **INDEXED**: Content ingested into RAG + graph. Fully searchable.
- **FAILED**: Extraction or indexing failed (error stored in node)

### Document Categories

| Category | Description |
|----------|------------|
| TECHNICAL | Technical documentation, API specs, architecture docs |
| BUSINESS | Business requirements, proposals, contracts |
| LEGAL | Legal documents, compliance, policies |
| PROCESS | Process documentation, workflows, SOPs |
| MEETING_NOTES | Meeting minutes, agendas |
| REPORT | Reports, analyses, summaries |
| SPECIFICATION | Product/feature specifications |
| OTHER | Uncategorized |

### Storage Path Convention

Documents are stored at the **client level** (not project level) since KB documents
can be cross-project. The directory structure managed by `DirectoryStructureService`:

```
{DATA_ROOT_DIR}/clients/{clientId}/kb-documents/{uuid}_{sanitized_filename}
```

- UUID ensures uniqueness
- Sanitized filename preserves readability
- Relative path stored in ArangoDB node (`storagePath` field)

### SourceUrn Format

```
doc::id:{uuid}                                                          # User-uploaded KB document
email-attachment::conn:{connId},msgId:{messageId},file:{filename}       # Email attachment
jira-attachment::conn:{connId},issueKey:{key},file:{filename}           # Jira attachment
confluence-attachment::conn:{connId},pageId:{pageId},file:{filename}    # Confluence attachment
```

Matches the `SourceUrn.document()`, `SourceUrn.emailAttachment()`, `SourceUrn.jiraAttachment()`,
and `SourceUrn.confluenceAttachment()` factories in Kotlin.

### API Endpoints (Python KB Service)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/v1/documents/upload` | Upload file + create node + extract + ingest |
| POST | `/api/v1/documents/register` | Register file already on FS (Kotlin stored it) |
| GET | `/api/v1/documents?clientId=&projectId=` | List documents |
| GET | `/api/v1/documents/{docId}` | Get document details |
| PUT | `/api/v1/documents/{docId}` | Update metadata (title, category, tags) |
| DELETE | `/api/v1/documents/{docId}` | Delete (purges RAG + graph node) |
| POST | `/api/v1/purge` | Purge all RAG chunks + graph refs for a `sourceUrn` (also cleans ThoughtAnchors) |
| POST | `/api/v1/documents/{docId}/reindex` | Re-extract and re-ingest |
| POST | `/api/v1/retag-group` | Update groupId on all KB nodes for a project (called on group change) |

### MCP Tools

| Tool | Description |
|------|------------|
| `kb_document_upload` | Upload a file from local disk / PVC into KB |
| `kb_document_list` | List all KB documents for a client |
| `kb_document_delete` | Delete a document from KB |
| `kb_delete_by_source` | Purge all RAG chunks + graph data for a `source_urn` (cleanup duplicates, outdated entries) |

### Key Files

| File | Purpose |
|------|---------|
| `shared/common-dto/.../kb/KbDocumentDtos.kt` | Document DTOs (state, category, upload/update/delete) |
| `shared/common-api/.../IKbDocumentService.kt` | KRPC service interface |
| `backend/server/.../rpc/KbDocumentRpcImpl.kt` | Kotlin RPC implementation (stores on FS, calls KB REST) |
| `backend/server/.../infrastructure/storage/DirectoryStructureService.kt` | FS storage methods (`storeKbDocument`, `readKbDocument`, `deleteKbDocument`) |
| `backend/server/.../infrastructure/indexing/AttachmentKbIndexingService.kt` | Shared service for indexing attachments as KB documents |
| `backend/server/.../infrastructure/llm/KnowledgeServiceRestClient.kt` | REST client to Python KB (document CRUD methods) |
| `backend/service-knowledgebase/app/api/routes.py` | Python endpoints (`/documents/*`) |
| `backend/service-knowledgebase/app/services/knowledge_service.py` | Document business logic (upload, extract, list, delete) |
| `backend/service-knowledgebase/app/services/graph_service.py` | ArangoDB node management (`kb_document` type) |
| `backend/service-mcp/app/main.py` | MCP tools (`kb_document_upload`, `kb_document_list`, `kb_document_delete`) |

---

## Knowledge Base Best Practices

### Key Practices

1. **Two-stage processing:** KB indexing (GPU-1 for LLM, GPU-2 for embedding) + orchestrator execution
2. **Bidirectional knowledge:** RAG (semantic) + Graph (structured)
3. **Evidence-based relationships:** Every edge has supporting evidence
4. **Multi-tenancy:** Per-client isolation in all storage layers
5. **Project group cross-visibility:** Projects in same group share KB data
6. **Fail-fast design:** Errors propagate, no silent failures
7. **Type safety:** Explicit input/output types throughout
8. **Infinite retry for operational errors:** Ollama busy/timeout → exponential backoff, never marks ERROR
9. **Queue priority:** Manual reordering of indexing queue items via `queuePosition`
10. **Write priority:** Dual semaphore ensures MCP/orchestrator writes never blocked by bulk indexing
11. **Direct structured ingest:** Git commits bypass LLM indexing — structured graph nodes + RAG embedding only
12. **Procedural Memory:** Learned workflows stored per-client for automatic procedure reuse
13. **Session Memory:** 7-day short-term memory bridging orchestrations for recent context

### Benefits

1. **Cost efficiency:** Local GPU for all operations, OpenRouter only for foreground chat when configured
2. **Scalability:** Parallel embedding (GPU-2), orchestrator execution on idle
3. **Explainability:** Evidence links for all relationships
4. **Flexibility:** Schema-less graph for new entity types
5. **Performance:** Hybrid search combining semantic + structured
6. **User priority:** Preemption ensures immediate response
7. **Resilience:** Infinite retry with backoff ensures no items lost during Ollama overload

---

## Procedural Memory (Multi-Agent System)

### Overview

Procedural Memory stores learned workflow procedures for the multi-agent orchestrator. When the orchestrator receives a task, it searches Procedural Memory for known workflows matching the task pattern.

### Storage

- **Collection:** ArangoDB `ProcedureNode` (per-client)
- **TTL:** Permanent with usage-decay (unused procedures gradually lose priority)
- **Access:** Via `procedural_memory.py` in the orchestrator

### ProcedureNode Structure

```python
class ProcedureNode(BaseModel):
    trigger_pattern: str        # "email_with_question", "task_completion", "bug_report"
    procedure_steps: list[dict] # [{agent: "CodeReviewAgent", action: "review"}, ...]
    success_rate: float         # 0.0-1.0 (how often the procedure succeeded)
    last_used: str | None
    usage_count: int
    source: str                 # "learned" | "user_defined"
    client_id: str              # Procedures are per-client
```

### How It Works

1. **Lookup:** Orchestrator's `plan_delegations` node searches for procedures matching the task pattern
2. **User-defined priority:** Procedures with `source="user_defined"` always take precedence over learned ones
3. **Learning:** After successful orchestration, the workflow pattern is saved as a new procedure
4. **Missing procedure:** If no procedure exists, orchestrator asks the user and saves the answer

### Example Procedures

| Trigger | Steps | Agents |
|---------|-------|--------|
| `task_completion` | Review → Deploy → Test → Close | CodeReview → DevOps → Test → IssueTracker |
| `email_deadline_question` | Find issue → Check status → Estimate → Reply | IssueTracker → Research → Communication |
| `bug_report` | Search KB → Analyze → Fix → Test → PR | Research → Coding → Test → Git |

---

## Session Memory (Multi-Agent System)

### Overview

Session Memory provides per-client/project short-term memory across orchestrations. It captures key decisions from recent interactions (chat + background) for use in subsequent orchestration runs.

### Why Not Just KB?

- KB is optimized for semantic search ("find everything about technology X")
- Session Memory is a fast key-value lookup ("what happened an hour ago?")
- KB indexing has latency; Session Memory is immediately available
- Session Memory is a cache; KB is permanent storage

### Storage

- **Collection:** MongoDB `session_memory`
- **Key:** `client_id + project_id`
- **TTL:** 7 days (configurable via `session_memory_ttl_days`)
- **Max entries:** 50 per client/project

### SessionEntry Structure

```python
class SessionEntry(BaseModel):
    timestamp: str
    source: str               # "chat" | "background" | "orchestrator_decision"
    summary: str              # Brief summary (max 200 chars)
    details: dict | None      # Optional details
    task_id: str | None       # Reference to task
```

### How It Works

1. **Read:** At orchestration start (intake node), Session Memory is loaded for the client/project
2. **Write:** After each orchestration, key decisions are saved to Session Memory
3. **Expiry:** Entries older than 7 days are pruned; important items are already in KB by then

### Key Files

| File | Purpose |
|------|---------|
| `app/context/session_memory.py` | MongoDB CRUD for session memory |
| `app/context/procedural_memory.py` | ArangoDB CRUD for procedural memory |
| `app/context/retention_policy.py` | Decides what to save to KB vs context_store |
| `app/context/summarizer.py` | Summarization utilities (no truncation of agent outputs) |

---

## K8s Deployment (Read/Write Split)

KB runs as two separate deployments (`k8s/app_knowledgebase.yaml`):

| Deployment | Mode | Workers | Embedding Concurrency | Priority | Purpose |
|------------|------|---------|----------------------|----------|---------|
| `jervis-knowledgebase-read` | `read` | 4 (uvicorn) | 2 per worker (8 total) | `high-priority` | Search, traverse, resolve |
| `jervis-knowledgebase-write` | `write` | 1 (uvicorn) | 5 per worker (5 total) | normal | Ingest, crawl, purge |

**Services:** `jervis-knowledgebase` (read, default) + `jervis-knowledgebase-write` (write)

**Probe configuration (read — stability-critical):**
- Startup: 30 × 5s = 150s max startup time
- Liveness: 30s timeout, 30s period, 5 failures → restart
- Readiness: 15s timeout, 15s period, 4 failures → remove from service

**Workers:** Configurable via `UVICORN_WORKERS` env var (Dockerfile: `--workers ${UVICORN_WORKERS:-1}`). Multi-worker prevents event loop blocking during heavy graph traversal from killing liveness probes.

**Build:** `k8s/build_knowledgebase.sh`

---

## Monitoring & Metrics

### Prometheus Metrics

KB service exposes Prometheus metrics at `/metrics` endpoint (both read and write deployments).

**Metric categories:**

| Category | Metrics | Labels |
|----------|---------|--------|
| **RAG operations** | `kb_rag_ingest_total`, `kb_rag_ingest_duration_seconds`, `kb_rag_ingest_chunks`, `kb_rag_query_total`, `kb_rag_query_duration_seconds` | `status` (success/error) |
| **Graph operations** | `kb_graph_write_total`, `kb_graph_write_duration_seconds`, `kb_graph_query_total`, `kb_graph_query_duration_seconds` | `operation`, `status` |
| **Extraction queue** | `kb_extraction_queue_depth`, `kb_extraction_workers_active`, `kb_extraction_task_total`, `kb_extraction_task_duration_seconds` | `status` |
| **HTTP requests** | `kb_http_requests_total`, `kb_http_request_duration_seconds` | `method`, `endpoint`, `status_code` |

### Scraping

K8s `ServiceMonitor` (`k8s/kb-servicemonitor.yaml`) scrapes both read and write services every 30s.

### Key Files (Metrics)

| File | Purpose |
|------|---------|
| `app/metrics.py` | Prometheus metric definitions |
| `app/main.py` | `/metrics` endpoint + HTTP middleware |
| `app/services/knowledge_service.py` | RAG/Graph operation instrumentation |
| `app/services/llm_extraction_worker.py` | Extraction queue instrumentation |
| `k8s/kb-servicemonitor.yaml` | Prometheus ServiceMonitor |

---

## Brain Jira (Cross-Project Aggregation)

The KB is complemented by an internal Jira+Confluence instance ("Brain") configured via `SystemConfigDocument`. This serves as a **cross-project aggregation layer** where actionable findings from all clients/projects are centralized.

### How It Connects to KB

1. **KB callback writes to both:** When KB returns `hasActionableContent=true`, the handler writes:
   - To **KB** (RAG + graph) — for semantic search and knowledge retrieval
   - To **Brain Jira** — for task tracking, planning, and orchestrator review

2. **Deduplication:** Brain issues are deduplicated by `corr:{correlationId}` label to prevent duplicates when the same content is re-qualified.

3. **Orchestrator reads both:** During task execution, the orchestrator has access to:
   - `kb_search` tool — semantic search across KB
   - `brain_search_issues` / `brain_search_pages` tools — structured search in Brain Jira/Confluence

4. **Idle Review:** When no tasks are pending, the orchestrator creates `IDLE_REVIEW` tasks that query both KB and Brain Jira to find stale items, missed deadlines, or conflicting information.

### Key Files (Brain)

| File | Purpose |
|------|---------|
| `backend/server/.../task/TaskQualificationService.kt` | KB dispatch and cross-project write |
| `backend/service-orchestrator/app/tools/brain_client.py` | Python HTTP client for brain endpoints |

---

## Thought Map (Navigation Layer)

> Full spec: [thought-map-spec.md](thought-map-spec.md)

Thought Map adds a **navigation layer** over the KB graph — spreading activation replaces flat search for proactive context retrieval.

### Collections (Global, Multi-Tenant)

| Collection | Type | Purpose |
|---|---|---|
| `ThoughtNodes` | document | High-level insights, decisions, problems (with 384-dim embeddings) |
| `ThoughtEdges` | edge | Relationships between thoughts (causes, blocks, same_domain) |
| `ThoughtAnchors` | edge | Connections from ThoughtNodes → KnowledgeNodes |

Named graph: `thought_graph`. Client isolation via `clientId` field filtering in AQL.

### Ingest Integration

Extended extraction prompt in `graph_service.py` adds `thoughts[]` to LLM JSON output. Match-first strategy (cosine ≥ 0.85) prevents duplicate thoughts — existing nodes are reinforced instead.

### API Endpoints

| Endpoint | Method | Router | Purpose |
|---|---|---|---|
| `/thoughts/traverse` | POST | read | Spreading activation from entry points |
| `/thoughts/reinforce` | POST | write | Hebbian reinforcement of activated nodes/edges |
| `/thoughts/create` | POST | write | Store new thoughts (match-first) |
| `/thoughts/bootstrap` | POST | write | Cold start — seed from top-100 KB nodes |
| `/thoughts/maintain` | POST | write | Trigger maintenance (light/heavy) |
| `/thoughts/stats` | GET | read | Graph statistics per client |

### Maintenance

- **Light** (GPU idle + 1h cooldown): decay (×0.995) + merge (cosine > 0.92)
- **Heavy** (01:00–06:00 nightly): + archive (score < 0.05, 30d) + Louvain hierarchy

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-knowledgebase/app/services/thought_service.py` | ThoughtService — schema, upsert, traverse, reinforce, bootstrap |
| `backend/service-knowledgebase/app/services/thought_maintenance.py` | Maintenance scheduler — decay, merge, archive, Louvain |
| `backend/service-orchestrator/app/kb/thought_prefetch.py` | Proactive thought traversal before LLM call |
| `backend/service-orchestrator/app/kb/thought_update.py` | Post-response reinforcement + thought extraction |


---

# UI Design Architecture

> (Dříve docs/ui-design.md — SSOT pro adaptive layout, komponenty, patterns)

# Jervis – UI Design System (Compose Multiplatform) – SSOT

**Last updated:** 2026-02-18
**Status:** Production Documentation

This document is the **single source of truth** for UI guidelines, design patterns, and shared components.
All new UI work MUST follow these patterns to keep the app visually and ergonomically unified.

---

## 0) Data Model and Relationships (Connection / Client / Project)

**Hierarchy:**
1. **Connection** – Technical connection to external system (GitHub, GitLab, Jira, Confluence, Bitbucket...)
   - Contains: credentials, URL, auth type
   - Has `capabilities`: Set<ConnectionCapability> (BUGTRACKER, WIKI, REPOSITORY, EMAIL_READ, EMAIL_SEND)
   - Can be global or assigned to a client

2. **Client** – Organization/team
   - **Has assigned Connections** (`connectionIds`) - e.g., GitHub org, Jira workspace
   - **Contains Project Groups** (`ProjectGroupDocument`) - logical grouping of projects
   - **Contains Environments** (`EnvironmentDocument`) - K8s namespace definitions
   - **Has connectionCapabilities** - default capability configuration for all projects:
     ```kotlin
     data class ClientConnectionCapabilityDto(
         val connectionId: String,
         val capability: ConnectionCapability,
         val enabled: Boolean = true,
         val resourceIdentifier: String? = null,
         val indexAllResources: Boolean = true,      // true = index all, false = only selected
         val selectedResources: List<String> = emptyList()
     )
     ```
   - **Has default Git commit configuration** for all its projects

3. **Project Group** – Logical grouping of projects within a client
   - **Has shared resources** (`ProjectResource`, `ResourceLink`)
   - **KB cross-visibility**: All projects in a group share KB data
   - **Environment inheritance**: Group-level environments apply to all projects in group

4. **Project** – Specific project within a client
   - **Belongs to optional group** (`groupId: ProjectGroupId?`)
   - **Has connectionCapabilities** - overrides client's defaults when set:
     ```kotlin
     data class ProjectConnectionCapabilityDto(
         val connectionId: String,
         val capability: ConnectionCapability,
         val enabled: Boolean = true,
         val resourceIdentifier: String? = null,     // e.g., "PROJ-KEY", "SPACE-KEY", "owner/repo"
         val selectedResources: List<String> = emptyList()
     )
     ```
   - **Inheritance**: If project doesn't have a capability configured, it inherits from client
   - **Can override client's Git commit configuration** (when `null`, inherits from client)
   - **Can override client's Cloud Model Policy** (`cloudModelPolicy: CloudModelPolicy?`, `null` = inherit)

5. **CloudModelPolicy** – Per-provider auto-escalation toggles
   - `autoUseAnthropic: Boolean` – auto-escalate to Anthropic Claude on local failure
   - `autoUseOpenai: Boolean` – auto-escalate to OpenAI GPT-4o on local failure
   - `autoUseGemini: Boolean` – auto-escalate to Gemini for large context (>49k tokens)
   - Client level: defaults for all projects. Project level: nullable override.

**UI Workflow:**
1. In **Settings -> Pripojeni** create technical connections (e.g., GitHub, Atlassian)
2. In **Settings -> Klienti** -> click client -> "Konfigurace schopnosti":
   - Assign connections to client
   - For each connection capability: enable/disable, choose "Index all" vs "Only selected resources"
3. In **Settings -> Projekty** -> click project -> "Konfigurace schopnosti projektu":
   - Override client's capability configuration if needed
   - Select specific resource (repo, Jira project, Confluence space) for each capability
4. Project can override client's Git configuration (checkbox "Prepsat konfiguraci klienta")
5. Cloud model policy: In client form "Cloud modely" section — 3 checkboxes for auto-escalation. Project can override with "Přepsat konfiguraci klienta" checkbox.

---

## 1) Adaptive Layout Architecture

### 1.1) Breakpoints

```kotlin
// JervisBreakpoints.kt
const val WATCH_DP = 200
const val COMPACT_DP = 600
const val MEDIUM_DP = 840
const val EXPANDED_DP = 1200

enum class WindowSizeClass { WATCH, COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowSizeClass(): WindowSizeClass
```

| Width         | Class        | Devices                        |
|---------------|--------------|--------------------------------|
| < 200 dp      | **Watch**    | watchOS (SwiftUI), Wear OS (Compose) |
| < 600 dp      | **Compact**  | iPhone, Android phone          |
| < 840 dp      | **Medium**   | Small tablet                   |
| >= 600 dp     | **Expanded** | iPad, Android tablet, Desktop  |

Detection uses `BoxWithConstraints` inside the layout composables. **Never add platform
expect/actual for layout decisions** -- width-based detection works everywhere.

### 1.2) App-Level Navigation Architecture

**Stack-based navigation** with `AppNavigator`:
- `navigateTo(screen)` pushes current screen to back-stack, shows new screen
- `goBack()` pops back-stack (returns to previous screen, not always Main)
- `navigateAndClearHistory(screen)` resets stack (e.g., navigating to Main from project link)
- `canGoBack: StateFlow<Boolean>` — true when back-stack is not empty

**PersistentTopBar** (always visible above all screens):
```
┌──────────────────────────────────────────────────────────────┐
│ [←] [🎙][📅][⚙]  Client ▾ / Project ▾   ● REC  🤖agent K8s●│
└──────────────────────────────────────────────────────────────┘
```
- **Back arrow** — shown only when `canGoBack` is true
- **Navigation icons** — Meetings, Calendar, Settings (3 icons, no hamburger menu)
- **Client/Project selector** — compact text "ClientName / ProjectName" with dropdown, `weight(1f)`, truncates on small screens
- **Recording indicator** — red blinking dot + duration, clickable → Meetings
- **Agent status** — spinner when running / dot when idle, clickable → AgentWorkload
- **K8s badge** — clickable → toggle environment panel (right sidebar)
- **Connection dot** — green (connected), spinner (connecting), refresh icon (disconnected)

**Screens (4 total):**
| Screen | Purpose |
|--------|---------|
| Main | Chat + task sidebar (left) + environment panel (right) |
| Meetings | Meeting list, recording, transcription |
| Calendar | Weekly grid — tasks, calendar events, deadlines |
| Settings | Client/project/connection configuration |

**Removed screens:** UserTasks (→ chat sidebar), Finance, Capacity, PendingTasks, IndexingQueue, ErrorLogs, RagSearch, EnvironmentManager/Viewer (→ right sidebar panel)

**Per-screen JTopBar** shows only the **title** (no back arrow — handled by PersistentTopBar).
Internal navigation (detail → list within a screen) still uses JTopBar's onBack.

### 1.2.1) Navigation Patterns by Mode

| Mode       | Category nav                          | Entity list -> detail            |
|------------|---------------------------------------|---------------------------------|
| Compact    | Full-screen list; tap -> full-screen section | List replaces with full-screen detail form |
| Expanded   | 240 dp sidebar + content side-by-side   | Same (list replaces with detail form)       |

### 1.3) Decision Tree -- Which Layout Composable to Use

```
Need category-based navigation (settings, admin panels)?
  -> JAdaptiveSidebarLayout

Need sidebar navigation alongside primary content (main screen)?
  -> Custom BoxWithConstraints with sidebar + content (see S5.1.1)

Need entity list with create/edit/detail (clients, projects)?
  -> JListDetailLayout + JDetailScreen for the edit form

Need a simple scrollable form (general settings)?
  -> Column with verticalScroll inside a JSection

Need a flat list with per-row actions (connections, logs)?
  -> LazyColumn with JCard items + JActionBar at top
```

---

## 2) Design Principles

### 2.1) Core Rules

| Rule                          | Details                                                                       |
|-------------------------------|-------------------------------------------------------------------------------|
| **Consistency**               | Use shared components from `com.jervis.ui.design`, don't invent new wrappers |
| **Fail-fast in UI**           | Show errors via `JErrorState` with retry, never silently hide                 |
| **Unified screen states**     | Every data-loading screen uses `JCenteredLoading` / `JErrorState` / `JEmptyState` |
| **Touch targets >= 44 dp**    | `JervisSpacing.touchTarget` -- all clickable rows, icon buttons, checkboxes    |
| **No fixed widths**           | Use `fillMaxWidth()`, `weight()`, scrolling. The only fixed width is the sidebar (240 dp on expanded) |
| **Czech UI labels**           | All user-facing text in Czech, code/comments/logs in English                 |
| **No secrets masking**        | Passwords, tokens, keys always visible (private app)                         |
| **No over-engineering**       | Solve the current screen, don't generalize prematurely                       |

### 2.2) Card Style

All list items, resource rows, log entries, connection cards use `JCard`:

```kotlin
JCard(
    modifier = Modifier.fillMaxWidth(),
) {
    // card content
}
```

`JCard` wraps `Card` with `CardDefaults.outlinedCardBorder()` and no elevation. Optional `onClick` and `selected` parameters handle click and selection state (uses `secondaryContainer` for selected).

**Never** use raw `Card(elevation = ..., surfaceVariant)` or manual `outlinedCardBorder()` calls -- always use `JCard`.
Cards in sections (like `JSection`) may omit the border because the section already provides visual grouping.

### 2.3) Touch Targets

Every interactive element must have a minimum height of 44 dp:

```kotlin
// Row with click action
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { ... }
        .heightIn(min = JervisSpacing.touchTarget),
    verticalAlignment = Alignment.CenterVertically,
)

// Icon button -- use JIconButton (enforces 44dp)
JIconButton(icon = Icons.Default.Edit, onClick = { ... })

// Checkbox/RadioButton rows
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.heightIn(min = JervisSpacing.touchTarget),
)
```

### 2.4) Action Buttons Placement

```
Top of a list screen    -> JActionBar with JRefreshButton + JAddButton (or JPrimaryButton)
Detail form bottom      -> JDetailScreen provides save/cancel automatically
Inline per-card actions -> Row with Arrangement.spacedBy(8.dp, Alignment.End)
                           using JEditButton, JDeleteButton, JRefreshButton
Delete with confirm     -> JConfirmDialog triggered by JDeleteButton
```

### 2.5) List Pagination & Server-Side Filtering (Universal Pattern)

**RULE:** Never load unbounded data into UI. All list screens MUST paginate server-side.

| Aspect            | Value                                                        |
|-------------------|--------------------------------------------------------------|
| **Page size**     | 10–20 items (configurable per screen, default 20)            |
| **Loading**       | First page on screen open. More via "Načíst další" button or scroll trigger |
| **Filter/search** | Server-side query (regex/like on DB), debounced 300ms        |
| **Chat history**  | Last 10 messages, older loaded on scroll up (`beforeSequence` cursor) |
| **Sort**          | Server-side (typically `createdAt DESC`)                     |

**Backend pattern:**
```kotlin
// RPC interface — offset-based pagination with server-side filter
suspend fun listAll(query: String? = null, offset: Int = 0, limit: Int = 20): PageDto

// PageDto — generic shape for paginated responses
data class PageDto(
    val items: List<ItemDto>,
    val totalCount: Int,
    val hasMore: Boolean,
)

// Service — MongoDB query with regex filter + skip/limit
val criteria = Criteria.where("type").`is`(TYPE)
if (!query.isNullOrBlank()) {
    criteria.orOperator(
        Criteria.where("field1").regex(".*${Regex.escape(query)}.*", "i"),
        Criteria.where("field2").regex(".*${Regex.escape(query)}.*", "i"),
    )
}
val dataQuery = Query(criteria)
    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
    .skip(offset.toLong()).limit(limit)
```

**UI pattern:**
```kotlin
// State
var items by remember { mutableStateOf<List<T>>(emptyList()) }
var hasMore by remember { mutableStateOf(false) }
var totalCount by remember { mutableStateOf(0) }

// Load function (append=true for "load more")
fun load(query: String?, append: Boolean = false) {
    val offset = if (append) items.size else 0
    val page = repository.service.listAll(query, offset, PAGE_SIZE)
    items = if (append) items + page.items else page.items
    hasMore = page.hasMore
    totalCount = page.totalCount
}

// Debounced filter (server-side)
LaunchedEffect(filterText) {
    delay(300)
    load(filterText)
}

// JListDetailLayout with listFooter for "load more"
JListDetailLayout(
    items = items,
    listFooter = if (hasMore) { { LoadMoreButton(...) } } else null,
    ...
)
```

**Forbidden:**
- Loading all records and filtering client-side (`findAll().toList().filter{}`)
- Displaying unbounded lists without pagination
- Client-side sorting of server data (sort on DB)

---

## 3) Shared Components Reference

All components live in the `com.jervis.ui.design` package, split by category (see Section 10).
Each `Design*.kt` file groups related components (buttons, forms, cards, etc.).

### 3.1) Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTopBar` | Navigation bar with IconButton + ArrowBack icon; uses `surfaceContainerHigh` background for subtle elevation in dark theme | `title`, `onBack?`, `actions` |
| `JSection` | Visual grouping with title and padding | `title`, `content` |
| `JActionBar` | Right-aligned action buttons bar | `modifier`, `content: RowScope` |
| `JVerticalSplitLayout` | Two-pane vertical split with draggable handle | `splitFraction`, `onSplitChange`, `topContent`, `bottomContent` |

### 3.2) State Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JCenteredLoading` | Centered circular progress | -- |
| `JErrorState` | Error message (selectable via `SelectionContainer`) + retry button | `message`, `onRetry?` |
| `JEmptyState` | Empty data state with icon (2 overloads) | `message`, `icon` |

### 3.3) Adaptive Layout Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JAdaptiveSidebarLayout<T>` | Sidebar (expanded) / category list (compact) | `categories`, `selectedIndex`, `onSelect`, `onBack`, `title`, `categoryIcon: @Composable (T) -> Unit`, `categoryTitle`, `categoryDescription`, `content` |
| `JListDetailLayout<T>` | List with detail navigation | `items`, `selectedItem`, `isLoading`, `onItemSelected`, `emptyMessage`, `emptyIcon`, `listHeader`, `listFooter?`, `listItem`, `detailContent` |
| `JDetailScreen` | Full-screen edit form with back + save/cancel | `title`, `onBack`, `onSave?`, `saveEnabled`, `actions`, `content: ColumnScope` |
| `JNavigationRow` | Touch-friendly nav row (compact mode) | `icon: @Composable () -> Unit`, `title`, `subtitle?`, `onClick`, `trailing` |
| `JVerticalSplitLayout` | Draggable vertical split (top/bottom) | `splitFraction`, `onSplitChange`, `topContent`, `bottomContent` |
| `JHorizontalSplitLayout` | Draggable horizontal split (left/right) | `splitFraction`, `onSplitChange`, `minFraction`, `maxFraction`, `leftContent`, `rightContent` |

Note: `categoryIcon` in `JAdaptiveSidebarLayout` takes a `@Composable (T) -> Unit` lambda (not a `String`). `JNavigationRow.icon` is also `@Composable () -> Unit` (not `String`).

### 3.4) Data Display Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JKeyValueRow` | Label/value pair with primary-colored label | `label`, `value` |
| `JStatusBadge` | Green/yellow/red status dot with label | `status` |
| `JCodeBlock` | Monospace text display | `content` |
| `JTableHeaderRow` | Table header row | `content` |
| `JTableHeaderCell` | Single header cell | `text`, `weight` |
| `JTableRowCard` | Selectable row card (outlinedCardBorder + secondaryContainer) | `selected`, `onClick`, `content` |

### 3.5) Form Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JTextField` | Wraps `OutlinedTextField` with error display | `value`, `onValueChange`, `label`, `placeholder?`, `isError`, `errorMessage?`, `enabled`, `singleLine`, `readOnly`, `trailingIcon?`, `visualTransformation`, `keyboardOptions`, `minLines`, `maxLines` |
| `JDropdown<T>` | Dropdown via `ExposedDropdownMenuBox` | `items`, `selectedItem`, `onItemSelected`, `label`, `itemLabel`, `enabled`, `placeholder` |
| `JSwitch` | Switch with label and optional description | `label`, `checked`, `onCheckedChange`, `description?`, `enabled` |
| `JSlider` | Slider with label and value display | `label`, `value`, `onValueChange`, `valueRange`, `steps`, `valueLabel`, `description?` |
| `JCheckboxRow` | Checkbox with label | `label`, `checked`, `onCheckedChange`, `enabled` |

### 3.6) Button Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JPrimaryButton` | Primary-color Material3 button (2 overloads: one with `enabled`, one with `icon`+`text`) | `onClick`, `enabled`, `content` / `icon`, `text` |
| `JSecondaryButton` | `OutlinedButton` wrapper | `onClick`, `enabled`, `content` |
| `JTextButton` | `TextButton` wrapper | `onClick`, `enabled`, `content` |
| `JDestructiveButton` | Error-colored button | `onClick`, `enabled`, `content` |
| `JRunTextButton` | `TextButton` with running state indicator | `onClick`, `isRunning`, `content` |
| `JIconButton` | Enforces 44dp touch target | `icon: ImageVector`, `onClick`, `contentDescription?` |
| `JRefreshButton` | Delegates to `JIconButton(Icons.Default.Refresh)` | `onClick` |
| `JDeleteButton` | Delegates to `JIconButton(Icons.Default.Delete)`, tinted error | `onClick` |
| `JEditButton` | Delegates to `JIconButton(Icons.Default.Edit)` | `onClick` |
| `JAddButton` | Delegates to `JIconButton(Icons.Default.Add)` | `onClick` |
| `JRemoveIconButton` | Close icon + built-in ConfirmDialog; fires `onConfirmed` only after confirmation | `onConfirmed`, `title`, `message`, `confirmText` |

### 3.7) Card Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JCard` | Outlined card, NO elevation, secondaryContainer for selection | `onClick?`, `selected?`, `modifier`, `content` |
| `JListItemCard` | Standard list item card | `title`, `subtitle?`, `onClick`, `leading?`, `trailing?`, `badges?`, `actions?` |
| `JTableHeaderRow` | Table header row | `content` |
| `JTableRowCard` | Selectable row card (NO elevation, outlinedCardBorder + secondaryContainer) | `selected`, `onClick`, `content` |

### 3.8) Dialog Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JConfirmDialog` | Confirmation with Czech defaults | `visible`, `title`, `message`, `confirmText="Potvrdit"`, `dismissText="Zrusit"`, `isDestructive`, `onConfirm`, `onDismiss` |
| `JFormDialog` | Form dialog with scrollable content | `visible`, `title`, `onConfirm`, `onDismiss`, `confirmEnabled`, `confirmText`, `content` |

### 3.9) Feedback Components

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JSnackbarHost` | Consistent snackbar placement | `hostState` |

### 3.10) Watch Components (prepared for future)

| Component | Purpose | Key params |
|-----------|---------|------------|
| `JWatchActionButton` | Full-width 56dp button for watch | `text`, `onClick`, `isDestructive?` |
| `JWatchApprovalCard` | Minimal approve/deny card | `title`, `description`, `onApprove`, `onDeny` |

### 3.11) Utility Components (`com.jervis.ui.util`)

| Component | Purpose |
|-----------|---------|
| `RefreshIconButton` | Delegates to `JRefreshButton` (Material `Icons.Default.Refresh`, 44dp) |
| `DeleteIconButton` | Delegates to `JDeleteButton` (Material `Icons.Default.Delete`, 44dp, error tint) |
| `EditIconButton` | Delegates to `JEditButton` (Material `Icons.Default.Edit`, 44dp) |
| `ConfirmDialog` | Confirmation dialog with Czech defaults ("Smazat"/"Zrusit"), keyboard support, `isDestructive` flag |
| `ApprovalNotificationDialog` | Orchestrator approval dialog (approve/deny with reason) |
| `CopyableTextCard` | `SelectionContainer` wrapping, outlinedCardBorder (no explicit copy buttons) |

### 3.12) Shared Form Helpers (`com.jervis.ui.screens.settings.sections.ClientsSharedHelpers.kt`)

These are `internal` functions shared by ClientsSettings and ProjectsSettings:

| Function | Purpose |
|----------|---------|
| `getCapabilityLabel(capability)` | Human-readable label for ConnectionCapability |
| `getIndexAllLabel(capability)` | Label for "Index all..." option per capability |
| `GitCommitConfigFields(...)` | Reusable form fields for git commit configuration |

---

## 4) Spacing Constants

```kotlin
object JervisSpacing {
    val outerPadding = 10.dp       // Outer margin around screens
    val sectionPadding = 12.dp     // Inner padding of JSection
    val itemGap = 8.dp             // Gap between list items
    val touchTarget = 44.dp        // Minimum touch target size
    val sectionGap = 16.dp         // Gap between sections in a form
    val fieldGap = 8.dp            // Gap between form fields within a section
    val watchTouchTarget = 56.dp   // Minimum touch target for watch UI
}
```

Breakpoints are defined in `JervisBreakpoints.kt` (see Section 1.1).

### Usage Guidelines

| Context | Spacing |
|---------|---------|
| Between sections in a form | `Arrangement.spacedBy(JervisSpacing.sectionGap)` |
| Between items in a LazyColumn | `Arrangement.spacedBy(JervisSpacing.itemGap)` |
| JSection internal spacing | `JervisSpacing.sectionPadding` (automatic) |
| Screen outer padding | `JervisSpacing.outerPadding` (automatic in JDetailScreen/JAdaptiveSidebarLayout) |
| Between form fields in a section | `Spacer(Modifier.height(JervisSpacing.fieldGap))` |
| Between label and field group | `Spacer(Modifier.height(12.dp))` |

---

## 5) Screen Anatomy Patterns

### 5.1) Category-Based Settings Screen

```
+---------------------------------------------+
| JAdaptiveSidebarLayout                      |
|                                             |
| EXPANDED (>=600dp):                         |
| +----------+----------------------------+   |
| | Sidebar  | Content                    |   |
| | 240dp    | (remaining width)          |   |
| |          |                            |   |
| | [<- Zpet]| Category Title (h2)       |   |
| |          | Category description       |   |
| |          |                            |   |
| | [*] Cat 1| +-- JSection -----------+  |   |
| |   Cat 2  | | Section content...    |  |   |
| |   Cat 3  | +------------------------+  |   |
| |   Cat 4  |                            |   |
| |   Cat 5  |                            |   |
| +----------+----------------------------+   |
|                                             |
| COMPACT (<600dp):                           |
| +---------------------------------------+   |
| | JTopBar: "Nastaveni" [<- back]        |   |
| |                                       |   |
| | +-- JNavigationRow -----------------+ |   |
| | | [Settings] Obecne             [>] | |   |
| | |    Zakladni nastaveni aplikace    | |   |
| | +-----------------------------------+ |   |
| | +-- JNavigationRow -----------------+ |   |
| | | [Business] Klienti a projekty [>] | |   |
| | |    Sprava klientu, projektu ...   | |   |
| | +-----------------------------------+ |   |
| | ...                                   |   |
| +---------------------------------------+   |
+---------------------------------------------+
```

**Implementation:**

```kotlin
enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
    val description: String,
) {
    GENERAL("Obecne", Icons.Default.Settings, "Zakladni nastaveni aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", Icons.Default.Business, "Sprava klientu, projektu a jejich konfigurace."),
    PROJECT_GROUPS("Skupiny projektu", Icons.Default.Folder, "Logicke seskupeni projektu se sdilenou KB."),
    CONNECTIONS("Pripojeni", Icons.Default.Power, "Technicke parametry pripojeni (Atlassian, Git, Email)."),
    GUIDELINES("Pravidla a smernice", Icons.Default.Gavel, "Coding standards, Git pravidla, review checklist, approval pravidla."),
    INDEXING("Indexace", Icons.Default.Schedule, "Intervaly automaticke kontroly novych polozek (Git, Jira, Wiki, Email)."),
    ENVIRONMENTS("Prostredi", Icons.Default.Language, "Definice K8s prostredi pro testovani."),
    CODING_AGENTS("Coding Agenti", Icons.Default.Code, "Nastaveni API klicu a konfigurace coding agentu."),
    SPEAKERS("Řečníci", Icons.Default.RecordVoiceOver, "Správa řečníků a hlasových profilů pro automatickou identifikaci."),
    WHISPER("Whisper", Icons.Default.Mic, "Nastaveni prepisu reci na text a konfigurace modelu."),
    GPG_CERTIFICATES("GPG Certifikaty", Icons.Default.Lock, "Sprava GPG klicu pro podepisovani commitu coding agentu."),
    OPENROUTER("OpenRouter", Icons.Default.Route, "Smerovani LLM pozadavku pres OpenRouter AI – API klic, filtry, prioritni seznam modelu."),
}

@Composable
fun SettingsScreen(repository: JervisRepository, onBack: () -> Unit) {
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Nastaveni",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category -> SettingsContent(category, repository) },
    )
}
```

**GENERAL category content (3 sections):**

| JSection title | Fields | Notes |
|---|---|---|
| "Vzhled" | Téma aplikace (Systémové) | Basic app preferences |
| "Lokalizace" | Jazyk (Čeština) | Language settings |
| "Mozek Jervise" | Bugtracker connection dropdown → Jira project dropdown, Wiki connection dropdown → Confluence space dropdown, Root page ID text field | Central Jira+Confluence for orchestrator brain. Connections filtered by capability (BUGTRACKER/WIKI). **Project/space dropdowns load dynamically** via `listAvailableResources()` after connection selection. Brain-reserved resources are **filtered out server-side** from all other resource lists in the app. Saved via `SystemConfigService`. |

**Internal project filtering:** `ProjectDto.isJervisInternal` flag + `filterVisible()` extension hides the orchestrator's internal project from all UI lists (top bar, settings, scheduler, meetings, RAG search). The flag is managed via `SystemConfig.jervisInternalProjectId` and synced when changed.

### 5.1.1) App Layout with PersistentTopBar

The app uses a global `PersistentTopBar` above all screens. The main screen (chat) has no SelectorsRow or AgentStatusRow — these live in the PersistentTopBar.

```
ALL SCREEN SIZES:
+----------------------------------------------+
| [←] [≡] Client / Project ▾ [🎙] ●REC [⏹] 🤖idle K8s●|  <-- PersistentTopBar (always visible)
|----------------------------------------------|
| [Recording bar — if recording]               |  <-- RecordingBar (global) + KeepScreenOn
| [MeetingHelperView — if helper connected]    |  <-- Helper messages from orchestrator
| [LiveHintsBubble — if liveAssist + hints]    |  <-- KB hints as single bubble
|----------------------------------------------|
| Chat messages...                             |  <-- Per-screen content
|                                              |
|----------------------------------------------|
| [Napiste zpravu...]                [Odeslat] |
+----------------------------------------------+

Menu dropdown (on click ≡):
+----------------------------+
| [List]      Uzivatelske ul.|  ← Daily
| [Mic]       Meetingy       |
|----------------------------|
| [Inbox]     Fronta uloh    |  ← Management
| [Calendar]  Planovac       |
| [Schedule]  Fronta indexace|
| [Dns]       Prostredi K8s  |
|----------------------------|
| [BugReport] Chybove logy  |  ← Debug
| [Search]    RAG Hledani    |
|----------------------------|
| [Settings]  Nastaveni      |  ← Config
+----------------------------+
```

**Menu items (reorganized — daily first, settings last):**

```kotlin
private enum class TopBarMenuItem(val icon: ImageVector, val title: String, val group: Int) {
    USER_TASKS(Icons.AutoMirrored.Filled.List, "Uživatelské úlohy", 0),
    MEETINGS(Icons.Default.Mic, "Meetingy", 0),
    PENDING_TASKS(Icons.Default.MoveToInbox, "Fronta úloh", 1),
    SCHEDULER(Icons.Default.CalendarMonth, "Plánovač", 1),
    INDEXING_QUEUE(Icons.Filled.Schedule, "Fronta indexace", 1),
    ENVIRONMENT_VIEWER(Icons.Default.Dns, "Prostředí K8s", 1),
    ERROR_LOGS(Icons.Default.BugReport, "Chybové logy", 2),
    RAG_SEARCH(Icons.Default.Search, "RAG Hledání", 2),
    SETTINGS(Icons.Default.Settings, "Nastavení", 3),
}
```

**Implementation:** `PersistentTopBar` in `PersistentTopBar.kt` is rendered in `App.kt` above `RecordingBar` and the screen `when` block. It contains the compact client/project selector, menu, recording indicator, agent status icon, K8s badge, and connection indicator. `MainScreenView` in `MainScreen.kt` only contains chat content (banners, messages, input).

**Ad-hoc recording:** The mic button (🎙) in PersistentTopBar starts an instant recording with `clientId=null, meetingType=AD_HOC`. During recording, a stop button (⏹) replaces the mic button. Unclassified meetings appear in a "Neklasifikované nahrávky" section in MeetingsScreen with a "Klasifikovat" button that opens `ClassifyMeetingDialog`.

**Offline mode:** When disconnected, the connection indicator shows an "Offline" chip (CloudOff icon + "Offline" text on `errorContainer` background, clickable for manual reconnect). No blocking overlay — the app is always usable. Chat input is disabled when offline. Recording is always local-first (chunks saved to disk, uploaded async by `RecordingUploadService`; works seamlessly offline). Cached clients/projects are shown from `OfflineDataCache`.

**Chat message types** (`ChatMessage.MessageType`):
- `USER_MESSAGE` — user bubble (primaryContainer, right-aligned)
- `PROGRESS` — compact row with `CircularProgressIndicator` (16dp) + bodySmall text
- `FINAL` — assistant bubble (secondaryContainer, left-aligned)
- `ERROR` — compact row with `Icons.Default.Warning` (16dp, error tint) + bodySmall text in `MaterialTheme.colorScheme.error`
- `BACKGROUND_RESULT` — background task result (surfaceVariant, hidden by default, shown via "Tasky" filter chip). Supports inline user response via `userResponse` field — after "Reagovat" reply, the response appears inside the card (Reply icon + text) and the "Reagovat" button hides
- `THINKING_GRAPH_UPDATE` — thinking graph update: foreground (from chat planning) or background push (from graph agent). Background push messages have `metadata["sender"] == "thinking_graph"` and are rendered as `ThinkingGraphBubble`
- `URGENT_ALERT` — urgent notification (errorContainer border, always visible)
- `APPROVAL_REQUEST` — handled via `ApprovalBanner`, not as a chat message

**Chat bubble layout** (`ChatMessageDisplay.kt`):

iMessage/WhatsApp-style chat with content-based width:
- **Spacing**: LazyColumn `contentPadding = PaddingValues(24.dp)`, `verticalArrangement = Arrangement.spacedBy(20.dp)`, bubble internal padding `16.dp`
- **Responsive max width**: Uses `BoxWithConstraints` to calculate max width as `maxWidth - 32.dp`
- **Content-based width**: `Card` with `Modifier.widthIn(min = 48.dp, max = maxBubbleWidth)` adapts to content length
- **User messages**: Plain text, `primaryContainer` background, right-aligned, with Edit + Copy icons. Attachment indicator (InsertDriveFile icon + filename list) shown below text when `metadata["attachments"]` present
- **Assistant messages**: Markdown rendering, `secondaryContainer` background, left-aligned, with Copy icon
- **Markdown support**: Uses `multiplatform-markdown-renderer:0.29.0` with Material 3 theme colors
- **Workflow steps**: Collapsible step list with status icons (✓ completed, ✗ failed, ↻ in-progress, ⏰ pending) and tool usage
- **Confidence badge** (E14-S4): Shown on assistant messages when fact-check metadata present. Reads `fact_check_confidence`, `fact_check_claims`, `fact_check_verified` from `ChatMessage.metadata`. Displays `Icons.Default.Verified` icon + "N% (X/Y)" text. Color: green (≥80%), amber (≥50%), red (<50%). Hidden when no claims.
- **Background result messages** (BACKGROUND_RESULT): `surfaceVariant` background, `Icons.Default.CheckCircle` (success) or `Icons.Default.Error` (failure) icon, collapsible content. Shows task title + summary. When `taskId` is present in metadata, shows "Zobrazit graf" button that lazy-loads the task decomposition graph via `ITaskGraphService.getGraph()`. Graph section shows: stats row (status, vertex count, LLM calls, tokens), depth-indented vertex cards (expandable: description, agent, tools, timing, input/result/context), and incoming edge annotations.
- **Task graph visualization** (`TaskGraphComponents.kt`): Embedded in BACKGROUND_RESULT card. `TaskGraphSection` — expandable header with graph summary + animated vertex tree. `VertexCard` — depth-indented, status-colored cards with expand/collapse for debug info (agent name, token count, LLM calls, tools used, timing, errors, result summary). For `task_ref` vertices: raw task ID hidden from display; "Zobrazit myšlenkový graf" button when sub-graph available (uses `localContext` if starts with `tg-`, otherwise falls back to `inputRequest` task ID). Callbacks: `onOpenSubGraph: ((String) -> Unit)?`, `onOpenLiveLog: ((String) -> Unit)?`. `EdgeRow` — shows source vertex title, edge type, and payload summary. `StatChip` — compact label:value chips. `ExpandableTextSection` — collapsible text blocks. All labels in Czech. **Vertex status colors**: `statusColor()` and `statusLabel()` are `internal` (shared with `ThinkingGraphPanel`).
- **Urgent alert messages** (URGENT_ALERT): `errorContainer` border, `Icons.Default.Warning` icon, always expanded. Shows source + summary + optional suggested action. User can reply in chat.
- **Inline thinking graph** (FINAL bubble): When `metadata["graph_id"]` present, shows `TaskGraphSection` inline after WorkflowStepsDisplay. Proactively loaded via `ChatViewModel.loadTaskGraph()` on FINAL. Shows load button if cache miss.
- **Background thinking graph bubble** (THINKING_GRAPH_UPDATE with `sender=thinking_graph`): `OutlinedCard` with `surfaceContainerLow` background and `CardDefaults.outlinedCardBorder()`. Status-aware icon: spinner (started/vertex_completed), `CheckCircle` (completed), `Error` (failed). Title shows "Přemýšlím:", "Zpracovávám:", "Hotovo:", "Selhalo:" prefix. On completion with `hasGraph=true`, shows expandable `TaskGraphSection`. Deduplicated by `taskId` in `ChatViewModel` — updates in-place. Terminal states (started/completed/failed) persisted to DB with `role=BACKGROUND`. Python pushes via POST `/internal/thinking-graph-update` with throttling (max 1 per 5s for intermediate, terminal always pushed).
- **Inline coding agent log** (FINAL bubble): When `metadata["coding_agent_task_id"]` present, shows `CodingAgentLogPanel` inline with expand/collapse. Uses existing `IJobLogsService.subscribeToJobLogs()` for live K8s Job log streaming. Max height 300dp.
- **Timestamps**: Human-readable formatting via `formatMessageTime()` — today: "HH:mm", yesterday: "Včera HH:mm", this year: "d. M. HH:mm", older: "d. M. yyyy HH:mm"

**Edit & Copy actions** (header row of each bubble):
- User messages: `Icons.Default.Edit` (pencil, 18dp icon in 32dp touch target) → sets input text for re-editing + `Icons.Default.ContentCopy` → copies to clipboard
- Assistant messages: `Icons.Default.ContentCopy` only
- Cross-platform clipboard via `ClipboardUtil` (expect/actual: JVM uses `java.awt.Toolkit`, iOS uses `UIPasteboard`, Android stub)

**Background message filtering** (`ChatContent` filter chips):
- Three `FilterChip` components above `ChatArea`, inside a `Row` with `Modifier.height(28.dp)` and `labelSmall` typography
- Visible when any background messages exist or user task count > 0
- **"Chat"** (default ON): toggles visibility of regular chat messages (USER_MESSAGE, PROGRESS, FINAL, ERROR)
- **"Tasky"** (default OFF): toggles visibility of all BACKGROUND_RESULT messages
- **"K reakci (N)"** (default OFF, shown when N > 0): server merges pending USER_TASKs (state=USER_TASK + ERROR) from `tasks` collection into chat history on initial load only (not on pagination). N = `userTaskCount` = pending USER_TASKs (both states) + actionable backgrounds. USER_TASKs are global (no scope filter). All filters OFF → empty result (server returns nothing).
- **Message ordering**: ALL messages ordered **chronologically** by creation time (server `sortedBy { timestamp }`). Priority determines urgency, not display order. Filters provide quick access without breaking timeline.
- **ALL filtering is DB-only** — each toggle triggers `reloadForCurrentFilter()` which calls `getChatHistory` with `showChat`/`showTasks`/`showNeedReaction` flags. Server + MongoDB decide what to return. NO client-side filtering.
- SSE `BACKGROUND_RESULT`/`URGENT_ALERT` events only update counters and trigger DB reload — they do NOT inject messages directly into `_chatMessages`
- `backgroundMessageCount` and `userTaskCount` come from `ChatHistoryDto` (set in `applyHistory()`)

**Time display format** (`formatMessageTime` in `util/TimeFormatter.kt`):
- Czech relative format — no "dnes" prefix for today, just time
- Today → `21:20`
- Yesterday → `včera 11:30`
- 2 days ago → `předevčírem 11:30`
- 3–7 days ago → day name `pondělí 11:30`
- Older than 7 days → full date `8. 3. 2026 11:30`
- Accepts ISO-8601 or epoch millis string
- Implementation: `ChatViewModel` exposes `showChat`, `showTasks`, `showNeedReaction`, `backgroundMessageCount`, `userTaskCount` StateFlows + toggle methods

**History pagination** (`ChatArea` component):
- Initial load: 10 messages via `getChatHistory(limit=10, excludeBackground=true)`
- "Načíst starší zprávy" `TextButton` at top of LazyColumn when `hasMore == true`
- Clicking loads next 10 messages using `beforeMessageId` cursor (ObjectId), prepends to existing
- Shows `CircularProgressIndicator` while loading

**Context compression markers** (`CompressionBoundaryIndicator`):
- Displayed between messages where compression occurred
- `HorizontalDivider` + `Icons.Default.Summarize` icon + "Komprese kontextu (N zpráv shrnuto)" label
- Expandable summary text with `AnimatedVisibility`
- Data from `CompressionBoundaryDto` (afterSequence, summary, compressedMessageCount, topics)

**File attachment support** (`InputArea` component):
- `Icons.Default.AttachFile` button (44dp, left of text field) opens platform file picker
- Selected files shown as `AssistChip` with file type icon + filename + close button
- File type icons: Image, PDF, Description (text), FolderZip (archives), InsertDriveFile (other)
- Size limit: reject >10MB with error message
- Files encoded to base64 via `AttachmentDto.contentBase64` for RPC transport
- Backend decodes and saves to storage directory
- Platform file pickers: JVM full implementation (`JFileChooser`), Android/iOS stubs returning null
- **Note**: Attachment data is currently NOT sent to server — `IChatService.sendMessage()` has no attachments parameter. Only the optimistic UI message shows attachment indicator via `metadata["attachments"]`

**Thinking Graph Panel** (`ThinkingGraphPanel.kt`):

Side panel showing Paměťový graf (Memory Graph) alongside chat. Resizable via drag handle.

- **Navigation stack**: Memory graph (default) → click TASK_REF "Zobrazit myšlenkový graf" → detail thinking graph (with back arrow)
- **Task history dropdown**: `Icons.Default.History` button in header → `DropdownMenu` with recent TASK_REF vertices sorted by `startedAt` desc (max 20). Each item shows status label/color, title, timestamp. Click navigates to sub-graph
- **Parameters**: `activeMap: TaskGraphDto?`, `detailGraph: TaskGraphDto?`, `isCompact: Boolean`, `onOpenSubGraph`, `onCloseSubGraph`, `onClose`
- **Live log overlay** (currently disabled): Split panel with `CodingAgentLogPanel` for SSE streaming. Disabled because thinking graph tasks don't have K8s Jobs. Parameters `liveLogTaskId`, `jobLogsService`, `onOpenLiveLog`, `onCloseLiveLog` exist but are not wired
- **Title logic**: Shows "Paměťový graf" for memory_graph, "Myšlenkový graf" for thinking_graph, "Detail grafu" for other types
- **Compact mode**: Uses `JTopBar` with back navigation; expanded mode has inline header with close button
- **ChatViewModel integration**: `detailThinkingGraph: StateFlow<TaskGraphDto?>`, `openSubGraph(id)` loads graph via `repository.taskGraphs.getGraph()`, `closeSubGraph()` clears detail. `jobLogsService` exposed from repository

```kotlin
// Responsive max width calculation
BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val maxBubbleWidth = maxWidth - 32.dp  // Account for LazyColumn padding
    Row(...) {
        Card(
            modifier = Modifier.widthIn(min = 48.dp, max = maxBubbleWidth)
        ) { ... }
    }
}
```

### 5.2) Entity List -> Detail Screen

```
LIST VIEW:
+-------------------------------+
| JActionBar: [Refresh] [+ Pridat] |
|                               |
| +-- JCard ------------------+ |
| | Entity Name           [>] | |
| | subtitle / metadata       | |
| +----------------------------+ |
| +-- JCard ------------------+ |
| | Entity Name 2         [>] | |
| | subtitle / metadata       | |
| +----------------------------+ |
| ...                           |
+-------------------------------+

DETAIL VIEW (replaces list):
+-------------------------------+
| JTopBar: "Entity Name" [<-]  |
|                               |
| +-- JSection: Zakladni -----+ |
| | [JTextField: Nazev]        | |
| | [JTextField: Popis]        | |
| +----------------------------+ |
| +-- JSection: Pripojeni ----+ |
| | Connection cards...        | |
| +----------------------------+ |
| ...                           |
|                               |
| +-- JActionBar --------------+ |
| |          [Zrusit] [Ulozit] | |
| +----------------------------+ |
+-------------------------------+
```

**Implementation:**

```kotlin
@Composable
fun ClientsSettings(repository: JervisRepository) {
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    // ...

    JListDetailLayout(
        items = clients,
        selectedItem = selectedClient,
        isLoading = isLoading,
        onItemSelected = { selectedClient = it },
        emptyMessage = "Zadni klienti nenalezeni",
        emptyIcon = "...",
        listHeader = {
            JActionBar {
                JRefreshButton(onClick = { loadClients() })
                JPrimaryButton(onClick = { /* new */ }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pridat klienta")
                }
            }
        },
        listItem = { client ->
            JCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { selectedClient = client },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(client.name, style = MaterialTheme.typography.titleMedium)
                        Text("ID: ${client.id}", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, null)
                }
            }
        },
        detailContent = { client ->
            ClientEditForm(client, repository, onSave = { ... }, onCancel = { selectedClient = null })
        },
    )
}
```

### 5.3) Edit Form (Detail Screen)

```kotlin
@Composable
private fun ClientEditForm(
    client: ClientDto,
    repository: JervisRepository,
    onSave: (ClientDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(client.name) }
    // ... more state ...

    JDetailScreen(
        title = client.name,
        onBack = onCancel,
        onSave = { onSave(client.copy(name = name, ...)) },
        saveEnabled = name.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
        ) {
            JSection(title = "Zakladni udaje") {
                JTextField(value = name, onValueChange = { name = it }, label = "Nazev")
            }
            JSection(title = "Pripojeni klienta") { ... }
            JSection(title = "Git Commit Konfigurace") {
                GitCommitConfigFields(...)  // Shared helper
            }
            Spacer(Modifier.height(JervisSpacing.sectionGap))  // Bottom breathing room
        }
    }
}
```

### 5.4) Flat List with Per-Row Actions (Connections, Logs)

```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    JActionBar {
        JPrimaryButton(onClick = { showCreateDialog = true }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pridat pripojeni")
        }
    }

    Spacer(Modifier.height(JervisSpacing.itemGap))

    if (isLoading && items.isEmpty()) {
        JCenteredLoading()
    } else if (items.isEmpty()) {
        JEmptyState(message = "Zadna pripojeni nenalezena", icon = "...")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(connections) { connection ->
                JCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        // ... content ...
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            JPrimaryButton(onClick = { ... }) { Text("Test") }
                            JEditButton(onClick = { ... })
                            JDeleteButton(onClick = { ... })
                        }
                    }
                }
            }
        }
    }
}
```

### 5.5) Agent Workload Screen (`AgentWorkloadScreen.kt`) — Accordion Layout

Full-screen view accessed by clicking the agent status icon in `PersistentTopBar`.
Uses an **accordion (harmonika) layout** with 4 sections — only one expanded at a time.
Clicking a collapsed header expands it and collapses the previously expanded section.

**Sections**: Agent (default expanded), Frontend, Backend, Historie

```
State: Agent expanded (default)
+-- JTopBar ("Aktivita agenta", onBack) ----------------------+
| V Agent                          <- expanded header          |
|--------------------------------------------------------------|
|                                                               |
|  Bezi: bms / Chat                                            |
|  "Co se dnes udalo na rannim..."                             |
|                                                               |
|  Cil 1/3                                     [X stop]        |
|  [spinner] Planovani kroku                                   |
|            Analyzing project structure...                      |
|  Krok 2/5                                    35%             |
|  [===========                           ] progress bar       |
|                                                               |
|--------------------------------------------------------------|
| > Frontend (3)                   <- collapsed, 44dp, badge   |
|--------------------------------------------------------------|
| > Backend (1)                    <- collapsed, 44dp, badge   |
|--------------------------------------------------------------|
| > Historie (18)                  <- collapsed, 44dp, badge   |
+--------------------------------------------------------------+

State: Frontend expanded
+-- JTopBar ("Aktivita agenta", onBack) ----------------------+
| V Frontend (3)                   <- expanded header          |
|--------------------------------------------------------------|
|  Chat . bms                                                  |
|  Co se dnes udalo na rannim stand...                         |
|  ─────────────────────────────────                           |
|  Chat . bms                                                  |
|  Shrn vysledky sprintu                                       |
|  ─────────────────────────────────                           |
|  Chat . bms                                                  |
|  Vyres UFO-24                                                |
|                                                               |
|--------------------------------------------------------------|
| [spinner] Agent                  <- collapsed + spinner      |
|--------------------------------------------------------------|
| > Backend (1)                    <- collapsed                |
|--------------------------------------------------------------|
| > Historie (18)                  <- collapsed                |
+--------------------------------------------------------------+
```

**AccordionSectionHeader** — shared header composable:
- `Surface` with `clickable`, min height 44dp
- Expanded: `surfaceContainerHigh` background, `KeyboardArrowDown` icon
- Collapsed: `surface` background, `KeyboardArrowRight` icon
- Badge chip (count > 0): `tertiaryContainer` background, `labelSmall`
- AGENT section collapsed: `CircularProgressIndicator(16dp)` if running, `●` dot if idle

**AgentSectionContent** — when running:
- Project name, task type, task preview
- **Recent chat messages**: Last 5 meaningful messages (USER_MESSAGE + FINAL) displayed above orchestrator progress, showing user↔agent conversation context
- Orchestrator progress: goal/step counters, node spinner, status message, progress bar
- Stop button (`cancelOrchestration`)
- When idle: `JEmptyState("Agent je necinny", Icons.Default.HourglassEmpty)`
- Note: KB indexing progress is shown in IndexingQueueScreen, NOT here

**QueueSectionContent** (Frontend):
- `LazyColumn` with client-side windowing (20 initial, load more on scroll)
- `CompactQueueItemRow`: type+project (labelSmall), preview (bodySmall, 1 line, ellipsis)
- If more items: "... a dalsich N uloh" text below
- If empty: `JEmptyState`

**BackendQueueSectionContent** (Backend) — DB-level paginated:
- First 20 items from `getPendingTasks()`, more via `getBackgroundTasksPage(limit, offset)`
- Badge shows `backgroundTotalCount` (total from DB)
- Infinite scroll with `snapshotFlow` trigger
- Loading indicator during page fetch

**HistorySectionContent** — grouped by tasks:
- `LazyColumn` with `TaskHistoryItem` composables
- Each item: task preview, project name, time range (start – end)
- Click to expand/collapse node list (animated)
- Nodes: `✓` done, `⟳` running, `○` pending
- Newest task on top

**Data models** (`com.jervis.ui.model.AgentActivityEntry`):
- `AgentActivityEntry`: `id`, `time` (HH:mm:ss), `type` (TASK_STARTED/TASK_COMPLETED/AGENT_IDLE/QUEUE_CHANGED), `description`, `projectName?`, `taskType?`, `clientId?`
- `PendingQueueItem`: `taskId`, `preview`, `projectName`, `processingMode` (FOREGROUND/BACKGROUND/IDLE), `queuePosition`
- `TaskHistoryEntry`: `taskId`, `taskPreview`, `projectName?`, `startTime`, `endTime?`, `status` (running/done/error), `nodes: List<NodeEntry>`
- `NodeEntry`: `node` (key), `label` (Czech), `status` (DONE/RUNNING/PENDING)
- Activity log stored in `AgentActivityLog` ring buffer (max 200), held by `QueueViewModel`
- Task history stored in `QueueViewModel.taskHistory: StateFlow<List<TaskHistoryEntry>>`, populated from `OrchestratorTaskProgress` and `OrchestratorTaskStatusChange` events

**Dual-queue state** in `QueueViewModel`:
- `foregroundQueue: StateFlow<List<PendingQueueItem>>` -- user-initiated tasks (FOREGROUND)
- `backgroundQueue: StateFlow<List<PendingQueueItem>>` -- system/indexing tasks (BACKGROUND)
- `reorderTask(taskId, newPosition)` -- reorder within current queue
- `moveTaskToQueue(taskId, targetMode)` -- move between FOREGROUND and BACKGROUND

**RPC methods** (`IAgentOrchestratorService`):
- `getPendingTasks()` -- returns both queues with taskId and processingMode
- `reorderTask(taskId, newPosition)` -- change position within queue
- `moveTask(taskId, targetProcessingMode)` -- switch between queues

**Queue status emissions**: Backend emits queue status updates that include both FOREGROUND and BACKGROUND items with their taskId, enabling the UI to display and manage both queues independently.

### 5.6) User Tasks Screen (`UserTasksScreen.kt`)

Full-screen view accessed from hamburger menu ("Uzivatelske ulohy"). Shows escalated tasks that require user attention (failed background tasks, approval requests). Uses `JListDetailLayout` + `JDetailScreen` pattern with **two-tier DTO loading**: lightweight `UserTaskListItemDto` for list, full `UserTaskDto` loaded on-demand for detail.

**List view (lightweight DTO):**
```
+-- JTopBar ("Uzivatelske ulohy", onBack, [Refresh]) ----------+
|                                                                |
| [JTextField: Filtr (server-side text index + regex fallback)] |
|                                                                |
| +-- JCard ------------------------------------------------+   |
| | Task title                            [Delete] [>]      |   |
| | * K vyrizeni  ❓  22.02.2025                             |   |
| | Agent question preview (120 chars)...                    |   |
| +----------------------------------------------------------+   |
| +-- JCard ------------------------------------------------+   |
| | Another task                          [Delete] [>]      |   |
| | * Zpracovava se  22.02.2025                              |   |
| +----------------------------------------------------------+   |
|                                                                |
|          [Nacist dalsi (20/45)]                                |
+----------------------------------------------------------------+
```

**Detail view (full DTO loaded via getById):**
```
+-- JDetailScreen ("Task title", onBack) -----------------------+
|                                                                 |
| +-- JSection: Informace -------------------------------------+ |
| | * K vyrizeni  22.02.2025 14:30                              | |
| | Zdroj: email/issue-123                                      | |
| +-------------------------------------------------------------+ |
| +-- JSection: Otazka agenta ---------------------------------+ |
| | Agent's pending question text (primary color, bodyLarge)    | |
| | Context explanation (onSurfaceVariant)                      | |
| +-------------------------------------------------------------+ |
| +-- JSection: Popis -----------------------------------------+ |
| | Task description text...                                    | |
| +-------------------------------------------------------------+ |
| +-- JSection: Historie konverzace ----------------------------+ |
| | [ChatBubble: User / Agent / System messages]                | |
| +-------------------------------------------------------------+ |
| +-- JSection: Odpoved ----------------------------------------+ |
| | [JTextField: placeholder]                                   | |
| +-------------------------------------------------------------+ |
|                                                                 |
| +-- JActionBar -----------------------------------------------+ |
| |              [Prevzit do chatu] [Odpovedět]                  | |
| +-------------------------------------------------------------+ |
+------------------------------------------------------------------+
```

**Routing modes:**
- "Do fronty" (`BACK_TO_PENDING`) -- returns task to BACKGROUND processing queue
- "Prevzit do chatu" (`DIRECT_TO_AGENT`) -- sends task directly to FOREGROUND agent processing

**Key components:**
- `UserTasksScreen` -- `JListDetailLayout<UserTaskListItemDto>` with server-side filter, lightweight list, on-demand detail
- `UserTaskListRow` -- `JCard` with title, state badge, pending question indicator ❓, question preview, `JDeleteButton`, chevron
- `UserTaskDetail` -- `JDetailScreen` with structured info header, prominent pending question, chat history, routing buttons
- `ChatBubble` -- Role-labeled message card (User/Agent/System)

**Data flow:**
- **List**: `repository.userTasks.listAllLightweight(query, offset, limit)` -- server-side paginated with MongoDB $text index (regex fallback), excludes content/attachments/agentCheckpointJson. Filters: `type=USER_TASK AND state IN (USER_TASK, ERROR)` — dismissed tasks (DONE) are excluded
- **Detail**: `repository.userTasks.getById(taskId)` -- loads full `UserTaskDto` on item selection
- **Search**: Server-side via `$text` index on `taskName` + `content`, falls back to regex; debounced 300ms
- Sorted by creation date (newest first)
- Delete via `repository.userTasks.cancel(taskId)` with `ConfirmDialog`

### 5.7) Meetings Screen (`MeetingsScreen.kt`)

Recording management screen accessed from the hamburger menu ("Meetingy").
Lists meeting recordings with state indicators, supports starting new recordings and viewing transcripts.
Uses **timeline grouping** with on-demand loading to avoid downloading long flat lists.

```
Compact (<600dp):
+-- JTopBar ("Meetingy", onBack, [+ Nova]) ------+
|                                                  |
| Neklasifikované nahrávky (if any)               |
|   [Ad-hoc nahrávka]  [Klasifikovat]            |
|                                                  |
| Tento týden                                      |
| +-- JCard (MeetingSummaryListItem) ----------+  |
| | Standup tym Alfa              ok  15:32    |  |
| | 8.2.2026  *  Standup tym                   |  |
| +--------------------------------------------+  |
|                                                  |
| ▶ Týden 27.1. – 2.2.           4 nahrávek      |
| ▶ Týden 20.1. – 26.1.          2 nahrávek      |
| ▶ Leden 2026                    8 nahrávek      |
| ▶ Rok 2025                     42 nahrávek      |
+--------------------------------------------------+
```

**Timeline grouping (lazy loading):**
- **Current week** -- Always expanded, shows `MeetingSummaryListItem` for each meeting. Loaded via `getMeetingTimeline()` on screen open.
- **Last 30 days** -- Grouped by week (`Týden D.M. – D.M.`). Collapsed by default, expand on click to fetch items via `listMeetingsByRange()`.
- **Last year** -- Grouped by month (`Leden 2025`). Same expand-on-click pattern.
- **Older** -- Grouped by year (`Rok 2024`). Same expand-on-click pattern.
- `TimelineGroupHeader` -- `OutlinedCard` with `surfaceVariant` background, ▶/▼ icon, label, count, loading spinner. Uses distinct background to visually separate group headers from meeting cards.
- `MeetingSummaryListItem` -- Lightweight card using `MeetingSummaryDto` (no transcript/correction data). On click, loads full `MeetingDto` via `selectMeetingById()`.

**DTOs:**
- `MeetingSummaryDto` -- Lightweight: id, title, meetingType, state, durationSeconds, startedAt, errorMessage
- `MeetingGroupDto` -- label, periodStart, periodEnd, count
- `MeetingTimelineDto` -- currentWeek: `List<MeetingSummaryDto>`, olderGroups: `List<MeetingGroupDto>`

**RPC methods:**
- `getMeetingTimeline(clientId, projectId?)` -- Returns current week items + older group metadata
- `listMeetingsByRange(clientId, projectId?, fromIso, toIso)` -- Returns `List<MeetingSummaryDto>` for a date range (called when expanding a group)
- `updateMeeting(MeetingClassifyDto)` -- Updates meeting metadata; if client/project changes on INDEXED meeting, purges KB + moves audio + resets to CORRECTED

**Key components:**
- `MeetingsScreen` -- List + detail, manages setup/finalize dialogs, timeline groups
- `MeetingViewModel` -- State: currentWeekMeetings, olderGroups, expandedGroups, loadingGroups, isRecording, recordingDuration, selectedMeeting
- `RecordingSetupDialog` -- Client, project, audio device selection, system audio toggle
- `RecordingFinalizeDialog` -- Meeting type (radio buttons), optional title
- `EditMeetingDialog` -- Edit name, type, client, project of classified meeting (with reassignment warning)
- `SpeakerAssignmentDialog` -- AlertDialog for assigning speaker profiles to diarization labels. "Nový řečník" button fixed at top (AnimatedVisibility inline form). Dropdown per label, voice sample extraction. Opened via People button in top bar (always shows AgentChatPanel underneath).
- `RecordingIndicator` -- Animated red dot + elapsed time + stop button (shown during recording)

**State icons:** RECORDING, UPLOADING, UPLOADED/TRANSCRIBING/CORRECTING, TRANSCRIBED/CORRECTED/INDEXED, FAILED

**Local-first recording:** All recordings use the unified local-first architecture — audio chunks are always saved to disk first via `AudioChunkQueue`, then uploaded asynchronously by `RecordingUploadService`. Recording state is persisted in `RecordingSessionStorage` (replaces legacy `RecordingState` and `OfflineMeeting` models). On stop, the recording is finalized only after all chunks have been uploaded. This works seamlessly both online and offline — when offline, chunks accumulate and upload when connection is restored.

**Speaker management:**
- `SpeakerDocument` (MongoDB collection `speakers`) -- per-client speaker profiles with name, nationality, languages, notes, voice sample reference, multi-embedding support
- **Multi-embedding**: `voiceEmbeddings: List<VoiceEmbeddingEntry>` — each entry has embedding (256-dim), label (e.g. meeting title), meetingId, createdAt. Legacy `voiceEmbedding` backward compat via `allEmbeddings()` migration method.
- `speakerMapping` on `MeetingDocument` -- maps diarization labels ("SPEAKER_00") to speaker profile IDs
- `speakerEmbeddings` on `MeetingDocument` -- pyannote 4.x 256-dim embeddings per diarization label
- `TranscriptSegmentDto.speakerName` -- resolved from mapping, shown in transcript instead of raw labels
- `SpeakerAssignmentDialog` -- AlertDialog (no longer replaces chat panel). Opened via People icon. JDropdown per speaker label, "Nový řečník" fixed at top with AnimatedVisibility inline form, voice sample save. Shows auto-match confidence badge with matched embedding label. Always adds embedding on save (multi-embedding, uses meeting title as label).
- **Speaker Settings** (`sections/SpeakerSettings.kt`) -- standalone section in Settings (SPEAKERS category). JListDetailLayout with client dropdown, speaker list cards, edit form (name, nationality, languages, notes), voiceprint labels display, create/delete.
- **Segment speaker detail** -- SegmentCorrectionDialog shows speaker with confidence badge + matched embedding label, JDropdown for speaker reassignment. TranscriptPanel shows confidence + embedding label in segment rows.
- `ISpeakerService` kRPC -- CRUD + assignSpeakers + setVoiceSample + setVoiceEmbedding (additive, never replaces)
- **Auto-identification flow:** After transcription, system compares new speaker embeddings against ALL known speaker embeddings (multi-embedding, best match across all conditions). Cosine similarity >= 0.70 for auto-mapping, >= 0.50 for showing confidence in UI. `AutoSpeakerMatchDto` includes `matchedEmbeddingLabel` showing which embedding variant matched. User confirms or corrects in `SpeakerAssignmentDialog` or directly in `SegmentCorrectionDialog`.

**MeetingDetailView** uses a split layout with transcript on top and agent chat on bottom (speaker assignment is a separate dialog):

**PipelineProgress** shows pipeline state with optional controls:
- When `state == TRANSCRIBING`: a stop button (`Icons.Default.Stop`, error-tinted) appears on the right side. Calls `viewModel.stopTranscription()` which resets the meeting to UPLOADED and deletes the K8s Whisper job.
- Below the status text: last transcript segment text preview (`bodySmall`, `alpha(0.7f)`, `maxLines = 2`) gives real-time feedback on transcription progress.

```
Expanded (>=600dp):
+--------------------------------------------------+
| JTopBar: "Meeting Title"  [play] [book] [...]    |
+--------------------------------------------------+
| Metadata: date, type, duration                   |
| PipelineProgress + Error/Questions cards         |
+--------------------------------------------------+
|                                                  |
| TRANSCRIPT PANEL (LazyColumn, selectable text)   |
|   Corrected/Raw toggle chips + action buttons    |
|   Each row: [time] [text] [edit] [play/stop]     |
|                                                  |
+======= DRAGGABLE SPLITTER (JVerticalSplitLayout) +
|                                                  |
| AGENT CHAT PANEL                                 |
|   Chat history (LazyColumn, auto-scroll)         |
|   Processing indicator (if correcting)           |
|   [Instruction JTextField] [Odeslat]             |
|                                                  |
+--------------------------------------------------+

Compact (<600dp):
Same layout but chat panel has fixed 180dp height (no interactive splitter).
```

**Split layout details:**
- Expanded: `JVerticalSplitLayout` with default 70/30 split, draggable via `draggable()` modifier (clamped 0.3..0.9)
- Compact: `Column` with `weight(1f)` transcript + fixed `height(180.dp)` chat
- No `JDetailScreen` wrapper (conflicts with nested scrolling); uses custom `Column` + `JTopBar`

**MeetingDetailView states:**

| State | UI Behaviour |
|-------|-------------|
| RECORDING | Text "Probiha nahravani..." |
| UPLOADING / UPLOADED | Text "Ceka na prepis..." |
| TRANSCRIBING | `JCenteredLoading` + text "Probiha prepis..." + stop button ("Zastavit") + last segment preview |
| CORRECTING | `JCenteredLoading` + text "Probiha korekce prepisu..." |
| CORRECTION_REVIEW | `CorrectionQuestionsCard` + best-effort corrected transcript |
| FAILED | Error card (`errorContainer`) with selectable text + "Přepsat znovu" button + "Zamítnout" (dismiss, only if transcript exists) |
| TRANSCRIBED | Raw transcript only (via `TranscriptPanel`) |
| CORRECTED / INDEXED | `FilterChip` toggle (Opraveny / Surovy) + "Prepsat znovu" button + `TranscriptPanel` |

**MeetingDetailView** actions bar includes:
- Edit icon -> opens `EditMeetingDialog` (only for classified meetings with `clientId != null`)
- Play/Stop toggle (full audio playback)
- Book icon -> navigates to `CorrectionsScreen` sub-view (managed as `showCorrections` state)
- JRefreshButton, JDeleteButton
- When `state == CORRECTION_REVIEW`: `CorrectionQuestionsCard` is shown below the pipeline progress

**EditMeetingDialog** — allows changing meeting name, type, client, and project after creation. Pre-fills current values. If client/project changes and meeting was INDEXED, shows a warning that KB data will be purged and re-indexed. Backend `updateMeeting()` handles: KB purge (SourceUrn-based), audio file move, state reset to CORRECTED for re-indexing.

**TranscriptPanel** -- composable with `FilterChip` toggle, action buttons, and `LazyColumn` wrapped in `SelectionContainer` for text copy support.

**TranscriptSegmentRow** -- each row layout: `[time (52dp)] [text (weight 1f)] [edit button] [play/stop button]`
- Text is always selectable/copyable (no correction mode toggle needed)
- Edit button opens `SegmentCorrectionDialog` with `SegmentEditState` (original + corrected text + timing)
- Play/Stop button plays the audio range from this segment's `startSec` to the next segment's `startSec`
- `playingSegmentIndex` state highlights the currently playing segment's play button

**SegmentCorrectionDialog** -- redesigned `JFormDialog` for segment editing:
- Shows read-only **original (raw) text** in a `Card(surfaceVariant)` with `SelectionContainer` for copy
- Play button next to "Original:" label plays segment audio range
- Editable `JTextField` pre-filled with **corrected text** (from `correctedTranscriptSegments` if exists, else raw)
- Confirm button enabled only when text is non-blank and differs from initial
- "Přepsat segment" button: retranscribes this segment via Whisper (calls `retranscribeSegments` RPC)
- On confirm: auto-switches to "Opraveny" (corrected) view via `showCorrected = true`
- State: `SegmentEditState(segmentIndex, originalText, editableText, startSec, endSec)`

**AudioPlayer** -- `expect class` with platform actuals:
- `play(audioData)` -- full audio playback
- `playRange(audioData, startSec, endSec)` -- range playback for per-segment play
- JVM: `javax.sound.sampled.Clip` with frame position + timer thread
- Android: `MediaPlayer` with `seekTo()` + `Handler.postDelayed()`
- iOS: `AVAudioPlayer` with `currentTime` + `NSTimer`

**AgentChatPanel** -- chat-style panel with:
- `LazyColumn` of chat bubbles (user = primaryContainer, agent = secondaryContainer, error = errorContainer)
- Auto-scroll to newest message via `LaunchedEffect`
- Processing indicator during active correction
- Input row: `JTextField` (1-3 lines) + "Odeslat" button
- Chat history persisted in `MeetingDocument.correctionChatHistory`
- Optimistic user message via `pendingChatMessage` ViewModel state

```kotlin
@Composable
private fun AgentChatPanel(
    chatHistory: List<CorrectionChatMessageDto>,
    pendingMessage: CorrectionChatMessageDto?,
    isCorrecting: Boolean,
    onSendInstruction: (String) -> Unit,
    modifier: Modifier,
)
```

**Audio capture:** `expect class AudioRecorder` with platform actuals:
- Android: AudioRecord API (VOICE_RECOGNITION source)
- Desktop: Java Sound API (TargetDataLine)
- iOS: AVAudioEngine

### 5.8) Corrections Screen (`CorrectionsScreen.kt`)

Sub-view of MeetingDetailView for managing KB-stored transcript correction rules.
Accessible via the book icon in MeetingDetailView action bar.

```
+-- JDetailScreen ("Korekce prepisu", onBack, [+ Pridat]) ---+
|                                                              |
| Jmena osob                  <- category header (primary)    |
| +-- JCard -----------------------------------------------+  |
| | "honza novak" -> "Honza Novak"              [Delete]   |  |
| |  Optional context text                                 |  |
| +--------------------------------------------------------+  |
| Nazvy firem                                                  |
| +-- JCard -----------------------------------------------+  |
| | "damek soft" -> "DamekSoft"                 [Delete]   |  |
| +--------------------------------------------------------+  |
| ...                                                          |
+--------------------------------------------------------------+
```

**Key components:**
- `CorrectionsScreen` -- `JDetailScreen` with `LazyColumn` of entries grouped by category string, add/delete
- `CorrectionViewModel` -- States: `corrections: StateFlow<List<TranscriptCorrectionDto>>`, `isLoading`; Methods: `loadCorrections()`, `submitCorrection()`, `deleteCorrection()`
- `CorrectionCard` -- `JCard` with original->corrected mapping, optional context text, `JDeleteButton`
- `CorrectionDialog` -- `JFormDialog` with fields: original, corrected, category (`JDropdown`), context; reusable (`internal`) from MeetingsScreen correction mode

**Correction categories**: person_name, company_name, department, terminology, abbreviation, general

### 5.8.1) Correction Questions Card

Inline card shown in MeetingDetailView when `state == CORRECTION_REVIEW`. Displays questions from the correction agent when it's uncertain about proper nouns or terminology. The card is in a resizable panel (draggable divider) so users can adjust the split between corrections and transcript.

```
+-- Card (tertiaryContainer) ------------------------------------+
| Agent potrebuje vase upesneni                                   |
| Opravte nebo potvdte spravny tvar (0/N potvrzeno):             |
|                                                                 |
| Correct spelling?                              [Play/Stop]     |
| Puvodne: "jan damek"                                           |
| [Jan Damek] [Jan Dameck]    <- FilterChip options              |
| [____Spravny tvar____]  [Nevim]  [Potvrdit]                   |
|                                                                 |
| "jan damek" -> "Jan Damek"                              [✓]   |
|                                    [Odeslat vse (N)]           |
+-----------------------------------------------------------------+
======== draggable divider (resizable) =========
```

**Correction flow:**
- Each question has a **play button** (±10s audio around the segment) so users can listen before deciding.
- **Potvrdit** confirms the correction locally (collapses to summary row).
- **Nevím** marks the segment for re-transcription with Whisper large-v3 (beam_size=10, ±10s audio extraction).
- **Odeslat vše** submits all confirmed answers:
  - Known corrections are applied **in-place** to the transcript segments (no full re-correction).
  - Each correction is saved as a KB rule for future use.
  - "Nevím" segments trigger targeted re-transcription + correction.
- The LLM agent **filters out** questions whose `original` text matches existing KB correction rules, preventing re-asking of already-corrected terms.
- The pipeline progress bar clears the last transcription segment preview once transcription is complete.

### 5.8) Pending Tasks Screen (`PendingTasksScreen.kt`)

Task queue management screen accessed from the hamburger menu ("Fronta uloh").
Shows filterable list of pending tasks with delete capability. Uses **Pattern D** (flat list with per-row actions).

```
+-- JTopBar ("Fronta uloh (42)", onBack, [Refresh]) ----------+
|                                                               |
| +-- JSection ("Filtry") -----------------------------------+ |
| | [Typ ulohy v Vse]    [Stav v Vse]                        | |
| +------------------------------------------------------------+ |
|                                                               |
| +-- JCard ------------------------------------------------+  |
| | Zpracovani emailu                          [Delete]     |  |
| | [K kvalifikaci]  [Projekt: abc12345]                    |  |
| | Klient: def456...                                       |  |
| | Vytvoreno: 2024-01-15 10:30                             |  |
| | Email content preview text here...                      |  |
| | Prilohy: 2                                              |  |
| +----------------------------------------------------------+  |
|                                                               |
| +-- JCard ------------------------------------------------+  |
| | Uzivatelsky vstup                          [Delete]     |  |
| | [Novy]                                                  |  |
| | ...                                                     |  |
| +----------------------------------------------------------+  |
+---------------------------------------------------------------+
```

**Key components:**
- `JDropdown` -- for task type and state filtering
- `PendingTaskCard` -- `JCard` with Czech labels for task types/states via `getTaskTypeLabel()` / `getTaskStateLabel()`
- `SuggestionChip` for state and project badges (consistent with ConnectionsSettings)
- `JSnackbarHost` in Scaffold for delete feedback
- `JConfirmDialog` for delete confirmation

**Data:** `PendingTaskDto` with `id`, `taskType`, `content`, `projectId?`, `clientId`, `createdAt`, `state`, `attachments`

### 5.9) Indexing Queue Screen

Dashboard showing the full indexing pipeline with 4 accordion sections. One section expanded at a time, collapsed sections show as headers with badge counts at the bottom.

```
+---------------------------------------------------------------+
| JTopBar: "Fronta indexace"                        [<- Zpet]   |
+---------------------------------------------------------------+
| [v] Zdroje (35)                       ← expanded section      |
+---------------------------------------------------------------+
| ConnectionGroupCard: GitHub (12)                               |
|   ├─ BUGTRACKER (5)  Za 8m  [Clock] [▶ Spustit]              |
|   │   ├─ Commerzbank (3)                             [v]      |
|   │   │   ├─ [Bug] GH-123 summary  |  GitHub  NEW            |
|   │   │   └─ [Bug] GH-456 login bug  |  GitHub  NEW          |
|   │   └─ ClientX (2)                                 [>]      |
|   └─ REPOSITORY (7)  Za 3m  [Clock] [▶ Spustit]              |
|       ├─ Commerzbank (5)                             [>]      |
|       └─ ClientY (2)                                 [>]      |
| ConnectionGroupCard: IMAP Mail (5)                    [>]      |
+---------------------------------------------------------------+
| [>] KB zpracování (3)                 ← collapsed             |
+---------------------------------------------------------------+
| [>] KB fronta (150)                   ← collapsed             |
+---------------------------------------------------------------+
| [>] Hotovo (2500)                     ← collapsed             |
+---------------------------------------------------------------+
```

**When "KB zpracování" is expanded:**
```
+---------------------------------------------------------------+
| [v] KB zpracování (3)                                          |
+---------------------------------------------------------------+
| [Bug] GH-100 · GitHub · Commerzbank                    1m 23s |
|   ● Rozhodnutí: obsah informační...      <1s  ← routing step  |
|     Rozhodnutí: → info_only · DONE                             |
|   · Analýza: entity detected...          21s   ← step duration |
|     Entity: X, Y · Actionable · Urgence: high                  |
|   · RAG uloženo: 5 chunks                3s                    |
|   · Ukládám do RAG...                    1s                    |
|   · Obsah připraven (3,200 znaků)        <1s                   |
|   · Zpracovávám obsah...                 2s   ← oldest step    |
|   · Odesílám do KB služby...             <1s                   |
|   · Text extrahován (3850 znaků)         1s                    |
|   · Zahajuji kvalifikaci...              <1s                   |
+---------------------------------------------------------------+
```

**When "Hotovo" item is expanded (clickable if has history):**
```
+---------------------------------------------------------------+
| [Mail] Subject · Email · Klient     12s  Hotovo  Před 5 min ▼ |
|   ┌────────────────────────────────────────────────────────────┐|
|   │ Kvalifikace: 12s                                           |
|   │ · Zahajuji kvalifikaci...                                  |
|   │ · Text extrahován (1295 znaků)                     <1s     |
|   │ · Odesílám do KB služby...                          1s     |
|   │ · Zpracovávám obsah...                              2s     |
|   │ · Obsah připraven (1,316 znaků)    1316 znaků      <1s    |
|   │ · RAG uloženo: 2 chunks           2 chunks          3s    |
|   │ · Analýza: ...    Entity: X · Actionable            21s   |
|   │ ● Rozhodnutí: obsah informační...  → info_only             |
|   └────────────────────────────────────────────────────────────┘|
+---------------------------------------------------------------+
```

**Accordion sections (3):**
1. **KB zpracování** (INDEXING, actively processing) — items currently being processed by KB service, with **elapsed time** (from `qualificationStartedAt`, not queue time), **live progress timeline** (current step on top, completed below), **step durations** (how long each step took), structured metadata. Merge: stored DB steps (base) + live steps newer than 1s after last stored (dedup by step name via `distinctBy`). Routing decision always visible (explicit routing step before "done", terminal events delayed 5s before removal from live map).
2. **KB fronta** (INDEXING) — waiting + retrying items, with pagination + reorder controls. Items show type label (Email/Issue/Wiki/Git) instead of "Čeká"
3. **Hotovo** (DONE) — completed tasks with **expandable indexing history**. Click to expand stored `qualificationSteps`. Shows indexing duration and full step log with metadata + per-step durations. Auto-refreshed every 10s (page 0 always updated so new items appear immediately).

**Live Qualification Progress with Audit Trail:**
- `QueueViewModel.qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>>` — per-task progress from events
- `QualificationProgress` events broadcast from `TaskQualificationService` via `NotificationRpcImpl`
- Events carry `metadata: Map<String, String>` with structured data for UI display
- **Persistent history:** Steps also saved to `TaskDocument.qualificationSteps` via `$push` for viewing in Hotovo
- **Granular progress steps from KB service (push callbacks via `/internal/kb-progress`):** start → attachments → content_ready → hash_match/purge → parallel_start → rag_done → summary_done → routing/simple_action → done
- **summary_done step metadata**: entities, actionable, urgency, suggestedActions, assignedTo, suggestedDeadline, summary
- **Routing step metadata**: route, targetState
- **Simple action step metadata**: actionType
- `ProgressStepRow` displays metadata as compact key-value rows (`MetadataRow` composable), **step duration** (how long the step took, not "how long ago")
- Item icon turns tertiary color when actively processing
- **Server timestamps:** Each step's `QualificationProgressStep.timestamp` uses server-side `epochMs` from event metadata (set by `NotificationRpcImpl` from `Instant.now().toEpochMilli()`), falling back to client `Clock.System` only if missing. This ensures consistent step timing even with client-server clock skew.
- **1s ticker for step durations:** `KbProcessingSectionContent` uses a `LaunchedEffect` ticker (`delay(1_000)`) that updates `nowMs` for the active step's running duration
- **Deduplication:** `distinctBy { it.step }` after merging stored + live steps; live steps must be ≥1s newer than last stored to prevent near-simultaneous DB/emit duplicates
- **Terminal event delay:** "done"/"simple_action_handled" events are added to steps first, then removed from live map after 5s delay (via `scope.launch { delay(5_000); remove }`) so routing decision is briefly visible
- **Routing step for all paths:** Non-actionable items now emit explicit `step=routing` before `step=done` so the agent's decision is always visible

**Hierarchy: Connection → Capability → Client** (in Sources section)

Three-level expandable tree inside each connection card:
1. **ConnectionGroupCard** -- connection name, provider icon, total item count
2. **CapabilityGroupSection** -- capability label+icon, item count, next check time (clickable → `PollingIntervalDialog`), "Spustit teď" button (triggers source polling)
3. **ClientGroupSection** -- client name, item count, expandable list of `QueueItemRow`

**Key components:**
- `IndexingSectionHeader` -- accordion header with arrow icon, title, badge count
- `ConnectionGroupCard` -- expandable `JCard` with 3-level hierarchy (connection → capability → client)
- `CapabilityGroupSection` -- capability header with next-check time, PlayArrow "Spustit teď" button
- `ClientGroupSection` -- client name header with expandable item list
- `QueueItemRow` -- row with type icon, title, sourceUrn badge, state
- `KbProcessingSectionContent` -- items with live progress overlay from `QualificationProgressInfo`
- `PipelineItemWithProgress` -- row with optional live progress message (tertiary color)
- `PipelineSectionContent` -- simple list of `PipelineItemCompactRow` (used for Hotovo)
- `PipelineSection` -- section with optional pagination and reorder controls (used for KB fronta)
- `PollingIntervalDialog` -- `JFormDialog` to change polling interval per capability
- `IndexingItemType` enum with `.icon()` / `.label()` helpers

**Reorder controls** (on "KB fronta" items):
- Up/Down arrows (KeyboardArrowUp/Down) for position adjustment
- Prioritize button (VerticalAlignTop) moves item to position 1
- Process Now button (PlayArrow) triggers immediate processing
- Calls `reorderKbQueueItem(taskId, newPosition)` or `prioritizeKbQueueItem(taskId)` or `processKbItemNow(taskId)` RPC

**Pipeline state labels (Czech):**
- WAITING → "Ceka", RETRYING → "Opakuje"
- Step labels (live): ingest → "Indexuje", summary → "Analyzuje", routing → "Rozhoduje", user_task → "Úkol", scheduled → "Naplánováno"

**Data:**
- `IndexingDashboardDto` with `connectionGroups`, `kbWaiting`, `kbProcessing`, `kbIndexed` (each with counts + totals), `kbPage`, `kbPageSize`
- `ConnectionIndexingGroupDto` with `connectionId`, `connectionName`, `provider`, `lastPolledAt?`, `capabilityGroups: List<CapabilityGroupDto>`, `totalItemCount`
- `CapabilityGroupDto` with `capability`, `nextCheckAt?`, `intervalMinutes`, `clients: List<ClientItemGroupDto>`, `totalItemCount`
- `ClientItemGroupDto` with `clientId`, `clientName`, `items: List<IndexingQueueItemDto>`, `totalItemCount`
- `PipelineItemDto` with `id`, `type`, `title`, `connectionName`, `clientName`, `sourceUrn?`, `pipelineState`, `retryCount`, `nextRetryAt?`, `errorMessage?`, `createdAt?`, `taskId?`, `queuePosition?`

**RPC:** `IIndexingQueueService.getIndexingDashboard(search, kbPage, kbPageSize)` -- single call returns hierarchy + all pipeline stages
Additional RPCs: `triggerIndexNow(connectionId, capability)`, `reorderKbQueueItem(taskId, newPosition)`, `prioritizeKbQueueItem(taskId)`, `processKbItemNow(taskId)`

---

## 6) Expandable / Collapsible Sections

For complex nested content (e.g., connection capabilities per connection), use an expandable card pattern:

```kotlin
var expanded by remember { mutableStateOf(false) }

JCard(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
) {
    Column(Modifier.padding(12.dp)) {
        // Header row -- always visible, clickable to toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                              else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        }

        // Expanded content
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // ... nested content ...
        }
    }
}
```

### 5.10) Environment Manager Screen (`EnvironmentManagerScreen.kt`)

Full environment management screen accessed from the hamburger menu ("Správa prostředí").
Uses `JListDetailLayout` for list→detail navigation with `TabRow` for detail tabs.

```
Expanded (>=600dp):
+-- JTopBar ("Správa prostředí") -------+
|                                        |
| +-- List ----+  +-- Detail ----------+|
| | [Nové prostředí]  | JDetailScreen    ||
| |             |  | TabRow:             ||
| | JCard       |  | Přehled|Komponenty| ||
| |  name       |  | K8s|Logy           ||
| |  namespace  |  |                     ||
| |  ● Běží     |  | (tab content)      ||
| |             |  |                     ||
| +-------------+  +--------------------+|
+----------------------------------------+
```

**Tabs (EnvironmentManagerTab enum):**
- Přehled — name, namespace, state badge, assignment, component summary, property mappings summary, actions (Provision/Stop/Delete)
- Komponenty — expandable JCards with inline editing (ComponentsTab + ComponentEditPanel)
- Mapování — property mappings management: auto-suggest from templates, manual add, expandable cards per mapping (PropertyMappingsTab)
- K8s zdroje — pod/deployment/service inspection (migrated from EnvironmentViewerScreen)
- Logy & Události — pod logs + K8s events

**Key components:**
- `EnvironmentManagerScreen` — `JListDetailLayout` with list header ("Nové prostředí" button)
- `EnvironmentListItem` — `JCard` with name, namespace (monospace), component count, `EnvironmentStateBadge`
- `EnvironmentDetail` — `JDetailScreen` + `TabRow` + tab content dispatch
- `OverviewTab` — **editable** fields (name, description, tier, namespace, storage size, agent instructions via JTextField/JDropdown) + read-only summary (assignment, components, property mappings) + "Uložit změny" button (shown only when changes detected) + action buttons. `onSave(EnvironmentDto)` callback wired through EnvironmentManagerScreen to `updateEnvironment()`.
- `ComponentsTab` — expandable JCards per component (collapsed: type + name + summary; expanded: read-only detail or inline editor)
- `ComponentEditPanel` — inline editor for EnvironmentComponentDto: name, type, image (with template version picker + custom image toggle, synchronized with AddComponentDialog pattern), ports list, ENV vars, resource limits, health check, startup config, source/build pipeline (sourceRepo, sourceBranch, dockerfilePath — visible for PROJECT type)
- `PropertyMappingsTab` — manages `PropertyMappingDto` entries: auto-suggest from `ComponentTemplateDto.propertyMappingTemplates`, manual form with concept explanation and detailed placeholder docs, expandable cards with resolved values
- `K8sResourcesTab` — namespace health summary, collapsible pods/deployments/services sections, pod log dialog, deployment detail dialog, restart
- `LogsEventsTab` — pod log viewer (dropdown pod selector, tail lines, monospace text area) + K8s events list (Warning/Normal coloring)

**Navigation:**
- `Screen.EnvironmentManager(initialEnvironmentId: String? = null)` — supports deep-link
- Menu item: "Správa prostředí" (Icons.Default.Dns)
- Reuses `NewEnvironmentDialog` from `EnvironmentDialogs.kt` (fields: name, namespace, tier DEV/STAGING/PROD, client, scope)
- Reuses `EnvironmentStateBadge` from `EnvironmentTreeComponents.kt`
- Reuses `environmentTierLabel()` from `EnvironmentDialogs.kt` for tier display

### 5.11) Environment Panel (Chat Sidebar) (`EnvironmentPanel.kt`)

Right-side panel in the main chat screen showing environment tree with live status.
Toggled via K8s badge in `PersistentTopBar`. On compact layouts opens full-screen.

**Features:**
- Tree: EnvironmentTreeNode → ComponentTreeNode (expandable)
- Context indicator: shows which environment the chat/agent is aware of (green dot + summary)
- Resolved env highlighted (auto-detected from selected project)
- User-selected env tracked via `selectedEnvironmentId` in EnvironmentViewModel
- Settings icon → opens Environment Manager (deep-link)
- Refresh button + auto-polling (30s for RUNNING/CREATING)
- **Deploy/Stop buttons:** PlayArrow (green) / Stop (red) on environment tree nodes, based on environment state
- **Component logs:** "Zobrazit logy" button on components → AlertDialog with monospace log viewer (SelectionContainer, verticalScroll)
- Log viewer uses `IEnvironmentService.getComponentLogs()` → reads pod logs via fabric8 K8s client

**Chat context bridge:**
- `EnvironmentViewModel.activeEnvironmentId` — resolved (from project) or user-selected
- `EnvironmentViewModel.getActiveEnvironmentSummary()` — short string for display
- Backend resolves environment from `projectId` automatically (server-side in AgentOrchestratorService)
- Panel shows "Chat kontext: ..." indicator so user sees what the agent knows

**Key files:**
- `EnvironmentPanel.kt` — panel composable (+ log viewer AlertDialog)
- `EnvironmentTreeComponents.kt` — EnvironmentTreeNode, ComponentTreeNode, EnvironmentStateBadge
- `EnvironmentViewModel.kt` — state management, polling, selection tracking, deploy/stop/logs

### 5.12) Watch Apps

**watchOS App** (`apps/watchApp/`) — SwiftUI (not Compose, native watchOS):
- Two-button home screen: **Ad-hoc Recording** (mic icon) and **Chat Voice Command** (chat icon)
- Recording screen shows waveform + elapsed time + stop button
- Audio chunks sent to iPhone via WatchConnectivity; iPhone-side `WatchSessionManager` feeds `RecordingUploadService`

**Wear OS App** (`apps/wearApp/`) — Compose for Wear OS:
- Recording screen: start/stop controls, elapsed time indicator
- Chat screen: voice command recording with send action
- Uses DataLayer API for phone communication

Both apps use the `WATCH` window size class (< 200 dp). UI is minimal — large touch targets, no complex navigation.

### 5.13) iOS Lock Screen Icon

`PlatformRecordingService` (iOS) sets `MPNowPlayingInfoCenter` artwork using a `JervisIcon` imageset (regular image, not AppIcon appiconset). The icon is stored as a standard imageset in the iOS asset catalog so it can be loaded at runtime via `UIImage(named: "JervisIcon")`.

---

## 7) Dialog Patterns

### 7.1) Selection Dialog (e.g., "Vybrat pripojeni")

```kotlin
AlertDialog(
    onDismissRequest = { showDialog = false },
    title = { Text("Vybrat pripojeni") },
    text = {
        LazyColumn {
            items(availableItems.filter { it.id !in selectedIds }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item); showDialog = false }
                        .padding(12.dp)
                        .heightIn(min = JervisSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) { /* item content */ }
                HorizontalDivider()
            }
        }
    },
    confirmButton = { JTextButton(onClick = { showDialog = false }) { Text("Zavrit") } },
)
```

### 7.2) Multi-Select Dialog (e.g., "Pridat zdroje")

```kotlin
AlertDialog(
    text = {
        Column {
            JTextField(value = filter, onValueChange = { filter = it }, label = "Filtrovat...")
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(filtered) { resource ->
                    Row(modifier = Modifier.clickable { toggle(resource) }.heightIn(min = JervisSpacing.touchTarget)) {
                        Checkbox(checked = resource in selected, ...)
                        Column { /* name, description */ }
                    }
                }
            }
        }
    },
    confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JTextButton(onClick = onDismiss) { Text("Zavrit") }
            if (selected.isNotEmpty()) {
                JPrimaryButton(onClick = { confirm(); onDismiss() }) { Text("Pridat vybrane (${selected.size})") }
            }
        }
    },
)
```

### 7.3) Create Dialog (e.g., "Vytvorit novy projekt")

Use `JFormDialog` for form-based creation:

```kotlin
JFormDialog(
    visible = showCreate,
    title = "Vytvorit novy projekt",
    onConfirm = { create() },
    onDismiss = { showCreate = false },
    confirmEnabled = name.isNotBlank(),
    confirmText = "Vytvorit",
) {
    JTextField(value = name, onValueChange = { name = it }, label = "Nazev")
    Spacer(Modifier.height(JervisSpacing.fieldGap))
    JTextField(value = desc, onValueChange = { desc = it }, label = "Popis (volitelne)", minLines = 2)
}
```

### 7.4) Delete Confirmation

Always use `ConfirmDialog` (from `com.jervis.ui.util.ConfirmDialog`):

```kotlin
JConfirmDialog(
    visible = showDelete,
    title = "Smazat pripojeni",
    message = "Opravdu chcete smazat \"${item.name}\"? Tuto akci nelze vratit.",
    confirmText = "Smazat",
    onConfirm = { showDelete = false; handleDelete() },
    onDismiss = { showDelete = false },
    isDestructive = true,
)
```

### 7.5) Remove-Action Confirmation (Inline Items)

Use `JRemoveIconButton` for inline remove buttons (X icon on list items). It encapsulates
the confirm dialog — no extra state variables needed:

```kotlin
JRemoveIconButton(
    onConfirmed = { removeItem(item) },
    title = "Odebrat zdroj?",
    message = "Zdroj \"${item.displayName}\" bude odebran z projektu.",
)
```

All remove actions across settings screens (resource, link, component, connection removal) use this component.

---

## 8) Typography & Color Conventions

| Context                | Style                              | Color                                     |
|------------------------|------------------------------------|-------------------------------------------|
| Card title             | `titleMedium`                      | default (onSurface)                       |
| Card subtitle / ID     | `bodySmall`                        | `onSurfaceVariant`                        |
| Section title          | `titleMedium` (via JSection)       | `primary`                                 |
| Capability group label | `labelMedium`                      | `primary`                                 |
| Help text / hint       | `bodySmall`                        | `onSurfaceVariant`                        |
| Error text             | `bodySmall`                        | `error`                                   |
| Chip / badge           | `labelSmall`                       | via `SuggestionChip`                      |
| Status indicator       | `labelMedium`                      | green / yellow / red (via `JStatusBadge`) |

### Button Colors

| Button type  | Component |
|-------------|-----------|
| Primary action | `JPrimaryButton` (primary container) |
| Secondary / outlined | `JSecondaryButton` (outlined button) |
| Text-only / cancel | `JTextButton` |
| Destructive | `JDestructiveButton` (error-colored) |
| Icon action | `JIconButton` / `JRefreshButton` / `JDeleteButton` / `JEditButton` / `JAddButton` / `JRemoveIconButton` |

---

## 9) Migration Rules & Checklist

When adding or modifying a settings screen:

1. **Does it need category navigation?** -> Use `JAdaptiveSidebarLayout`
2. **Does it list entities with CRUD?** -> Use `JListDetailLayout` + `JDetailScreen`
3. **Is it a simple flat list?** -> `LazyColumn` + `JActionBar` + state components
4. **Cards** -> Always `JCard` (never raw `Card` with manual `outlinedCardBorder()`)
5. **Touch targets** -> All rows/buttons >= 44 dp (`JervisSpacing.touchTarget`)
6. **Loading/Empty/Error** -> Use `JCenteredLoading` / `JEmptyState` / `JErrorState`
7. **Git config** -> Use shared `GitCommitConfigFields()` from ClientsSharedHelpers
8. **Capability labels** -> Use shared `getCapabilityLabel()` / `getIndexAllLabel()`
9. **Back navigation** -> `JTopBar(onBack = ...)` or `JDetailScreen(onBack = ...)`
10. **Forms** -> `JTextField` with label parameter (never raw `OutlinedTextField`)
11. **Dropdowns** -> `JDropdown` (never raw `ExposedDropdownMenuBox`)
12. **Switches** -> `JSwitch` with label/description
13. **Confirm destructive actions** -> `JConfirmDialog` (or `ConfirmDialog` from util/)
14. **Refresh data** -> `JRefreshButton` in `JActionBar`
15. **Delete actions** -> `JDeleteButton` (error-tinted, 44dp)
16. **Create dialogs** -> `JFormDialog` with `JTextField` fields
17. **Status display** -> `JStatusBadge` (green/yellow/red dot with label)
18. **Key-value display** -> `JKeyValueRow` (primary-colored label)
19. **Theme** -> All screens wrapped in `JervisTheme` (auto light/dark)

### Forbidden Patterns

| Don't | Do instead |
|-------|-----------|
| `Card(elevation = ..., colors = surfaceVariant)` | `JCard()` |
| `Card(border = CardDefaults.outlinedCardBorder())` | `JCard()` |
| `Box { CircularProgressIndicator() }` centered | `JCenteredLoading()` |
| `OutlinedTextField(...)` directly | `JTextField(...)` |
| `ExposedDropdownMenuBox` directly | `JDropdown(...)` |
| `Button(colors = errorColors)` for delete | `JDestructiveButton(...)` or `JDeleteButton(...)` |
| `IconButton` without explicit size | `JIconButton(icon = ..., onClick = ...)` |
| Inline save/cancel below form | `JDetailScreen(onSave = ..., onBack = ...)` |
| Fixed sidebar width without adaptive | `JAdaptiveSidebarLayout` |
| `Row` of buttons without alignment | `JActionBar { ... }` or `Row(Arrangement.spacedBy(8.dp, Alignment.End))` |
| Duplicating `getCapabilityLabel()` | Import from `ClientsSharedHelpers.kt` (internal) |
| `TopAppBar` directly | `JTopBar(title, onBack, actions)` |
| Emoji strings for icons ("...") | Material `ImageVector` icons (`Icons.Default.*`) |
| `StatusIndicator` (deleted) | `JStatusBadge(status)` |
| `SettingCard` (deleted) | `JCard()` |
| `ActionRibbon` (deleted) | `JDetailScreen` (provides save/cancel) |
| `TextButton("< Zpet")` for nav | `JTopBar(onBack = ...)` with ArrowBack icon |

---

## 10) File Structure Reference

```
shared/ui-common/src/commonMain/kotlin/com/jervis/ui/
+-- design/                            <- Design system (split by component category)
|   +-- DesignTheme.kt                <- JervisTheme, JervisSpacing, COMPACT_BREAKPOINT_DP
|   +-- DesignState.kt                <- JCenteredLoading, JErrorState, JEmptyState
|   +-- DesignLayout.kt               <- JTopBar, JSection, JActionBar, JAdaptiveSidebarLayout, JListDetailLayout, JDetailScreen, JNavigationRow, JVerticalSplitLayout, JHorizontalSplitLayout
|   +-- DesignButtons.kt              <- JPrimaryButton, JSecondaryButton, JTextButton, JDestructiveButton, JRunTextButton, JIconButton, JRefreshButton, JDeleteButton, JEditButton, JAddButton, JRemoveIconButton
|   +-- DesignCards.kt                <- JCard, JListItemCard, JTableHeaderRow, JTableHeaderCell, JTableRowCard
|   +-- DesignForms.kt                <- JTextField, JDropdown, JSwitch, JSlider, JCheckboxRow
|   +-- DesignDialogs.kt              <- JConfirmDialog, JFormDialog, JSelectionDialog
|   +-- DesignDataDisplay.kt          <- JKeyValueRow, JStatusBadge, JCodeBlock, JSnackbarHost
|   +-- JervisColors.kt               <- Semantic colors: success, warning, info + light/dark schemes
|   +-- JervisTypography.kt           <- Responsive typography definitions
|   +-- JervisShapes.kt               <- Centralized shape definitions
|   +-- ComponentImportance.kt        <- ESSENTIAL/IMPORTANT/DETAIL enum + JImportance composable
|   +-- JervisBreakpoints.kt          <- WATCH_DP/COMPACT_DP/MEDIUM_DP/EXPANDED_DP + WindowSizeClass + rememberWindowSizeClass()
+-- navigation/
|   +-- AppNavigator.kt               <- Screen sealed class + navigator
+-- meeting/                           <- Meeting feature package
|   +-- MeetingsScreen.kt             <- Public entry point (list + routing)
|   +-- MeetingListItems.kt           <- MeetingListItem, DeletedMeetingListItem, MeetingSummaryListItem (internal)
|   +-- MeetingDetailView.kt          <- Detail view with split layout
|   +-- TranscriptPanel.kt            <- Transcript segments display
|   +-- AgentChatPanel.kt             <- Correction agent chat
|   +-- PipelineProgress.kt           <- Pipeline state display
|   +-- CorrectionQuestionsCard.kt    <- Correction review questions
|   +-- SegmentCorrectionDialog.kt    <- Segment editing dialog
|   +-- MeetingHelpers.kt             <- formatDateTime, stateIcon, stateLabel helpers (internal)
|   +-- MeetingViewModel.kt           <- Meeting state management
|   +-- CorrectionsScreen.kt          <- KB correction rules CRUD
|   +-- CorrectionViewModel.kt        <- Corrections state
|   +-- RecordingSetupDialog.kt       <- Audio device + client/project selection
|   +-- RecordingIndicator.kt         <- Animated recording indicator
+-- screens/
|   +-- settings/
|   |   +-- SettingsScreen.kt         <- JAdaptiveSidebarLayout + categories
|   |   +-- sections/
|   |       +-- ClientsSettings.kt    <- Client list + expandable cards
|   |       +-- ClientEditForm.kt     <- Client edit form (internal)
|   |       +-- ClientEditSections.kt <- ClientConnectionsSection, ClientProjectsSection (internal)
|   |       +-- CapabilityConfiguration.kt <- ConnectionCapabilityCard (internal)
|   |       +-- ProviderResources.kt  <- ProviderResourcesCard (internal)
|   |       +-- ClientsSharedHelpers.kt <- getCapabilityLabel, getIndexAllLabel, GitCommitConfigFields (internal)
|   |       +-- ProjectsSettings.kt   <- Project list
|   |       +-- ProjectEditForm.kt    <- Project edit form (internal)
|   |       +-- ProjectResourceDialogs.kt <- Project resource selection dialogs (internal)
|   |       +-- ProjectGroupsSettings.kt <- Group list
|   |       +-- ProjectGroupEditForm.kt  <- Group edit form (internal)
|   |       +-- ProjectGroupDialogs.kt   <- Group create dialog (internal)
|   |       +-- EnvironmentsSettings.kt  <- Environment list + read-only summary + cross-link to Environment Manager
|   |       +-- EnvironmentDialogs.kt    <- NewEnvironmentDialog, AddComponentDialog, componentTypeLabel(), environmentTierLabel()
|   |       +-- ConnectionsSettings.kt <- Connection list + per-card actions (DISCOVERING state → spinner + "Zjišťuji dostupné služby...")
|   |       +-- ConnectionDialogs.kt    <- Connection create/edit dialogs (internal)
|   |       +-- ConnectionFormComponents.kt <- Connection form fields (internal)
|   |       +-- CodingAgentsSettings.kt <- Coding agent config
|   |       +-- IndexingSettings.kt     <- Indexing intervals config
|   |       +-- WhisperSettings.kt      <- Whisper transcription config
|   +-- environment/
|   |   +-- EnvironmentManagerScreen.kt  <- JListDetailLayout + tabbed detail (Správa prostředí)
|   |   +-- EnvironmentManagerTabs.kt    <- EnvironmentManagerTab enum (OVERVIEW, COMPONENTS, PROPERTY_MAPPINGS, K8S_RESOURCES, LOGS_EVENTS)
|   |   +-- OverviewTab.kt              <- Overview tab: editable fields + read-only summary + onSave + action buttons
|   |   +-- ComponentsTab.kt            <- Components tab: expandable JCards with inline editing
|   |   +-- ComponentEditPanel.kt       <- Inline component editor (name, type, image, ports, ENV, limits, health, startup)
|   |   +-- PropertyMappingsTab.kt      <- Property mappings tab: auto-suggest, manual add, expandable cards
|   |   +-- K8sResourcesTab.kt          <- K8s resources tab: pods, deployments, services (migrated from EnvironmentViewerScreen)
|   |   +-- LogsEventsTab.kt            <- Logs & Events tab: pod log viewer + K8s namespace events
|   +-- IndexingQueueScreen.kt        <- Indexing queue dashboard (4 accordion sections + live indexing progress)
|   +-- IndexingQueueSections.kt      <- ConnectionGroupCard, CapabilityGroupSection, PipelineSection, PollingIntervalDialog (internal)
|   +-- ConnectionsScreen.kt          <- Placeholder (desktop has full UI)
|   +-- PipelineMonitoringScreen.kt  <- Pipeline funnel view with auto-refresh (E2-S7)
|   +-- DeadlineDashboardWidget.kt   <- Deadline urgency widget (E8-S4)
+-- MainScreen.kt                      <- Chat content (no selectors — moved to PersistentTopBar)
+-- PersistentTopBar.kt               <- Global top bar: back, menu, client/project, recording, agent, K8s, connection
+-- MainViewModel.kt                   <- Coordinator: client/project selection, event routing
+-- ConnectionViewModel.kt            <- Connection state, offline detection
+-- ChatMessageDisplay.kt             <- Chat messages, workflow steps display
+-- AgentStatusRow.kt                 <- Agent status indicator (legacy, replaced by PersistentTopBar icon)
+-- ChatInputArea.kt                  <- Message input + send button
+-- AgentWorkloadScreen.kt            <- Agent workload accordion layout
+-- AgentWorkloadSections.kt          <- Agent/queue/history sections (internal)
+-- SchedulerScreen.kt                <- Task scheduling calendar with DONE filter toggle
+-- SchedulerComponents.kt            <- ScheduledTaskDetail, ScheduleTaskDialog (internal)
+-- RagSearchScreen.kt                <- RAG search interface
+-- UserTasksScreen.kt                <- Escalated task list (lightweight DTO) + detail (full DTO on-demand)
+-- PendingTasksScreen.kt             <- Task queue with filters
+-- ErrorLogsScreen.kt                <- Error logs display
+-- model/
|   +-- AgentActivityEntry.kt         <- Data models for agent activity
+-- chat/
|   +-- ChatViewModel.kt              <- Chat messages, streaming, history, attachments, pending retry
+-- queue/
|   +-- QueueViewModel.kt             <- Orchestrator queue, task history, progress tracking
+-- audio/
|   +-- AudioPlayer.kt                <- expect class AudioPlayer
|   +-- AudioRecorder.kt              <- expect class AudioRecorder
|   +-- PlatformRecordingService.kt   <- Recording service bridge (iOS: JervisIcon for lock screen)
|   +-- RecordingServiceBridge.kt
|   +-- TtsClient.kt                  <- Piper TTS HTTP client (POST /tts, /tts/stream)
+-- notification/
|   +-- NotificationViewModel.kt      <- User tasks: approve/deny/reply, badge count
|   +-- ApprovalNotificationDialog.kt <- Orchestrator approval dialog
|   +-- NotificationActionChannel.kt
|   +-- PlatformNotificationManager.kt
+-- storage/
|   +-- PendingMessageStorage.kt
|   +-- RecordingSessionStorage.kt
+-- util/
|   +-- IconButtons.kt                <- RefreshIconButton, DeleteIconButton, EditIconButton
|   +-- ConfirmDialog.kt              <- ConfirmDialog (Czech defaults, keyboard support)
|   +-- CopyableTextCard.kt           <- CopyableTextCard (SelectionContainer + outlinedCardBorder)
|   +-- BrowserHelper.kt              <- expect fun openUrlInBrowser
|   +-- FilePickers.kt                <- expect fun pickTextFileContent
+-- App.kt                            <- Root composable (navigation routing, global dialogs)
+-- JervisApp.kt                      <- App-level setup
```

### 10.1) File Organization Rules

See `docs/guidelines.md` § "UI File Organization" for the complete reference.

**Summary:**
- One feature = one package under `screens/`
- Three-level decomposition: Screen → Content → Sections
- File max ~300 lines, must split at 500+
- Only Screen composable touches ViewModel
- `internal` for feature-scoped, `private` for file-local, `public` for entry points + design system

## 11) Cloud Model Policy Settings

Cloud model auto-escalation toggles in client/project edit forms.

### Client level (defaults)

In `ClientEditForm` (`ClientEditForm.kt`), section "Cloud modely":

```kotlin
JSection(title = "Cloud modely") {
    Text("Automatická eskalace na cloud modely při selhání lokálního modelu.")
    JCheckboxRow(label = "Anthropic (Claude) – reasoning, analýza", checked/onChange)
    JCheckboxRow(label = "OpenAI (GPT-4o) – editace kódu", checked/onChange)
    JCheckboxRow(label = "Google Gemini – extrémní kontext (>49k)", checked/onChange)
}
```

DTO fields: `autoUseAnthropic: Boolean`, `autoUseOpenai: Boolean`, `autoUseGemini: Boolean`

### Project level (override)

In `ProjectEditForm` (`ProjectEditForm.kt`), section "Cloud modely – přepsání":

```kotlin
JSection(title = "Cloud modely – přepsání") {
    Text("Standardně se používá konfigurace z klienta.")
    JCheckboxRow(label = "Přepsat konfiguraci klienta", checked = overrideCloudPolicy)
    if (overrideCloudPolicy) {
        // Same 3 checkboxes as client form
    }
}
```

DTO fields: `autoUseAnthropic: Boolean?`, `autoUseOpenai: Boolean?`, `autoUseGemini: Boolean?`
(null = inherit from client)

When "Přepsat konfiguraci klienta" unchecked → all nulls sent → entity stores `cloudModelPolicy = null`.

**Deleted files** (no longer exist):
- `components/SettingComponents.kt` -- SettingCard, StatusIndicator, ActionRibbon replaced by JCard, JStatusBadge, JDetailScreen
- `screens/settings/sections/BugTrackerSettings.kt` -- dead code, removed
- `screens/settings/sections/GitSettings.kt` -- dead code, removed
- `screens/settings/sections/LogsSettings.kt` -- dead code, replaced by ErrorLogsScreen
- `screens/settings/sections/SchedulerSettings.kt` -- dead code, replaced by SchedulerScreen

---

## 12) Reactive data streams — push-only architecture

**SSOT for rule #9 in `docs/guidelines.md`.**

Every live UI surface consumes data **only** through kRPC `Flow<Snapshot>`
subscriptions. The server owns a per-scope `MutableSharedFlow<Snapshot>(replay=1)`,
emits a fully-rendered snapshot on every relevant write, and the UI `collect`s
into a `StateFlow` that Compose renders reactively. No refresh buttons on live
views, no event→pull round-trips, no one-shot `getXxx()` that the screen reopens
on focus.

### 12.1) Stream endpoint catalog

| Screen / panel | RPC interface | Stream method | Snapshot DTO |
|---|---|---|---|
| Chat task sidebar | `IPendingTaskService` | `subscribeSidebar(clientId: String?)` | `SidebarSnapshot(sections: List<SidebarSection>, doneCount: Int)` |
| Task drill-in (breadcrumb + brief) | `IPendingTaskService` | `subscribeTask(taskId: String)` | `TaskSnapshot(task: PendingTaskDto, relatedTasks: List<PendingTaskDto>, conversationEvents: List<ProgressEvent>)` |
| Main chat history | `IChatService` | `subscribeChatHistory(scope: ChatScope, filter: ChatFilter)` | `ChatHistoryDto` |
| Task conversation | `IChatService` | `subscribeTaskConversation(taskId: String)` | `ConversationSnapshot(messages: List<ChatMessage>, progress: List<ProgressEvent>)` |
| Queue (fg/bg) | `IQueueService` | `subscribeQueue()` | `QueueSnapshot(foreground: List<PendingQueueItem>, background: List<PendingQueueItem>, activity: List<ActivityEntry>)` |
| Meeting detail | `IMeetingService` | `subscribeMeeting(meetingId: String)` | `MeetingSnapshot(meeting: MeetingDto, transcriptionProgress: Double?, correctionProgress: Double?, recordingState: String)` |
| User tasks (K reakci) | `IUserTaskService` | `subscribeUserTasks(clientId: String?)` | `List<UserTaskListItemDto>` |
| Notification signals (badges, toasts) | `INotificationService` | `subscribeToEvents(clientId: String)` | `JervisEvent` — **only signals**, not data |

**Rule of thumb:** a Compose screen/panel that renders live data opens **at
most 2 streams** (data stream + optional dedicated progress stream). Anything
more means your snapshot is too narrow — widen the DTO.

### 12.2) ViewModel pattern

```kotlin
class ChatSidebarViewModel(
    private val repository: JervisRepository,
    private val scope: CoroutineScope,
    private val clientId: StateFlow<String?>,
) {
    private val _sidebar = MutableStateFlow<SidebarSnapshot?>(null)
    val sidebar: StateFlow<SidebarSnapshot?> = _sidebar.asStateFlow()

    init {
        // One collector per scope change — previous collector cancels automatically
        // thanks to collectLatest. Reconnect handled by repository layer (below).
        scope.launch {
            clientId.collectLatest { id ->
                repository.streamingCall { services ->
                    services.pendingTasks.subscribeSidebar(id).collect { snap ->
                        _sidebar.value = snap
                    }
                }
            }
        }
    }
}
```

```kotlin
// Composable
@Composable
fun ChatTaskSidebar(viewModel: ChatSidebarViewModel, ...) {
    val snap by viewModel.sidebar.collectAsState()
    if (snap == null) { JCenteredLoading(); return }
    LazyColumn { /* render snap.sections */ }
}
```

### 12.3) Server pattern

```kotlin
@Service
class SidebarStreamService(
    private val taskRepository: TaskRepository,
    private val projectionMapper: SidebarProjectionMapper,
) {
    // One flow per scope key (clientId or global "")
    private val scopes = ConcurrentHashMap<String, MutableSharedFlow<SidebarSnapshot>>()

    fun subscribe(clientId: String?): Flow<SidebarSnapshot> {
        val key = clientId.orEmpty()
        val flow = scopes.computeIfAbsent(key) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 8).also {
                // Lazy seed: on first subscriber, compute + emit snapshot
            }
        }
        return flow.onSubscription {
            if (flow.replayCache.isEmpty()) emit(buildSnapshot(clientId))
        }
    }

    // Called from TaskService.save() / markDone() / reopen() hooks
    suspend fun invalidate(clientId: String?) {
        val snap = buildSnapshot(clientId)
        scopes[clientId.orEmpty()]?.emit(snap)
        // also emit to global scope if this write affects it
        scopes[""]?.emit(buildSnapshot(null))
    }

    private suspend fun buildSnapshot(clientId: String?): SidebarSnapshot = /* DB query + projection */
}
```

**Invalidation trigger points** (all live in Kotlin server, not UI):
- `TaskRepository.save/delete` → `sidebarStreamService.invalidate(task.clientId)` + per-task flow emit
- `ChatMessageRepository.save` → `chatStreamService.invalidate(scope)` + task-conversation emit
- Orchestrator progress callback → append to `TaskSnapshot.conversationEvents` + emit
- Mongo change streams for collections not written through services (rare)

### 12.4) Reconnect

`RpcConnectionManager` exposes `generation: StateFlow<Int>`. On every
successful reconnect, generation bumps. All streaming ViewModels are wrapped so
that their `collect {}` is restarted from the new kRPC session. `replay=1` on
the server delivers the current snapshot immediately — UI never flashes empty.

```kotlin
// JervisRepository.streamingCall helper
suspend fun <T> streamingCall(block: suspend (Services) -> T) {
    rpcConnectionManager.generation.collect { _ ->
        val services = awaitConnected()
        try { block(services) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* log + loop will retry on next generation bump */ }
    }
}
```

### 12.5) What becomes unary (non-Flow) RPC

These stay unary because they are writes or one-shot historical reads:

- `markDone`, `reopen`, `sendMessage`, `cancel`, `dismiss` — writes, return `Ack`/updated DTO
- `listDoneTasksPaged(page, pageSize)` — archive pagination, not a live view
- `getMeetingTranscript(id)` — download of a finalized artifact
- `downloadFile` — binary stream, separate concern
- Settings CRUD (`createClient`, `updateProject`, …) — static config forms

### 12.6) Removed / forbidden patterns

Replaced by streams:

- `LaunchedEffect(refreshTrigger) { loadActive() }` in any live view
- `_sidebarRefreshTrigger: MutableStateFlow<Int>` (any "bump-to-reload" counter)
- `reloadForCurrentFilter()` on filter change — filter is now a stream param
- `JRefreshButton` inside chat/sidebar/queue/meeting (kept only for Settings CRUD)
- `INotificationService.subscribeToEvents` handler that calls `listXxx()` / `getXxx()`
- Loading the task's current state via `getById(taskId)` when opening a chat
  drill-in — `subscribeTask(taskId)` replaces it
- Comment "polls every 15s" or "wait for next polling cycle"

SSOT rule: if you're adding `LaunchedEffect { service.listXxx(...) }` for a live
view, stop — add a stream instead.


---

# Inter-Service Contracts

> (Dříve docs/inter-service-contracts.md — SSOT pro Protobuf + gRPC)

# Inter-Service Contracts — SSOT

> **Single source of truth** for every HTTP API between service pods.
> A change on one side forces regeneration and rebuild on every consumer.
> CI blocks the PR if generated code is out of date or if the change is breaking
> without every consumer being updated in the same PR.

---

## 1. Scope

**IN scope** (covered by this document):

- Kotlin server (`backend/server`) ↔ Python service pods (`backend/service-*`).
- Python service pod ↔ Python service pod (orchestrator ↔ KB, orchestrator ↔ router, correction agent ↔ KB, teams pod ↔ server callbacks, …).
- Any future pod — Kotlin or Python — that speaks HTTP/JSON to another pod.

**OUT of scope**:

- **kRPC/CBOR** between Kotlin UI clients and the Kotlin server (`shared/common-api`, `shared/common-dto`). Stays as-is — already single-source-of-truth, stable, streaming-aware (push-only Flows per `docs/ui-design.md` §12).
- Coding agents invoked as K8s Jobs (Claude CLI, Aider, Junie, OpenHands) — those are spawned by the orchestrator, not called via RPC.
- External third-party APIs (Graph API, Slack API, Teams Graph, Atlassian) — those are adapters inside each service, not contracts we own.

---

## 2. Stack

**Protobuf + Buf + gRPC over HTTP/2 cleartext (h2c).**

| Concern | Tool |
|---|---|
| Schema language | Protobuf (proto3) |
| Linting, breaking-change detection, codegen orchestration | [Buf CLI](https://buf.build) |
| Transport | gRPC over HTTP/2 cleartext inside the cluster |
| Kotlin message codegen | `protoc-gen-java` (from `grpc-java`) |
| Kotlin RPC codegen | [`grpc-kotlin`](https://github.com/grpc/grpc-kotlin) (Google, coroutine-native) |
| Python message + RPC codegen | [`grpcio-tools`](https://grpc.io/docs/languages/python/) run **locally** from the top-level `Makefile` (not via a Buf remote plugin) |
| Debug CLI | [`grpcurl`](https://github.com/fullstorydev/grpcurl) with server reflection enabled |

Rationale:

- **gRPC over ConnectRPC** — Protobuf + gRPC is the industry-standard for pod-to-pod RPC: production-proven by Google/Netflix/Square for 10+ years, server-streaming is boring and reliable, deadline/cancellation semantics are canonical, observability tooling is mature. An April 2026 maturity audit flagged `connect-kotlin` as maintenance-only since March 2024 and `connect-python` as pre-1.0 beta with an in-flight 1.0 rewrite by Buf; adopting either as a big-bang target would force a second migration within the year.
- **Buf over raw `protoc`** — canonical lint rules (`STANDARD`), `buf breaking` = CI guardrail blocking drift, per-module config, first-party Gradle plugin.
- **`grpc-kotlin` over Wire** — Wire is excellent for KMP clients, but the server here is JVM-only and `grpc-kotlin` is the reference Google client with first-class coroutine support. Avoiding Wire also sidesteps the "Wire messages + grpc-java stubs" runtime seam.
- **`grpcio-tools` over betterproto** — `grpcio` is Google's reference implementation, covers streaming/deadlines/cancellation/reflection, asyncio-native (`grpc.aio`). `betterproto` v1 is abandoned (last release 2021); `betterproto2` is sub-1.0 — neither is acceptable for the big-bang target.
- **Python codegen runs locally (Makefile), not via the `buf.build/protocolbuffers/python` remote plugin.** The remote plugin has shipped gencode several versions ahead of the latest `protobuf` runtime published on PyPI in 2026 (gencode `7.34.1` vs runtime `6.33.6`), causing `google.protobuf.runtime_version.VersionError` on import. `python -m grpc_tools.protoc` pinned at the same version as every service's installed `grpcio` keeps gencode/runtime in lockstep. Kotlin/Java codegen stays on Buf remote plugins — there is no robust local alternative for `grpc-kotlin`.
- **h2c inside cluster** — K8s ClusterIP services and every Python ASGI server speak HTTP/2 cleartext natively. No TLS termination inside the pod network; observability/tracing via standard gRPC interceptors.

### Versioning

**None.** Single namespace `jervis.*`. Every consumer is in this monorepo; we rebuild everything on breaking changes. No `v1/`, no `v2/`, no suffixes. This matches the project stage ("PoC — no backward compat, full refactoring") and the user's directive that no public API will ever exist.

---

## 3. Directory layout

```
proto/
├── buf.yaml
├── buf.gen.yaml
├── buf.lock
└── jervis/
    ├── common/
    │   ├── types.proto          # Scope, RequestContext (see §"No contract data in HTTP headers"), Urn, Timestamp, IDs
    │   ├── errors.proto         # ErrorDetail message for gRPC status details
    │   ├── pagination.proto
    │   └── enums.proto          # Capability, Priority, TierCap, Kind, SourceType, SourceCredibility
    ├── router/
    │   ├── decide.proto         # RouterService.Decide
    │   └── chat.proto           # RouterService.Chat (server-streaming tokens)
    ├── knowledgebase/
    │   ├── ingest.proto
    │   ├── retrieve.proto
    │   ├── documents.proto
    │   └── graph.proto
    ├── orchestrator/
    │   ├── orchestrate.proto    # OrchestratorService.Orchestrate (server-streaming events)
    │   ├── qualify.proto
    │   ├── approve.proto
    │   └── chat.proto
    ├── server_callback/          # Kotlin server as gRPC server, Python as client
    │   ├── progress.proto
    │   ├── status.proto
    │   ├── streaming_token.proto
    │   ├── qualification.proto
    │   └── agent_question.proto
    ├── whisper/
    │   └── transcribe.proto     # server-streaming segments
    ├── correction/
    │   └── correct.proto
    ├── meeting_attender/
    │   └── attend.proto
    ├── teams_pod/
    │   └── agent.proto
    ├── whatsapp_browser/
    │   └── session.proto
    ├── o365_browser_pool/
    │   ├── session.proto
    │   ├── files.proto
    │   └── mail.proto
    ├── coding_engine/
    │   └── execute.proto
    ├── joern/
    │   └── cpg.proto
    ├── document_extraction/
    │   └── extract.proto        # Tika replacement boundary
    ├── visual_capture/
    │   └── capture.proto
    ├── tts/
    │   └── speak.proto          # server-streaming audio chunks
    └── mcp/
        └── internal.proto       # only if MCP server exposes non-SSE internal RPC
```

Each subdirectory maps 1:1 to one `backend/service-*` pod. File naming follows the dominant capability; split when a single file exceeds ~300 lines of proto.

---

## 4. Transport details

### Wire protocol

gRPC over HTTP/2 cleartext (h2c). Standard gRPC path layout `/jervis.<domain>.<Service>/<Method>` with binary Protobuf body. No JSON on the wire.

Examples:

- `/jervis.router.RouterService/Decide` (unary)
- `/jervis.knowledgebase.KnowledgebaseService/Retrieve` (unary)
- `/jervis.orchestrator.OrchestratorService/Orchestrate` (server-streaming)

### Ports

- Kotlin server: existing Ktor `:5500` keeps kRPC/WebSocket for UI clients. A new listener `:5501` serves gRPC (in-process `ServerBuilder.forPort(5501)`), isolated from Ktor. Health/readiness probes for both ports added to K8s manifests in Phase 0.
- Each Python pod exposes its existing ASGI port as h2c; FastAPI sidechannel (blob upload + vendor proxies like Ollama API) mounts on the same port via ASGI dispatch.

### Debugging

- **gRPC reflection enabled** on every server (`grpc-reflection`) so `grpcurl` discovers schemas at runtime.
- `grpcurl -plaintext -d @ localhost:5501 jervis.router.RouterService/Decide < request.json` round-trips a hand-authored JSON body just like `curl` would against REST.
- `grpcurl -plaintext localhost:5501 list` / `describe` for ad-hoc exploration.
- Server logs emit the full RPC name on every request via a shared logging interceptor.

### No contract data in HTTP headers

**Rule**: every value that affects routing, authorization, business logic, or observability semantics lives **inside the proto payload**. HTTP headers carry transport metadata only (`Content-Type`, `Content-Length`, standard HTTP/2 pseudo-headers). Custom `X-*` headers are forbidden on contract-bearing traffic.

Concretely, every existing custom header is migrated to a payload field:

| Removed header | Payload field |
|---|---|
| `X-Client-Id` | `RequestContext.scope.client_id` |
| `X-Capability` (routing hint) | `RequestContext.capability` |
| `X-Ollama-Priority` | `RequestContext.priority` |
| `X-Intent` | `RequestContext.intent` |
| per-request deadline header | `RequestContext.deadline_iso` |
| correlation / trace header | `RequestContext.request_id`, `RequestContext.trace` |

Every request message that crosses a pod boundary starts with:

```proto
// jervis/common/types.proto
message Scope {
  string client_id = 1;
  string project_id = 2;   // empty string = no project scope
  string group_id  = 3;    // empty string = no group scope
}

message RequestContext {
  Scope scope                  = 1;
  Priority priority            = 2;   // BACKGROUND / FOREGROUND / CRITICAL (enums.proto)
  Capability capability        = 3;   // routing hint (enums.proto)
  string intent                = 4;   // free-form routing tag
  string deadline_iso          = 5;   // RFC3339 UTC; "" = no deadline (falls back to priority)
  TierCap max_tier             = 6;   // NONE / T1 / T2 cap for paid models
  string request_id            = 7;   // correlation id, server-generated if empty
  string task_id               = 8;   // related JERVIS task, "" if none
  int64  issued_at_unix_ms     = 9;
  map<string, string> trace    = 10;  // w3c-traceparent-style extras; no ABI guarantees
}
```

And every RPC's request carries it as field 1:

```proto
message DecideRequest {
  RequestContext ctx          = 1;
  // RPC-specific fields follow
  Capability target_capability = 2;   // what to decide — distinct from ctx.capability
  int32 min_model_size_b       = 3;
}
```

Rationale:

- **One SSOT for cross-cutting semantics.** `buf breaking` catches drift in scope/priority/deadline the same way it catches drift in any other field. Headers were invisible to the schema checker.
- **Uniform serialization.** Binary gRPC never has to parse string headers. JSON debugging (`grpcurl -d @`) shows every contract value in one place.
- **No split between "routed by headers" and "routed by body"** — a router or middleware that wants to inspect the request unmarshals proto, period.
- **Trivial auth extension** later: `RequestContext.credential` proto message if an internal service ever needs per-call auth beyond K8s network policy.

Implementation enforcement: a shared interceptor on every client populates `ctx.request_id`, `ctx.issued_at_unix_ms`, and `ctx.trace`. A shared server interceptor validates `ctx.scope.client_id` is non-empty on every RPC except explicitly-marked unauthenticated ones (health checks). Both interceptors are ~30 LOC in `shared:service-contracts` (Kotlin) and `libs/jervis_contracts/` (Python).

### Streaming

gRPC's server-streaming over HTTP/2 is the canonical mechanism. Used for:

| RPC | Reason |
|---|---|
| `OrchestratorService.Orchestrate` | Progress events (`node_start`, `node_end`, `status_change`, `result`). Replaces current push-back POSTs to Kotlin server. |
| `WhisperService.Transcribe` | Progressive segments as audio chunks are processed. |
| `RouterService.Chat` | Token-by-token LLM streaming to UI. |
| `OrchestratorChatService.Chat` | Chat tokens + tool calls + approvals. |
| `VoiceService.Process` | Voice pipeline (preliminary answer, responding, tokens, TTS). |
| `CompanionService.StreamSession` | Claude companion session events. |
| `TtsService.SpeakStream` | PCM chunks. |

No client-streaming and no bidi until a concrete need appears. Deadlines (`grpc.Deadline`) are honored on every streaming RPC using `RequestContext.deadline_iso` — the client-side interceptor converts ISO to a gRPC deadline automatically.

### Replacing current push-back pattern

Today the Python orchestrator pushes progress via POST to Kotlin `/internal/orchestrator-progress`. After migration:

- **Preferred path**: Kotlin opens `Orchestrate` as a server-stream, holds it open for the duration of the task, and consumes events as the stream emits. One connection, typed events, automatic cancellation when the client disconnects.
- **Fallback path** (only if long-running disconnects prove fragile): keep a `ServerOrchestratorCallbackService` gRPC service on the Kotlin side that Python calls. Same schema benefit — no more untyped dicts — but two connections.

We start with the preferred streaming path. Fallback is pre-specified so there's no untyped regression if streaming must be abandoned.

---

## 5. Kotlin integration

### New Gradle module: `shared:service-contracts`

- **Target**: JVM only (server is JVM; UI does not speak these protocols).
- **Source**: `proto/` (the monorepo single source).
- **Output**: `com.jervis.contracts.<domain>.*` — generated Protobuf messages (Java, Kotlin-friendly) + gRPC service stubs (`grpc-java`) + coroutine-native stubs (`grpc-kotlin`).
- **Depends on**: `shared:common-dto` is NOT a dependency. `service-contracts` is independent. Server modules depend on both and map between them where needed (usually a thin mapper per domain).

### Gradle wiring

```kotlin
// shared/service-contracts/build.gradle.kts
plugins {
    kotlin("jvm")
    id("build.buf")        // Buf Gradle plugin wraps `buf generate`
}

buf {
    // Invokes `buf generate` with proto/buf.gen.yaml as the source of truth
    generate {
        includeImports = true
    }
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/source/buf/java"))
    java.srcDir(layout.buildDirectory.dir("generated/source/buf/grpc-java"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/source/buf/grpc-kotlin"))
}

tasks.named("compileKotlin") {
    dependsOn("buf")
}

dependencies {
    api("io.grpc:grpc-protobuf:1.66.+")
    api("io.grpc:grpc-stub:1.66.+")
    api("io.grpc:grpc-kotlin-stub:1.4.+")
    api("io.grpc:grpc-netty-shaded:1.66.+")
    api("io.grpc:grpc-services:1.66.+")        // reflection for grpcurl
    api("com.google.protobuf:protobuf-kotlin:4.28.+")
}
```

### Consumer wiring

```kotlin
// backend/server/build.gradle.kts
dependencies {
    implementation(project(":shared:service-contracts"))
}
```

Old DTO files that are replaced (deleted, not renamed):

- `PythonOrchestratorClient.kt` — DTOs and HTTP calls move to generated gRPC stubs.
- `KnowledgeServiceRestClient.kt` — same.
- `WhisperRestClient.kt`, `CorrectionClient.kt`, `CascadeLlmClient.kt`, `PythonChatClient.kt`, `OrchestratorCompanionClient.kt`, `DocumentExtractionClient.kt` — same.

Mapping to kRPC DTOs (when UI needs a slightly different shape) lives in a new thin module layer:

- `backend/server/src/main/kotlin/com/jervis/contracts/mappers/*.kt` — pure functions `fun ContractDto.toDomain(): UiDto` and back.

---

## 6. Python integration

### New package: `libs/jervis_contracts/`

```
libs/jervis_contracts/
├── pyproject.toml
├── jervis/                        # generated Protobuf + gRPC stubs — committed
│   ├── common/
│   ├── router/
│   ├── knowledgebase/
│   └── ...
└── jervis_contracts/              # hand-written helpers (interceptors, …)
    ├── __init__.py
    └── interceptors/
```

The split has two roots on purpose: `grpc_tools.protoc` emits absolute
imports (`from jervis.common import enums_pb2`), so the generated tree
must be reachable as the top-level `jervis` package. Hand-written helpers
live under `jervis_contracts/` so they never collide with generated names
and the import surface is explicit (`from jervis.common import ...` for
contract messages, `from jervis_contracts.interceptors import ...` for
client/server middleware).

`pyproject.toml` runtime deps: `grpcio`, `grpcio-tools`, `grpcio-reflection`, `protobuf`. Every Python service consumes the local package:

```toml
# backend/service-orchestrator/pyproject.toml (or requirements.txt)
dependencies = [
    "jervis-contracts @ file://../../libs/jervis_contracts",  # editable
    "grpcio>=1.66",
    "grpcio-reflection>=1.66",
    ...
]
```

In Docker images the package is copied and `pip install -e /libs/jervis_contracts` is run before other deps.

### Client usage example (Python)

```python
from jervis.router import admin_pb2, admin_pb2_grpc
from jervis.common.types_pb2 import RequestContext, Scope
from jervis.common.enums_pb2 import Capability, Priority
from jervis_contracts.interceptors import ClientContextInterceptor, prepare_context
import grpc

async with grpc.aio.insecure_channel("ollama-router:5501") as channel:
    stub = router_pb2_grpc.RouterServiceStub(channel)
    req = router_pb2.DecideRequest(
        ctx=RequestContext(
            scope=Scope(client_id="..."),
            priority=Priority.PRIORITY_FOREGROUND,
            capability=Capability.CAPABILITY_CHAT,
            deadline_iso="2026-04-16T15:00:00Z",
        ),
        target_capability=Capability.CAPABILITY_CHAT,
        min_model_size_b=7,
    )
    resp = await stub.Decide(req)
```

No more `payload = {"capability": ..., "deadline_iso": ...}` dicts. Enum values come from the generated module; a typo is a Python attribute error, not a 422 at runtime.

### Server usage example (Python)

```python
import grpc
from grpc_reflection.v1alpha import reflection
from jervis.knowledgebase import retrieve_pb2, retrieve_pb2_grpc

class KnowledgebaseImpl(retrieve_pb2_grpc.KnowledgeRetrieveServiceServicer):
    async def Retrieve(self, request: retrieve_pb2.RetrieveRequest, context) -> retrieve_pb2.RetrieveResponse:
        ...

async def serve():
    server = grpc.aio.server()
    retrieve_pb2_grpc.add_KnowledgeRetrieveServiceServicer_to_server(KnowledgebaseImpl(), server)
    reflection.enable_server_reflection(
        [retrieve_pb2.DESCRIPTOR.services_by_name["KnowledgeRetrieveService"].full_name,
         reflection.SERVICE_NAME],
        server,
    )
    server.add_insecure_port("[::]:5501")
    await server.start()
    await server.wait_for_termination()
```

FastAPI is kept only for the blob side channel (`PUT /blob/{type}/{id}` raw-bytes uploads) and vendor pass-through (Ollama REST proxy in `service-ollama-router`). Every contract-bearing endpoint becomes gRPC. FastAPI and gRPC can coexist on different ports in the same process; no shared ASGI dispatch needed.

---

## 7. Buf configuration

### `proto/buf.yaml`

```yaml
version: v2
modules:
  - path: .
breaking:
  use:
    - FILE
lint:
  use:
    - STANDARD
  except:
    - PACKAGE_VERSION_SUFFIX   # no versioning by design
  enum_zero_value_suffix: _UNSPECIFIED
  rpc_allow_same_request_response: false
  rpc_allow_google_protobuf_empty_requests: true
  rpc_allow_google_protobuf_empty_responses: true
```

### `proto/buf.gen.yaml`

```yaml
version: v2
plugins:
  # Kotlin/Java — messages
  - remote: buf.build/protocolbuffers/java
    out: ../shared/service-contracts/build/generated/source/buf/java

  # Kotlin/Java — gRPC stubs (grpc-java)
  - remote: buf.build/grpc/java
    out: ../shared/service-contracts/build/generated/source/buf/grpc-java

  # Kotlin — coroutine-native stubs (grpc-kotlin)
  - remote: buf.build/grpc/kotlin
    out: ../shared/service-contracts/build/generated/source/buf/grpc-kotlin

  # Python is generated outside Buf — see Makefile `proto-generate-python`
  # which invokes `python -m grpc_tools.protoc` directly so gencode tracks
  # the installed `grpcio-tools` version (the `buf.build/protocolbuffers/
  # python` remote plugin ships gencode ahead of the PyPI `protobuf`
  # runtime, causing VersionError on import).
```

### `proto/buf.lock`

Committed. Pinned plugin and remote-dep versions. Regenerated only via explicit `buf mod update`.

---

## 8. Build integration

### Local developer flow

```
# Edit proto/jervis/router/decide.proto
$ cd proto
$ buf lint
$ buf breaking --against '.git#branch=master'
$ buf generate
$ git status   # shows regenerated Kotlin + Python files
$ git add -A
```

### Top-level Makefile

```makefile
# /Users/damekjan/git/jervis/Makefile
.PHONY: proto-lint proto-breaking proto-generate proto-verify

proto-lint:
	cd proto && buf lint

proto-breaking:
	cd proto && buf breaking --against '.git#branch=master'

proto-generate:
	cd proto && buf generate

proto-verify: proto-lint proto-breaking
	cd proto && buf generate
	git diff --exit-code -- proto shared/service-contracts/build/generated libs/jervis_contracts/jervis_contracts/_generated
```

### Gradle integration

`shared/service-contracts` calls `buf generate` as a task dependency so a fresh checkout of the repo produces a buildable Kotlin module without any extra command.

### Python integration

Each `backend/service-*/Dockerfile`:

```dockerfile
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install -e /libs/jervis_contracts
COPY backend/service-<name> /app
RUN pip install -e /app
```

For local dev, a `pip install -e libs/jervis_contracts` once per venv is enough.

---

## 9. CI enforcement

Pipeline order on every PR (non-negotiable):

1. **`make proto-lint`** — style.
2. **`make proto-breaking`** — blocks breaking changes unless every consumer change is in the same PR.
3. **`make proto-generate && git diff --exit-code`** — generated code must be committed and current.
4. **Full Kotlin build** (`./gradlew :shared:service-contracts:build :backend:server:build`).
5. **Full Python build** (per-service `python -m compileall`, unit test entrypoints, mypy/pyright).

If step 2 fails, the PR must either revert the breaking change or include all consumer fixes. There is no allow-list and no override. The combined invariant is: **at every commit on master, every consumer compiles against current proto.**

---

## 10. Generated code commit policy

Generated files are **committed** to the repo. Rationale:

- IDEs (IntelliJ, PyCharm, VS Code) need to resolve types before a Gradle/pip build runs.
- CI can diff against master without regenerating (faster).
- Drift is visible in code review.

Paths:

- `shared/service-contracts/build/generated/source/buf/…` — despite being under `build/`, this tree is un-ignored at the root `.gitignore` (`!shared/service-contracts/build/generated/source/buf/`) so IDEs resolve types on fresh checkout.
- `libs/jervis_contracts/jervis/…` — plain committed directory; the `jervis_contracts/` sibling directory holds hand-written interceptors and is also committed.

Formatters (ktlint, black, ruff) are configured to skip generated paths. Linters likewise.

---

## 11. Migration plan — ordered hard cuts

Each step is one PR. No feature flags, no dual-path operation. Old client is deleted the same PR the new generated client is wired in.

1. **Infrastructure PR**: `proto/` skeleton with `common/*.proto` only, `shared/service-contracts/`, `libs/jervis_contracts/`, Buf config, Makefile, CI wiring. Kotlin module builds empty, Python package installs empty. CI green.
2. **Router** (`service-ollama-router`): smallest surface, highest call volume. Defines `decide.proto`, `chat.proto`. Kotlin `CascadeLlmClient.kt` and Python `router_client.py` rewritten.
3. **Knowledge Base** (`service-knowledgebase`): highest drift today. `ingest.proto`, `retrieve.proto`, `documents.proto`, `graph.proto`. Deletes `PythonIngestRequest` etc. from `KnowledgeServiceRestClient.kt`.
4. **Orchestrator ↔ Kotlin server** (`service-orchestrator`): `orchestrate.proto` as server-streaming replaces push-back POST pattern. Also `qualify`, `approve`, `chat`. Drops untyped dict payloads in `kotlin_client.py`.
5. **Whisper + Correction**.
6. **Teams pod, WhatsApp browser, O365 browser pool, Meeting attender**.
7. **Coding engine, Joern, Document extraction (Tika), Visual capture, TTS**.
8. **MCP internal endpoints** if any remain beyond SSE.
9. **Cleanup PR**: remove all `*Client.kt` DTO duplicates, remove all Pydantic models that were only used for HTTP contracts. Audit confirms zero `payload = {…}` dicts hitting HTTP.

Expected duration: 2–3 weeks of focused work. Each PR is deployable independently (K8s rolling restart; both sides of the wire migrate in the same PR so no partial state exists in production).

---

## 12. Naming conventions

| Element | Convention | Example |
|---|---|---|
| Proto package | `jervis.<domain>` | `jervis.router` |
| Proto file | `snake_case.proto` | `ingest.proto` |
| Service name | `PascalCase` + `Service` | `KnowledgebaseService` |
| Method name | `PascalCase` verb | `Retrieve`, `IngestFull` |
| Message for RPC I/O | `<Method>Request` / `<Method>Response` | `DecideRequest`, `DecideResponse` |
| Domain message | `PascalCase` noun, no suffix | `RouterDecision`, `KbDocument` |
| Field | `snake_case` | `client_id`, `deadline_iso` |
| Enum type | `PascalCase` | `Capability` |
| Enum value | `UPPER_SNAKE_CASE` prefixed with enum name | `CAPABILITY_CHAT` |
| Zero enum value | `<ENUM>_UNSPECIFIED = 0` (mandatory) | `CAPABILITY_UNSPECIFIED = 0` |

Kotlin mapping (grpc-kotlin + grpc-java):

- Proto `jervis.router` → Java `com.jervis.contracts.router` (via `option java_package`) with `RouterServiceGrpcKt` coroutine stub and `RouterServiceGrpc` blocking/async stubs.
- Proto `snake_case` fields → generated Java builders with `setSnakeCase` / `getSnakeCase`; Kotlin callers use idiomatic DSL `routerRequest { snakeCase = "..." }`.

Python mapping (grpcio-tools):

- Proto `jervis.router` → Python `jervis_contracts.router` (with generated `router_pb2.py`, `router_pb2_grpc.py`).
- Proto `snake_case` fields → Python `snake_case` (unchanged).

---

## 13. Enum discipline — preventing drift

The project has a recorded anti-pattern: enum values added in one place, forgotten elsewhere (`feedback-enum-refactor-grep-new-values.md`). Protobuf + `buf breaking` eliminates this:

- Every enum has `<NAME>_UNSPECIFIED = 0` as the first value.
- New values append to the end with a new tag number. Never reuse or reorder tags.
- Renaming a value requires `reserved` on the old tag/name:

```proto
enum Capability {
  CAPABILITY_UNSPECIFIED = 0;
  CAPABILITY_CHAT = 1;
  CAPABILITY_EMBEDDING = 2;
  // Renamed CAPABILITY_VISION → CAPABILITY_VLM
  reserved 3;
  reserved "CAPABILITY_VISION";
  CAPABILITY_VLM = 4;
}
```

`buf breaking` blocks any change that removes, reorders, or retypes an enum value. Consumers using the old name fail to compile. No grep required — the compiler is the grep.

---

## 14. Error handling

gRPC's built-in [status codes](https://grpc.io/docs/guides/status-codes/) are canonical. Services return standard codes:

- `INVALID_ARGUMENT` — validation failure.
- `NOT_FOUND` — resource absent.
- `UNAVAILABLE` — downstream pod not ready.
- `RESOURCE_EXHAUSTED` — rate limit, 503 from router.
- `DEADLINE_EXCEEDED` — matches existing deadline-driven routing (auto-populated from `RequestContext.deadline_iso` via client interceptor).
- `INTERNAL` — unexpected.

Consumer-side: `grpc-kotlin` surfaces errors as `io.grpc.StatusException`; `grpcio` surfaces them as `grpc.RpcError` / `grpc.aio.AioRpcError`. No custom error envelope at the application layer.

A shared `jervis.common.ErrorDetail` message carries structured diagnostics when code alone is insufficient (e.g., router returns which model was tried):

```proto
message ErrorDetail {
  string reason = 1;               // machine-readable short code
  map<string, string> metadata = 2; // free-form context
}
```

Attached via gRPC status `details` field (`google.rpc.Status.details` / `io.grpc.protobuf.StatusProto`), not via response body shape.

---

## 15. What does NOT change

- `shared/common-api` + `shared/common-dto` — kRPC/CBOR single source of truth for UI ↔ server. No proto involvement.
- Push-only Flow streams for UI (`docs/ui-design.md` §12) — unchanged.
- KB graph schema, Memory structures, Thought Map — unchanged.
- K8s deployment, registry, Traefik/nginx ingress — unchanged for external HTTP. Pod-to-pod gRPC stays inside the cluster over ClusterIP; no ingress hop.
- Coding agent spawn pattern (K8s Jobs for Claude CLI, Aider, etc.) — unchanged.
- Memory and MEMORY.md conventions — unchanged.

---

## 16. Non-goals

- No publishing to `buf.build` registry. Proto stays internal to the repo.
- No external public API. Breaking changes are always safe because every consumer is in the monorepo.
- No OpenAPI side-channel. One canonical contract per boundary.
- No GraphQL, no SOAP, no JSON-Schema-only contracts.
- No coexistence with deprecated HTTP endpoints. Migration is a hard cut per service.

---

## 17. Checklist — adding a new endpoint

1. Edit the relevant `proto/jervis/<domain>/<file>.proto`. Add message, add method on the service.
2. `cd proto && buf lint && buf breaking --against '.git#branch=master' && buf generate`.
3. Implement server handler (Kotlin in `backend/server/…` or Python in `backend/service-<name>/…`) against generated service base class.
4. Call from client via generated stub.
5. Delete any old hand-written DTO that duplicates the new message.
6. Update relevant `docs/*.md` if behavior (not just transport) changes.
7. Commit proto, generated code, implementation, and docs in one PR.
8. CI verifies drift-free state, breaking-change compliance, and full-stack build.

---

## 18. Open questions (to resolve during infra PR)

- **Gradle Buf plugin version pin**: `build.buf` 0.11.0 (Jan 2026) is the first-party Gradle plugin; pin exactly in Phase 0 to avoid surprise bumps.
- **grpc-kotlin × Netty classloader**: run gRPC Netty on `:5501` isolated from Ktor `:5500`; use `grpc-netty-shaded` to avoid Netty version conflicts with any transitive Ktor Netty.
- **Python FastAPI coexistence**: each Python service runs gRPC (`grpc.aio` on `:5501`) and FastAPI (for blob upload side-channel on the existing port) as two separate listeners in one process. Verify graceful shutdown coordinates both.
- **Blob upload for KB documents**: resolved — split into `KnowledgeDocumentService.Register(hash, metadata)` (gRPC) + `PUT /blob/kb-doc/{hash}` (thin REST raw-bytes). Proto carries `blob_ref` string; REST carries bytes.
- **MCP SSE**: stays REST (protocol requirement). Only the internal MCP RPC endpoints (if any) go through gRPC; SSE continues on its existing ingress.
- **Ingress h2c**: not required for MVP because pod-to-pod gRPC stays inside the cluster (ClusterIP). If a future client needs gRPC from outside, Traefik supports h2c via configuration — revisit then.
- **Public voice API (Siri/Watch)**: stays REST + multipart. A thin Kotlin handler unpacks multipart and fans out to the gRPC orchestrator internally.

None blocks the architecture decision — all are implementation detail for Phase 0.


---

# Orchestrator Architecture (Detailed)

> (Dříve docs/orchestrator-detailed.md — komplete reference LangGraph orchestrátoru)

# Orchestrator — Detailed Technical Reference

> Kompletní referenční dokument pro Python orchestrátor a jeho integraci s Kotlin serverem.
> Základ pro analýzu, rozšiřování a debugging celé orchestrační vrstvy.
> **Automaticky aktualizováno:** 2026-02-25

---

## Agent Selection Strategy

Only two agent types remain. Claude is the default and only production agent:

| Agent | Use Case | Provider | Auth | Status |
|-------|----------|----------|------|--------|
| **Claude** | Default agent for all tasks (coding + review) | Anthropic (Claude SDK) | Setup token or API key | ✅ Active |
| **Kilo** | Future alternative agent | — | — | Placeholder |

Agent type is always `claude` unless user explicitly selects otherwise.

**Auth methods** (Claude):
- **Setup Token** (recommended): `claude setup-token` → long-lived `sk-ant-oat01-...` token, stored in MongoDB
- **API Key**: Anthropic Console pay-as-you-go key

**Configuration:** `shared/ui-common/.../sections/CodingAgentsSettings.kt` (UI), `coding_agent_settings` collection (MongoDB).

---

## Obsah

### Legacy Orchestrator (14-node graph)

1. [Přehled systému](#1-přehled-systému)
2. [Architektura komunikace](#2-architektura-komunikace)
3. [Životní cyklus úlohy — kompletní flow](#3-životní-cyklus-úlohy--kompletní-flow)
4. [OrchestrateRequest — vstupní data](#4-orchestraterequest--vstupní-data)
5. [LangGraph StateGraph — graf orchestrace (legacy)](#5-langgraph-stategraph--graf-orchestrace)
6. [OrchestratorState — kompletní stav](#6-orchestratorstate--kompletní-stav)
7. [Nodes — detailní popis každého uzlu (legacy)](#7-nodes--detailní-popis-každého-uzlu)
8. [LLM Provider — model a volání](#8-llm-provider--model-a-volání)
9. [Knowledge Base integrace](#9-knowledge-base-integrace)
10. [K8s Job Runner — spouštění coding agentů](#10-k8s-job-runner--spouštění-coding-agentů)
11. [Workspace Manager — příprava prostředí](#11-workspace-manager--příprava-prostředí)
12. [Context Store — hierarchické úložiště](#12-context-store--hierarchické-úložiště)
13. [Approval Flow — interrupt/resume mechanismus](#13-approval-flow--interruptresume-mechanismus)
14. [Concurrency Control — multi-orchestration](#14-concurrency-control--multi-orchestration)
15. [Stuck detection a liveness](#15-stuck-detection-a-liveness)
16. [Chat Context Persistence — paměť agenta](#16-chat-context-persistence--paměť-agenta)
17. [Correction Agent — korekce přepisů](#17-correction-agent--korekce-přepisů)

### Multi-Agent Delegation System (7-node graph, feature-flagged)

18. [Delegation Graph — přehled](#18-delegation-graph--přehled)
19. [Delegation Graph — nové nodes](#19-delegation-graph--nové-nodes)
20. [OrchestratorState — delegation fields](#20-orchestratorstate--delegation-fields)
21. [DAG Executor — paralelní execution](#21-dag-executor--paralelní-execution)
22. [Agent Communication Protocol](#22-agent-communication-protocol)
23. [Specialist Agents — registr 19 agentů](#23-specialist-agents--registr-19-agentů)
24. [Memory Integration — 4 vrstvy](#24-memory-integration--4-vrstvy)
25. [Feature Flags a backward compatibility](#25-feature-flags-a-backward-compatibility)

### Shared Infrastructure

26. [Kotlin integrace — kompletní API](#26-kotlin-integrace--kompletní-api)
27. [Konfigurace a deployment](#27-konfigurace-a-deployment)
28. [Datové modely — kompletní referenční seznam](#28-datové-modely--kompletní-referenční-seznam)
29. [Souborová mapa](#29-souborová-mapa)

### Chat Quality & Reliability

35. [Two-Tier Tool System for Chat](#35-two-tier-tool-system-for-chat)
36. [Anti-Hallucination Pipeline](#36-anti-hallucination-pipeline)
37. [OpenRouter FREE Queue — Model Error Tracking](#37-openrouter-free-queue--model-error-tracking)

---

## 1. Přehled systému

Orchestrátor je **Python FastAPI** služba založená na **LangGraph** (stavový graf s checkpointy). Řeší VŠECHNY uživatelské požadavky — od jednoduchých dotazů přes coding úlohy po celé epicy.

### Klíčové principy

| Princip | Implementace |
|---------|-------------|
| **Žádné hard timeouty** | Streaming + token-arrival liveness (300s bez tokenu = dead); timestamp-based stuck detection (15 min) |
| **Push-based komunikace** | Python → Kotlin POST callbacky; polling je pouze safety-net (60s) |
| **Local-first LLM** | Ollama lokálně, cloud jen na explicitní eskalaci |
| **Single orchestration** | Jeden GPU request najednou (asyncio.Semaphore) |
| **Persistent state** | MongoDB checkpointy — přežijí restart podu |
| **KB-first architecture** | Každý node má přístup ke Knowledge Base kontextu |

### Služby v systému

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Kotlin Server (:5500)                       │
│  AgentOrchestratorService → dispatch/resume                        │
│  OrchestratorStatusHandler → state transitions                     │
│  BackgroundEngine → safety-net polling + timestamp-based stuck detection │
│  KtorRpcServer → /internal/ endpoints (push receivers)             │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ REST (HTTP)
┌───────────────────────────▼─────────────────────────────────────────┐
│                   Python Orchestrator (:8090)                       │
│  FastAPI + LangGraph StateGraph + MongoDBSaver                     │
│  LLM Provider (litellm) → Ollama / Anthropic / OpenAI / Gemini    │
│  Job Runner → K8s Jobs (claude, kilo)                              │
│  Workspace Manager → .jervis/ files, CLAUDE.md, MCP config        │
│  Context Store → orchestrator_context (MongoDB)                    │
│  Correction Agent → whisper transcript corrections                  │
└───────────┬────────────┬───────────────┬────────────────────────────┘
            │            │               │
    ┌───────▼───┐  ┌─────▼─────┐  ┌──────▼──────┐
    │  Ollama   │  │    KB      │  │  K8s Jobs   │
    │  (GPU)    │  │  (:8080)   │  │  (agents)   │
    │ :11434    │  │  Weaviate  │  │  PVC shared  │
    └───────────┘  │  ArangoDB  │  └─────────────┘
                   └───────────┘
```

---

## 2. Architektura komunikace

### 2.1 Směry komunikace

```
Kotlin → Python:
  POST /orchestrate/stream     — fire-and-forget dispatch (vrací thread_id)
  POST /approve/{thread_id}    — fire-and-forget resume (po user approval)
  POST /cancel/{thread_id}     — zrušení orchestrace
  GET  /status/{thread_id}     — safety-net polling (60s interval)
  GET  /health                 — health check + busy flag
  POST /internal/compress-chat — async chat komprese (po dokončení)

Python → Kotlin:
  POST /internal/orchestrator-progress  — node transitions (updates stateChangedAt)
  POST /internal/orchestrator-status    — completion/error/interrupt
  POST /internal/correction-progress    — correction agent progress
```

### 2.2 Push-based model (primární)

Na každém přechodu mezi nody Python volá `kotlin_client.report_progress()`:

```python
await kotlin_client.report_progress(
    task_id=request.task_id,       # MongoDB ObjectId string
    client_id=request.client_id,
    node="respond",                # aktuální node
    message="Generating response...",
    goal_index=0, total_goals=1,
    step_index=0, total_steps=1,
)
```

Kotlin přijímá na `/internal/orchestrator-progress`:
1. Updates `stateChangedAt` timestamp on TaskDocument (for stuck detection)
2. Emituje `OrchestratorTaskProgress` event do UI via Flow subscription

Při dokončení/chybě/interruptu:

```python
await kotlin_client.report_status_change(
    task_id=task_id,
    thread_id=thread_id,
    status="done",           # "done" | "error" | "interrupted" | "cancelled"
    summary="...",           # pro "done"
    error="...",             # pro "error"
    interrupt_action="...",  # pro "interrupted": "clarify", "commit", "push"
    interrupt_description="...",
)
```

### 2.3 Safety-net polling (sekundární)

`BackgroundEngine.runOrchestratorResultLoop()` — každých 60 sekund:

1. Najde tasks ve stavu `PROCESSING`
2. Zkontroluje `orchestrationStartedAt` / `stateChangedAt` z TaskDocument v DB
3. Pokud čas od posledního update < 15 minut (`STUCK_THRESHOLD_MINUTES`) → OK, čeká
4. Pokud čas od posledního update > 15 minut → zavolá Python `GET /status/{thread_id}`
5. Pokud Python nedostupný → reset task na `QUEUED` (retry)
6. Pokud Python vrátí stav → deleguje na `OrchestratorStatusHandler`

### 2.4 UI notifikace

Kotlin → UI: via kRPC WebSocket Flow subscriptions
- `OrchestratorTaskProgress` — node transitions, progress bar
- `OrchestratorTaskStatusChange` — terminal stavy
- Queue status updates — kolik tasků čeká, co běží

---

## 3. Životní cyklus úlohy — kompletní flow

### 3.1 FOREGROUND (chat) — uživatel píše do chatu

```
1. UI: User types message
     ↓
2. AgentOrchestratorRpcImpl.sendChat()
     ↓
3. Saves ChatMessageDocument (role=USER, auto-sequence)
     ↓
4. Finds/creates TaskDocument for this client+project
   - Reuses existing task with orchestratorThreadId (for resume)
   - Or creates new FOREGROUND task (type=TaskTypeEnum.INSTANT)
     ↓
5. Task state → QUEUED
     ↓
6. BackgroundEngine.runExecutionLoop() picks up task
     ↓
7. AgentOrchestratorService.run(task, userInput)
   - Path A: task.orchestratorThreadId != null → resumePythonOrchestrator()
   - Path B: new task → dispatchToPythonOrchestrator()
     ↓
8. dispatchToPythonOrchestrator():
   a. Guard: countByState(PROCESSING) == 0
   b. pythonOrchestratorClient.isHealthy() == true
   c. Load project rules, environment, client/project names
   d. ChatHistoryService.prepareChatHistoryPayload(taskId) → chat context
   e. Build OrchestrateRequestDto (includes chat_history)
   f. POST /orchestrate/stream → returns {thread_id, stream_url}
   g. If 429 → return false (orchestrator busy, retry later)
   h. Task state → PROCESSING, save orchestratorThreadId
     ↓
9. Python _run_and_stream():
   a. asyncio.Semaphore acquire
   b. run_orchestration_streaming(request, thread_id)
   c. For each node event → kotlin_client.report_progress() (push callback)
   d. After completion → kotlin_client.report_status_change()
     ↓
10. Kotlin receives push callback:
    - /internal/orchestrator-progress → stateChangedAt update + UI emit
    - /internal/orchestrator-status → OrchestratorStatusHandler.handleStatusChange()
     ↓
11. OrchestratorStatusHandler:
    - "done" → handleDone():
      a. Emit final response to chat stream
      b. Save ASSISTANT ChatMessageDocument
      c. Check for inline messages (arrived during orchestration)
      d. If inline → re-queue to QUEUED (process new messages)
      e. If no inline → DONE (terminal)
      f. Async: ChatHistoryService.compressIfNeeded() (non-blocking)
    - "interrupted" → handleInterrupted():
      a. Emit clarification/approval to chat
      b. Save ASSISTANT ChatMessageDocument
      c. Task state → DONE (keeps orchestratorThreadId for resume)
    - "error" → handleError():
      a. Emit error to chat, save error message
      b. Task state → ERROR
```

### 3.2 BACKGROUND (scheduler, indexer) — stručně

```
TaskSchedulingService creates task with processingMode=BACKGROUND
  → BackgroundEngine picks up
  → Same dispatch flow
  → On interrupt: creates USER_TASK (notification in UI sidebar)
  → On done: deletes task (no chat UI)
```

### 3.3 Resume flow (approval/clarification)

```
1. User responds in chat (after interrupt)
     ↓
2. AgentOrchestratorRpcImpl detects existing task with orchestratorThreadId
     ↓
3. Task state → QUEUED
     ↓
4. BackgroundEngine picks up → AgentOrchestratorService.run()
     ↓
5. task.orchestratorThreadId != null → resumePythonOrchestrator()
     ↓
6. Determines: clarification vs approval
   - Clarification (no "Schválení:" prefix): approved=true, reason=user's answer
   - Approval: parse yes/no intent from user text
     ↓
7. POST /approve/{thread_id} with {approved, reason}
     ↓
8. Python approve() endpoint (main.py):
   - Fire-and-forget: creates asyncio task, registers in _active_tasks
   - Graph Agent path: compiled.ainvoke(Command(resume=resume_value), config)
   - Legacy path: resume_orchestration(thread_id, resume_value, chat_history)
   - On completion: report_status_change(status="done")
   - On error: report_status_change(status="error")
```

---

## 4. OrchestrateRequest — vstupní data

```python
class OrchestrateRequest(BaseModel):
    task_id: str                          # MongoDB ObjectId string
    client_id: str                        # ClientId string
    project_id: str | None                # ProjectId string (optional)
    client_name: str | None               # Human-readable client name
    project_name: str | None              # Human-readable project name
    workspace_path: str                   # "clients/{clientId}/{projectId}"
    query: str                            # User's original message
    agent_preference: str = "auto"        # "auto" | "claude" | "kilo"
    rules: ProjectRules                   # Branch naming, approval gates, cloud policies
    environment: dict | None              # Resolved environment context (infra, links)
    jervis_project_id: str | None         # JERVIS internal project for tracker ops
    chat_history: ChatHistoryPayload | None  # Conversation context (recent + summaries)
```

### ProjectRules

```python
class ProjectRules(BaseModel):
    branch_naming: str = "task/{taskId}"
    commit_prefix: str = "task({taskId}):"
    require_review: bool = False
    require_tests: bool = False
    require_approval_commit: bool = True    # interrupt() before commit
    require_approval_push: bool = True      # interrupt() before push
    allowed_branches: list[str]             # ["task/*", "fix/*"]
    forbidden_files: list[str]              # ["*.env", "secrets/*"]
    max_changed_files: int = 20
    auto_push: bool = False
    auto_use_anthropic: bool = False        # Cloud model auto-eskalace
    auto_use_openai: bool = False
    auto_use_gemini: bool = False
    max_openrouter_tier: str = "NONE"     # "NONE"/"FREE"/"PAID"/"PREMIUM" — OpenRouter fallback tier
```

### ChatHistoryPayload

```python
class ChatHistoryPayload(BaseModel):
    recent_messages: list[ChatHistoryMessage]  # Last 20 messages verbatim
    summary_blocks: list[ChatSummaryBlock]     # Compressed older blocks (max 15)
    total_message_count: int                   # Celkový počet zpráv v konverzaci
```

---

## 5. LangGraph StateGraph — graf orchestrace (legacy 14-node)

> **Poznámka:** Tento graf je zachován v `build_orchestrator_graph()` pro backward compatibility.
> Nový 7-nodový delegační graf je popsán v [sekci 18](#18-delegation-graph--přehled).
> Přepínání mezi grafy řídí feature flag `use_delegation_graph` (default: False = legacy).

### 5.1 Vizuální diagram

```
                                    ┌──────────────────────────────────┐
                                    │            ENTRY                 │
                                    └──────────────┬───────────────────┘
                                                   │
                                           ┌───────▼───────┐
                                           │    intake      │
                                           │ (classify +    │
                                           │  clarify)      │
                                           └───────┬────────┘
                                                   │
                                           ┌───────▼───────┐
                                           │ evidence_pack  │
                                           │ (KB + tracker  │
                                           │  artifacts)    │
                                           └───────┬────────┘
                                                   │
                                    ┌──────────────┼──────────────────┐
                                    │              │                  │
                          ┌─────────▼──┐   ┌──────▼──────┐   ┌──────▼──────┐
                          │  respond   │   │    plan     │   │ plan_epic / │
                          │ (ADVICE)   │   │(SINGLE_TASK)│   │   design    │
                          └─────┬──────┘   └──────┬──────┘   │(EPIC/GEN)  │
                                │                 │          └──────┬──────┘
                                │     ┌───────────┤                 │
                                │     │           │          ┌──────▼──────┐
                                │  ┌──▼────┐  ┌───▼────┐    │ select_goal │◄──┐
                                │  │respond│  │execute │    └──────┬──────┘   │
                                │  │(anal.)│  │ _step  │◄──┐      │          │
                                │  └──┬────┘  └───┬────┘   │  ┌───▼────┐    │
                                │     │           │        │  │plan    │    │
                                │     │      ┌────▼────┐   │  │_steps  │    │
                                │     │      │evaluate │   │  └───┬────┘    │
                                │     │      └────┬────┘   │      │         │
                                │     │           │        │      │         │
                                │     │    ┌──────┼────┐   │      └─────────┘
                                │     │    │      │    │   │
                                │     │ ┌──▼──┐   │ ┌──▼───▼──┐
                                │     │ │adv. │   │ │advance  │
                                │     │ │step │───┘ │_goal    │
                                │     │ └─────┘     └─────────┘
                                │     │
                          ┌─────▼─────▼────┐
                          │ git_operations  │
                          │ (commit/push    │
                          │  approval)      │
                          └───────┬─────────┘
                                  │
                          ┌───────▼─────────┐
                          │    finalize      │
                          │ (final report)   │
                          └───────┬─────────┘
                                  │
                              ┌───▼───┐
                              │  END  │
                              └───────┘
```

### 5.2 Routing logika

**Po evidence_pack** — `_route_by_category(state)`:
- `task_category == "advice"` → `respond`
- `task_category == "single_task"` → `plan`
- `task_category == "epic"` → `plan_epic`
- `task_category == "generative"` → `design`

**Po plan** — `route_after_plan(state)`:
- Všechny steps jsou `StepType.RESPOND` → `respond`
- Jinak → `execute_step` (coding loop)

**Po evaluate** — `next_step(state)`:
- `evaluation.acceptable == false` → `finalize` (skip zbylé kroky)
- `current_step_index + 1 < len(steps)` → `advance_step` → `execute_step`
- `current_goal_index + 1 < len(goals)` → `advance_goal` → `select_goal`
- Všechno hotovo → `git_operations`

**Po plan_epic / design** — `_route_after_epic_or_design(state)`:
- `error` nebo `final_result` nastaveno → `finalize` (zamítnuto)
- Jinak → `select_goal` (schváleno, spustit)

### 5.3 State persistence

- **Checkpointer**: `MongoDBSaver` z `langgraph-checkpoint-mongodb`
- **Databáze**: `jervis` (shared MongoDB database, collections `checkpoints` + `checkpoint_writes`)
- Automaticky ukládá stav po každém node
- Thread ID = `thread-{task_id}-{uuid[:8]}` — link mezi TaskDocument a checkpoint
- `recursion_limit = 150` (prevence infinite loops)

### 5.4 Delegation Graph (Multi-Agent System)

When `use_delegation_graph=True`, the orchestrator uses an alternative 7-node graph:

```
intake → evidence_pack → plan_delegations → execute_delegation → synthesize → finalize → END
                                                    ↑          │
                                                    └──────────┘
                                              (more pending delegations)
```

**Nodes:**

| Node | Purpose |
|------|---------|
| `plan_delegations` | LLM selects agents from AgentRegistry, builds ExecutionPlan (DAG of delegations) |
| `execute_delegation` | Dispatches DelegationMessage to agents, loops until all delegations complete |
| `synthesize` | Merges AgentOutput results, RAG cross-check, translates to response_language |

**Routing after execute_delegation:**
- If pending delegations remain → loop back to `execute_delegation`
- If all complete → proceed to `synthesize`

**Feature flag:** `settings.use_delegation_graph` (default: False). The `get_orchestrator_graph()` function returns either the legacy or delegation graph based on this flag.

> **Full reference:** See [section 18](#18-delegation-graph--přehled) for the complete delegation graph documentation including visual diagram, graph builder code, and feature flag switching.

---

## 6. OrchestratorState — kompletní stav

```python
class OrchestratorState(TypedDict, total=False):
    # --- Core task data ---
    task: dict                          # CodingTask.model_dump()
    rules: dict                         # ProjectRules.model_dump()
    environment: dict | None            # Resolved env context from Kotlin
    jervis_project_id: str | None       # JERVIS internal project for tracker ops

    # --- Task identity (top-level for easy access from all nodes) ---
    client_name: str | None
    project_name: str | None

    # --- Chat history (conversation context across sessions) ---
    chat_history: dict | None           # ChatHistoryPayload.model_dump()

    # --- Intake results ---
    task_category: str | None           # "advice" | "single_task" | "epic" | "generative"
    task_action: str | None             # "respond" | "code" | "tracker_ops" | "mixed"
    external_refs: list | None          # ["UFO-24", "https://..."]
    evidence_pack: dict | None          # EvidencePack.model_dump()
    needs_clarification: bool

    # --- Branch awareness ---
    target_branch: str | None           # Branch detected from user query (e.g. "feature/auth")

    # --- Clarification (from intake interrupt/resume) ---
    project_context: str | None         # KB project context (fetched in intake, branch-aware)
    task_complexity: str | None         # "simple" | "medium" | "complex" | "critical"
    clarification_questions: list | None
    clarification_response: dict | None # User's answers after resume
    allow_cloud_prompt: bool            # User explicitly requested cloud

    # --- Goals & steps ---
    goals: list                         # [Goal.model_dump(), ...]
    current_goal_index: int
    steps: list                         # [CodingStep.model_dump(), ...]
    current_step_index: int
    step_results: list                  # [StepResult.model_dump(), ...]
    goal_summaries: list                # [GoalSummary.model_dump(), ...] — cross-goal context

    # --- Results ---
    branch: str | None                  # Git branch name (after git_operations)
    final_result: str | None            # Final response text (set by respond/finalize)
    artifacts: list                     # Changed files
    error: str | None                   # Error message
    evaluation: dict | None             # Evaluation.model_dump() (last step)

    # --- Delegation system (multi-agent, new) ---
    execution_plan: dict | None          # ExecutionPlan — DAG of delegations
    delegation_states: dict              # {delegation_id: DelegationState}
    active_delegation_id: str | None     # Currently executing delegation
    completed_delegations: list          # Completed delegation IDs
    delegation_results: dict             # {delegation_id: result summary}
    response_language: str               # ISO 639-1 detected language (e.g. "cs", "en")
    domain: str | None                   # DomainType classification
    session_memory: list                 # Recent session memory entries
    _delegation_outputs: list            # Raw AgentOutput dicts for synthesis
```

> **Full reference:** See [section 20](#20-orchestratorstate--delegation-fields) for detailed field descriptions, usage per node, and initial state builder.

---

## 7. Nodes — detailní popis každého uzlu (legacy graph)

### 7.1 intake

**Soubor**: `app/graph/nodes/intake.py`
**Účel**: Klasifikace úlohy, detekce intentu, branch detection, povinná klarifikace

**Kroky**:
1. **Detekce target branch** z query (`_detect_branch_reference`) — hledá vzory:
   - Explicitní: `"on branch feature/auth"`, `"branch: main"`, `"na větvi develop"`
   - Branch prefixy: `feature/*`, `fix/*`, `hotfix/*`, `release/*`
   - Známé názvy: `main`, `master`, `develop`, `staging`, `production`
2. Fetch project context z KB (`fetch_project_context`, **branch-aware** — předá `target_branch`)
3. Build environment summary (pokud `state.environment` existuje)
4. Detekce cloud promptu (`detect_cloud_prompt` — keywords "use cloud", "použi cloud" atd.)
5. Build context section — client/project names + KB context (s branch info)
6. Recent conversation context (posledních 5 zpráv z `chat_history` pro klasifikaci)
7. LLM structured output — JSON s klasifikací

**LLM prompt vyžaduje**:
```json
{
  "category": "advice|single_task|epic|generative",
  "action": "respond|code|tracker_ops|mixed",
  "complexity": "simple|medium|complex|critical",
  "goal_clear": true,
  "external_refs": ["UFO-24"],
  "clarification_questions": [...]
}
```

**Povinná klarifikace**: Pokud `goal_clear == false` a `clarification_questions` neprázdné:
- Vytvoří `ClarificationQuestion` objekty
- Zavolá `interrupt()` — graf se zastaví
- Python pushne `status=interrupted, action=clarify`
- Kotlin: FOREGROUND → emitne do chatu + DONE; BACKGROUND/IDLE → USER_TASK
- Po resume: `clarification_response` obsahuje user's answers

**Output**: `task_category`, `task_action`, `external_refs`, `task_complexity`, `project_context`, `allow_cloud_prompt`, `needs_clarification`, **`target_branch`**

### 7.2 evidence_pack

**Soubor**: `app/graph/nodes/evidence.py`
**Účel**: Paralelní sběr kontextu z KB a trackeru

**Kroky**:
1. KB retrieve — task-relevant kontext (`prefetch_kb_context`)
2. External refs — pro každý ref (max 10) fetch z KB
3. Chat history summary — sestaví z `chat_history.summary_blocks`
4. Sestaví `EvidencePack`

**Output**: `evidence_pack` dict obsahující:
- `kb_results: [{source, content}]`
- `tracker_artifacts: [{ref, content}]`
- `chat_history_summary: str`
- `external_refs`, `facts`, `unknowns`

### 7.3 respond

**Soubor**: `app/graph/nodes/respond.py`
**Účel**: Přímá odpověď na ADVICE a SINGLE_TASK/respond dotazy

**BACKGROUND skip**: Pro `processing_mode == "BACKGROUND"` se celý respond node přeskočí (okamžitý return `{"final_result": "Background task completed."}`). BACKGROUND tasky nemají příjemce odpovědi — task se po dokončení smaže.

**Používá se pro** (FOREGROUND only): Meeting summaries, knowledge queries, planning advice, analýzy

**Context building**:
1. Task identity (client/project names)
2. Project context z KB
3. Evidence pack KB results + tracker artifacts
4. **Chat history — plný konverzační kontext** (summary blocks + recent messages)
5. User clarification (pokud proběhla)
6. Environment context

**LLM system prompt**: "You are Jervis, an AI assistant... Use Czech language." Obsahuje:
- Anti-halucinační pravidla (NIKDY netvrd že jsi provedl akci bez potvrzeného výsledku z nástroje)
- Korekce vs. příkazy (rozlišení uživatelské opravy od příkazu k akci)
- Explicitní capabilities (co UMÍŠ a NEUMÍŠ — git je READ-ONLY, žádné code changes, branch ops, deploy)

**Agentic tool-use loop** (max 8 iterations): LLM call → tool calls → execute → repeat.
Tools: `web_search`, `kb_search`, `kb_delete`, `store_knowledge`, `ask_user`, `create_scheduled_task`, + KB stats, git, filesystem, terminal tools.

**Tool loop detection** (5 signals): Sleduje historii `(tool_name, args_json)`. Signály: (1) Consecutive same — 2× identický tool+args. (2) Same tool 3×+ — jeden tool volán celkově 3+×. (3) **Alternating pair** — A→B→A→B pattern (detekuje ping-pong mezi dvěma tools, např. brain_search + brain_update). (4) Domain drift — 3 iterace s 3+ různými doménami bez průniku. (5) Excessive tools — 8+ distinct tools po 4+ iteracích. Při detekci se injektuje system message "STOP" a vynutí finální odpověď.

**ask_user tool**: Pokud agent potřebuje upřesnění od uživatele, zavolá `ask_user(question)`. Executor vyhodí `AskUserInterrupt`, respond node zachytí → volá `interrupt()` → graf se zastaví → uživatel odpoví v chatu → graf pokračuje s odpovědí jako tool result. Viz [§13 Approval Flow](#13-approval-flow--interruptresume-mechanismus).

**Token streaming (background tasks)**: Finální odpověď (po poslední LLM iteraci bez tool_calls) se streamuje do UI po malých chuncích (12 znaků) přes `kotlin_client.emit_streaming_token()` → Kotlin `/internal/streaming-token` endpoint (legacy no-op). **Pozn.:** Foreground chat nyní používá přímý SSE stream přes `IChatService.subscribeToChatEvents()` → `ChatRpcImpl` → `PythonChatClient` (viz architecture.md §16).

**Output**: `final_result` — odpověď v češtině

### 7.4 plan

**Soubor**: `app/graph/nodes/plan.py`
**Účel**: Plánování pro SINGLE_TASK (routing dle task_action)

**Routing dle action**:

| task_action | Co dělá | Výstup |
|-------------|---------|--------|
| `respond` | Vytvoří single respond step | 1 goal, 1 step (StepType.RESPOND) |
| `code` | LLM dekomponuje na goals + steps | N goals, M steps (StepType.CODE) |
| `tracker_ops` | LLM plánuje tracker operace | 1 goal, N steps (StepType.TRACKER) |
| `mixed` | LLM plánuje mix respond+code+tracker | 1 goal, N steps (mixed types) |

**Context pro LLM**: client/project identity, project context, KB results, clarification, **key decisions z chat history summaries** (posledních 10)

**`_plan_coding_task()`**: Volá LLM pro dekompozici → goals se seznamem dependencies

**`route_after_plan()`**: Pokud všechny steps jsou RESPOND → `respond` node; jinak → `execute_step`

### 7.5 decompose (coding pipeline)

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Alternativní dekompozice pro EPIC/GENERATIVE path (task → goals)

**Rozdíl oproti plan**: `decompose` je standalone node pro staré flow; `plan._plan_coding_task()` dělá totéž ale v rámci plan nodu.

### 7.6 select_goal

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Výběr aktuálního cíle s validací závislostí

**Logika**:
1. Vezme `goals[current_goal_index]`
2. Zkontroluje `goal.dependencies` proti `completed_ids` (z goal_summaries)
3. Pokud nesplněné závislosti → pokusí se swapnout s pozdějším goal bez závislostí
4. Pokud swap nelze → pokračuje best-effort

### 7.7 plan_steps

**Soubor**: `app/graph/nodes/coding.py`
**Účel**: Vytvoření execution steps pro aktuální goal

**Context**: Cross-goal kontext (goal_summaries — co bylo dřív hotovo, změněné soubory)

**LLM output**: JSON steps s konkrétními instrukcemi pro coding agenta, soubory, agent type

**Agent selection**: `select_agent(complexity, preference)`:
- All complexities → Claude CLI (default)
- Kilo → only if explicitly configured
- Manual preference override: `agent_preference != "auto"`

### 7.8 execute_step

**Soubor**: `app/graph/nodes/execute.py`
**Účel**: Provedení jednoho kroku (dispatch dle step type)

**Tři typy**:

#### StepType.RESPOND
- LLM + KB context → přímá odpověď
- Nastaví `final_result` i `step_results`

#### StepType.CODE
1. Pre-fetch KB context pro step (soubory, task description)
2. `workspace_manager.prepare_workspace()` → zapisuje instrukce do `.jervis/`
3. `job_runner.run_coding_agent()` → K8s Job
4. Čte výsledek z `.jervis/result.json`
5. Přidá `StepResult` do `step_results`

#### StepType.TRACKER
- Volá Kotlin internal API endpointy:
  - `POST /internal/tracker/create-issue`
  - `POST /internal/tracker/update-issue`
- Pro každou operaci (create/update/comment)

### 7.9 evaluate

**Soubor**: `app/graph/nodes/evaluate.py`
**Účel**: Evaluace výsledku posledního kroku

**Kontroly**:
1. Step success — pokud `last_result.success == false` → FAILED
2. Forbidden files — `fnmatch` proti `rules.forbidden_files` → BLOCKED
3. Max file count — `len(changed_files) > rules.max_changed_files` → WARNING

**Výstup**: `Evaluation(acceptable, checks)` — `acceptable = not any(BLOCKED or FAILED)`

### 7.10 advance_step / advance_goal

**advance_step**: Jednoduše `current_step_index += 1`

**advance_goal**:
1. `current_goal_index += 1`
2. Build `GoalSummary` z recent step results
3. Přidá do `goal_summaries` (cross-goal context pro další goals)

### 7.11 git_operations

**Soubor**: `app/graph/nodes/git_ops.py`
**Účel**: Git commit/push s approval gatami

**Flow**:
1. Zkontroluje, zda existují úspěšné code changes (changed_files)
2. Pokud ne → return `{branch: None}`
3. **Commit approval gate** (`require_approval_commit`):
   - `interrupt()` → graf se zastaví
   - User schválí/zamítne
4. `workspace_manager.prepare_git_workspace()` → přepíše CLAUDE.md na git-permissive
5. Deleguje commit na Claude agenta (K8s Job s `ALLOW_GIT=true`):
   - Instructions: commit message s prefixem, stage only relevant files, NO push
6. **Push approval gate** (`require_approval_push`, pokud `auto_push == true`):
   - `interrupt()` → user approval
   - Deleguje push na Claude agenta

### 7.12 finalize

**Soubor**: `app/graph/nodes/finalize.py`
**Účel**: Generování finálního reportu

**BACKGROUND skip**: Pro `processing_mode == "BACKGROUND"` se summary generation přeskočí (žádný LLM call). KB outcome ingestion a Memory Agent flush stále běží.

**Logika**:
1. **BACKGROUND** → skip summary, keep `final_result` from respond (or default)
2. Pokud `final_result` už nastaveno (respond node) → skip
3. Sestaví kontext: client/project, branch, artifacts, **conversation stats z chat_history**, **key decisions**
4. LLM generuje český souhrn (max 3-5 vět)
5. Fallback: strukturovaný souhrn bez LLM

---

## 8. LLM Provider — model a volání

**Soubor**: `app/llm/provider.py`

### 8.1 Tiery modelů

Fixní `num_ctx` na GPU — žádná dynamická selekce. GPU1 = 48k, GPU2 = 32k (s embedding modelem).

| Tier | Model | Context | Kdy |
|------|-------|---------|-----|
| `LOCAL_STANDARD` | `ollama/qwen3-coder-tool:30b` | 48k | Všechny lokální úlohy (default) |
| `LOCAL_COMPACT` | `ollama/qwen3-coder-tool:30b` | 32k | Pojistka pro GPU2 |
| `CLOUD_REASONING` | `anthropic/claude-sonnet-4-5` | - | Architektura, design (auto=anthropic) |
| `CLOUD_CODING` | `openai/gpt-4o` | - | Code editing (auto=openai) |
| `CLOUD_PREMIUM` | `anthropic/claude-opus-4-6` | - | Kritické úlohy |
| `CLOUD_LARGE_CONTEXT` | `google/gemini-2.5-pro` | 1M | Ultra-large context (auto=gemini) |
| `CLOUD_OPENROUTER` | dle fronty | varies | OpenRouter fallback při busy GPU |

### 8.2 Routing — `select_route()`

**Soubor**: `app/llm/openrouter_resolver.py`

Nahrazuje dynamickou eskalaci. Rozhoduje local vs cloud dle GPU stavu a `maxOpenRouterTier`:

```python
async def select_route(
    estimated_tokens: int,
    max_tier: str = "NONE",       # "NONE" / "FREE" / "PAID" / "PREMIUM"
    priority: str = "CRITICAL",
) -> Route:
```

Logika:
1. `max_tier == "NONE"` → vždy local (čeká na GPU)
2. `estimated_tokens > 48k` → LARGE_CONTEXT fronta (pokud `max_tier >= PAID`)
3. GPU volná → local (`LOCAL_STANDARD`, 48k)
4. GPU busy → iteruj fronty dle `max_tier`:
   - Vždy zkus FREE frontu první
   - `max_tier >= PAID` → zkus PAID
   - `max_tier >= PREMIUM` → zkus PREMIUM
5. Fallback: čekej na local GPU

### 8.3 OpenRouter fronty

4 fronty konfigurované v OpenRouter nastavení:

| Fronta | Modely (default) | Kdy |
|--------|-------------------|-----|
| `FREE` | p40 (local), qwen3-30b:free | GPU busy, maxTier >= FREE |
| `PAID` | p40 (local), claude-haiku-4, gpt-4o-mini | maxTier >= PAID |
| `PREMIUM` | p40 (local), claude-sonnet-4, o3-mini | maxTier >= PREMIUM |
| `LARGE_CONTEXT` | gemini-2.5-pro (1M), claude-sonnet-4 (200k) | estimated > 48k, maxTier >= PAID |

### 8.4 Cloud eskalace (`llm_with_cloud_fallback`)

**Soubor**: `app/graph/nodes/_helpers.py`

Pro LangGraph nody (ne chat/background handler):
```
1. context_tokens > 49_000? → rovnou cloud (pokud auto-enabled)
2. Pokus o lokální model (LOCAL_STANDARD, 48k)
3. Lokální selhal → cloud eskalace:
   a. auto_providers neprázdné? → auto-escalate (bez ptaní)
   b. žádný provider auto? → interrupt() (zeptat se usera)
   c. user zamítl? → RuntimeError
```

**Auto-providers**: `rules.auto_use_anthropic/openai/gemini` + `rules.max_openrouter_tier != "NONE"` → openrouter

### 8.5 Streaming + token-arrival timeout

```python
async def _iter_with_timeout(stream):
    # asyncio.wait_for per chunk — TOKEN_TIMEOUT_SECONDS = 300
    async for chunk in stream:
        yield chunk  # each chunk must arrive within timeout
    # raises TokenTimeoutError if no token for 5 min
```

- **Streaming** (bez tool calls): per-chunk token-arrival timeout. Pokud tokeny přicházejí → čeká neomezeně. Pokud 5 min bez tokenu → `TokenTimeoutError`.
- **Blocking** (tool calls — litellm omezení): **300s** timeout pro všechny tiery (fixní num_ctx, žádný CPU-spill)
- Cloud tiery: **300s** (rychlé API)

---

## 9. Knowledge Base integrace

**Soubor**: `app/kb/prefetch.py`

### 9.1 Dva typy KB fetche

**`prefetch_kb_context()`** — pro coding agenty (zapisuje se do `.jervis/kb-context.md`):
1. Relevantní znalosti pro task (5 results, confidence 0.7, graph expansion)
2. Coding conventions (3 results, client-level)
3. Architecture decisions (3 results, project-level)
4. File-specific knowledge (2 results per file, max 3 soubory)

**`fetch_project_context(target_branch=...)`** — pro orchestrátor (intake, decompose):
1. **Repository & branch structure** — graph search pro `repository` a `branch` node types
   - Zobrazí available branches s `← TARGET` marker pro detekovanou branch
2. **Project structure** — files + classes (branch-scoped pokud `target_branch` specifikován)
   - Používá `_graph_search_branch_aware()` s `branchName` query parametrem
   - File/class nodes annotovány `[branch: main]` pokud není target branch fixní
3. Architecture & modules (5 results, graph expansion)
4. Coding conventions (3 results, client-level)
5. Task-relevant context (5 results, confidence 0.6, graph expansion)

### 9.2 KB API volání

```python
POST {kb_url}/api/v1/search
Body: {
    "query": "...",
    "client_id": "...",
    "project_id": "...",
    "max_results": 5,
    "min_confidence": 0.7,
    "expand_graph": true,
}
```

### 9.3 Runtime KB přístup (HTTP MCP)

Coding agenti mají runtime přístup přes unified HTTP MCP server (`service-mcp:8100`):
- `kb_search(query, client_id, project_id, max_results)` — full-text search + graph
- `kb_search_simple(query)` — quick RAG-only search
- `kb_traverse(start_node, direction, max_hops)` — graph traversal
- `kb_graph_search(query, node_type, limit)` — graph node search
- `kb_get_evidence(node_key)` — supporting RAG chunks for a node
- `kb_resolve_alias(alias)` — entity alias resolution
- `kb_store(content, kind, metadata)` — store new knowledge

Agents connect via HTTP (`.claude/mcp.json` → `type: "http"`, `url: "http://jervis-mcp:8100/mcp"`).

### 9.4 Proaktivní Thought Map traversal

> Spec: [thought-map-spec.md](thought-map-spec.md)

Místo reaktivního `kb_search` (LLM musí zavolat tool) se kontext z Thought Map **předloží automaticky před LLM callem**.

**Flow:**
1. `handler_context.py: load_runtime_context()` volá `prefetch_thought_context(query, client_id)`
2. `thought_prefetch.py` → `POST /thoughts/traverse` (spreading activation v ArangoDB)
3. KB vrátí aktivované ThoughtNodes + anchored KnowledgeNodes
4. Formátovaný kontext se injektuje do system promptu (~5000 tokenů)
5. Po odpovědi LLM → fire-and-forget:
   - `reinforce_activated_thoughts()` → Hebbian reinforcement
   - `extract_and_store_response_thoughts()` → pattern-based extraction nových thoughts

**Klíčové soubory:**
- `app/kb/thought_prefetch.py` — proaktivní traversal
- `app/kb/thought_update.py` — post-response reinforcement + extraction
- `app/chat/handler_context.py` — integrace do `load_runtime_context()`
- `app/agent/sse_handler.py` — fire-and-forget post-response update

`kb_search` tool zůstává jako fallback pro explicitní dotazy.

---

## 10. K8s Job Runner — spouštění coding agentů

**Soubor**: `app/agents/job_runner.py`

### 10.1 Agent typy a limity

| Agent | Image | Concurrent limit | Timeout |
|-------|-------|-------------------|---------|
| claude | `jervis-claude` | 2 | 1800s |
| kilo | `jervis-kilo` | 2 | 1800s |

### 10.2 K8s Job spec

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: jervis-{agent_type}-{task_id[:12]}
  namespace: jervis
  labels:
    app: jervis-agent
    agent-type: {agent_type}
    task-id: {task_id}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 300
  template:
    spec:
      serviceAccountName: jervis-coding-agent  # ← when environment has namespaces
      automountServiceAccountToken: true
      containers:
        - name: agent
          image: {registry}/jervis-{image_name}:latest
          env:
            - TASK_ID, CLIENT_ID, PROJECT_ID
            - WORKSPACE_PATH (absolut na PVC)
            - OLLAMA_URL, KNOWLEDGEBASE_URL
            - ALLOW_GIT (true/false)
            - ANTHROPIC_API_KEY (pro Claude agenta)
            - KUBE_NAMESPACES (comma-separated, when environment available)
          volumeMounts:
            - /opt/jervis/data (PVC shared s orchestrátorem)
          resources:
            requests: 256Mi memory, 250m CPU
            limits: 1Gi memory, 1000m CPU
      restartPolicy: Never
```

**kubectl access:** Agent image includes `kubectl` binary. When `KUBE_NAMESPACES` is set, the ServiceAccount `jervis-coding-agent` (from `jervis` namespace) has a dynamically created Role+RoleBinding in each target namespace granting full resource access. RBAC is created by `EnvironmentK8sService.ensureAgentRbac()` during environment provisioning.

### 10.3 Lifecycle (Non-blocking Async Dispatch)

Coding agent dispatch je **non-blocking** — orchestrátor nepotí na dokončení jobu, ale použije LangGraph `interrupt()` pattern:

```
execute_step() → dispatch_coding_agent() → notify_agent_dispatched() → interrupt()
    ↓ graph checkpoints, thread released (task state → CODING)
AgentTaskWatcher._poll_once() → get_job_status() → [succeeded] → collect_result()
    → POST /internal/tasks/{id}/agent-completed
    → resume_orchestration_streaming(thread_id, result)
    ↓ graph resumes from interrupt, processes result
```

**Krokový detail:**

1. `prepare_workspace()` — zapíše instrukce do `.jervis/`
2. `dispatch_coding_agent()` — vytvoří K8s Job, **vrátí okamžitě** s `{job_name, agent_type}`
3. `notify_agent_dispatched()` — POST na Kotlin, nastaví task state na `CODING`
4. `interrupt({"type": "waiting_for_agent", ...})` — graph se checkpointne, thread uvolněn
5. Agent čte `.jervis/instructions.md`, pracuje v workspace
6. Agent zapíše `.jervis/result.json` s výsledkem
7. `AgentTaskWatcher` polluje CODING tasks (každých 10s)
8. Watcher detekuje completed job → `collect_result()` čte `result.json`
9. Watcher notifikuje Kotlin (→ PROCESSING) a resumuje orchestraci
10. Graph pokračuje od `interrupt()`, zpracuje result

**Klíčové soubory:**
- `app/agents/job_runner.py` — `dispatch_coding_agent()`, `get_job_status()`, `collect_result()`
- `app/agent_task_watcher.py` — background polling service
- `app/graph/nodes/execute.py` — dispatch + interrupt pattern
- `app/graph/nodes/git_ops.py` — same pattern for commit/push

### 10.4 Result format

```json
{
  "taskId": "69aedeb939184882a4a8609c",
  "success": true,
  "summary": "Implemented feature X...",
  "agentType": "claude",
  "changedFiles": ["src/main.kt", "src/test.kt"],
  "branch": "bugfix/UFO-4166",
  "timestamp": "2026-03-09T14:52:48.000Z"
}
```

The `branch` field is critical for MR/PR creation — `AgentTaskWatcher` uses it to call `create_merge_request()` after job completion. Written by `entrypoint-job.sh` (via `_JERVIS_BRANCH` env var) and `claude_sdk_runner.py` (via `_get_current_branch()`).

### 10.5 Direct Coding Task Completion Flow

Tasks dispatched from chat (`sourceUrn="chat:coding-agent"`) follow a two-step completion in `AgentTaskWatcher`:

1. `POST /internal/tasks/{id}/agent-completed` → CODING → PROCESSING
2. `POST /orchestrate/v2/report-status-change` → PROCESSING → DONE
3. If `result.branch` exists → create MR/PR + dispatch code review (async)
4. Memory map TASK_REF vertex → COMPLETED

Fix tasks (`sourceUrn="code-review-fix:{id}"`) reuse existing MR URL, don't create new MR.

See `docs/architecture.md` § "Coding Agent → MR/PR → Code Review Pipeline" for full flow.

---

## 11. Workspace Manager — příprava prostředí

**Soubor**: `app/agents/workspace_manager.py`

### 11.1 Struktura `.jervis/` adresáře

```
workspace/
├── .jervis/
│   ├── instructions.md       # Step instructions pro agenta
│   ├── task.json              # Task metadata (id, client, project, type)
│   ├── kb-context.md          # Pre-fetched KB context
│   ├── environment.json       # Raw environment data
│   └── environment.md         # Rendered environment context
├── .claude/
│   └── mcp.json               # MCP server config (Claude only)
├── CLAUDE.md                  # Claude agent instructions + KB tools
└── (agent-specific configs if needed)
```

### 11.2 Claude MCP config

```json
{
  "mcpServers": {
    "jervis": {
      "type": "http",
      "url": "http://jervis-mcp:8100/mcp",
      "headers": {"Authorization": "Bearer <token>"}
    }
  }
}
```

### 11.3 CLAUDE.md pro coding

Obsahuje:
- Název projektu, klient, popis úlohy
- Forbidden actions: NIKDY nepoužívej git příkazy
- KB tools: 7 nástrojů pro runtime KB přístup (+ kb_resolve_alias)
- Environment tools: 6 nástrojů pro K8s namespace inspekci
- kubectl access section (when environment available): assigned namespace(s), `environment_sync_from_k8s` tool reference
- Pravidla: write result to `.jervis/result.json`

### 11.4 CLAUDE.md pro git delegaci

Permisivní verze — `ALLOW_GIT=true`:
- Povoleno: git add, commit, push, branch
- Zakázáno: force push, reset --hard, rebase
- Used: `git_operations` node deleguje commit/push na Claude agenta

---

## 12. Context Store — hierarchické úložiště

**Soubor**: `app/context/context_store.py`

### 12.1 MongoDB kolekce

```
orchestrator_context:
  _id: ObjectId
  task_id: str
  scope: "step" | "goal" | "epic" | "task" | "agent_result"
  scope_key: "goal/0/step/1"
  summary: str (krátký pro list)
  detail: dict (plný pro on-demand fetch)
  created_at: datetime
  expire_at: datetime (30 days TTL)
```

### 12.2 Scope hierarchy

```
task_id
├── scope=step, key=goal/0/step/0
├── scope=step, key=goal/0/step/1
├── scope=goal, key=goal/0
├── scope=step, key=goal/1/step/0
├── scope=goal, key=goal/1
├── scope=agent_result, key=goal/0/step/0
└── scope=task, key=final
```

### 12.3 Použití

- `save_step_result()` — po execute_step
- `save_goal_summary()` — po advance_goal
- `assemble_step_context()` — pro execute_step (prev step, KB context)
- `assemble_evaluate_context()` — pro evaluate (result, rules)
- `assemble_epic_review_context()` — pro epic review (goal names + status only)

---

## 13. Approval Flow — interrupt/resume mechanismus

### 13.1 Interrupt body

```python
interrupt({
    "type": "approval_request" | "clarification",
    "action": "clarify" | "commit" | "push" | "cloud_model" | "epic_plan" | "generative_design",
    "description": "Human-readable popis",
    "task_id": task.id,
    # + action-specific fields (branch, changed_files, cloud_tier, goals_count, ...)
})
```

### 13.2 Kde se interrupt volá

| Node | Action | Kdy |
|------|--------|-----|
| `intake` | `clarify` | Goal is unclear → mandatory clarification |
| `respond` | `clarify` | Agent needs user input (via `ask_user` tool) |
| `git_operations` | `commit` | Before commit (if `require_approval_commit`) |
| `git_operations` | `push` | Before push (if `require_approval_push && auto_push`) |
| `_helpers.py` | `cloud_model` | Local LLM failed, cloud not auto-enabled |
| `plan_epic` | `epic_plan` | Show wave structure for approval |
| `design` | `generative_design` | Show generated plan for approval |

### 13.3 Resume value

```python
resume_value = {
    "approved": True/False,
    "reason": "user's text input",
    "modification": None,  # reserved
}
```

Pro clarification: `approved=True` vždy, `reason` = user's answer text

### 13.4 State po interrupt

- FOREGROUND task: `DONE` (keeps `orchestratorThreadId`)
  - Clarification/approval emitováno do chatu jako ASSISTANT message
  - User odpovídá přímo v chatu → task reused → resume
- BACKGROUND task: `USER_TASK` (notification v sidebar)
  - User responds in sidebar → new QUEUED

---

## 14. Concurrency Control — multi-orchestration

### 14.1 Tři vrstvy

**Kotlin (early guard)**:
```kotlin
val orchestratingCount = taskRepository.countByState(TaskStateEnum.PROCESSING)
if (orchestratingCount >= maxConcurrent) return false  // skip dispatch
```

**Python**: Žádný umělý limit — orchestrátor zpracovává neomezeně souběžných požadavků. Concurrency řídí router (GPU queue) a Kotlin (Kotlin early guard).

**Per-agent type limits** (K8s Job level):
```python
MAX_CONCURRENT = {"claude": 2, "kilo": 2}
```

### 14.2 Non-blocking dispatch model

Async agent dispatch — Python vrátí HTTP 200 okamžitě, K8s Job běží na pozadí:

```
Thread 1: dispatch → interrupt → thread free
Thread 2: dispatch → interrupt → thread free
  [oba K8s Jobs běží paralelně, AgentTaskWatcher je resumuje]
Thread 1: [watcher resumuje] → process result → done
Thread 2: [watcher resumuje] → process result → done
```

### 14.3 Task state machine

```
NEW → PROCESSING (dispatch to Python)
    → CODING (coding agent K8s Job dispatched)
    → PROCESSING (agent completed, graph resumed)
    → DONE / FAILED
```

### 14.4 Multi-pod concurrency

Orchestrátor nemá globální lock — více podů může zpracovávat požadavky souběžně. Kotlin BackgroundEngine zajišťuje, že na GPU jde vždy jen jeden background task (atomic claim přes DB). Chat (foreground) nemá žádné omezení počtu souběžných požadavků.

---

## 15. Stuck detection a liveness

### 15.1 Timestamp-based stuck detection (Kotlin)

Task-level stuck detection uses DB timestamps instead of in-memory heartbeat trackers (OrchestratorHeartbeatTracker and CorrectionHeartbeatTracker have been removed):

- **`orchestrationStartedAt`**: Set when task enters `PROCESSING`
- **`stateChangedAt`**: Updated on each `/internal/orchestrator-progress` callback
- **`STUCK_THRESHOLD_MINUTES = 15`**: If no progress for 15 min, task is considered stuck

This approach survives server restarts (timestamps are in MongoDB) and eliminates in-memory state synchronization issues.

### 15.2 BackgroundEngine stuck detection

```
every 60s:
  for each task in PROCESSING:
    lastUpdate = task.stateChangedAt ?: task.orchestrationStartedAt
    if lastUpdate == null:
      // Task just dispatched, wait
      continue
    if Duration.between(lastUpdate, now) < 15 minutes:  // STUCK_THRESHOLD_MINUTES
      // Recent activity — all good
      continue
    // Stuck → poll Python directly
    try:
      status = pythonClient.getStatus(threadId)
      orchestratorStatusHandler.handleStatusChange(...)
    catch (connectionError):
      // Python unreachable → reset task for retry
      task.state = QUEUED
      task.orchestratorThreadId = null
```

### 15.3 LLM token-arrival timeout (Python)

```python
TOKEN_TIMEOUT_SECONDS = 300  # 5 min

async def _iter_with_timeout(stream):
    aiter = stream.__aiter__()
    while True:
        chunk = await asyncio.wait_for(aiter.__anext__(), timeout=TOKEN_TIMEOUT_SECONDS)
        yield chunk
    # raises TokenTimeoutError on timeout
```

Note: This is a read timeout on the LLM stream (token arrival monitoring), separate from task-level stuck detection above.

### 15.3a MeetingContinuousIndexer stuck detection

Pipeline 5 stuck detection for correction tasks uses `stateChangedAt` timestamp:
- **`STUCK_CORRECTING_THRESHOLD_MINUTES = 15`**: Meetings in CORRECTING state for >15 min without progress are reset
- Replaces the former `CorrectionHeartbeatTracker` in-memory approach

### 15.3b Python crash handler

The Python orchestrator registers a crash handler (`atexit` + `SIGTERM` signal handler) that sends best-effort error callbacks for all active tasks on unexpected shutdown. This ensures Kotlin's stuck detection doesn't need to wait the full 15-min threshold when the Python process crashes.

### 15.4 AgentTaskWatcher — K8s Job monitoring

**Soubor**: `app/agent_task_watcher.py`

Background asyncio service polling for CODING tasks:

```python
class AgentTaskWatcher:
    async def _poll_once(self):
        # 1. GET /internal/tasks/by-state?state=CODING
        # 2. For each task: check K8s Job status via job_runner.get_job_status()
        # 3. On completion: collect result, notify Kotlin, resume orchestration
```

- Poll interval: `agent_watcher_poll_interval` (default 10s)
- Job timeout determined by `agent_timeout_*` per agent type (e.g. 1800s claude)
- Started/stopped in `main.py` lifespan
- Survives pod restarts — all state in MongoDB (TaskDocument + LangGraph checkpoints)

**Internal Kotlin endpoints used by watcher:**
- `GET /internal/tasks/by-state?state=CODING` — fetch waiting tasks
- `POST /internal/tasks/{taskId}/agent-completed` — mark agent done, transition to PROCESSING
- `POST /internal/tasks/{taskId}/agent-dispatched` — mark task as CODING (called from graph nodes)

---

## 16. Chat Context Persistence — paměť agenta

### 16.1 Tři vrstvy

| Vrstva | Storage | Max tokenů | Obsah |
|--------|---------|------------|-------|
| Recent messages | In request (verbatim) | ~2000 | Last 20 messages as-is |
| Rolling summaries | MongoDB `chat_summaries` | ~1500 | LLM-compressed blocks po 20 zprávách |
| Total count | In request | - | Celkový počet zpráv |

### 16.2 Příprava (Kotlin → Python)

`ChatHistoryService.prepareChatHistoryPayload(taskId)`:
1. Load all `ChatMessageDocument` for task (ordered by sequence)
2. Take last 20 → `recent_messages` (verbatim)
3. Load `ChatSummaryDocument` → take last 15 → `summary_blocks`
4. Return `ChatHistoryPayloadDto`

### 16.3 Komprese (async po dokončení)

`ChatHistoryService.compressIfNeeded(taskId, clientId)`:
1. Count total messages; if ≤ 20 → skip
2. Find last summarized sequence
3. Messages before recent window, after last summary → unsummarized
4. If unsummarized ≥ 20 → POST `/internal/compress-chat` (Python LLM)
5. Store `ChatSummaryDocument`

### 16.4 Summary trust level

Souhrny (rolling summaries) mohou obsahovat halucinace z dřívějších LLM odpovědí. Aby se zabránilo self-reinforcing loop (špatná odpověď → uložena → komprimována → znovu použita jako fakt):

1. **Context assembler** (`context.py`) označuje souhrny prefixem `[Neověřený souhrn]` a přidává varování k celé sekci souhrnů.
2. **System prompt** (`system_prompt.py`) obsahuje explicitní instrukci "KRITICKÁ DISTANCE K HISTORII" — LLM nesmí přebírat fakta ze souhrnů bez ověření přes tools.

### 16.5 Použití v nodech

| Node | Co používá | Jak |
|------|-----------|-----|
| intake | `recent_messages[-5:]` | Klasifikace kontextu ("continuation" vs "new topic") |
| respond | Full history (summaries + recent) | Kompletní konverzační kontext v LLM promptu |
| evidence | `summary_blocks` | Populate `EvidencePack.chat_history_summary` |
| plan | `summary_blocks[].key_decisions` | Key decisions pro plánování (posledních 10) |
| finalize | `total_message_count` + key decisions | Konverzační stats ve finálním reportu |

---

## 17. Correction Agent — korekce přepisů

**Soubor**: `app/whisper/correction_agent.py`

Transcript correction agent sdílí orchestrátor service kvůli přístupu k Ollama GPU.

### 17.1 Endpointy

| Endpoint | Účel |
|----------|------|
| `POST /correction/submit` | Uloží korekční pravidlo do KB |
| `POST /correction/correct` | Opraví segmenty pomocí KB + Ollama |
| `POST /correction/list` | Výpis pravidel pro klienta |
| `POST /correction/delete` | Smaže pravidlo z KB |
| `POST /correction/instruct` | Re-korekce dle NL instrukce |
| `POST /correction/correct-targeted` | Cílená korekce retranskripcí |
| `POST /correction/answer` | Uloží odpovědi na otázky jako pravidla |

### 17.2 Klíčové parametry

- Model: `qwen3-coder-tool:30b` (non-reasoning)
- CHUNK_SIZE: 20 segmentů na LLM call
- OUTPUT_BUDGET: 8192 tokenů
- GPU_CTX_CAP: 49152 (nad tím spill do CPU RAM)
- LLM liveness: 300s (5 min bez tokenu = dead)
- Stuck detection: `STUCK_CORRECTING_THRESHOLD_MINUTES = 15` (timestamp-based, via `stateChangedAt`)
- Korekce uloženy jako KB chunks s `kind="transcript_correction"`

---

## 18. Delegation Graph — přehled

> **Status:** Feature-flagged (`use_delegation_graph = False` default). Nový multi-agent delegační systém běží vedle legacy 14-node grafu. Přepíná se přes `get_orchestrator_graph()`.

### 18.1 Motivace

Stávající orchestrátor je monolitický 14-nodový graf optimalizovaný pro coding úlohy (4 kategorie: ADVICE, SINGLE_TASK, EPIC, GENERATIVE). Nový delegační systém přestavuje orchestrátor na **univerzálního multi-agent asistenta** — nejen programování, ale i projekt management, komunikace, právní analýza, DevOps, bezpečnost a další domény.

**Klíčová změna:** Místo hardcoded cest pro 4 kategorie máme univerzální delegační engine. `plan_delegations` node vybírá z registru 19+ specialist agentů a sestavuje DAG (directed acyclic graph) delegací. Agenti mohou volat sub-agenty rekurzivně (max depth 4).

### 18.2 Graf — vizuální diagram

```
                    ┌──────────┐
                    │  ENTRY   │
                    └────┬─────┘
                         │
                  ┌──────▼──────┐
                  │   intake     │  (reused from legacy, extended with
                  │              │   language detection, session memory)
                  └──────┬──────┘
                         │
                  ┌──────▼──────┐
                  │evidence_pack │  (reused from legacy)
                  └──────┬──────┘
                         │
              ┌──────────▼──────────┐
              │  plan_delegations   │  NEW — LLM-driven agent selection
              │  (reads registry,   │  Outputs: ExecutionPlan (delegations
              │   procedural mem,   │           + parallel groups)
              │   session memory)   │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │ execute_delegation  │◄─┐  NEW — dispatches via DAGExecutor
              │ (parallel groups,   │  │  Progress reporting to Kotlin
              │  agent dispatch)    │  │  Full results → context_store
              └──────────┬──────────┘  │
                         │             │
                    ┌────▼────┐        │
                    │ more    │────yes──┘  (loop back if pending delegations)
                    │pending? │
                    └────┬────┘
                         │ no
              ┌──────────▼──────────┐
              │    synthesize       │  NEW — merges AgentOutput results
              │ (LLM combine,      │  RAG cross-check vs KB
              │  translate to       │  Outputs: final_result
              │  response_language) │
              └──────────┬──────────┘
                         │
                  ┌──────▼──────┐
                  │  finalize    │  (reused from legacy, extended)
                  └──────┬──────┘
                         │
                    ┌────▼────┐
                    │   END   │
                    └─────────┘
```

### 18.3 Porovnání s legacy grafem

| Aspekt | Legacy (14 nodů) | Delegation (7 nodů) |
|--------|------------------|---------------------|
| **Routing** | Hardcoded 4 cesty (ADVICE/SINGLE_TASK/EPIC/GENERATIVE) | Univerzální delegation engine |
| **Agenti** | 2 coding agenti (Claude/Kilo) | 19+ specialist agentů |
| **Domény** | Pouze kód + tracker | Kód, DevOps, PM, komunikace, právní, finanční, osobní... |
| **Execution** | Sekvenční (step by step) | DAG s paralelními skupinami |
| **Memory** | Chat history + KB prefetch | + Session Memory + Procedural Memory |
| **Jazyk** | Odpověď vždy česky | Detekce jazyka vstupu, odpověď v `response_language` |
| **Graph builder** | `build_orchestrator_graph()` | `build_delegation_graph()` |

### 18.4 Graph builder — kód

**Soubor**: `app/graph/orchestrator.py`

```python
def build_delegation_graph() -> StateGraph:
    """Build 7-node delegation graph (new multi-agent system)."""
    graph = StateGraph(OrchestratorState)

    # Reused nodes
    graph.add_node("intake", intake)                        # Extended
    graph.add_node("evidence_pack", evidence_pack)          # Reused
    graph.add_node("finalize", finalize)                    # Extended

    # New delegation nodes
    graph.add_node("plan_delegations", plan_delegations)
    graph.add_node("execute_delegation", execute_delegation)
    graph.add_node("synthesize", synthesize)

    # Edges
    graph.set_entry_point("intake")
    graph.add_edge("intake", "evidence_pack")
    graph.add_edge("evidence_pack", "plan_delegations")
    graph.add_edge("plan_delegations", "execute_delegation")
    graph.add_conditional_edges(
        "execute_delegation",
        _route_after_execution,
        {
            "execute_delegation": "execute_delegation",  # More pending
            "synthesize": "synthesize",                  # All done
        },
    )
    graph.add_edge("synthesize", "finalize")
    graph.add_edge("finalize", END)

    return graph
```

### 18.5 Graph switching — feature flag

```python
def get_orchestrator_graph():
    """Feature flag: delegation graph vs legacy graph."""
    global _compiled_graph
    if _compiled_graph is None:
        if _checkpointer is None:
            raise RuntimeError("Checkpointer not initialized.")

        if settings.use_delegation_graph:
            graph = build_delegation_graph()
        else:
            graph = build_orchestrator_graph()  # Legacy 14-node

        _compiled_graph = graph.compile(checkpointer=_checkpointer)
    return _compiled_graph
```

**Invariant:** Oba grafy sdílejí stejný `MongoDBSaver` checkpointer, stejnou `OrchestratorState` definici, a stejné API endpointy. Kotlin server nepotřebuje žádné změny.

---

## 19. Delegation Graph — nové nodes

### 19.1 plan_delegations

**Soubor**: `app/graph/nodes/plan_delegations.py`
**Účel**: LLM-driven výběr agentů a sestavení execution plánu

**Vstupy** (ze state):
- `evidence_pack` — KB context, tracker artifacts
- `task_category`, `task_action` — z intake klasifikace
- `session_memory` — recent decisions pro tento client/project
- `response_language` — detekovaný jazyk (z intake)

**Kroky**:
1. Načte `AgentRegistry.get_capability_summary()` — textový přehled všech dostupných agentů, jejich domén a nástrojů
2. Hledá v Procedural Memory (`find_procedure(trigger_pattern, client_id)`) — existuje naučený postup pro tento typ úkolu?
3. Načte Session Memory — čerstvá rozhodnutí pro tento client/project (max 50 entries, 7 dní TTL)
4. Sestaví LLM prompt s kontextem:
   - User query + evidence pack
   - Seznam agentů s capabilities
   - Procedural memory hit (pokud existuje)
   - Session memory entries
5. LLM structured output → `ExecutionPlan`:

```python
class ExecutionPlan(BaseModel):
    delegations: list[DelegationMessage]     # Konkrétní delegace na agenty
    parallel_groups: list[list[str]]         # Skupiny delegation_ids pro paralelní běh
    domain: DomainType                       # Primární doména úkolu
```

**LLM output format**:
```json
{
  "domain": "code",
  "delegations": [
    {
      "delegation_id": "del-001",
      "agent_name": "research",
      "task_summary": "Find architecture docs for auth module",
      "expected_output": "Summary of current auth architecture"
    },
    {
      "delegation_id": "del-002",
      "agent_name": "coding",
      "task_summary": "Implement OAuth2 login endpoint",
      "expected_output": "Working implementation with tests",
      "constraints": ["no changes to database schema"]
    }
  ],
  "parallel_groups": [["del-001"], ["del-002"]]
}
```

**Poznámka k sekvenčnosti**: Groups run sequentially (group 1 before group 2), delegations within a group run in parallel. V příkladu výše: research first, then coding.

**Output**: `execution_plan`, `delegation_states` (všechny v `PENDING`), `domain`, `response_language`

### 19.2 execute_delegation

**Soubor**: `app/graph/nodes/execute_delegation.py`
**Účel**: Dispatch delegací na agenty přes DAGExecutor

**Kroky**:
1. Načte `execution_plan` ze state
2. Najde další skupinu pending delegací
3. Pro každou delegaci ve skupině:
   a. Sestaví kontext (`assemble_delegation_context` s token budgetem dle depth)
   b. Resolve agent z `AgentRegistry.get(agent_name)`
   c. Nastaví `delegation_states[id].status = RUNNING`
   d. Report progress do Kotlin:
      ```python
      await kotlin_client.report_progress(
          task_id=..., node="execute_delegation",
          message=f"Delegating to {agent_name}: {task_summary[:60]}",
          delegation_id=delegation_id,
          delegation_agent=agent_name,
          delegation_depth=0,
      )
      ```
4. Spustí delegace přes `DAGExecutor`:
   - Paralelní v rámci skupiny (`asyncio.gather`)
   - Sekvenční mezi skupinami
5. Pro každý dokončený výsledek:
   a. `delegation_states[id].status = COMPLETED` (nebo `FAILED`)
   b. `delegation_results[id] = output.result` (summary)
   c. Full result uložen do `context_store` se scope `agent_result`
   d. Přidá do `completed_delegations`
6. Pokud zbývají pending skupiny → route back do `execute_delegation`
7. Pokud vše done → route do `synthesize`

**Routing logic** (`_route_after_execution`):
```python
def _route_after_execution(state: dict) -> str:
    plan = state.get("execution_plan", {})
    completed = set(state.get("completed_delegations", []))
    all_ids = {d["delegation_id"] for d in plan.get("delegations", [])}
    if completed >= all_ids:
        return "synthesize"
    return "execute_delegation"
```

**Output**: Updated `delegation_states`, `completed_delegations`, `delegation_results`, `_delegation_outputs`

### 19.3 synthesize

**Soubor**: `app/graph/nodes/synthesize.py`
**Účel**: Sloučení výsledků z více agentů do koherentní odpovědi

**Kroky**:
1. Načte `_delegation_outputs` — list `AgentOutput` ze všech delegací
2. Pokud jen 1 výsledek a `confidence >= 0.8` → přímo použije jako `final_result`
3. Pokud více výsledků → LLM kombinuje:
   - Input: Všechny agent results + original query + evidence pack
   - Instrukce: "Combine these agent results into a coherent, complete response"
4. **RAG cross-check** — pokud jakýkoli agent nastavil `needs_verification: true`:
   - Extrahuje klíčová tvrzení z výsledku
   - Hledá v KB protichůdné informace (`kb_search`)
   - Pokud rozpor nalezen → přidá varování do odpovědi
5. **Překlad** do `response_language` (z intake):
   - Celý interní chain běží anglicky
   - Finální odpověď se přeloží do detekovaného jazyka vstupu
6. Nastaví `final_result`

**Output**: `final_result`, updated `artifacts`

---

## 20. OrchestratorState — delegation fields

Nový delegační graf přidává tyto fieldy k existujícímu `OrchestratorState`. Stávající fieldy zůstávají nezměněné pro backward compatibility.

```python
class OrchestratorState(TypedDict, total=False):
    # --- Existing fields (unchanged, see section 6) ---
    task: dict
    rules: dict
    environment: dict | None
    # ... all existing fields preserved ...

    # --- NEW: Delegation system ---
    execution_plan: dict | None              # ExecutionPlan.model_dump()
    delegation_states: dict                  # {delegation_id: DelegationState.model_dump()}
    active_delegation_id: str | None         # Currently running delegation
    completed_delegations: list              # List of completed delegation IDs
    delegation_results: dict                 # {delegation_id: result_summary}
    response_language: str                   # ISO 639-1 code (cs/en/es/de...)
    domain: str | None                       # DomainType (code/devops/legal/...)
    session_memory: list                     # Recent SessionEntry dicts for this client/project
    _delegation_outputs: list                # List of AgentOutput.model_dump() (transient)
```

### 20.1 Field descriptions

| Field | Type | Set by | Used by | Description |
|-------|------|--------|---------|-------------|
| `execution_plan` | dict | `plan_delegations` | `execute_delegation` | DAG of delegations with parallel groups |
| `delegation_states` | dict | `plan_delegations`, `execute_delegation` | `execute_delegation`, `synthesize` | Status tracking per delegation (PENDING/RUNNING/COMPLETED/FAILED) |
| `active_delegation_id` | str | `execute_delegation` | `execute_delegation` | Currently executing delegation (for checkpoint/restore) |
| `completed_delegations` | list | `execute_delegation` | `execute_delegation` (routing) | Set of finished delegation IDs |
| `delegation_results` | dict | `execute_delegation` | `synthesize`, `finalize` | Summary result per delegation |
| `response_language` | str | `intake` | `synthesize`, `finalize` | Detected language of user input (default "en") |
| `domain` | str | `plan_delegations` | Progress reporting | Primary domain of the task |
| `session_memory` | list | `intake` | `plan_delegations` | Recent decisions loaded from MongoDB `session_memory` collection |
| `_delegation_outputs` | list | `execute_delegation` | `synthesize` | Full AgentOutput objects (transient, not checkpointed) |

### 20.2 Initial state (delegation graph)

```python
def _build_initial_state_delegation(request: OrchestrateRequest) -> dict:
    """Build initial state for delegation graph."""
    state = _build_initial_state(request)  # All legacy fields
    state.update({
        # Delegation system
        "execution_plan": None,
        "delegation_states": {},
        "active_delegation_id": None,
        "completed_delegations": [],
        "delegation_results": {},
        "response_language": "en",  # Detected in intake
        "domain": None,
        "session_memory": [],
        "_delegation_outputs": [],
    })
    return state
```

---

## 21. DAG Executor — paralelní execution

**Soubor**: `app/graph/dag_executor.py`

### 21.1 Koncept

DAG Executor provádí delegace podle `ExecutionPlan.parallel_groups`:
- **Sekvenční** mezi skupinami (group 1 musí být done před group 2)
- **Paralelní** v rámci skupiny (nezávislé delegace běží současně)
- Respektuje závislosti mezi delegacemi

### 21.2 Implementace

```python
class DAGExecutor:
    """Execute delegations respecting parallel groups and dependencies."""

    def __init__(self, registry: AgentRegistry, context_store: ContextStore):
        self._registry = registry
        self._store = context_store

    async def execute_group(
        self,
        delegations: list[DelegationMessage],
        state: dict,
    ) -> list[AgentOutput]:
        """Execute one parallel group of delegations."""
        tasks = []
        for msg in delegations:
            agent = self._registry.get(msg.agent_name)
            if agent is None:
                # Fallback to LegacyAgent if specialist not found
                agent = self._registry.get("legacy")
            tasks.append(self._execute_one(agent, msg, state))

        # asyncio.gather — parallel execution within group
        results = await asyncio.gather(*tasks, return_exceptions=True)

        outputs = []
        for msg, result in zip(delegations, results):
            if isinstance(result, Exception):
                outputs.append(AgentOutput(
                    delegation_id=msg.delegation_id,
                    agent_name=msg.agent_name,
                    success=False,
                    result=f"Agent failed: {result}",
                    confidence=0.0,
                ))
            else:
                outputs.append(result)

            # Store full result in context_store
            await self._store.save_agent_result(
                task_id=state["task"]["id"],
                delegation_id=msg.delegation_id,
                output=result if not isinstance(result, Exception) else None,
            )

        return outputs

    async def _execute_one(
        self, agent: BaseAgent, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute a single delegation with timeout."""
        return await asyncio.wait_for(
            agent.execute(msg, state),
            timeout=settings.delegation_timeout,
        )
```

### 21.3 Token budgets per depth

Kontext pro agenta je omezen dle hloubky delegace (depth):

| Depth | Budget | Kdo |
|-------|--------|-----|
| 0 | 48,000 tokens | Orchestrátor (direct delegation) |
| 1 | 16,000 tokens | First-level sub-delegation |
| 2 | 8,000 tokens | Second-level sub-delegation |
| 3-4 | 4,000 tokens | Deep sub-delegations |

```python
def _get_token_budget(depth: int) -> int:
    budgets = {
        0: settings.token_budget_depth_0,   # 48000
        1: settings.token_budget_depth_1,   # 16000
        2: settings.token_budget_depth_2,   # 8000
    }
    return budgets.get(depth, settings.token_budget_depth_3)  # 4000
```

### 21.4 Constraint enforcement

- **Max depth 4** — Agent na depth 3 nemůže volat sub-agenta na depth 5
- **Cycle detection** — Stack delegací se trackuje, nelze volat agenta co už je ve stacku
- **Summarization up** — Rodič NIKDY nevidí plný output sub-agenta, jen summary (max 500 znaků). Plný výsledek je v `context_store`.

---

## 22. Agent Communication Protocol

### 22.1 DelegationMessage — vstup pro agenta

```python
class DelegationMessage(BaseModel):
    delegation_id: str                # Unique ID (e.g. "del-001")
    parent_delegation_id: str | None  # For sub-delegations
    depth: int = 0                    # 0=orchestrator, 1-4=sub-agents
    agent_name: str                   # Target agent name
    task_summary: str                 # What the agent should do (ENGLISH)
    context: str = ""                 # Token-budgeted context
    constraints: list[str]            # Restrictions (forbidden files, max changes, etc.)
    expected_output: str = ""         # What orchestrator expects back
    response_language: str = "en"     # ISO 639-1 for final response
    # Data isolation
    client_id: str = ""
    project_id: str | None = None
    group_id: str | None = None       # If set, agent sees KB of entire group
```

### 22.2 AgentOutput — výstup agenta

```python
class AgentOutput(BaseModel):
    delegation_id: str
    agent_name: str
    success: bool
    result: str = ""                  # Main output (text answer, summary)
    structured_data: dict             # Structured data (diff, issues, etc.)
    artifacts: list[str]              # Created files, commits, etc.
    changed_files: list[str]
    sub_delegations: list[str]        # Sub-delegation IDs (for tracing)
    confidence: float = 1.0           # 0.0-1.0
    needs_verification: bool = False  # Request KB cross-check
```

### 22.3 Structured response format

All agents respond with structured format (enforced by `BaseAgent._agentic_loop`):

```
STATUS: 1|0|P
RESULT: <complete, compact content>
ARTIFACTS: <files, commits>
ISSUES: <problems, blockers>
CONFIDENCE: 0.0-1.0
NEEDS_VERIFICATION: true/false
```

- `STATUS: 1` = success, `0` = failure, `P` = partial (needs more work)
- No truncation of agent responses. Agents are instructed to be maximally compact but include all substantive content.
- Full results stored in `context_store` for retrieval. Only summaries passed up the delegation chain.

### 22.4 Jazyková pravidla

- **Intake node** detekuje jazyk vstupního requestu a ukládá do `response_language`
- **Celý interní chain** běží ANGLICKY (LLM instructions, delegation messages, agent outputs)
- **Finální odpověď** (synthesize/finalize) se přeloží do `response_language`
- **Proč anglicky interně:** LLM modely jsou nejpřesnější v angličtině, menší chybovost, menší token count

### 22.5 Failure handling

| Typ selhání | Detekce | Akce |
|-------------|---------|------|
| **Soft failure** | `confidence < 0.5` | Orchestrátor zkusí jiného agenta nebo eskaluje na uživatele |
| **Hard failure** | Exception/timeout | Retry 1x, pak eskalace |
| **Quality failure** | RAG cross-check vs KB neprošel | Vrátí agentovi s vysvětlením co je špatně |

### 22.6 LLM eskalační řetězec (per agent)

```
1. LOCAL_FAST (qwen3-coder-tool, 8k ctx)       → rychlé, jednoduché úkoly
2. LOCAL_STANDARD (qwen3-coder-tool, 32k ctx)   → standardní
3. LOCAL_LARGE (qwen3-coder-tool, 49k ctx)      → max local
4. CLOUD (Anthropic/OpenAI/Gemini)               → až když local nestačí
```

Agent netuší kdo ho odbavuje — Ollama Router řídí GPU vs CPU routing transparentně.

---

## 23. Specialist Agents — registr 19 agentů

### 23.1 AgentRegistry

**Soubor**: `app/agents/registry.py`

```python
class AgentRegistry:
    """Singleton registry of all available specialist agents."""
    _instance = None
    _agents: dict[str, BaseAgent]

    @classmethod
    def instance(cls) -> AgentRegistry

    def register(self, agent: BaseAgent) -> None
    def get(self, name: str) -> BaseAgent | None
    def list_agents(self) -> list[AgentCapability]
    def find_for_domain(self, domain: DomainType) -> list[BaseAgent]
    def get_capability_summary(self) -> str   # Text summary for LLM in plan_delegations
```

`get_capability_summary()` vrací textový přehled pro LLM prompt:
```
Available agents:
- coding: Delegates to coding agents (Claude/Kilo), manages workspace. Domains: code
- research: KB search, web search, code exploration, filesystem tools. Domains: research
- issue_tracker: CRUD issues in Jira/GitHub/GitLab via Kotlin API. Domains: project_management, code
- wiki: CRUD wiki pages in Confluence/GitHub/GitLab. Domains: research, communication
- documentation: Generates/updates project documentation. Domains: code, research
- devops: CI/CD, Docker, K8s, deployment operations. Domains: devops
...
```

### 23.2 BaseAgent

**Soubor**: `app/agents/base.py`

```python
class BaseAgent(ABC):
    name: str                         # Unique agent name
    description: str                  # For orchestrator LLM prompt
    domains: list[DomainType]         # Where agent operates
    tools: list[dict]                 # OpenAI function-calling schemas
    can_sub_delegate: bool = True     # Can call sub-agents?
    max_depth: int = 4

    @abstractmethod
    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Main execution."""

    async def _agentic_loop(
        self, msg: DelegationMessage, state: dict,
        system_prompt: str, max_iterations: int = 10,
    ) -> AgentOutput:
        """Shared agentic loop: LLM call → tool calls → iterate."""

    async def _call_llm(self, messages, tools=None, model_tier=None) -> str:
        """LLM calling with tier selection and token-arrival liveness."""

    async def _execute_tool(self, tool_name, arguments, state) -> str:
        """Execute registered tool via ToolExecutor."""

    async def _sub_delegate(
        self, target_agent_name, task_summary, context, parent_msg, state,
    ) -> AgentOutput:
        """Delegate to another agent (depth+1, cycle detection)."""
```

### 23.3 Agent catalog

#### Tier 1 — Core agenti

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 1 | **CodingAgent** | `code_agent.py` | K8s Job delegace (Claude/Kilo), workspace, results | - |
| 2 | **GitAgent** | `git_agent.py` | Commit, push, branch, PR/MR, merge conflicts | CodingAgent |
| 3 | **CodeReviewAgent** | `review_agent.py` | Code review, soulad se zadáním, forbidden files | CodingAgent, TestAgent, ResearchAgent |
| 4 | **TestAgent** | `test_agent.py` | Generování testů, spouštění, analýza | CodingAgent |
| 5 | **ResearchAgent** | `research_agent.py` | KB search, web search, code exploration, filesystem | - |

> **CodingAgent** je centrální brána ke coding agentům. Centralizovaná kontrola workspace, job management, cost tracking.

#### Tier 2 — DevOps & Project Management

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 6 | **IssueTrackerAgent** | `tracker_agent.py` | CRUD issues (Jira/GitHub/GitLab), search, transitions | ResearchAgent |
| 7 | **WikiAgent** | `wiki_agent.py` | CRUD wiki stránek (Confluence/GitHub/GitLab) | ResearchAgent |
| 8 | **DocumentationAgent** | `documentation_agent.py` | Generuje/updatuje docs, READMEs, API docs | ResearchAgent |
| 9 | **DevOpsAgent** | `devops_agent.py` | CI/CD, Docker, K8s, deployment | - |
| 10 | **ProjectManagementAgent** | `project_management_agent.py` | Sprint planning, epic management | IssueTrackerAgent |
| 11 | **SecurityAgent** | `security_agent.py` | Security analýza, vulnerability scan | ResearchAgent |

#### Tier 3 — Komunikace & Administrativa

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 12 | **CommunicationAgent** | `communication_agent.py` | Hub pro veškerou komunikaci, drafty, reporty | EmailAgent |
| 13 | **EmailAgent** | `email_agent.py` | Read/compose/search emailů | - |
| 14 | **CalendarAgent** | `calendar_agent.py` | Termíny, reminders, scheduling | - |
| 15 | **AdministrativeAgent** | `administrative_agent.py` | Plánování cest, logistika | CalendarAgent |

#### Tier 4 — Byznysová podpora

| # | Agent | Soubor | Odpovědnost | Sub-deleguje na |
|---|-------|--------|-------------|-----------------|
| 16 | **LegalAgent** | `legal_agent.py` | Smlouvy, NDA, compliance | ResearchAgent |
| 17 | **FinancialAgent** | `financial_agent.py` | Rozpočet, faktury, odhady | - |
| 18 | **PersonalAgent** | `personal_agent.py` | Nákupy, dovolená, osobní asistence | CalendarAgent |
| 19 | **LearningAgent** | `learning_agent.py` | Tutoriály, evaluace technologií | ResearchAgent |

#### Special — Fallback

| Agent | Soubor | Odpovědnost |
|-------|--------|-------------|
| **LegacyAgent** | `legacy_agent.py` | Wrapper stávající logiky — fallback pokud specialist selže |

### 23.4 Implementační vzor (sdílený)

Všichni specialist agenti sdílejí stejný vzor:

```python
class SpecialistAgent(BaseAgent):
    name = "specialist_name"
    description = "What this agent does"
    domains = [DomainType.CODE, DomainType.RESEARCH]
    tools = [TOOL_KB_SEARCH, TOOL_SPECIFIC, ...]
    can_sub_delegate = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        # 1. Optional: sub-delegate to ResearchAgent for context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=f"Gather context for: {msg.task_summary}",
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            enriched_context = f"{msg.context}\n---\n{research_output.result}"
            msg = msg.model_copy(update={"context": enriched_context})

        # 2. Build agent-specific system prompt
        system_prompt = "You are the SpecialistAgent..."

        # 3. Agentic loop: LLM call → tool calls → iterate
        return await self._agentic_loop(
            msg=msg, state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )
```

### 23.5 Registrace agentů (startup)

V `app/main.py` lifespan:

```python
from app.agents.registry import AgentRegistry
from app.agents.specialists.tracker_agent import IssueTrackerAgent
from app.agents.specialists.wiki_agent import WikiAgent
# ... all 19 agents

registry = AgentRegistry.instance()
registry.register(IssueTrackerAgent())
registry.register(WikiAgent())
# ... all agents + LegacyAgent
```

---

## 24. Memory Integration — 4 vrstvy

### 24.1 Přehled vrstev

| Vrstva | Storage | TTL | Obsah | Použití v delegation grafu |
|--------|---------|-----|-------|---------------------------|
| **Working Memory** | OrchestratorState (checkpoint) | Orchestrace | Aktuální stav, delegation stack | Celý graf |
| **Episodic Memory** | MongoDB `context_store` | 30 dní | Výsledky delegací, interakce | `execute_delegation` ukládá, `synthesize` čte |
| **Semantic Memory** | KB (Weaviate + ArangoDB) | Permanentní | Fakta, konvence, pravidla | `evidence_pack`, `synthesize` (cross-check) |
| **Procedural Memory** | ArangoDB `ProcedureNode` | Permanentní (decay) | Naučené workflow postupy | `plan_delegations` |

Plus:

| Vrstva | Storage | TTL | Obsah | Použití |
|--------|---------|-----|-------|---------|
| **Session Memory** | MongoDB `session_memory` | 7 dní | Per-client/project rozhodnutí | `intake` loads, `plan_delegations` reads |

### 24.2 Session Memory

**Soubor**: `app/context/session_memory.py`
**Collection**: MongoDB `session_memory`

```python
class SessionEntry(BaseModel):
    timestamp: str
    source: str               # "chat" | "background" | "orchestrator_decision"
    summary: str              # Max 200 chars
    details: dict | None
    task_id: str | None

class SessionMemoryPayload(BaseModel):
    client_id: str
    project_id: str | None
    entries: list[SessionEntry]   # Max 50 entries per client/project
```

**Lifecycle:**
1. `intake` node loads session memory for `client_id + project_id`
2. `plan_delegations` reads session memory entries to inform agent selection
3. After orchestration completes, key decisions saved to session memory
4. TTL: 7 days auto-expiry. Important items also saved to KB (permanent).

**Proč ne jen KB:** Session Memory = fast key-value lookup pro "co se stalo před hodinou". KB = semantic search pro "najdi mi všechno o technologii X". Session Memory je cache, KB je storage.

### 24.3 Procedural Memory

**Soubor**: `app/context/procedural_memory.py`
**Storage**: ArangoDB `ProcedureNode` (via KB service REST API)

```python
class ProcedureNode(BaseModel):
    trigger_pattern: str            # "email_with_question", "task_completion", "bug_report"
    procedure_steps: list[ProcedureStep]
    success_rate: float = 0.0       # 0.0-1.0
    last_used: str | None
    usage_count: int = 0
    source: str = "learned"         # "learned" | "user_defined" | "default"
    client_id: str = ""             # Per-client procedures

class ProcedureStep(BaseModel):
    agent: str                      # Agent name (e.g. "code_review")
    action: str                     # What to do
    parameters: dict                # Agent-specific params
```

**Příklady uložených postupů:**
- `task_completion`: Code review → Deploy → Test → Close issue → Notify
- `bug_report`: Search KB → Analyze code → Fix → Test → PR
- `email_deadline_question`: Find issue → Check status → Estimate deadline → Reply

**Učení:**
1. Po úspěšné orchestraci se vzor uloží (`source: "learned"`)
2. Při podobném úkolu se použije jako šablona v `plan_delegations`
3. Pokud postup neexistuje → orchestrátor se ZEPTÁ uživatele → odpověď uloží jako nový postup (`source: "user_defined"`)
4. `user_defined` mají vždy vyšší prioritu než `learned`
5. Usage-decay: nepoužívané postupy postupně klesají v prioritě

### 24.4 Context assembly s token budgets

**Soubor**: `app/context/context_assembler.py`

```python
async def assemble_delegation_context(
    delegation: DelegationMessage,
    evidence_pack: dict,
    session_memory: list,
) -> str:
    """Build context for agent delegation with token budget."""
    budget = _get_token_budget(delegation.depth)

    # Priority order (higher priority = more budget):
    # 1. Task description + constraints (always included)
    # 2. Evidence pack (KB results, tracker artifacts)
    # 3. Session memory (recent decisions)
    # 4. Chat history summary

    context_parts = []
    remaining = budget

    # Always include task
    task_section = f"Task: {delegation.task_summary}\n"
    context_parts.append(task_section)
    remaining -= _count_tokens(task_section)

    # Evidence (up to 60% of remaining)
    evidence_budget = int(remaining * 0.6)
    evidence_section = _trim_to_budget(evidence_pack, evidence_budget)
    context_parts.append(evidence_section)
    remaining -= _count_tokens(evidence_section)

    # Session memory (up to 20% of remaining)
    if session_memory:
        mem_budget = int(remaining * 0.5)
        mem_section = _trim_to_budget(session_memory, mem_budget)
        context_parts.append(mem_section)

    return "\n\n".join(context_parts)
```

### 24.5 Retention policy — co uložit vs zahodit

**Soubor**: `app/context/retention_policy.py`

Po dokončení orchestrace:

| Co | Kam | Kdy |
|----|-----|-----|
| User decisions (z chatu, z approval) | KB (permanent) + Session Memory (7d) | Vždy |
| Agent results (success) | `context_store` (30d) | Vždy |
| Agent results (failure) | `context_store` (7d shorter TTL) | Vždy |
| Successful workflow pattern | Procedural Memory | Pokud `use_procedural_memory` flag |
| Key facts discovered | KB | Pokud `confidence >= 0.8` |
| Routine status checks | Nikam (zahodit) | - |

---

## 25. Feature Flags a backward compatibility

### 25.1 Feature flags

**Soubor**: `app/config.py` — Settings class

```python
# Feature flags (multi-agent system) — all default to False
use_delegation_graph: bool = False       # Main switch: 7-node delegation vs 14-node legacy
use_specialist_agents: bool = False      # 19 specialist agents vs LegacyAgent fallback
use_dag_execution: bool = False          # Parallel DAG execution vs sequential
use_procedural_memory: bool = False      # KB procedure learning + lookup
use_graph_agent: bool = False            # Graph Agent — vertex/edge DAG execution (overrides all above)
```

| Flag | Default | Effect when True | Effect when False |
|------|---------|-----------------|-------------------|
| `use_graph_agent` | `False` | `run_orchestration` uses Graph Agent (LangGraph-based vertex/edge DAG with responsibility-typed vertices) | Falls through to delegation/legacy graph |
| `use_delegation_graph` | `False` | `get_orchestrator_graph()` returns 7-node delegation graph | Returns legacy 14-node graph |
| `use_specialist_agents` | `False` | `plan_delegations` selects from 19 registered agents | Routes everything to `LegacyAgent` |
| `use_dag_execution` | `False` | `execute_delegation` uses `DAGExecutor` (parallel) | Sequential execution only |
| `use_procedural_memory` | `False` | `plan_delegations` looks up learned procedures in KB | No procedure lookup |

**Priority:** `use_graph_agent` is checked first — if True, delegation_graph and legacy graph are never used.

### 25.1b Centralized LLM & handler settings

All handler constants are in `app/config.py` Settings class (env prefix `ORCHESTRATOR_`):

```python
# LLM token budgets
default_output_tokens: int = 4096        # Output token reserve for tier estimation
gpu_vram_token_boundary: int = 40_000    # P40 VRAM limit — above this spills to CPU

# Handler iterations
chat_max_iterations: int = 6
chat_max_iterations_long: int = 3
background_max_iterations: int = 15
respond_max_iterations: int = 8

# Token estimation
token_estimate_ratio: int = 4            # Chars-per-token (rough, cs/en)
```

All handlers (foreground, background, respond, plan, synthesize) use:
- `estimate_tokens()` from `app.config` — single implementation
- `settings.default_output_tokens` — no hardcoded `4096`
- `settings.gpu_vram_token_boundary` — no hardcoded `40_000`
- `clamp_tier()` from `app.llm.provider` — unified tier clamping
- `extract_tool_calls()` from `app.tools.ollama_parsing` — unified Ollama workaround

### 25.2 Backward compatibility guarantees

1. **API endpointy identické** — Kotlin server nepotřebuje žádné změny
   - `POST /orchestrate/stream` — unchanged
   - `POST /approve/{thread_id}` — unchanged
   - `GET /status/{thread_id}` — unchanged
   - `GET /health` — unchanged
2. **Legacy graf zachován** — `build_orchestrator_graph()` se NEMAŽE. Zůstává jako default.
3. **Feature flags defaultují na False** — Nový systém je opt-in
4. **MongoDBSaver checkpointer sdílený** — Oba grafy používají stejný checkpointer
5. **Backward compatible progress** — Nové optional fieldy v progress reportech:

```python
# Existing fields (unchanged):
taskId, clientId, node, message, percent,
goalIndex, totalGoals, stepIndex, totalSteps

# New optional fields (Kotlin ignores if not supported):
delegationId: str | None          # ID of current delegation
delegationAgent: str | None       # Name of agent executing
delegationDepth: int | None       # Recursion depth (0-4)
thinkingAbout: str | None         # What orchestrator is considering (for "thinking" UI)
```

### 25.3 Rollback procedure

Pokud nový systém selže:
1. Nastavit `use_delegation_graph = False` v config/env
2. Restart orchestrator podu
3. Všechny nové orchestrace půjdou přes legacy graf
4. In-flight orchestrace (v checkpointu) se zastaví — safety-net polling je resetuje

### 25.4 Přechod na nový systém

Doporučená sekvence zapínání:
1. `use_delegation_graph = True` + `use_specialist_agents = False` → nový graf s LegacyAgent (test graph flow)
2. `use_specialist_agents = True` + `use_dag_execution = False` → specialist agenti, sekvenční (test agents)
3. `use_dag_execution = True` → full parallel execution (test performance)
4. `use_procedural_memory = True` → learning enabled (test long-term)

---

## 26. Kotlin integrace — kompletní API

### 26.1 PythonOrchestratorClient (Kotlin → Python)

```kotlin
class PythonOrchestratorClient(baseUrl: String) {
    // Orchestrace
    suspend fun orchestrate(request): OrchestrateResponseDto           // blocking (legacy)
    suspend fun orchestrateStream(request): StreamStartResponseDto?    // fire-and-forget (primary)
    suspend fun approve(threadId, approved, reason)                     // fire-and-forget resume
    suspend fun cancelOrchestration(threadId)
    suspend fun resume(threadId): OrchestrateResponseDto               // blocking resume (legacy)
    suspend fun getStatus(threadId): Map<String, String>               // safety-net polling

    // Health
    suspend fun isHealthy(): Boolean
    suspend fun isBusy(): Boolean
    fun streamUrl(threadId): String

    // Chat compression
    suspend fun compressChat(request): CompressChatResponseDto

    // Correction agent
    suspend fun submitCorrection(request)
    suspend fun correctTranscript(request)
    suspend fun listCorrections(request)
    suspend fun deleteCorrection(sourceUrn)
    suspend fun correctWithInstruction(request)
    suspend fun correctTargeted(request)
    suspend fun answerCorrectionQuestions(request)
}
```

### 26.2 Internal endpoints (Python → Kotlin push)

**KtorRpcServer** registruje:

```
POST /internal/orchestrator-progress
  Body: { taskId, node, message, percent, goalIndex, totalGoals, stepIndex, totalSteps, clientId }
  → Update stateChangedAt on TaskDocument (timestamp-based stuck detection)
  → Emit OrchestratorTaskProgress to UI

POST /internal/orchestrator-status
  Body: { taskId, status, summary, error, interruptAction, interruptDescription, branch, artifacts, threadId }
  → OrchestratorStatusHandler.handleStatusChange(...)
  → Emit OrchestratorTaskStatusChange to UI

POST /internal/correction-progress
  Body: { meetingId, phase, chunkIndex, totalChunks, segmentsProcessed, totalSegments, message }
  → Update stateChangedAt on meeting document (timestamp-based stuck detection)
  → Emit notification to UI

POST /internal/memory-graph-changed
  Body: (empty)
  → NotificationRpcImpl.emitMemoryGraphChanged() [broadcast to ALL connected clients]
  → UI re-fetches Paměťový graf via getGraph("master") [500ms debounce]
  Triggered by: vertex status changes (PENDING→RUNNING→COMPLETED), TASK_REF linking
  Only fires for MEMORY_GRAPH graphs (not THINKING_GRAPH thinking graphs)
```

### 26.3 AgentOrchestratorService

```kotlin
class AgentOrchestratorService(
    pythonOrchestratorClient, preferenceService, czechKeyboardNormalizer,
    taskService, taskRepository, environmentService,
    clientService, projectService, chatHistoryService,
) {
    // Entry points
    suspend fun enqueueChatTask(...)     // Create FOREGROUND task
    suspend fun handle(text, ctx, ...)   // Direct handle (legacy)
    suspend fun run(task, userInput, ...) // Main router:
        // Path 1: orchestratorThreadId != null → resumePythonOrchestrator()
        // Path 2: new → dispatchToPythonOrchestrator()
        // Path 3: unavailable → error response

    private suspend fun dispatchToPythonOrchestrator(...):
        // 1. Guard: PROCESSING count == 0
        // 2. isHealthy()
        // 3. Load rules, environment, names, chat history
        // 4. Build OrchestrateRequestDto
        // 5. POST /orchestrate/stream → get thread_id
        // 6. Save PROCESSING + orchestratorThreadId

    private suspend fun resumePythonOrchestrator(...):
        // Distinguish clarification vs approval
        // POST /approve/{thread_id}
        // Save PROCESSING
}
```

### 26.4 OrchestratorStatusHandler

```kotlin
class OrchestratorStatusHandler(
    taskRepository, taskService, userTaskService,
    agentOrchestratorRpc, chatMessageRepository, chatHistoryService,
) {
    suspend fun handleStatusChange(taskId, status, summary, error,
        interruptAction, interruptDescription, branch, artifacts):

        when (status):
            "running"     → no-op (timestamp-based stuck detection handles liveness)
            "interrupted" → handleInterrupted(task, action, description)
            "done"        → handleDone(task, summary)
            "error"       → handleError(task, error)

    private suspend fun handleInterrupted(task, action, description):
        // FOREGROUND: emit to chat stream + save ASSISTANT message + DONE
        // BACKGROUND: create USER_TASK notification

    private suspend fun handleDone(task, summary):
        // FOREGROUND: emit response + save ASSISTANT message
        // Check inline messages (arrived during orchestration)
        //   → if yes: re-queue to QUEUED
        //   → if no: DONE (terminal)
        // BACKGROUND: delete task after completion
        // Async: chatHistoryService.compressIfNeeded()

    private suspend fun handleError(task, error):
        // Emit error + save error message
        // Create USER_TASK + set ERROR state
}
```

### 26.5 BackgroundEngine (relevantní loops)

```kotlin
// Execution loop (orchestrator): three-tier priority — FOREGROUND > BACKGROUND > IDLE
private suspend fun runExecutionLoop():
    while (true):
        // 0. Preemption: FG preempts BG+IDLE, BG preempts IDLE
        checkPreemption(runningTask)
        // 1. FOREGROUND (chat) — highest priority
        task = getNextForegroundTask()
        // 2. BACKGROUND (user-scheduled) — if no FG and no active chat
        if task == null: task = getNextBackgroundTask()
        // 3. IDLE (system idle work) — only when truly idle
        if task == null: task = getNextIdleTask()
        if task != null:
            agentOrchestratorService.run(task, task.content)

// Orchestrator result loop: safety-net for PROCESSING
private suspend fun runOrchestratorResultLoop():
    while (true):
        delay(60_000)                  // 60s interval
        tasks = findAll(PROCESSING)
        for task in tasks:
            checkOrchestratorTaskStatus(task)
            // → timestamp stuck check → Python poll → status handler
```

---

## 27. Konfigurace a deployment

### 27.1 Python config (`app/config.py`)

```python
class Settings:
    host = "0.0.0.0"
    port = 8090
    mongodb_url = env("MONGODB_URL", "mongodb://localhost:27017")
    kotlin_server_url = env("KOTLIN_SERVER_URL", "http://jervis-server:5500")
    knowledgebase_url = env("KNOWLEDGEBASE_URL", "http://jervis-knowledgebase:8080")
    ollama_url = env("OLLAMA_URL", "http://192.168.100.117:11434")
    k8s_namespace = env("K8S_NAMESPACE", "jervis")
    data_root = env("DATA_ROOT", "/opt/jervis/data")
    container_registry = env("CONTAINER_REGISTRY", "registry.damek-soft.eu/jandamek")

    # LLM models
    default_local_model = "qwen3-coder-tool:30b"
    default_cloud_model = "claude-sonnet-4-5-20250929"
    default_premium_model = "claude-opus-4-6"
    default_openai_model = "gpt-4o"
    default_large_context_model = "gemini-2.5-pro"

    # API keys (optional)
    anthropic_api_key = env("ANTHROPIC_API_KEY", None)
    openai_api_key = env("OPENAI_API_KEY", None)
    google_api_key = env("GOOGLE_API_KEY", None)

    # Agent timeouts
    agent_timeouts = {
        "claude": 1800, "kilo": 1800,
    }
    job_ttl_seconds = 300

    # Feature flags (multi-agent delegation system)
    use_delegation_graph: bool = False       # 7-node delegation vs 14-node legacy
    use_specialist_agents: bool = False      # 19 agents vs LegacyAgent
    use_dag_execution: bool = False          # Parallel DAG execution
    use_procedural_memory: bool = False      # KB procedure learning

    # Delegation settings
    max_delegation_depth: int = 4
    delegation_timeout: int = 300

    # Token budgets per depth
    token_budget_depth_0: int = 48000
    token_budget_depth_1: int = 16000
    token_budget_depth_2: int = 8000
    token_budget_depth_3: int = 4000

    # Context budgeting (ChatContextAssembler)
    total_context_window: int = 32_768    # Model context window
    system_prompt_reserve: int = 2_000    # Tokens for system prompt + tools
    response_reserve: int = 4_000         # Tokens for LLM response
    recent_message_count: int = 100       # Max verbatim messages (budget limits actual inclusion)
    max_summary_blocks: int = 15          # Max compressed summaries
    compress_threshold: int = 20          # Compress at >=N unsummarized msgs
    compress_max_retries: int = 2         # Compression retry limit
    max_tool_result_in_msg: int = 2_000   # Max chars per tool result
    token_estimate_ratio: int = 4         # Chars-per-token ratio

    # Chat handler constants
    chat_max_iterations: int = 6          # Max agentic loop iters (normal)
    chat_max_iterations_long: int = 3     # Max iters for long messages
    decompose_threshold: int = 8000       # Chars to trigger decomposition
    summarize_threshold: int = 16000      # Chars to trigger summarization
    subtopic_max_iterations: int = 3      # Max iters per sub-topic
    max_subtopics: int = 5               # Max sub-topics from decomposition

    # Background handler constants
    background_max_iterations: int = 15   # Max agentic loop iters for bg

    # Streaming
    stream_chunk_size: int = 40           # Chars per fake-streaming chunk

    # Guidelines cache
    guidelines_cache_ttl: int = 300       # TTL seconds (5 min)

    # Session memory
    session_memory_ttl_days: int = 7
    session_memory_max_entries: int = 50
```

> **All settings use env prefix `ORCHESTRATOR_`**, e.g. `ORCHESTRATOR_TOTAL_CONTEXT_WINDOW=65536`.

### 27.2 K8s Deployment

```yaml
# k8s/app_orchestrator.yaml
Deployment: jervis-orchestrator
  replicas: 1
  serviceAccountName: jervis-orchestrator  # RBAC pro K8s Jobs
  image: registry.damek-soft.eu/jandamek/jervis-orchestrator:latest
  port: 8090
  resources:
    requests: 256Mi, 250m
    limits: 1Gi, 2000m
  volumeMounts:
    - /opt/jervis/data (PVC: jervis-data-pvc)
  probes:
    liveness: GET /health (30s interval, 10s timeout)
    readiness: GET /health (15s interval, 10s timeout)

Service: jervis-orchestrator
  port: 8090 → 8090
```

### 27.3 RBAC

```yaml
# k8s/orchestrator-rbac.yaml
ServiceAccount: jervis-orchestrator
Role: jervis-orchestrator-role
  - resources: [jobs, pods, pods/log]
    verbs: [get, list, watch, create, delete]
RoleBinding: jervis-orchestrator → jervis-orchestrator-role
```

### 27.4 Build & Deploy

```bash
k8s/build_orchestrator.sh
# → Docker build --platform linux/amd64
# → Docker push registry.damek-soft.eu/jandamek/jervis-orchestrator:v{N}
# → kubectl apply -f k8s/orchestrator-rbac.yaml
# → kubectl apply -f k8s/app_orchestrator.yaml
# → kubectl set image deployment/jervis-orchestrator ...
```

---

## 28. Datové modely — kompletní referenční seznam

### 28.1 Python modely (`app/models.py`)

```python
# === Legacy Enums ===
AgentType        # claude, kilo
Complexity       # simple, medium, complex, critical
ModelTier        # local_fast/standard/large, cloud_openrouter/reasoning/coding/premium/large_context
TaskCategory     # advice, single_task, epic, generative
TaskAction       # respond, code, tracker_ops, mixed
StepType         # respond, code, tracker
RiskLevel        # LOW, MEDIUM, HIGH, CRITICAL

# === Legacy Core models ===
CodingTask       # id, client_id, project_id, workspace_path, query, agent_preference
Goal             # id, title, description, complexity, dependencies
CodingStep       # index, instructions, step_type, agent_type, files, tracker_operations
StepResult       # step_index, success, summary, agent_type, changed_files
Evaluation       # acceptable, checks, diff
GoalSummary      # goal_id, title, summary, changed_files, key_decisions

# Clarification
ClarificationQuestion  # id, question, options, required

# Evidence
EvidencePack     # kb_results, tracker_artifacts, chat_history_summary, external_refs, facts, unknowns

# Chat history
ChatHistoryMessage   # role, content, timestamp, sequence
ChatSummaryBlock     # sequence_range, summary, key_decisions, topics, is_checkpoint, checkpoint_reason
ChatHistoryPayload   # recent_messages, summary_blocks, total_message_count

# Approval
ApprovalRequest  # action_type, description, details, risk_level, reversible
ApprovalResponse # approved, modification, reason

# API
OrchestrateRequest   # task_id, client_id, ..., rules, environment, chat_history
OrchestrateResponse  # task_id, success, summary, branch, artifacts, step_results, thread_id
ProjectRules         # branch_naming, commit_prefix, require_*, auto_*, forbidden_files

# === NEW: Delegation system models ===

# Enums
DomainType           # code, devops, project_management, communication, legal, financial,
                     # administrative, personal, security, research, learning
DelegationStatus     # pending, running, completed, failed, interrupted

# Delegation protocol
DelegationMessage    # delegation_id, parent_delegation_id, depth, agent_name, task_summary,
                     # context, constraints, expected_output, response_language,
                     # client_id, project_id, group_id
AgentOutput          # delegation_id, agent_name, success, result, structured_data,
                     # artifacts, changed_files, sub_delegations, confidence, needs_verification
DelegationState      # delegation_id, agent_name, status, result_summary,
                     # sub_delegation_ids, checkpoint_data
ExecutionPlan        # delegations, parallel_groups, domain

# Agent registry
AgentCapability      # name, description, domains, can_sub_delegate, max_depth, tool_names

# Memory
SessionEntry         # timestamp, source, summary, details, task_id
SessionMemoryPayload # client_id, project_id, entries
ProcedureStep        # agent, action, parameters
ProcedureNode        # trigger_pattern, procedure_steps, success_rate, last_used,
                     # usage_count, source, client_id

# Monitoring
DelegationMetrics    # delegation_id, agent_name, start_time, end_time,
                     # token_count, llm_calls, sub_delegation_count, success
```

### 28.2 Kotlin DTOs (`PythonOrchestratorClient.kt`)

```kotlin
// Orchestrace
OrchestrateRequestDto      // task_id, client_id, ..., chat_history
OrchestrateResponseDto     // task_id, success, summary, isInterrupted
StreamStartResponseDto     // thread_id, stream_url
ApprovalResponseDto        // approved, modification, reason
ProjectRulesDto            // branch_naming, commit_prefix, require_*, auto_*
StepResultDto              // step_index, success, summary, agent_type, changed_files

// Chat history
ChatHistoryPayloadDto      // recent_messages, summary_blocks, total_message_count
ChatHistoryMessageDto      // role, content, timestamp, sequence
ChatSummaryBlockDto        // sequence_range, summary, key_decisions, topics, is_checkpoint

// Chat compression
CompressChatRequestDto     // messages, previous_summary, client_id, task_id
CompressChatResponseDto    // summary, key_decisions, topics, is_checkpoint, checkpoint_reason

// Correction agent
CorrectionSubmitRequestDto, CorrectionSubmitResultDto
CorrectionRequestDto, CorrectionResultDto, CorrectionSegmentDto
CorrectionQuestionPythonDto, CorrectionAnswerRequestDto, CorrectionAnswerItemDto
CorrectionListRequestDto, CorrectionListResultDto, CorrectionChunkDto
CorrectionInstructRequestDto, CorrectionInstructResultDto
CorrectionTargetedRequestDto, CorrectionDeleteRequestDto
```

### 28.3 MongoDB kolekce (orchestrátor-related)

| Kolekce | Účel | Indexy |
|---------|------|--------|
| `checkpoints` / `checkpoint_writes` | LangGraph graph state | thread_id |
| `orchestrator_context` | Hierarchické context store | (task_id, scope, scope_key), TTL 30d |
| `orchestrator_locks` | Distributed lock | _id = "orchestration_slot" |
| `chat_messages` | Jednotlivé zprávy | (taskId, sequence), taskId, correlationId |
| `chat_summaries` | Komprimované souhrny | (taskId, sequenceEnd), taskId |
| `tasks` | TaskDocument lifecycle | state, clientId, projectId, type |
| `session_memory` | **NEW:** Per-client/project session memory | (client_id, project_id), TTL 7d |
| `delegation_metrics` | **NEW:** Per-agent delegation metrics | (delegation_id), (agent_name, start_time) |

---

## 29. Souborová mapa

### Python orchestrátor

```
backend/service-orchestrator/
├── app/
│   ├── main.py                          # FastAPI app, endpoints, concurrency, crash handler (atexit + SIGTERM)
│   ├── config.py                        # Environment-based configuration (+feature flags)
│   ├── agent_task_watcher.py            # Background watcher for async K8s Job monitoring
│   ├── models.py                        # Pydantic models (ALL data structures + delegation models)
│   ├── chat/
│   │   ├── __init__.py
│   │   ├── context.py                   # Chat context assembler (MongoDB read/write)
│   │   ├── router.py                    # FastAPI router: /chat, /orchestrate/v2, /internal/*
│   │   ├── models.py                    # NEW v6: ChatRequest, ChatStreamEvent, ChatEventType
│   │   ├── system_prompt.py             # NEW v6: Runtime context fetch + system prompt builder
│   │   ├── tools.py                     # NEW v6: 8 chat-specific tool definitions
│   │   └── handler.py                   # NEW v6: Foreground SSE agentic loop (15 iterations)
│   ├── background/
│   │   ├── __init__.py                  # NEW v6
│   │   ├── escalation.py               # NEW v6: EscalationTracker, needs_escalation()
│   │   ├── tools.py                     # NEW v6: Background tool subset (~30 tools)
│   │   └── handler.py                   # NEW v6: Simplified background agentic loop
│   ├── graph/
│   │   ├── orchestrator.py              # LangGraph StateGraph, state, routing, streaming
│   │   │                                #   build_orchestrator_graph() — legacy 14-node
│   │   │                                #   build_delegation_graph()  — NEW 7-node delegation
│   │   │                                #   get_orchestrator_graph()  — feature flag switch
│   │   ├── dag_executor.py              # NEW: DAG parallel execution engine
│   │   └── nodes/
│   │       ├── __init__.py              # Re-exports all nodes
│   │       ├── _helpers.py              # LLM wrapper, JSON parsing, cloud escalation
│   │       ├── intake.py                # Classification, clarification (+language detection)
│   │       ├── evidence.py              # KB + tracker artifact fetch
│   │       ├── respond.py               # Direct answers (ADVICE + SINGLE_TASK/respond)
│   │       ├── plan.py                  # SINGLE_TASK planning (respond/code/tracker/mixed)
│   │       ├── execute.py               # Step execution (respond/code/tracker dispatch)
│   │       ├── evaluate.py              # Result evaluation, routing, step/goal advancement
│   │       ├── git_ops.py               # Git commit/push with approval gates
│   │       ├── finalize.py              # Final report generation
│   │       ├── coding.py                # Decompose, select_goal, plan_steps
│   │       ├── epic.py                  # EPIC planning + wave execution (Phase 3)
│   │       ├── design.py                # GENERATIVE design (Phase 3)
│   │       ├── plan_delegations.py      # NEW: LLM-driven agent selection
│   │       ├── execute_delegation.py    # NEW: Dispatch + monitoring via DAGExecutor
│   │       └── synthesize.py            # NEW: Merge agent results + RAG cross-check
│   ├── llm/
│   │   ├── provider.py                  # LLM abstraction (litellm), streaming, token-arrival liveness
│   │   └── (gpu_router.py removed — auto-reservation handled by router)
│   ├── agents/
│   │   ├── __init__.py
│   │   ├── base.py                      # NEW: BaseAgent abstract class, agentic loop
│   │   ├── registry.py                  # NEW: AgentRegistry singleton
│   │   ├── legacy_agent.py              # NEW: Wrapper of existing 14-node logic (fallback)
│   │   ├── job_runner.py                # K8s Job creation, async dispatch, status polling, result reading
│   │   ├── workspace_manager.py         # .jervis/ files, CLAUDE.md, MCP config
│   │   └── specialists/                 # NEW: 19 specialist agents
│   │       ├── __init__.py
│   │       ├── code_agent.py            # CodingAgent — K8s Job delegation
│   │       ├── git_agent.py             # GitAgent — git operations
│   │       ├── review_agent.py          # CodeReviewAgent — code review
│   │       ├── test_agent.py            # TestAgent — test generation/execution
│   │       ├── research_agent.py        # ResearchAgent — KB/web/code search
│   │       ├── tracker_agent.py         # IssueTrackerAgent — issue CRUD
│   │       ├── wiki_agent.py            # WikiAgent — wiki page CRUD
│   │       ├── documentation_agent.py   # DocumentationAgent — docs generation
│   │       ├── devops_agent.py          # DevOpsAgent — CI/CD, K8s
│   │       ├── project_management_agent.py  # ProjectManagementAgent — sprint/epic
│   │       ├── security_agent.py        # SecurityAgent — security analysis
│   │       ├── communication_agent.py   # CommunicationAgent — messaging hub
│   │       ├── email_agent.py           # EmailAgent — email operations
│   │       ├── calendar_agent.py        # CalendarAgent — scheduling
│   │       ├── administrative_agent.py  # AdministrativeAgent — logistics
│   │       ├── legal_agent.py           # LegalAgent — contracts, compliance
│   │       ├── financial_agent.py       # FinancialAgent — budget, invoices
│   │       ├── personal_agent.py        # PersonalAgent — personal assistant
│   │       └── learning_agent.py        # LearningAgent — tutorials, evaluations
│   ├── context/
│   │   ├── context_store.py             # MongoDB hierarchical context store (+scope=delegation)
│   │   ├── context_assembler.py         # Per-node LLM context assembly (+token budgets)
│   │   ├── distributed_lock.py          # MongoDB distributed lock
│   │   ├── session_memory.py            # NEW: Per-client/project session memory (7d TTL)
│   │   ├── procedural_memory.py         # NEW: KB procedure lookup/save
│   │   ├── summarizer.py               # NEW: AgentOutput summarization
│   │   └── retention_policy.py          # NEW: What to save vs discard
│   ├── kb/
│   │   └── prefetch.py                  # KB context pre-fetch for agents and orchestrator
│   ├── tools/
│   │   ├── definitions.py               # Tool schemas (+per-agent tool sets)
│   │   ├── executor.py                  # NEW: Tool execution engine for agents
│   │   └── kotlin_client.py             # Push client (progress, status, streaming tokens → Kotlin)
│   ├── monitoring/
│   │   └── delegation_metrics.py        # NEW: Per-agent delegation metrics
│   └── whisper/
│       └── correction_agent.py          # Transcript correction (KB + Ollama)
├── Dockerfile
└── requirements.txt
```

### Kotlin server (orchestrátor-related)

```
backend/server/src/main/kotlin/com/jervis/
├── configuration/
│   └── PythonOrchestratorClient.kt      # REST client + all DTOs
├── entity/
│   ├── ChatMessageDocument.kt           # Individual chat messages
│   └── ChatSummaryDocument.kt           # Compressed chat summary blocks
├── repository/
│   ├── ChatMessageRepository.kt         # Message CRUD + search
│   └── ChatSummaryRepository.kt         # Summary CRUD
├── rpc/
│   ├── KtorRpcServer.kt                 # /internal/ push endpoints
│   └── AgentOrchestratorRpcImpl.kt      # Chat RPC + emit helpers
├── service/
│   ├── agent/coordinator/
│   │   ├── AgentOrchestratorService.kt  # Dispatch + resume logic
│   │   ├── OrchestratorStatusHandler.kt # State transitions (push + poll)
│   │   # (OrchestratorHeartbeatTracker removed — replaced by timestamp-based stuck detection via DB fields)
│   ├── background/
│   │   └── BackgroundEngine.kt          # 4 loops: indexing, execution, scheduler, result
│   └── chat/
│       ├── ChatMessageService.kt        # Message CRUD service
│       └── ChatHistoryService.kt        # History payload + async compression
```

---

## Common Bugs and Fixes

### Respond Node Tool-Use Loop (Fixed 2026-02-11)

**Symptom:** Agent executes 1 tool call, then immediately logs "max iterations (5) reached" and returns answer.

**Root Cause:** Incorrect indentation in `respond.py`. The "max iterations reached" block (lines 180-192) was INSIDE the while loop instead of OUTSIDE. This caused it to execute after EVERY tool execution instead of only when the loop limit was reached.

**Incorrect Code:**
```python
while iteration < _MAX_TOOL_ITERATIONS:
    iteration += 1
    response = await llm_with_cloud_fallback(...)
    
    if not tool_calls or finish_reason == "stop":
        return {"final_result": message.content}
    
    # Execute tools
    for tool_call in tool_calls:
        result = execute_tool(...)
        messages.append({"role": "tool", ...})
    
    # ❌ WRONG - This is INSIDE the while loop!
    logger.warning("Respond: max iterations reached")
    final_response = await llm_with_cloud_fallback(...)
    return {"final_result": answer}
```

**Fixed Code:**
```python
while iteration < _MAX_TOOL_ITERATIONS:
    iteration += 1
    response = await llm_with_cloud_fallback(...)
    
    if not tool_calls or finish_reason == "stop":
        return {"final_result": message.content}
    
    # Execute tools
    for tool_call in tool_calls:
        result = execute_tool(...)
        messages.append({"role": "tool", ...})
    # Continue loop - will call LLM again with tool results

# ✅ CORRECT - This is OUTSIDE the while loop
logger.warning("Respond: max iterations reached")
final_response = await llm_with_cloud_fallback(...)
return {"final_result": answer}
```

**Fix:** De-indent the "max iterations" block by one level (4 spaces) so it executes only AFTER the while loop exits.

**Commit:** `6f257acd` — "fix: chat UI improvements and respond loop bug"

### Empty Response from LLM with Tool Calls (Fixed 2026-02-11)

**Symptom:** Error "Empty response from local model" when LLM makes a tool call.

**Root Cause:** Validation in `_helpers.py` line 85-87 only checked `message.content`, which is None/empty when the LLM calls a tool. The actual response is in `message.tool_calls`, not `content`.

**Incorrect Code:**
```python
content = response.choices[0].message.content
if not content or not content.strip():
    raise ValueError("Empty response from local model")  # ❌ WRONG
```

**Fixed Code:**
```python
message = response.choices[0].message
content = message.content
tool_calls = getattr(message, "tool_calls", None)

# Valid response = has content OR has tool_calls
if (not content or not content.strip()) and not tool_calls:
    raise ValueError("Empty response from local model")  # ✅ CORRECT
```

**Fix:** Accept either `content` OR `tool_calls` as a valid response. Only raise error if BOTH are missing.

**Commit:** `60205503` — "fix: accept tool_calls as valid LLM response"

---

## Architecture Decisions

### PVC for Orchestrator: NO (Decided 2026-02-11)

**Question:** Should orchestrator have PVC mount for filesystem tools access?

**Decision:** **NO** — Orchestrator should remain stateless and use knowledge APIs instead.

**Reasoning:**

1. **Separation of Concerns:**
   - Orchestrator = BRAIN (decides what to do)
   - Coding Agents = HANDS (execute tasks, access code)
   - Filesystem access is agent responsibility, not orchestrator

2. **Scalability:**
   - Stateless orchestrator can scale horizontally
   - PVC would tie orchestrator to specific nodes
   - Multiple orchestrator pods would need shared PVC (complexity)

3. **Security:**
   - Orchestrator with filesystem access = larger attack surface
   - Agents already have controlled workspace access
   - Principle of least privilege

4. **Existing Solutions:**
   - **Knowledge Base:** Indexed, searchable project knowledge (fast)
   - **web_search:** External information via SearXNG
   - **kb_search:** Internal project-specific knowledge
   - **Coding agents:** Direct code access when needed

**Alternative Approach:**
If orchestrator needs code exploration:
1. Use `kb_search` tool for indexed knowledge
2. Dispatch to coding agent (Claude/Kilo) for deeper exploration
3. Agent can use filesystem tools and report back

**Conclusion:** Keep orchestrator stateless. Use tools (web_search, kb_search) + agent dispatch for information gathering.

---

## Memory Agent

> **Spec:** See `docs/orchestrator-memory-spec.md` for full architecture.

### Overview

The Memory Agent provides structured working memory between turns, context switching when users change topics, and immediate availability of recently stored data. It adds two graph nodes (`memory_load`, `memory_flush`) and three LLM tools to every orchestration.

### New Graph Nodes

**`memory_load`** — runs between `intake` and `evidence_pack`:
- Creates/restores `MemoryAgent` from serialized state (or cold-starts from KB)
- Detects context switches via LLM classification: `CONTINUE`, `SWITCH`, `AD_HOC`, `NEW_AFFAIR`
- Parks current affair and activates target on SWITCH
- Composes token-budgeted context string for downstream nodes
- Returns: `memory_agent` (dict), `memory_context` (str), `context_switch_type` (str)

**`memory_flush`** — runs between `respond`/`synthesize` and `finalize`:
- Appends current query + response to active affair messages (max 20)
- Drains write buffer (pending KB writes)
- Returns: updated `memory_agent` (dict)

### New State Fields

| Field | Type | Description |
|-------|------|-------------|
| `memory_agent` | `dict \| None` | Serialized MemoryAgent (affairs, session, LQM reference) |
| `memory_context` | `str \| None` | Composed context string injected into respond node |
| `context_switch_type` | `str \| None` | Last detected switch type |

### New LLM Tools (respond node)

| Tool | Description |
|------|-------------|
| `memory_store` | Store facts/decisions/orders to active affair + KB write buffer |
| `memory_recall` | Search LQM write buffer + affairs + KB with scope (current/all/kb_only) |
| `list_affairs` | List active + parked affairs with details |

### Key Components (`app/memory/`)

- **`models.py`** — `Affair`, `AffairStatus`, `SessionContext`, `ContextSwitchResult`, `PendingWrite`, `WritePriority`
- **`content_reducer.py`** — **Central content reduction module.** `reduce_for_prompt()` (async LLM reduction), `reduce_messages_for_prompt()` (batch message fitting), `trim_for_display()` (display-only truncation). Replaces all hard-coded `[:N]` truncation. Supports cloud escalation via `state` parameter (auto-Gemini for content exceeding current model's context)
- **`lqm.py`** — Local Quick Memory: 3-layer RAM cache (hot affairs dict, async write buffer queue, LRU warm cache with TTL)
- **`context_switch.py`** — LLM-based context switch detection (Czech prompt, confidence threshold 0.7). Uses `reduce_for_prompt` for summary/message in classification prompt
- **`affairs.py`** — Affair lifecycle: create, park (with LLM summarization), resume, resolve, load from KB. Uses `reduce_messages_for_prompt` for budget-aware message building
- **`composer.py`** — Token-budgeted context composition (40% active affair, 10% parked, 15% user context). **Async** — uses LLM reduction for large summaries/facts/messages instead of hard truncation
- **`agent.py`** — `MemoryAgent` facade; process-global LQM singleton, per-orchestration agent instances
- **`consolidation.py`** — Topic-aware memory consolidation. Uses `reduce_for_prompt` for combined summaries and `reduce_messages_for_prompt` for affair messages

### Graph Flow (when enabled)

```
intake → memory_load → evidence_pack → ... → respond → memory_flush → finalize
```

For delegation graph:
```
intake → memory_load → evidence_pack → plan_delegations → execute_delegation → synthesize → memory_flush → finalize
```

### Outcome Ingestion

When Memory Agent is active, ADVICE tasks with affair context become significant for KB ingestion. The extraction prompt is enriched with active affair title, key facts, and parked affair titles.

---

## TODO / Future Improvements

### Short-Term Conversation Memory (Priority: HIGH) — PARTIALLY ADDRESSED

**Problem:** Agent doesn't retain context from previous iterations in the same conversation.

**Status:** Partially addressed by Session Memory (section 24.2) and Chat History (section 16). Session Memory provides per-client/project 7-day cache of recent decisions. Chat History provides verbatim last 20 messages + rolling summaries.

**Remaining gap:** Session Memory is cross-task (shared across orchestrations for same client/project). Within a single orchestration, agent nodes still rely on chat_history for conversational context. The agentic loop within specialist agents (section 23.4) does not yet carry forward intermediate LLM conversation turns between tool calls.

### Multi-Agent System Rollout (Priority: HIGH)

**Status:** Foundation in progress (see sections 18-25). Key implementation tasks remaining:

1. **Complete specialist agent implementations** — 6 of 19 agents have initial implementations (tracker, wiki, documentation, devops, project_management, security). Remaining 13 need implementation.
2. **base.py + registry.py** — BaseAgent and AgentRegistry need to be created (spec defined in plan).
3. **New graph nodes** — `plan_delegations.py`, `execute_delegation.py`, `synthesize.py` need implementation.
4. **DAG executor** — `dag_executor.py` needs implementation.
5. **Memory layers** — `session_memory.py`, `procedural_memory.py`, `summarizer.py`, `retention_policy.py` need implementation.
6. **Tool executor** — `tools/executor.py` for agent tool dispatch.
7. **Integration testing** — End-to-end delegation flow with feature flags.

---

## 30. Hardening (W-9 to W-23) — Robustness Improvements

> **Implemented:** 2026-02-21 | **Scope:** respond node, executor, context, provider, distributed lock

### 30.1 Overview

Systematic hardening of the Python orchestrator addressing 15 weak spots identified in the post-v5 audit. All changes are backward-compatible and use local-first approach (cloud never called unless explicitly allowed in project rules).

### 30.2 Changes by File

#### `app/tools/executor.py`
- **W-11: Tool Result Size Bound** — **REMOVED.** `_truncate_result()` is now a pass-through (returns result as-is). No truncation of tool results — routing system handles context limits (>48k → OpenRouter).
- **W-22: Tool Execution Timeout** — `_TOOL_EXECUTION_TIMEOUT_S = 120`. Exported for use in respond node.

#### `app/graph/nodes/respond.py`
- **W-22: Tool Execution Timeout** — Each `execute_tool()` call wrapped in `asyncio.wait_for(timeout=120s)`. Timeout returns error string (not exception).
- **W-17: JSON Workaround Validation** — Ollama tool_call JSON parsing now validates structure: checks `tool_calls` is list, each entry is dict, has `function.name`. Invalid entries are skipped with warning.
- **W-13: Quality Escalation** — Short answer detection: if answer < 40 chars, retries once with "expand your answer" system message. `_MIN_ANSWER_CHARS = 40`, `_MAX_SHORT_RETRIES = 1`.
- **W-12: Real Token Streaming** — New `_stream_answer_realtime()` uses `llm_provider.stream_completion()` for real-time token emission. Falls back to fake chunked streaming on error.
- **W-19: User Message Save** — User query and assistant answer saved to MongoDB via `chat_context_assembler.save_message()` for chat history persistence.

#### `app/graph/nodes/_helpers.py`
- **W-14: Context Overflow Guard** — Before LLM call, validates `context_tokens` fits selected tier's `num_ctx`. If exceeded, removes whole messages (oldest tool results first, then middle messages) while protecting system message and last 4 messages. **No per-message content truncation** — messages are either included in full or removed entirely.

#### `app/chat/context.py`
- **W-20: Sequence Number Race** — `get_next_sequence()` uses atomic `findOneAndUpdate` on `chat_sequence_counters` collection instead of `count_documents + 1`.
- **W-15: Compression Error Handling** — `_compress_block()` retries `COMPRESS_MAX_RETRIES = 2` times with exponential backoff. On exhaustion, saves placeholder marker so block isn't re-attempted. `maybe_compress()` accepts `done_callback` for completion notification. Compression prompt is content-complete (no arbitrary char limit), preserves KB references (sourceUrn, correlationId, ticket IDs), and tags multi-project summaries with `[Projekt X]:` prefixes. Input messages are truncated to 2000 chars (not 500).
- **W-10: Checkpoint Message Growth** — **REMOVED.** `save_message()` stores full TOOL role messages without truncation. No `MAX_TOOL_RESULT_IN_MSG` limit.

#### `app/llm/provider.py`
- **W-21: REMOVED** — LLM rate limiting semaphores removed. Router manages all GPU concurrency via its request queue (priority-based dispatch, CRITICAL preemption). No artificial limits on orchestrator side.

#### `app/graph/nodes/finalize.py`
- **W-16: Background Quality Escalation** — Logs warning when background task has failed steps (quality check without LLM summary generation).

#### `app/memory/lqm.py`
- **W-18: Global Cache Race** — Added `asyncio.Lock` for affair mutations in LQM. Defensive measure for concurrent asyncio coroutines.

### 30.3 New MongoDB Collections

| Collection | Purpose | Document schema |
|-----------|---------|-----------------|
| `chat_sequence_counters` | W-20: Atomic sequence numbers | `{_id: "seq_{taskId}", counter: int}` |

### 30.4 New Constants

| Constant | Value | File | Purpose |
|----------|-------|------|---------|
| `MAX_TOOL_RESULT_CHARS` | ~~8000~~ REMOVED | executor.py | W-11: No longer enforced — pass-through |
| `_TOOL_EXECUTION_TIMEOUT_S` | 120 | executor.py | W-22: Per-tool timeout |
| `_MIN_ANSWER_CHARS` | 40 | respond.py | W-13: Short answer threshold |
| `_MAX_SHORT_RETRIES` | 1 | respond.py | W-13: Retry limit |
| `COMPRESS_MAX_RETRIES` | 2 | context.py | W-15: Compression retry limit |
| `MAX_TOOL_RESULT_IN_MSG` | ~~2000~~ REMOVED | context.py | W-10: No longer enforced — full storage |

### 30.5 Test Suite

Tests in `backend/service-orchestrator/tests/test_hardening.py`:
- Unit tests for truncation, context guard, JSON validation, escalation policy
- No MongoDB/LLM required — pure unit tests
- Run: `cd backend/service-orchestrator && python -m pytest tests/`

### 30.6 Cloud Safety

**CRITICAL:** Cloud models are NEVER called unless explicitly allowed in project rules (`auto_use_anthropic`, `auto_use_openai`, `auto_use_gemini`, `auto_use_openrouter`). All hardening changes (W-14 context guard, W-13 quality retry) use local Ollama only. The `llm_with_cloud_fallback` in `_helpers.py` checks `auto_providers(rules)` which derives from project settings — this is the ONLY path to cloud and it respects project configuration. OpenRouter is unrestricted — when enabled, it can handle any task type and any context size, routing via the priority model list configured in OpenRouter settings.

---

## 31. v6 Architecture — Dedicated Chat & Background Handlers

> **Implemented:** 2026-02-21 | **Scope:** New foreground chat handler, simplified background handler, model escalation, runtime context, chat-specific tools

### 31.1 Motivation

The v5 architecture routed both foreground (interactive chat) and background (autonomous tasks) through the same 14-node LangGraph orchestrator. This caused:

- **Foreground latency:** Every chat message traversed 14 nodes even for simple Q&A
- **Streaming complexity:** Progress streaming was bolted onto graph nodes
- **Inflexible tool sets:** Same tools for chat and background, no chat-specific capabilities
- **No model escalation:** Background tasks couldn't recover from model failures

v6 introduces **two dedicated handlers**, independent from LangGraph, with purpose-built tool sets and model escalation.

### 31.2 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Kotlin Server                             │
│                                                              │
│  AgentOrchestratorRpcImpl ──┐                               │
│                              │  POST /chat                   │
│  BackgroundEngine ──────────┤  POST /orchestrate/v2          │
│                              │  POST /orchestrate (legacy)    │
└──────────────────────────────┼──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│               Python Orchestrator (FastAPI)                   │
│                                                              │
│  /chat ──────────────→ app/chat/handler.py                  │
│                          • 45 tools (37 base + 8 chat)       │
│                          • Chat SSE streaming via kotlin_client│
│                          • Runtime context system prompt      │
│                          • MongoDB save + compression         │
│                                                              │
│  /orchestrate/v2 ────→ app/background/handler.py            │
│                          • ~30 tools (background subset)      │
│                          • No streaming (status push)         │
│                          • Model tier escalation              │
│                          • Fire-and-forget via asyncio.task   │
│                                                              │
│  /orchestrate ───────→ app/graph/orchestrator.py (legacy)   │
│                          • 14-node LangGraph (kept for now)   │
└─────────────────────────────────────────────────────────────┘
```

### 31.3 Foreground Chat Handler (`app/chat/handler.py`)

**Entry point:** `POST /chat` → `handle_chat(ChatRequest) → dict`

**Flow:**

1. **Save user message** to MongoDB via `chat_context_assembler.save_message()`
2. **Fetch runtime context** — clients/projects, pending user tasks, unclassified meetings (cached 5min)
3. **Load memory context** — KB search for task-relevant context
4. **Build system prompt** — persona rules + dynamic runtime sections
5. **Assemble history** — last 20 messages + summaries from MongoDB
6. **Agentic loop** (max 15 iterations):
   - Call LLM with messages + 45 tools
   - Parse tool_calls (native or Ollama JSON workaround)
   - Execute tools (120s timeout per tool) with **effective scope** (updated by `switch_context`)
   - Detect tool loop (same tool+args 3× → force answer)
   - Append results → next iteration
   - **Scope tracking:** `effective_client_id`/`effective_project_id` initialized from request, updated by `switch_context` and tool arguments. All tool calls use effective scope, not stale request scope.
   - **Project boundary:** On `switch_context`, a `[KONTEXT PŘEPNUT]` boundary message is saved to MongoDB so summaries and context assembly can distinguish project contexts. The LLM is instructed to not carry information from previous project to the new one.
7. **Stream answer** — chunked tokens via `kotlin_client.emit_streaming_token()`
8. **Short answer retry** — if < 40 chars, retry once with "expand" instruction
9. **Save assistant message** to MongoDB
10. **Fire-and-forget compression** — `asyncio.create_task(maybe_compress())`

**Constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `_MAX_ITERATIONS` | 15 | Max agentic loop turns |
| `_MIN_ANSWER_CHARS` | 40 | Short answer detection (W-13) |
| `_MAX_SHORT_RETRIES` | 1 | Short answer retry limit |
| `_STREAM_CHUNK_SIZE` | 12 | Token chunk size for streaming |
| `_RUNTIME_CTX_TTL` | 300.0 | Runtime context cache TTL (5 min) |

**Communication:**
- **Kotlin → Python:** `POST /chat` with `ChatRequest` JSON
- **Python → Kotlin:** `POST /internal/streaming-token` (real-time tokens)
- **Python → MongoDB:** `chat_messages` (save), `chat_summaries` (compress)
- **Python → Kotlin internal API:** `/internal/clients-projects`, `/internal/pending-user-tasks/summary`, `/internal/unclassified-meetings/count`, `/internal/tasks/*`

### 31.4 Chat Request Model (`app/chat/models.py`)

```python
class ChatRequest(BaseModel):
    task_id: str
    client_id: str
    project_id: str | None = None
    client_name: str | None = None
    project_name: str | None = None
    query: str
    workspace_path: str = ""
    processing_mode: str = "FOREGROUND"
    max_openrouter_tier: str = "NONE"  # "NONE" / "FREE" / "PAID" / "PREMIUM"
    auto_use_anthropic: bool = False
    auto_use_openai: bool = False
    auto_use_gemini: bool = False
    auto_use_openrouter: bool = False

class ChatStreamEvent(BaseModel):
    event_type: ChatEventType  # token | thinking | tool_call | tool_result | done | error
    content: str = ""
    tool_name: str | None = None
    tool_args: dict | None = None
    tool_call_id: str | None = None
    metadata: dict | None = None
```

### 31.5 Runtime System Prompt (`app/chat/system_prompt.py`)

The system prompt is assembled dynamically at each chat turn:

```
┌─────────────────────────────────────────┐
│ Static persona rules (Czech language)    │
│  • Role, behavior, output format         │
│  • Tool usage instructions               │
│  • Safety constraints                    │
├─────────────────────────────────────────┤
│ Dynamic: Available clients & projects    │  ← /internal/clients-projects
├─────────────────────────────────────────┤
│ Dynamic: Pending user tasks              │  ← /internal/pending-user-tasks/summary
├─────────────────────────────────────────┤
│ Dynamic: Unclassified meetings count     │  ← /internal/unclassified-meetings/count
├─────────────────────────────────────────┤
│ Dynamic: User context from request       │  ← ChatRequest fields
├─────────────────────────────────────────┤
│ Dynamic: Memory/KB context               │  ← kb_search results
└─────────────────────────────────────────┘
```

`fetch_runtime_context()` calls Kotlin internal API endpoints with graceful degradation — if any endpoint fails, that section is omitted (not a fatal error). Results are cached for 5 minutes.

### 31.6 Chat-Specific Tools (`app/chat/tools.py`)

8 new tools available ONLY in foreground chat (not in background):

| Tool | Description | Implementation |
|------|-------------|----------------|
| `create_background_task` | Create a new background task for autonomous processing | POST /internal/tasks/create |
| `search_tasks` | Search existing tasks by query | POST /internal/tasks/search |
| `get_task_status` | Get status of a specific task | GET /internal/tasks/{id} |
| `list_recent_tasks` | List recent tasks for current client | GET /internal/tasks/recent |
| `respond_to_user_task` | Respond to a pending user-review task | POST /internal/tasks/{id}/respond |
| `dispatch_coding_agent` | Dispatch K8s coding agent (Claude/Kilo) | K8s Job via job_runner |
| `classify_meeting` | Classify an unclassified meeting | POST /internal/meetings/{id}/classify |
| `list_unclassified_meetings` | List meetings awaiting classification | GET /internal/unclassified-meetings |

Additionally, 6 thinking graph tools for coordination/planning tasks:

| Tool | Description | Implementation |
|------|-------------|----------------|
| `create_thinking_graph` | Create a new thinking graph (visual DAG in chat panel) | `app/chat/thinking_graph.py` |
| `add_graph_vertex` | Add a vertex to the active thinking graph | `thinking_graph.add_vertex()` |
| `update_graph_vertex` | Update an existing vertex | `thinking_graph.update_vertex()` |
| `remove_graph_vertex` | Remove a vertex and its edges | `thinking_graph.remove_vertex()` |
| `dispatch_thinking_graph` | Finalize graph and dispatch as background task | `thinking_graph.dispatch_graph()` |
| `run_graph_vertex` | Dispatch a single vertex as background task | `thinking_graph.run_vertex()` |

**Total tool count:** 37 base tools (from `ALL_RESPOND_TOOLS_FULL`) + 8 chat-specific + 6 thinking graph = **51 tools**

### 31.6a Chat Workflow: Direct Coding vs Thinking Graphs

Two distinct workflows in foreground chat:

**Direct coding tasks** (simple, single-scope implementation):
```
User: "Přidej active field do ProjectDocument"
  → LLM understands the request
  → LLM produces a text plan (Czech summary of what will be done)
  → User approves the plan in chat
  → dispatch_coding_agent (no approval gate, no thinking graph)
  → K8s Job runs asynchronously
  → Result notification in chat
```
Direct coding tasks do NOT use thinking graphs. The LLM generates a plain text plan, the user confirms, and `dispatch_coding_agent` is called directly. `dispatch_coding_agent` is no longer gated by the approval flow — it dispatches immediately after user confirmation in chat.

**Thinking graphs** (coordination, planning, multi-system analysis):
```
User: "Naplánuj dovolenou pro tým — koordinace s kalendáři, úkoly, notifikace"
  → LLM creates thinking graph (create_thinking_graph)
  → LLM adds vertices (add_graph_vertex) — investigation, coordination, execution steps
  → Graph displayed visually in chat panel
  → User reviews and approves
  → dispatch_thinking_graph → background task with full graph execution
```
Thinking graphs are used for complex coordination tasks: vacation planning, cross-project work, multi-system analysis — anything requiring structured decomposition with dependencies. The graph is built interactively in chat and dispatched only after explicit user approval.

**Source:** `app/chat/thinking_graph.py`, `app/chat/tools.py`

### 31.7 Background Handler (`app/background/handler.py`)

**Entry point:** `POST /orchestrate/v2` → fire-and-forget → `handle_background(OrchestrateRequest) → dict`

**4-Phase Flow:**

```
Phase 1: INTAKE
  └─ Analyze task, select initial model tier, build context

Phase 2: EXECUTE (agentic loop, max 15 iterations)
  └─ LLM → tool_calls → execute → repeat
  └─ On failure → EscalationTracker bumps model tier
  └─ Max 3 escalation retries per failure

Phase 3: DISPATCH (if coding needed)
  └─ K8s Job via dispatch_coding_agent tool

Phase 4: FINALIZE
  └─ Save result to MongoDB
  └─ Notify Kotlin (report_status_change)
  └─ Log quality metrics
```

**Context management (dynamic tier selection):**

Background handler dynamically estimates context size before each LLM call
(same pattern as chat `handler_agentic.estimate_and_select_tier`):
1. Estimates tokens: `(messages + tools + output_reserve) // 4`
2. Selects tier via `EscalationPolicy.select_local_tier()`, clamped to `LOCAL_XLARGE` (128k max)
3. Re-estimates after each iteration — tool results grow the context
4. Auto-escalates if context exceeds 85% of current tier's `num_ctx`
5. Detects Ollama context overflow ("Operation not allowed" as text response) and escalates

**Search tool rate limiting:**

Search tools (`brain_search_issues`, `kb_search`, `web_search`, `brain_search_pages`)
are limited to max 3 calls per task. After 3 calls, a STOP message forces the LLM
to conclude with available results. Prevents idle-review loops.

**Key differences from foreground chat:**

| Aspect | Foreground (chat) | Background (v2) |
|--------|-------------------|------------------|
| Streaming | SSE tokens via kotlin_client | No streaming (status push only) |
| Tools | 45 (37 base + 8 chat) | ~30 (subset, no ask_user/memory/list_affairs) |
| Model selection | Dynamic + clamped to LOCAL_LARGE | Dynamic + clamped to LOCAL_XLARGE |
| Execution | Synchronous (awaited) | Fire-and-forget (asyncio.create_task) |
| System prompt | Dynamic runtime context | Basic task context |
| Compression | Fire-and-forget after answer | At finalize |

### 31.8 Model Tier Escalation (`app/background/escalation.py`)

Background tasks use progressive model escalation when the current tier fails:

```
LOCAL_FAST → LOCAL_STANDARD → LOCAL_LARGE → LOCAL_XLARGE → (cloud, if allowed)
```

**`needs_escalation(answer, tool_parse_failures, iteration)`** detects:
- Empty or None response
- Refusal patterns ("I cannot", "I'm unable", "I don't have access")
- Tool parse failures ≥ 2 (JSON workaround issues)
- Gibberish detection (low alphabetic ratio)

**`get_next_tier(current, cloud_allowed)`** follows the escalation path. Cloud bridge (`CLOUD_OPENROUTER → CLOUD_REASONING → CLOUD_CODING → CLOUD_PREMIUM`) is ONLY accessible when `cloud_allowed=True`, derived from project rules. OpenRouter is first in the cloud chain — unrestricted, handles any task.

**`EscalationTracker`** — stateful tracker per background execution:
- Tracks current tier, escalation count, failure reasons
- Max 3 escalation retries before giving up
- `escalate() → ModelTier | None` — returns next tier or None if exhausted

### 31.9 Background Tool Subset (`app/background/tools.py`)

Background tasks use a reduced tool set — excludes interactive and session-specific tools:

**Included:** KB (search, store, traverse, graph_search), web_search, repository tools (list_repos, list_branches, get_commits, read_file_content, search_code), git workspace tools (git_status, git_diff, git_commit, git_push, create_branch), filesystem tools (read_file, list_files, search_files, write_file, create_directory), terminal (execute_command), scheduled tasks (create_scheduled_task, list_scheduled_tasks), coding agent dispatch, brain tools (Jira/Confluence CRUD).

**Excluded:** `ask_user`, `memory_store`, `memory_recall`, `list_affairs` (foreground-only), `respond_to_user_task`, `classify_meeting`, `list_unclassified_meetings`.

### 31.10 New API Endpoints

| Endpoint | Method | Handler | Mode |
|----------|--------|---------|------|
| `/chat` | POST | `app/chat/handler.handle_chat` | Synchronous (awaited) |
| `/orchestrate/v2` | POST | `app/background/handler.handle_background` | Fire-and-forget |
| `/approve/{thread_id}` | POST | `app/main.approve` | Fire-and-forget resume |

`/chat` and `/orchestrate/v2` are registered in `app/chat/router.py`. `/approve/{thread_id}` is in `app/main.py`.

### 31.11 Kotlin Internal REST Endpoints (Implemented)

All v6 Python tool endpoints are implemented in `KtorRpcServer.kt` as thin REST wrappers
delegating to existing Kotlin services:

| Endpoint | Method | Delegates to | Used by |
|----------|--------|-------------|---------|
| `/internal/clients-projects` | GET | `ClientRpcImpl.getAllClients()` + `ProjectRpcImpl.listProjectsForClient()` | system_prompt.py |
| `/internal/pending-user-tasks/summary` | GET | `TaskRepository.findByTypeAndState(USER_TASK, PENDING)` | system_prompt.py |
| `/internal/unclassified-meetings/count` | GET | `MeetingRpcImpl.listUnclassifiedMeetings()` | system_prompt.py |
| `/internal/tasks/create` | POST | `TaskService.createTask()` | chat tool |
| `/internal/tasks/search` | GET | `TaskRepository.findAllByOrderByCreatedAtAsc()` + text filter | chat tool |
| `/internal/tasks/{id}/status` | GET | `TaskRepository.getById()` | chat tool |
| `/internal/tasks/recent` | GET | `TaskRepository.findAllByOrderByCreatedAtAsc()` | chat tool |
| `/internal/tasks/{id}/respond` | POST | `TaskRepository.save()` (state transition) | chat tool |
| `/internal/meetings/{id}/classify` | POST | `MeetingRpcImpl.classifyMeeting()` | chat tool |
| `/internal/unclassified-meetings` | GET | `MeetingRpcImpl.listUnclassifiedMeetings()` | chat tool |
| `/internal/dispatch-coding-agent` | POST | `TaskService.createTask(QUEUED)` | chat tool |

Request DTOs: `InternalCreateTaskRequest` (in InternalTaskApiRouting.kt), `InternalRespondToTaskRequest`, `InternalClassifyMeetingRequest`, `InternalDispatchCodingAgentRequest` (in KtorRpcServer.kt). `InternalCreateTaskRequest` accepts both legacy (`query`) and new (`title`, `description`, `schedule`, `daysOffset`, `createdBy`) fields.

Graceful degradation: `fetch_runtime_context()` catches HTTP errors per-endpoint, so chat works even if some endpoints fail.

### 31.12 New File Inventory

```
backend/service-orchestrator/
├── app/
│   ├── chat/
│   │   ├── __init__.py                    # (existing)
│   │   ├── context.py                     # (existing) Chat context assembler
│   │   ├── router.py                      # (modified) Added /chat, /orchestrate/v2
│   │   ├── models.py                      # NEW: ChatRequest, ChatStreamEvent
│   │   ├── system_prompt.py               # NEW: Runtime context + system prompt builder
│   │   ├── tools.py                       # NEW: 8 chat-specific tool definitions
│   │   └── handler.py                     # NEW: Foreground SSE agentic loop
│   └── background/
│       ├── __init__.py                    # NEW: Package init
│       ├── escalation.py                  # NEW: Model tier escalation logic
│       ├── tools.py                       # NEW: Background tool subset
│       └── handler.py                     # NEW: Simplified background agentic loop

backend/server/
└── src/main/kotlin/com/jervis/rpc/
    └── KtorRpcServer.kt                   # (modified) Added 11 /internal/* REST endpoints + 4 DTOs
```

### 31.13 Cloud Safety

Both handlers enforce the same cloud safety rule as the rest of the system:

- **Chat handler:** Passes `auto_use_*` flags from `ChatRequest` to `llm_with_cloud_fallback()` via project rules. Cloud is never used unless explicitly allowed.
- **Background handler:** Derives `cloud_allowed` from `OrchestrateRequest.rules` (`auto_use_anthropic or auto_use_openai or auto_use_gemini or auto_use_openrouter`). `EscalationTracker` only bridges to cloud tiers when `cloud_allowed=True`.
- **No implicit cloud:** If all project `auto_use_*` flags are `False`, escalation stops at `LOCAL_XLARGE` and the task fails if that tier can't handle it.
- **OpenRouter routing:** When `auto_use_openrouter=True`, OpenRouter is the first cloud tier tried (`CLOUD_OPENROUTER`). It routes via the priority model list configured in Settings → OpenRouter. No task type or context size restrictions — OpenRouter can handle everything.

### 31.14 Kotlin Integration (Implemented)

**Foreground chat** (master `7d31a405`):
- `ChatRpcImpl` → `ChatService` → `PythonChatClient.chat()` → SSE `POST /chat`
- Bypasses `AgentOrchestratorService` entirely; streams tokens directly to UI

**Background tasks** (v6 dispatch):
- `BackgroundEngine` → `AgentOrchestratorService.dispatchToPythonOrchestrator()`
- `dispatchBackgroundV6()` → `PythonOrchestratorClient.orchestrateV2()` → `POST /orchestrate/v2`
- Fallback: `dispatchLegacy()` → `PythonOrchestratorClient.orchestrateStream()` → `POST /orchestrate/stream`

| Kotlin method | Python endpoint | Mode |
|---------------|-----------------|------|
| `PythonChatClient.chat()` | `POST /chat` | Foreground SSE |
| `PythonOrchestratorClient.orchestrateV2()` | `POST /orchestrate/v2` | Background fire-and-forget |
| `PythonOrchestratorClient.orchestrateStream()` | `POST /orchestrate/stream` | Legacy fallback |

### 31.15 Migration Path

The v6 handlers coexist with the legacy LangGraph orchestrator:

1. **Phase 1 (current):** Both systems active; foreground→`/chat` (SSE), background→`/orchestrate/v2` with legacy fallback
2. **Phase 2 (stabilization):** Remove `dispatchLegacy()` fallback after v6 background handler is proven stable
3. **Phase 3 (cleanup):** Remove old 14-node LangGraph (`app/graph/orchestrator.py`), 22 specialist agents (`app/agents/specialists/`), unused graph nodes (`app/graph/nodes/`)

### 31.16 Chat Focus — Intent Classification & Drift Detection

**Problem:** qwen3-coder:30b model gets lost in 26 tools (~10.6k tokens = 33% of 32k context). Instead of answering simple questions, it cycles 8+ tool-call iterations (5-8 min). See `docs/chat-issues-analysis.md`.

**Solution:** 7 components reducing tool noise, maintaining focus, and detecting drift.

#### A. Tool Categories (`app/chat/tools.py`)

26 tools divided into 4 categories via `ToolCategory` enum:

| Category | Count | When exposed |
|----------|-------|-------------|
| **CORE** | 3 | Always (kb_search, web_search, memory_recall) |
| **RESEARCH** | 4 | Code/KB introspection keywords |
| **BRAIN** | 8 | Jira/Confluence keywords (issue, ticket, TPT-xxx, confluence...) |
| **TASK_MGMT** | 11 | Task/meeting keywords + switch_context + memory_store (úkol, přepni na, zapamatuj...) |

**Design decision (2026-02-23):** `switch_context` and `memory_store` moved from CORE to TASK_MGMT. Qwen3-30b has strong tool-calling bias — with 5 CORE tools, it called unnecessary tools (kb_search, switch_context, memory_store) on simple questions, causing 2 min response time instead of 5s. With 3 CORE tools, simple questions get direct answers without tool calls.

`TOOL_DOMAINS` dict maps each tool to a semantic domain (search, memory, brain, task, meeting, scope) for drift detection.

#### B. Intent Classifier (`app/chat/intent.py`)

Regex-based pre-pass (no LLM call, <1ms):

```python
classify_intent(user_message, has_pending_user_tasks, has_unclassified_meetings, has_context_task_id)
→ set[ToolCategory]   # always includes CORE
```

Patterns: `_BRAIN_PATTERNS` (Czech+English), `_TASK_MGMT_PATTERNS`, `_RESEARCH_PATTERNS`, `_FILTERING_PATTERNS`, `_GREETING_PATTERNS`. Git/coding keywords (git, branch, commit, push, merge, deploy, build) match both `_TASK_MGMT_PATTERNS` (for `dispatch_coding_agent`) and `_RESEARCH_PATTERNS` (for `code_search`). Context-driven: greeting + pending tasks → TASK_MGMT. User_task response → TASK_MGMT.

`select_tools(categories)` builds deduplicated tool list from matched categories.

**Typical result:** simple question → 3 CORE tools (~1.2k tokens) instead of 26 (~10.6k tokens).

#### B2. Simple Message Fast Path

For short messages (<500 chars) with CORE-only intent (no keywords matched), the handler tries a **direct answer without tools** first. LLM gets `tools=None` → must answer directly. If the answer is sufficient (no "potřebuji informace" markers), it's returned immediately (~5s). If insufficient, falls through to the normal agentic loop.

This eliminates the 60-120s overhead of 3-4 unnecessary tool calls for simple questions like "ahoj" or "na čem pracuju?".

#### C. Focus Reminder

After each iteration's tool results, a system message reminds the model:

```
[FOCUS] Původní otázka: "{message[:200]}"
Zbývá {remaining} iterací. Pokud máš dost info, ODPOVĚZ.
```

~80 tokens/iteration — pulls model back to the original question.

#### D. Multi-Signal Drift Detection (`_detect_drift()`)

Replaces simple "consecutive same signature" with 3 signals:

1. **Consecutive same** (2× identical tool+args) — model stuck in loop
2. **Domain drift** (3 iterations, 3+ distinct domains, no common domain) — model wandering between unrelated areas
3. **Excessive tools** (8+ distinct tools after 4+ iterations) — model unfocused

On detection, forces text response without tools (same as loop break).

#### E. Thinking Events (3 types, distinct wording)

1. **Pre-LLM** (before first iteration): `"Připravuji odpověď..."` or `"Analyzuji dlouhou zprávu..."` (>4k chars). Immediately replaces client-side "Zpracovávám..." so user gets feedback within milliseconds.
2. **Pre-tool** (before each tool call): `_describe_tool_call()` e.g. "Hledám v KB: ..."
3. **Inter-iteration** (after tool results, before next LLM): `"Analyzuji výsledky..."`. Prevents stale "Přepínám na..." during 60s LLM call.
4. **Long message warning** (>49k estimated tokens, iteration 0): `"Dlouhá zpráva — zpracování potrvá déle..."`. GPU VRAM exceeded → CPU spill → much slower.

#### F. System Prompt — Strong Direct Answer Rules + Anti-Dump

System prompt restructured (2026-02-23) to combat Qwen3's tool-calling bias:

- **⚠️ KLÍČOVÉ PRAVIDLO section** — explicit "answer from context ABOVE" instruction with concrete examples (Q: "Na čem pracuju?" → look at client/project in context and ANSWER, don't call kb_search)
- **Negative examples** — "NEVOLEJ tools v těchto případech" list with specific tools and when NOT to use them
- **Few-shot examples** — 3 concrete Q&A showing correct no-tool behavior
- "Maximálně 2-3 tool calls" — cap tool usage
- "NIKDY neukládej celou zprávu uživatele do KB/memory" — prevents model from storing user's message verbatim
- "NIKDY neukládej runtime stav" — prevents storing trivial facts like "active project is nUFO"

#### G. MAX_ITERATIONS 15 → 6

With intent filtering + focus reminders + drift detection, 6 iterations suffice. Typical: 1-2 (simple), 3-4 (multi-intent). Worst case: 6 × 60s = 6 min vs 15 × 60s = 15 min.

#### H. Long Message Intent (head+tail)

For messages >2000 chars, `classify_intent()` analyzes only first 500 + last 500 characters. Long messages (bug reports, analyses) contain keywords from all categories in the body, but the actual intent is in the first/last sentences. Full message is sent to LLM unchanged — only intent classification uses the excerpt.

#### I. Duplicate Send Guard (ChatViewModel)

`_isLoading` set to `true` synchronously BEFORE `scope.launch`. `sendMessage()` returns immediately if `_isLoading` is already true. Prevents race condition where rapid double-click or UI retry created two parallel SSE connections.

#### J. Long Message Strategy — Summarize then Act

Foreground chat uses a multi-layer strategy for long messages:

```
Message length?
  < 8k chars:  pass through unchanged (fits in context easily)
  8k-16k:      try decompose (multi-topic), else single-pass
  > 16k:       SUMMARIZE first (LOCAL_FAST ~5s), then agentic loop on summary
```

**Summarize-then-Act (messages >16k chars):**
1. Original message saved to KB FIRST — nothing is ever lost
2. LLM summarizer (LOCAL_FAST, CRITICAL priority, **90s timeout**) creates structured summary (~2-4k chars)
3. Summary preserves ALL: requirements, action items, questions, key details (IDs, names, numbers)
4. Agentic loop works with compact summary instead of raw message
5. If summarizer fails → **suggest background task** (NEVER fall back to pre-trim)

**NO-TRIM PRINCIPLE (CRITICAL):**
The current user message is NEVER truncated/trimmed. This is enforced at two levels:
- **Handler**: if summarizer fails, immediately suggest background task instead of falling through
- **LLM Provider**: `_trim_messages_for_context()` skips the last (current) user message;
  only OLD conversation history messages may be trimmed

**Summarizer timeout (90s):**
Must be >60s because Router GPU cleanup can take up to 60s when embedding model needs
to be unloaded (wait loop with 2s polling + force unload at 60s). Previous 30s timeout
caused summarizer to ALWAYS fail when embedding model was loaded on GPU.

**Tier Ceiling (VRAM protection):**
Foreground chat NEVER goes above LOCAL_LARGE (40k context). LOCAL_XLARGE (131k) causes
catastrophic CPU spill on P40 (6GB overflow, 630s for 387 tokens). Instead of escalating,
the message is summarized (not trimmed) to fit in 40k.

**Dynamic MAX_ITERATIONS:**
- Short messages (<8k): MAX_ITERATIONS=6 (standard)
- Long messages (>8k): MAX_ITERATIONS_LONG=3 (each iteration costs 3-5 min on GPU)

**Background offload:**
- FOCUS hint with background suggestion injected for ALL long messages (>16k), not just summarized ones
- System prompt instructs model: if message contains >5 distinct tasks, suggest
  `create_background_task` instead of processing everything in foreground chat
- If summarizer fails: handler immediately suggests background task (no fallback to trim)

**Cooperative disconnect:**
Disconnect event is checked not just between iterations but also inside the tool execution
loop. This prevents zombie streams from continuing to execute tools after the user sent
a new message (which sets disconnect_event on the old stream).

#### K. Long Message Decomposition

Multi-topic long messages (>8000 chars) are detected and split:

```
Message > 8000 chars?
  NO → existing flow
  YES → LLM classifier (LOCAL_FAST, head + middle samples + tail, ~3s)
        → single-topic? → existing flow (fallback)
        → multi-topic?  → extract sub-topics with char ranges
                        → process each in mini agentic loop (3 iter max)
                        → combine results via LLM combiner
```

- **Classifier**: LOCAL_FAST with CRITICAL priority, ~3500 chars sampled:
  head (1500) + 2 middle samples at 1/3 and 2/3 (500 each) + tail (500).
  Middle samples catch topic boundaries that head+tail alone would miss.
- **Sub-topic processing**: each gets own agentic mini-loop (SUBTOPIC_MAX_ITERATIONS=3), same tools/drift detection
- **Combiner**: LOCAL_FAST with CRITICAL priority, merges sub-results into one cohesive response
- **Fallback**: any classifier/parse failure → existing single-pass flow (zero regression)
- **Latency**: 3-topic message ~90s (classifier 3s + 3×25s + combiner 8s) vs 4-8 min single-pass CPU-spill

#### L. Context Overflow Handling (NO TRUNCATION)

**No message content is ever truncated.** When context exceeds the GPU's 48k token limit,
the routing system automatically sends the request to OpenRouter (up to 200k context).
For content exceeding even OpenRouter limits, LLM-based summarization (`reduce_for_prompt`)
is used — the original is saved to KB first, so nothing is lost.
If summarization fails, a background task is suggested instead of truncating.

#### M. Anti-dump Guards

The model tends to dump entire long messages into KB/memory instead of answering.
Three-layer defense:

1. **Tool removal**: For messages >8000 chars, `store_knowledge` and `memory_store`
   are removed from the tool set entirely.
2. **Content-length guard**: Both tools reject content >2000 chars with an error
   message instructing the model to summarize.
3. **Focus injection**: Long single-topic messages get an extra system message
   before the agentic loop: "ODPOVĚZ na zprávu, NEUKLÁDEJ ji."

#### N. Classifier Timeout

The decompose classifier has a hard 15s timeout (`asyncio.wait_for`).
If GPU is busy (model swap, semaphore queue), it falls back to single-pass
rather than adding 2+ minutes overhead.

#### O. Server-side Chat Dedup

If a new POST /chat arrives for a session_id that already has an active SSE stream,
the previous stream is stopped (disconnect_event.set()) before starting the new one.
Prevents duplicate concurrent processing from kRPC retries or double-clicks.

#### P. Tier Timeout Strategy

Blocking calls (tool-call mode) use tier-based timeouts:

| Tier | num_ctx | Timeout | Rationale |
|------|---------|---------|-----------|
| LOCAL_FAST | 8k | 300s | Pure GPU, ~30 tok/s |
| LOCAL_STANDARD | 32k | 300s | Pure GPU, ~30 tok/s |
| LOCAL_LARGE | **40k** | **600s** | Fits in P40 VRAM (30b + 40k KV < 24GB) |
| LOCAL_XLARGE | 128k | 900s | CPU RAM spill, ~7-12 tok/s (NOT used in foreground chat) |
| LOCAL_XXLARGE | 256k | 1200s | CPU, slowest (NOT used in foreground chat) |
| Cloud tiers | — | 300s | Fast APIs |

#### Q. Enhanced Drift Detection

Four signals for detecting model loops:

1. **Consecutive same** (existing): 2× identical tool+args → stuck
2. **Same tool 3×** (NEW): same tool name called 3+ times across ANY iterations, even non-consecutive.
   Catches: `kb_search("X")` in iter 1, `create_task` in iter 2, `kb_search("X")` in iter 4, `kb_search("Y")` in iter 6 — 3× kb_search → drift.
3. **Domain drift** (existing): 3 iterations with 3+ distinct domains, no common → wandering
4. **Excessive tools** (existing): 8+ distinct tools after 4+ iterations → unfocused

#### R. Dynamic/Learning System Prompt

System prompt contains a dynamic "Naučené postupy a konvence" section loaded from KB at chat start.

- **Loading**: `_load_learned_procedures()` searches KB for entries with procedure/convention keywords, cached 5 min.
- **Learning**: When user teaches a new procedure ("pro BMS vždy vytvoř issue"), the model stores it via `memory_store(category="procedure")`.
- **Persistence**: Stored in KB → survives restarts. Next chat session loads updated procedures.
- **Instruction**: System prompt tells model to use `memory_store(category="procedure")` for new learnings.

#### Token Impact

| | Before | After | Delta |
|---|---|---|---|
| Tool schemas (avg) | ~2,600 | ~900 | -1,700 |
| System prompt tools | ~375 | ~120 | -255 |
| Focus reminder | 0 | +80/iter | +160 |
| **Per call total** | **~2,975** | **~1,180** | **-1,795** |

---

## 32. Intent Router + Cloud-First Chat (feature-flagged)

> **Implemented:** 2026-03-01 | **Feature flag:** `use_intent_router=False` (disabled by default)

### 32.1 Motivation

Monolithic system prompt (160 lines) + 26 tools = excessive context, slow responses, unfocused tool usage.
Intent router enables: focused prompts (~60-80 lines), 3-13 tools per category, cloud-first routing for quality.

### 32.2 Two-Pass Classification

**Pass 1: Regex fast-path** (0ms, handles ~60% of messages):
- CORE only → DIRECT (no tools needed)
- Single non-CORE category → map directly (RESEARCH, BRAIN, TASK_MGMT)

**Pass 2: LLM classification** (~2-3s, LOCAL_FAST tier on P40):
- Multiple regex hits → LLM decides category + confidence
- Low confidence (<0.7) → fallback to RESEARCH

### 32.3 Categories & Tool Sets

| Category | Tools (count) | Max Iters | Use Case |
|----------|---------------|-----------|----------|
| DIRECT | none (0) | 1 | Greetings, simple questions |
| RESEARCH | kb_search, code_search, web_search, memory_recall, switch_context (5) | 3 | Information lookup |
| BRAIN | brain_* + switch_context (11) | 4 | Jira/Confluence CRUD |
| TASK_MGMT | task lifecycle + meetings + KB (11) | 4 | Background tasks, meetings |
| COMPLEX | work plans, coding, KB, brain, web (7) | 6 | Multi-step complex tasks |
| MEMORY | kb_search, kb_delete, memory_store, store_knowledge, memory_recall, code_search (6) | 3 | KB corrections, learning |

### 32.4 Prompt Architecture

```
core.py (shared identity + time + scope + runtime data + CRITICAL RULES)
  + category-specific prompt (10-20 lines each)
  = focused system prompt (~60-80 lines vs ~160 lines monolithic)
```

**Critical rules in core.py** (always applied):
- Absolute client/project isolation
- "User is always right" — corrections ≠ feature requests
- KB may contain hallucinations — verify before trusting
- Trust hierarchy: User > code_search > brain_search > kb_search

### 32.5 Cloud Routing

CHAT_CLOUD queue: claude-sonnet-4 → gpt-4o → p40 (fallback).
DIRECT category stays on P40 (LOCAL_FAST). All other categories use cloud-first when OpenRouter is available.

### 32.6 Files

- `app/chat/intent_router.py` — route_intent(), _llm_classify()
- `app/chat/prompts/` — core.py, direct.py, research.py, brain.py, task_mgmt.py, complex.py, memory.py, builder.py
- `app/chat/models.py` — ChatCategory, RoutingDecision
- `app/chat/tools.py` — select_tools_by_names()
- `app/llm/openrouter_resolver.py` — CHAT_CLOUD queue
- `app/config.py` — use_intent_router + per-category settings

---

## 33. Hierarchical Task System & Work Plan Decomposition

> **Implemented:** 2026-03-01

### 33.1 Task Hierarchy

TaskDocument now supports parent-child relationships:
- `parentTaskId` — links child to root task
- `blockedByTaskIds` — dependencies that must complete before this task runs
- `phase` + `orderInPhase` — ordering within work plan phases
- State: `BLOCKED` (waiting for deps; also used for root tasks being decomposed)

### 33.2 WorkPlanExecutor

New loop in BackgroundEngine (15s interval):
1. Find BLOCKED tasks → if all blockedByTaskIds are DONE → unblock to INDEXING
2. Find BLOCKED root tasks (with children) → if all children DONE → root.state = DONE with summary
3. If any child ERROR → root escalated to USER_TASK

### 33.3 create_work_plan Tool

Chat tool that creates hierarchical work plans:
- LLM sends phases + tasks with dependencies
- Python forwards to `POST /internal/tasks/create-work-plan`
- Kotlin creates root (BLOCKED) + children (BLOCKED/INDEXING)
- First phase tasks without dependencies start immediately
- WorkPlanExecutor handles the rest automatically

### 33.4 Unified Chat Stream

Background results and urgent alerts pushed to chat:
- `ChatRpcImpl.pushBackgroundResult()` — on task completion
- `ChatRpcImpl.pushUrgentAlert()` — on urgent KB results
- New MessageRole: BACKGROUND, ALERT
- ChatContextAssembler maps to "system" role with prefixes for LLM awareness

## 34. Graph Agent — Vertex/Edge Task Decomposition DAG

> **Status:** Fully implemented — LangGraph execution with responsibility-based vertex types and agentic tool loop
> **Source:** `backend/service-orchestrator/app/graph_agent/`

### 34.1 Motivace

Současný delegation systém (sekce 18-25) používá fixní `parallel_groups` bez přenosu kontextu mezi delegacemi. Graph Agent nahrazuje tento model plným DAG:

- Vstupní požadavek se rozloží na **vrcholy** (vertices) propojené **hranami** (edges)
- Každý vrchol se dál rozpracovává (rekurzivní dekompozice)
- **Hranou** do dalšího vrcholu jde **sumář výsledku** + **plný kontext** (prohledávatelný)
- **Fan-in**: pokud se do vrcholu sejde 10 hran → 10 sumářů + 10 kontextů
- **Fan-out**: vrchol se rozloží na více sub-vrcholů
- Výsledek se skládá z výsledků terminálních vrcholů

### 34.2 Data Model

```python
# Enums — responsibility-based vertex types
VertexType:  ROOT | PLANNER | INVESTIGATOR | EXECUTOR | VALIDATOR | REVIEWER | SYNTHESIS | GATE | SETUP | TASK | DECOMPOSE
VertexStatus: PENDING | READY | RUNNING | COMPLETED | FAILED | SKIPPED | CANCELLED
EdgeType:    DEPENDENCY | DECOMPOSITION | SEQUENCE
GraphStatus: BUILDING | READY | EXECUTING | COMPLETED | FAILED | CANCELLED

# What flows through an edge (filled when source completes)
class EdgePayload:
    source_vertex_id: str
    source_vertex_title: str
    summary: str           # Concise result summary
    context: str           # Full context (searchable at target)

# Processing unit
class GraphVertex:
    id, title, description, vertex_type, status
    agent_name: str | None
    input_request: str
    incoming_context: list[EdgePayload]  # From incoming edges
    result: str
    result_summary: str         # For outgoing edges
    local_context: str          # Full context (searchable downstream)
    parent_id: str | None       # Decomposition hierarchy
    depth: int

# Connection
class GraphEdge:
    id, source_id, target_id, edge_type
    payload: EdgePayload | None  # Filled when source completes

# Complete DAG
class TaskGraph:
    id, task_id, client_id, project_id
    root_vertex_id: str
    vertices: dict[str, GraphVertex]
    edges: list[GraphEdge]
    status: GraphStatus
```

### 34.3 Graph Operations

| Operation | Description |
|-----------|-------------|
| `create_task_graph()` | Create graph with root vertex |
| `add_vertex()` | Add vertex, auto-depth from parent |
| `add_edge()` | Add edge, recalculate target readiness |
| `get_ready_vertices()` | Vertices where ALL incoming edges have payloads |
| `start_vertex()` | Mark RUNNING, populate `incoming_context` from edges |
| `complete_vertex()` | Mark COMPLETED, fill outgoing edge payloads, cascade readiness |
| `fail_vertex()` | Mark FAILED, propagate SKIPPED to unreachable downstream |
| `topological_order()` | Kahn's algorithm for execution order |
| `get_final_result()` | Compose result from terminal vertices |

### 34.4 Context Accumulation Flow

```
vertex A completes → edge A→C gets EdgePayload(summary_A, context_A)
vertex B completes → edge B→C gets EdgePayload(summary_B, context_B)
                                        ↓
vertex C becomes READY (all incoming edges have payloads)
C.incoming_context = [payload_A, payload_B]
C processes with access to both upstream contexts
C completes → edge C→D gets EdgePayload(summary_C, context_C)
                                        ↓
D.incoming_context includes C's context which itself references A and B
```

After N vertices, the context chain carries N summaries + full contexts.

### 34.5 MongoDB Persistence

Collection: `task_graphs` (TTL: 30 days, unique index on `task_id`)

Supports atomic vertex status updates via MongoDB dot notation:
```python
await store.update_vertex_status(task_id, vertex_id, VertexStatus.COMPLETED, result="...")
await store.update_edge_payload(task_id, edge_id, payload)
await store.update_graph_status(task_id, GraphStatus.COMPLETED)
```

### 34.6 Progress Reporting

Uses existing `kotlin_client.report_progress()` with `delegation_id`, `delegation_agent`, `delegation_depth` to stream vertex progress to UI.

### 34.7 Implementation Parts

| Part | Status | Description |
|------|--------|-------------|
| **1** | Done | Core data model, graph operations, persistence, progress |
| **2** | Done | LLM-driven decomposition engine (root + recursive), graph validation |
| **3** | Done | LangGraph execution: StateGraph, responsibility-based vertex types, agentic tool loop |
| **4** | Done | Default tool sets per vertex type, `request_tools` meta-tool for dynamic expansion |
| **5** | Done | Integration: feature flag `use_graph_agent`, wired into `run_orchestration` |

### 34.8 Decomposition Engine

**Source:** `app/graph_agent/decomposer.py`

Two entry points:
- `decompose_root(graph, state, evidence, guidelines)` — decomposes the root vertex from user request
- `decompose_vertex(graph, vertex_id, state, guidelines)` — recursively decomposes a DECOMPOSE-type vertex

**LLM Prompt Pattern:**

The decomposer asks the LLM to choose the correct **responsibility type** for each vertex:
```json
{
  "vertices": [
    {"title": "...", "description": "...", "type": "investigator|planner|executor|task|validator|reviewer|gate|setup|decompose", "agent": "research", "depends_on": [0]},
    ...
  ],
  "synthesis": {"title": "Combine results", "description": "..."}
}
```

The `depends_on` field references indices within the vertices array. Synthesis vertex auto-depends on all others.

**Typical decomposition patterns:**
- `investigator → executor → validator` (research → do → verify)
- `planner → multiple executors → reviewer` (plan → parallel work → review)
- `investigator → gate → executor` (research → decide → act)

**Limits:**
- `MAX_VERTICES_PER_DECOMPOSE = 10` (per LLM call)
- `MAX_TOTAL_VERTICES = 200` (entire graph)
- `MAX_DECOMPOSE_DEPTH = 8` (recursive depth)

**Fallback:** If decomposition fails, creates a single TASK vertex that executes the entire request directly.

### 34.9 Graph Validation

**Source:** `app/graph_agent/validation.py`

`validate_graph(graph)` returns `ValidationResult(valid, errors, warnings)`:

| Check | Type | Limit |
|-------|------|-------|
| Root exists | Error | — |
| No cycles (DAG) | Error | — |
| Vertex count | Error | max 50 |
| Edge references exist | Error | — |
| Orphan vertices | Warning | — |
| Fan-in | Error | max 15 |
| Fan-out | Warning | max 10 |
| TASK has description | Error | — |
| Depth limit | Error | max 4 |

### 34.10 LangGraph Execution

**Source:** `app/graph_agent/langgraph_runner.py`

Uses LangGraph `StateGraph` for execution. TaskGraph is carried in LangGraph state — LangGraph handles checkpointing, interrupt/resume, and execution flow.

**StateGraph flow:**
```
decompose → select_next → dispatch_vertex → select_next → ... → synthesize → END
```

**LangGraph nodes:**

| Node | Responsibility |
|------|---------------|
| `node_decompose` | Call LLM decomposer, create TaskGraph, validate, persist |
| `node_select_next` | Find ALL READY vertices (all incoming edges have payloads) |
| `node_dispatch_vertex` | Run agentic tool loop — parallel `asyncio.gather` for multiple ready vertices |
| `node_synthesize` | LLM-based synthesis of results (falls back to concatenation if LLM fails) |

**Routing:** `route_after_select` → dispatch_vertex (if vertex found) or synthesize (if done). `route_after_dispatch` → always back to select_next.

**Checkpointing:** MongoDB (`jervis` DB) via `MongoDBSaver`. Recursion limit: 200.

### 34.11 Agentic Tool Loop

Each vertex executes via a unified agentic tool loop (`_agentic_vertex`):

1. Load default tools for vertex type via `get_default_tools(vertex_type)`
2. Build system prompt from `_SYSTEM_PROMPTS[vertex_type]`
3. Call LLM with tools (max 12 iterations)
4. If LLM returns tool calls → execute each → append results to messages → repeat
5. If LLM calls `request_tools` meta-tool → add requested categories to tool set
6. If LLM returns text (no tool calls) → that's the final result

**Special cases:**
- EXECUTOR/TASK with `agent_name` + `use_specialist_agents` → try specialist agent dispatch first, fall back to LLM
- Tool loop detection via `detect_tool_loop()` (skip duplicate calls)
- Per-tool timeout: 60s via `asyncio.wait_for()`
- Per-vertex overall timeout: 600s (wraps entire vertex execution)
- Parallel execution: multiple READY vertices run concurrently via `asyncio.gather()`

### 34.12 Default Tool Sets

**Source:** `app/graph_agent/tool_sets.py`

| Vertex Type | Default Tools | Can Request More? |
|-------------|--------------|-------------------|
| PLANNER/DECOMPOSE | KB search, memory recall, repo info/structure, tech stack, KB stats, queue, **get_guidelines** | Yes |
| INVESTIGATOR | Above + web search, file listing, commits, branches, indexed items, **list_unclassified_meetings** | Yes |
| EXECUTOR/TASK | KB search, **ask_user**, web search, files, repo, coding agent, KB write, memory, scheduling, **get/update_guidelines**, **classify/list_meetings** | Yes |
| VALIDATOR | KB search, files, repo, branches, commits, **dispatch_coding_agent** | Yes |
| REVIEWER | KB search, files, repo, branches, commits, tech stack, **get_guidelines** | Yes |
| SYNTHESIS | KB search, memory recall, KB write, memory store | No |
| GATE | KB search, memory recall, **ask_user** | Yes |
| SETUP | KB search, ask_user, environment CRUD, project mgmt, repo info/structure, tech stack, coding agent, KB write, memory, **get/update_guidelines** | Yes |

**`request_tools` meta-tool:** Any vertex with this tool can dynamically request additional categories:
- Categories: `kb`, `web`, `git`, `code`, `memory`, `scheduling`, **`interactive`**, **`guidelines`**, **`meetings`**, `queue`, `environment`, `project_management`, `setup`, `all`
- `interactive` = ask_user (for vertices that don't have it by default)
- `guidelines` = get_guidelines + update_guideline
- `meetings` = classify_meeting + list_unclassified_meetings
- Tools are appended to the current set (deduplicated by name)
- Available in next LLM call iteration

### 34.13 Recursive Decomposition

PLANNER/DECOMPOSE vertices don't execute via agentic tool loop. `node_dispatch_vertex` detects these types and calls `decompose_vertex()` to create new sub-vertices + edges in the graph. Children are picked up by subsequent `select_next` cycles.

**Limits:** `MAX_DECOMPOSE_DEPTH=8`, `MAX_TOTAL_VERTICES=200`. When hit, vertex auto-converts to EXECUTOR.

### 34.14 Discussion vs Implementation — Decomposer Intelligence

The decomposer distinguishes between **discussion/specification** and **implementation commands**:

**Discussion phase** (vague/incomplete requirements):
- "Klient by chtěl aplikaci na správu domácí knihovny" → single conversational vertex (asks "what platforms? what features?")
- "Mělo by to mít konektivitu na databázi knih" → single vertex (refines: "which API? what data to fetch?")
- Requirements accumulate in **memories (affairs)** across messages
- Each confirmed decision is stored to KB via `store_knowledge(category='specification')`

**Progressive KB capture during discussion:**
Discussion executor vertices automatically persist each decision to KB with `category='specification'`. This creates structured, searchable entries like:
- Subject: "Platform decision" → Content: "Android + iOS with KMP"
- Subject: "Storage choice" → Content: "PostgreSQL for book catalog, Redis for caching"
- Subject: "Feature: auth" → Content: "OAuth2 with Google and Apple Sign-In"

These entries survive memory compression — when memories get compacted over hours/days of discussion, the full details remain in KB.

**Cross-project references:**
When the user mentions another project (e.g., "this logging solution would work in project XYZ"), `store_knowledge` accepts `target_project_name` parameter. The knowledge is stored for BOTH the current project and the referenced project. When later working on project XYZ, the knowledge is discoverable via KB search.

**Implementation command** (explicit + sufficient context):
- "Tak to implementuj" / "Build it" → full graph with SETUP, EXECUTOR, VALIDATOR vertices
- "Napiš aplikaci v KMP s PostgreSQL backendem" → full workflow (clear spec in one message)
- SETUP vertex **reconstructs from KB**: first searches for all `specification` entries, then combines with memories summary to build complete requirements brief

**SETUP reconstruction flow:**
1. `kb_search("specification")` → finds all progressively stored requirements
2. `kb_search("platform decision")`, `kb_search("feature")` → targeted searches for specific aspects
3. Upstream context from memories → high-level summary
4. Combine KB details + memory summary → complete requirements brief for `get_stack_recommendations`

**Key principle:** The agent leads a natural discussion, asking clarifying questions, until the user explicitly commands implementation. The memories system accumulates requirements across messages. SETUP vertex is NEVER created for vague/incomplete requirements.

No heuristic short-circuit for "trivial" requests. Text length says nothing about complexity — "jaký je stav projektu?" is short but requires deep analysis. The decomposer LLM decides: simple request → 1 vertex, complex → multiple vertices with dependencies.

### 34.15 ArangoDB Artifact Graph — Impact Analysis

**Source:** `app/graph_agent/artifact_graph.py`, `app/graph_agent/impact.py`

ArangoDB-backed graph tracking ALL entities Jervis manages — code artifacts (from Joern CPG via KB), documents, meetings, people, test plans, budgets, etc. Direct ArangoDB access from orchestrator (`python-arango`).

**Collections:**

| Collection | Type | Purpose |
|------------|------|---------|
| `graph_artifacts` | Vertex | Entities of all kinds (code, docs, people, events, etc.) |
| `artifact_deps` | Edge | Structural/organizational dependencies |
| `task_artifact_links` | Document | TaskGraph vertex → entity it touches (with `artifact_id`, `vertex_id`, `touch_kind`) |

**Impact analysis flow (per vertex completion):**

1. LLM extracts touched entities from vertex result (`_EXTRACT_ARTIFACTS_PROMPT`)
2. Entities + dependencies persisted in ArangoDB (`upsert_artifacts_batch`, `add_dependencies_batch`)
3. For each modifying touch → AQL traversal (INBOUND, BFS, depth 3) finds all dependents
4. Cross-check: which OTHER planned vertices touch affected entities?
5. If found → inject VALIDATOR vertex into graph (blocks affected vertices until verified)
6. Detect conflicts: two vertices modifying same entity → log warning

**Code artifacts** link to existing KnowledgeNodes (Joern CPG) via `kb_node_key` — no duplication.

**Key AQL patterns:**
- `find_affected_artifacts()` — BFS traversal through `artifact_deps` INBOUND
- `find_affected_task_vertices()` — two-step: traverse deps → join with `task_artifact_links`
- `find_conflicting_vertices()` — group `task_artifact_links` by artifact, filter multi-vertex

### 34.16 Cancellation & Graceful Degradation

**Cancellation flow:**

1. User clicks Cancel → Kotlin calls `cancelOrchestration(taskId)` → reads `orchestratorThreadId` → `POST /cancel/{thread_id}`
2. `/cancel` endpoint marks `graph.status = CANCELLED` in MongoDB persistence
3. `/cancel` reports `status="cancelled"` to Kotlin via `POST /internal/orchestrator-status`
4. Kotlin `OrchestratorStatusHandler.handleCancelled()` transitions task to `DONE`, saves cancel message, cleans up
5. `/cancel` then calls `task.cancel()` on the asyncio Task + removes from `_active_tasks`
6. In the agentic tool loop (`_agentic_vertex`), each iteration checks `graph.status` before the next LLM call
7. Running vertex gets `VertexStatus.CANCELLED`, returns `("Cancelled by user.", "Cancelled")`

**ArangoDB resilience (retry with backoff):**

- `artifact_graph_store.init()` retries with exponential backoff: `5s → 15s → 30s → 60s → 5min cap`
- Matches the project-wide resilience pattern (workspace recovery, task dispatch)
- Service startup blocks until ArangoDB is reachable — no partial-feature state
- Each attempt logs a warning with attempt count and next retry delay

### 34.17 Background Dispatch & Queue Priority

**Background path:** When `use_graph_agent=True`, `handle_background()` routes directly to `run_graph_agent()`. No legacy 5-phase loop. Flow:

```
Chat LLM → create_background_task tool → Kotlin creates BACKGROUND TaskDocument
  → BackgroundEngine picks up → Python /orchestrate/v2 → handle_background()
  → run_graph_agent() (async, doesn't block chat)
  → Progress via pushBackgroundResult → appears in chat
```

**Priority tools for PLANNER vertex:**

| Tool | Purpose |
|------|---------|
| `task_queue_inspect` | List queued BACKGROUND tasks across all clients/projects (ordered by priorityScore) |
| `task_queue_set_priority` | Set priorityScore (0–100) for a task — higher = sooner execution |

**Priority as decomposition:** PLANNER vertex gets queue tools by default. When decomposing a new task, it can:
1. Inspect the current queue (`task_queue_inspect`)
2. Analyze dependencies between tasks
3. Set optimal priority scores (`task_queue_set_priority`)
4. Decompose its own task accordingly

This means **LLM decides priority** based on understanding of tasks, not hardcoded rules. Cross-project, cross-client.

**Kotlin internal API:**
- `GET /internal/tasks/queue?clientId=&limit=` — queued BACKGROUND tasks ordered by priority
- `POST /internal/tasks/{id}/priority` — set priorityScore (0–100)

### 34.18 Project Management & Git Internal APIs

**Internal REST endpoints** for SETUP vertex type and MCP tools:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/internal/clients` | POST | Create new client |
| `/internal/clients` | GET | List all clients |
| `/internal/projects` | POST | Create project for client |
| `/internal/projects` | GET | List projects (optionally by clientId) |
| `/internal/projects/{id}` | PUT | Update project (description, gitRemoteUrl) |
| `/internal/connections` | POST | Create external service connection |
| `/internal/connections` | GET | List all connections |
| `/internal/git/repos` | POST | Create GitHub/GitLab repository via provider API |
| `/internal/git/init-workspace` | POST | Trigger workspace clone for project |
| `/internal/project-advisor/recommendations` | POST | Get stack recommendations (advisor pattern) |
| `/internal/project-advisor/archetypes` | GET | List available architecture archetypes |

**SETUP vertex advisor workflow:**
1. SETUP vertex calls `get_stack_recommendations(requirements)` — accumulates all requirements from conversation history
2. Recommendations include architecture archetype, platforms, storage, features — each with pros/cons/alternatives
3. SETUP vertex presents choices to user via `ask_user` for confirmation
4. After confirmation: create infrastructure (client, project, connection, git repo)
5. Dispatch `coding_agent` with scaffolding instructions from recommendations
6. Provision environment and init workspace

**Source files:**
- `backend/server/.../rpc/internal/InternalProjectManagementRouting.kt`
- `backend/server/.../rpc/internal/InternalGitRouting.kt`
- `backend/server/.../git/GitRepositoryCreationService.kt`
- `backend/server/.../project/ProjectTemplateService.kt` — advisor pattern (recommendations, not file generation)

**Orchestrator tools** (SETUP vertex + MCP):
- `create_client(name, description)` — create client
- `create_project(client_id, name, description)` — create project
- `create_connection(name, provider, auth_type, base_url, bearer_token, client_id)` — create connection (optionally linked to client)
- `create_git_repository(client_id, name, description, connection_id, is_private)` — create GitHub/GitLab repo
- `update_project(project_id, description, git_remote_url)` — update project, link git repo
- `init_workspace(project_id)` — trigger workspace clone
- `get_stack_recommendations(requirements)` — get technology recommendations with pros/cons

### 34.19 Orchestration Entry Point

**Source:** `app/graph_agent/langgraph_runner.py`

`run_graph_agent(request, thread_id)` — called from `handle_background()` (BACKGROUND tasks) or `run_orchestration()` (FOREGROUND):
```
LangGraph.ainvoke(initial_state) → decompose → [select → dispatch → loop] → synthesize → END
```

Returns the final LangGraph state dict with `final_result` and `task_graph`.

### 34.18 Graph Visualization in Chat UI

**Data flow:**
```
Python GET /graph/{task_id} → JSON
  → PythonOrchestratorClient.getTaskGraph()
  → TaskGraphRpcImpl (ITaskGraphService) → lenient JSON → TaskGraphDto
  → ChatViewModel.loadTaskGraph() → _taskGraphs cache
  → ChatMessageDisplay BACKGROUND_RESULT → TaskGraphSection composable
```

**DTOs** (`shared/common-dto/.../graph/TaskGraphDtos.kt`): `TaskGraphDto`, `GraphVertexDto`, `GraphEdgeDto`, `EdgePayloadDto` — mirror Python models with `@SerialName` snake_case mapping.

**UI components** (`shared/ui-common/.../chat/TaskGraphComponents.kt`):

| Component | Purpose |
|-----------|---------|
| `TaskGraphSection` | Expandable section in BACKGROUND_RESULT card. Collapsed: graph icon + summary line. Expanded: stats row + vertex tree |
| `GraphStatsRow` | FlowRow of `StatChip`s: status, vertex count, edge count, LLM calls, tokens, project |
| `VertexCard` | Depth-indented card per vertex. Header: type icon + title + status badge. Expandable body: description, debug stats (agent, depth, tokens, LLM calls, tools), timing, errors, input request, result, local context, incoming edges |
| `EdgeRow` | Source vertex title + edge type + payload summary |
| `ExpandableTextSection` | Collapse/expand for long text fields (input, result, context) |

**Loading pattern:** Lazy — graph is fetched on demand when user clicks "Zobrazit graf" button in the BACKGROUND_RESULT card. Cached in `ChatViewModel._taskGraphs: Map<String, TaskGraphDto?>`. `null` value = loading in progress.

**Vertex status colors:**
- `completed` → surface (default)
- `running` → primaryContainer (30% alpha)
- `failed` → errorContainer (20% alpha)
- `cancelled` → surfaceVariant (50% alpha)

### 34.19 Key Files

| File | Purpose |
|------|---------|
| `app/graph_agent/__init__.py` | Package docstring |
| `app/graph_agent/models.py` | All data models and enums (responsibility-based VertexType) |
| `app/graph_agent/graph.py` | Graph operations (add/remove/traverse/complete/fail) |
| `app/graph_agent/persistence.py` | MongoDB CRUD with atomic updates |
| `app/graph_agent/progress.py` | Progress reporting to Kotlin server |
| `app/graph_agent/decomposer.py` | LLM-driven decomposition (root + recursive, depth 8) |
| `app/graph_agent/validation.py` | Structural validation (cycles, limits, orphans) |
| `app/graph_agent/langgraph_runner.py` | LangGraph execution: StateGraph, agentic tool loop, trivial short-circuit |
| `app/graph_agent/tool_sets.py` | Default tool sets per vertex type, `request_tools` meta-tool |
| `app/graph_agent/artifact_graph.py` | ArangoDB entity graph: artifacts, deps, impact traversal |
| `app/graph_agent/impact.py` | Impact propagation: extract entities, traverse deps, create validators |
| `shared/common-dto/.../graph/TaskGraphDtos.kt` | KMP DTOs for graph transfer (TaskGraphDto, GraphVertexDto, GraphEdgeDto) |
| `shared/common-api/.../ITaskGraphService.kt` | kRPC interface: `getGraph(taskId)` |
| `backend/server/.../rpc/TaskGraphRpcImpl.kt` | Kotlin RPC impl — calls Python, deserializes with lenient JSON |
| `shared/ui-common/.../chat/TaskGraphComponents.kt` | Compose UI: TaskGraphSection, VertexCard, EdgeRow, StatChip |

---

## 35. Two-Tier Tool System for Chat

> **Status:** Implemented | **Source:** `app/chat/tools.py`, `app/chat/handler_agentic.py`

### 35.1 Problem

Free OpenRouter models have limited tool-calling reliability. Sending 30+ tool definitions increases hallucinated tool calls and argument errors. Context budget is wasted on tool schemas the model never needs.

### 35.2 Design

**Two tiers: 10 initial core tools + on-demand expansion via `request_tools(category)` meta-tool.**

**Initial tools (always available, 10 total):**

| Tool | Purpose |
|------|---------|
| `kb_search` | Internal knowledge, code, architecture |
| `web_search` | Internet search |
| `web_fetch` | Read web page content |
| `store_knowledge` | Save to KB |
| `dispatch_coding_agent` | Send coding task to agent |
| `create_background_task` | Create background work item |
| `respond_to_user_task` | Answer pending user task |
| `check_task_graph` | Check thinking graph status |
| `answer_blocked_vertex` | Answer blocked graph vertex |
| `request_tools` | Meta-tool: load additional tool categories |

**Expandable categories (6 categories, loaded on demand):**

| Category | Tools | Count |
|----------|-------|-------|
| `planning` | create/add/update/remove/dispatch/run thinking graph | 6 |
| `task_mgmt` | search_tasks, get_task_status, list_recent_tasks, retry_failed_task, dismiss_user_tasks | 5 |
| `meetings` | classify_meeting, list_unclassified_meetings, get_meeting_transcript, list_meetings | 4 |
| `memory` | memory_store, memory_recall, list_affairs, get_kb_stats, get_indexed_items, kb_delete | 6 |
| `filtering` | set_filter_rule, list_filter_rules, remove_filter_rule | 3 |
| `admin` | switch_context, get_guidelines, update_guideline, query_action_log | 4 |

### 35.3 Handler Mechanics

When the model calls `request_tools(category)`:

1. `handler_agentic.py` intercepts the tool call (before general tool execution)
2. Looks up `TOOL_CATEGORIES[category]` for tool definitions
3. Appends new tools to `selected_tools` (deduplicates by function name)
4. Returns human-readable confirmation with list of newly added tool names
5. Next LLM iteration sees the expanded tool set in its schema

**Domain mapping** (`TOOL_DOMAINS`): Each tool maps to a semantic domain (`search`, `memory`, `task`, `meeting`, `scope`, `guidelines`, `filtering`). Used by drift detection to identify cross-domain ping-ponging.

### 35.4 Files

| File | Purpose |
|------|---------|
| `app/chat/tools.py` | `CHAT_INITIAL_TOOLS`, `TOOL_REQUEST_TOOLS`, `ToolCategory`, `TOOL_CATEGORIES`, `TOOL_DOMAINS` |
| `app/chat/handler_agentic.py` | `request_tools` handler (lines 371-408) |
| `app/chat/system_prompt.py` | Prompt section documenting available categories for the LLM |

---

## 36. Anti-Hallucination Pipeline

> **Status:** Implemented (EPIC 14) | **Source:** `app/guard/fact_checker.py`, `app/chat/source_attribution.py`, `app/chat/drift.py`, `app/chat/system_prompt.py`

### 36.1 Overview

Three-layer defense against hallucinated facts in chat responses:

1. **Drift guard** — prevents runaway tool loops, forces evidence-only responses
2. **Active fact-checking** — post-response verification of claims against collected evidence
3. **Source attribution** — tracks KB and web sources used during the loop

### 36.2 Drift Guard (`app/chat/drift.py`)

Multi-signal detection running after every tool iteration:

| Signal | Condition | Action |
|--------|-----------|--------|
| Consecutive same | 2x identical tool+args | Force response |
| Exact duplicate | Same tool+args called 2+ times anywhere | Force response |
| Tool spam | Same tool called 8+ times (any args) | Force response |
| Alternating pair | A→B→A→B pattern | Force response |
| Domain drift | 4+ iterations, 3+ unrelated domains outside workflow chains | Force response |
| Excessive tools | 8+ distinct tools after 4+ iterations | Force response |

**Workflow chains** (exempt from domain drift): `{search, memory, task}`, `{search, memory}`, `{search, task}`, `{memory, task}`, `{search, memory, scope}`, `{search, guidelines}`, `{memory, task, scope}`.

When drift is detected, the handler injects a system message enforcing **evidence-only response rules**:
- Answer ONLY from tool results (web_search, web_fetch, kb_search)
- NEVER fill in missing data from training knowledge
- Cite source URL for every claim
- Prefer 3 verified results over 10 unverified

### 36.3 Active Fact-Checking (`app/guard/fact_checker.py`)

Post-processing step after every final response:

1. **Claim extraction** — regex-based extraction of verifiable claims:
   - `FILE_PATH` — project file paths
   - `URL` — http/https URLs
   - `API_ENDPOINT` — REST endpoints (GET/POST/PUT/DELETE)
   - `CODE_REFERENCE` — class/function names in backticks
   - `REAL_WORLD_ENTITY` — phone numbers, ratings, emails, prices

2. **Verification** — each claim verified against its source:
   - File paths → KB code_search
   - Code references → KB search for matching class/function
   - Real-world entities → substring match against collected `web_evidence` (from SourceTracker)

3. **Result** — `FactCheckResult` with overall confidence score:
   - `VERIFIED` (0.9 weight) — claim found in evidence
   - `UNVERIFIED` (0.5 weight) — claim not found but not contradicted
   - `CONTRADICTED` (0.1 weight) — claim contradicts evidence

**Confidence badge** in SSE `done` metadata: `high` (>=0.8), `medium` (>=0.5), `low` (<0.5).

### 36.4 Source Attribution (`app/chat/source_attribution.py`)

`SourceTracker` instance created per chat request, collects evidence throughout the agentic loop:

- **KB sources**: Extracted from `kb_search` results (sourceUrn, score, kind, sourceType)
- **Web evidence**: Raw text from `web_search` and `web_fetch` results

Source types: `GIT_FILE`, `WEB_SEARCH`, `CHAT_HISTORY`, `KB_CHUNK`.

Top 5 sources attached to assistant message metadata (`source_count`, `sources`, `source_types`).
Structured `source_attributions` array in SSE `done` event for UI display.

### 36.5 Per-Entity Verification Workflow (System Prompt)

The system prompt enforces a strict workflow for real-world entity lookups:

1. For EACH entity: `web_search` → get URL
2. For EACH entity: `web_fetch` on best URL → read actual page content
3. Include ONLY facts from `web_fetch` results with `[source: URL]`
4. If no `web_fetch` result for entity → write "unverified, no data"
5. NEVER combine web_search snippets with training knowledge
6. Prefer 3 verified results over 10 unverified
7. For 2+ entities → 2+ separate `web_search` calls (one per entity)

### 36.6 Files

| File | Purpose |
|------|---------|
| `app/guard/fact_checker.py` | Claim extraction + verification + FactCheckResult |
| `app/chat/handler_fact_check.py` | Wrapper: error handling, metadata formatting, confidence badge |
| `app/chat/source_attribution.py` | SourceTracker: KB source + web evidence collection |
| `app/chat/drift.py` | Multi-signal drift detection (shared with handler_decompose) |
| `app/chat/handler_agentic.py` | Integration: SourceTracker lifecycle, drift injection, fact-check call |
| `app/chat/system_prompt.py` | Anti-hallucination rules + per-entity verification workflow |

---

## 37. OpenRouter FREE Queue — Model Error Tracking

> **Status:** Implemented | **Source:** `backend/service-ollama-router/app/openrouter_catalog.py`

### 37.1 Model Selection Cascade

The router iterates queues in order: **FREE → PAID → PREMIUM**, respecting the client's `maxOpenRouterTier`. Within each queue, models are ordered by tool-calling reliability (configured in Kotlin `OpenRouterSettingsDocument.modelQueues`). The first model whose `maxContextTokens` fits the estimated context is selected.

Models with `capabilities` restrictions are filtered by requested capability (e.g., `chat`, `thinking`, `coding`). Models with empty capabilities list are compatible with all capabilities.

### 37.2 Error Tracking & Auto-Disable

Per-model error state tracked in-memory (`_model_errors` dict):

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `_MAX_CONSECUTIVE_ERRORS` | 3 | Disable model after N consecutive failures |
| `_RATE_LIMIT_PAUSE_S` | 60s | Pause model on 429/rate limit (no error count increment) |
| `_AUTO_RECOVERY_S` | 300s (5 min) | Auto re-enable disabled model after cooldown |
| `_MAX_ERROR_HISTORY` | 10 | Keep last N error messages per model for debugging |

**Error types:**
- **Rate limit** (429 / "rate limit" / "too many"): Pause for 60s, do NOT increment error counter
- **Regular error** (empty response, provider error, timeout): Increment counter, disable at 3

**Lifecycle:**
1. Model error reported → `report_model_error(model_id, error_message)`
2. Rate limit → `disabled_until = now + 60s` (temporary pause, counter unchanged)
3. Regular error → `count += 1`, at count >= 3 → `disabled = True`, `disabled_until = now + 300s`
4. Auto-recovery: when `disabled_until` expires, model re-enabled on next selection attempt
5. Success reported → `report_model_success(model_id)` resets entire error state

**Orchestrator integration** (`app/llm/provider.py`):
- On blocking call error with `CLOUD_OPENROUTER` tier → fire-and-forget `_report_error_bg(model, error)`
- On successful response with content or tool_calls → fire-and-forget `_report_success_bg(model)`
- Chat handler passes `skip_models` to route decision for per-request retry with different model

**Skip models** (`router_client.py`): The orchestrator can pass `skip_models` list to `route_request()`, telling the router to skip specific model IDs that already failed in the current request. The router's `_first_cloud_model()` filters these out before the error-tracking check.


---

# Teams Pod Architecture

> (Dříve docs/teams-pod-agent.md)

# Teams Browser Pod Agent — Design Spec

**Status:** Canonical SSOT
**Last updated:** 2026-04-16
**Phase:** Read-only (Phase 1). Send/write capabilities are explicitly out of scope.

---

## 1. Purpose

Autonomous agent managing **one Connection** to Microsoft 365 via Teams web.
One pod per Connection (Deployment per `ConnectionDocument._id`). The pod is
isolated, self-healing, and speaks to the Kotlin server via HTTP callbacks only.

### What the pod watches
- **Teams chat** — chat list + open conversations (direct + group)
- **Outlook mail** — inbox metadata (only if the account has Outlook access)
- **Outlook calendar** — upcoming events
- **Meeting screens** — detected in Teams; recording is strictly opt-in per user approval

### What the pod does NOT do (Phase 1)
- Never sends messages (no `CHAT_SEND`, `EMAIL_SEND`)
- Never writes calendar events (no `CALENDAR_WRITE`)
- Never auto-joins meetings — requires explicit user approval per invite
- Never closes and reopens tabs — tabs stay alive for the whole session

### Persistence
All pod output lives in MongoDB. The Kotlin server polls `o365_scrape_messages`
(state machine `NEW → INDEXED`) and `o365_message_ledger` (summary state per
chat). The pod is the sole writer; the server is the sole indexer.

---

## 2. Architecture — single ReAct loop

```
┌──────────────────────────────────────────────────────────────┐
│   PodAgent (single ReAct loop, LLM with tools)               │
│                                                              │
│   while True:                                                │
│     response = LLM.chat(messages, tools=TOOLS)               │
│     for tc in response.tool_calls:                           │
│       result = await execute_tool(tc.name, tc.args, ctx)     │
│       messages.append(tool_result)                           │
│     if response.is_done(): break                             │
└──────────┬───────────────────────────────────────────────────┘
           │
           │ uses
           ▼
┌──────────────────────────────────────────────────────────────┐
│   Tool registry                                              │
│   - inspect_dom (primary observation, pierces shadow DOM)    │
│   - look_at_screen (VLM fallback / 5-min heartbeat)          │
│   - click, fill, press, navigate                             │
│   - report_state, notify_user                                │
│   - query_user_activity, is_work_hours                       │
│   - scrape_chat, scrape_mail, scrape_calendar                │
│   - mark_seen (updates message ledger)                       │
│   - wait, done, error                                        │
└──────────────────────────────────────────────────────────────┘
```

One agent, one persistent Playwright `BrowserContext`, one login tab, and
sibling tabs (Mail, Calendar) sharing auth cookies. Tabs **never close**; the
agent navigates and switches between them.

---

## 3. Observation policy — agent chooses per turn

No fixed "DOM-first" or "VLM-first" rule. The agent picks the fastest
appropriate tool for each turn based on what it expects to find.
**VLM is the default when state is unknown; scoped DOM is the default when
verifying a known field.** Self-correcting: empty or unexpected DOM
automatically escalates to VLM.

Why not DOM-first universally: Microsoft ships markup changes regularly;
hardcoded extractors return empty silently and the agent thinks nothing
changed. Why not VLM-first universally: every VLM call is 2–10 seconds via
the router queue; a full scraping cycle would take minutes.

### Decision table (lives in the system prompt)

| Situation | Default tool | Escalation |
|-----------|--------------|------------|
| Cold start, restart, after navigate, after error, first turn of a re-observe cycle | `look_at_screen(reason)` — VLM | — |
| Known app state, checking a specific field (unread count, meeting stage, element visible) | `inspect_dom(selector, attrs=[…])` — scoped DOM, ~50–200ms | VLM when DOM returns `count=0` or unexpected shape |
| Reading MFA sign-in number | `inspect_dom("[data-display-sign-in-code],[aria-live] .number")` first | VLM `look_at_screen(reason="mfa_code")` when DOM misses it |
| Periodic sanity (ACTIVE idle > 5 min) | VLM heartbeat | — |
| Verifying an action landed (post-click) | Scoped DOM on the expected new element | VLM when DOM unchanged after 1s |
| Scraping a chat/mail/calendar list | Scoped DOM for IDs + timestamps + unread flags | VLM to disambiguate sender/content when ambiguous |

### `inspect_dom` — generic scoped query, no hardcoded extractors

The tool is a **query**, not a semantic extractor:

```
inspect_dom(
  selector: str,                 # CSS selector, shadow-DOM-piercing
  attrs: list[str] = [],         # which element attrs to return per match
  text: bool = True,             # include visible text per match
  max_matches: int = 200,
) -> {
  matches: [{text, attrs: {<k>: <v>}, bbox: {x,y,w,h}}],
  count: int,
  url: str,
  truncated: bool,
}
```

No `chat_rows`, `calendar_events`, `conversation_messages` in the return —
those were the hand-written parsers in the historical `dom_probe.py` that
broke silently when Microsoft changed markup. The agent composes higher-level
meaning turn by turn: e.g. selector `[data-tid="chat-list-item"]` with
`attrs=["data-chat-id","data-thread-id","data-unread"]` → agent reads the
JSON and decides what to open.

Shadow DOM pierce is supported transparently — the selector walks
`element.shadowRoot` recursively.

### `look_at_screen` — VLM via router

```
look_at_screen(
  reason: str,                   # e.g. "cold_start_ambiguous", "mfa_code",
                                 #      "post_action_verify", "heartbeat"
  ask: str | None = None,        # optional focused question
) -> {
  app_state: str,                # login|mfa|chat_list|conversation|meeting_stage|loading|unknown
  summary: str,                  # short natural-language description
  visible_actions: [{label, bbox}],
  detected_text: {…}             # focused extracted strings when asked
                                 # (mfa_code, error_banner, sender_name, …)
}
```

Agent passes `client_id` + `capability="vision"` to the router; the router
picks the model and backend (local VLM or cloud). No model names in pod
code.

### Self-correction rule

When `inspect_dom` returns `count=0` for a selector the agent believed
should match, the agent MUST NOT retry with a different selector guess. It
falls back to `look_at_screen` to reset its model of what is on screen.
This prevents the failure mode that made pure DOM-first unworkable: silent
empty-probe → agent thinks "nothing new" → stuck.

---

## 4. Adaptive cadence

| Event | Next tick |
|-------|-----------|
| Action taken (click/fill/navigate) | 2s |
| Page loading (spinner) | 4s |
| AUTHENTICATING | 4s |
| AWAITING_MFA | 5s scoped DOM poll on sign-in-number element; VLM once per 30s as anomaly check |
| ACTIVE, recent observation delta | 30s |
| ACTIVE, no delta for >5min | `look_at_screen` heartbeat, then 120s |
| ERROR | 60s (wait for instruction) |

Scrape cadence (agent schedules its own `wait` between cycles):
- Chat list: every 30s while unread > 0; every 5min idle
- Open conversation: every 15s while the user is reading an active chat
- Mail: every 15min
- Calendar: every 30min

Each scrape cycle is fully agent-composed from observation + storage
primitives (§5). There is no hardcoded `scrape_chat()` that walks the sidebar
on its own.

---

## 5. Tool registry

Every capability the agent has is a `@tool`-decorated Python function. No
compound "scrape_chat" that walks the whole sidebar — the agent composes
scraping from smaller primitives. Categorized below.

### Observation (§3)

| Tool | Parameters | Returns |
|------|-----------|---------|
| `inspect_dom(selector, attrs=[], text=True, max_matches=200)` | CSS selector (shadow-pierce), attrs list | `{matches: […], count, url, truncated}` |
| `look_at_screen(reason, ask=None)` | reason + optional focused question | `{app_state, summary, visible_actions, detected_text}` |

### Navigation

| Tool | Parameters | Returns |
|------|-----------|---------|
| `list_tabs()` | — | `[{name, url, active}]` |
| `open_tab(name, url)` | short name + URL | `{name}` |
| `switch_tab(name)` | — | `{url}` |
| `close_tab(name)` | — | `{closed: bool}` |
| `navigate(url)` | — | `{url}` |

### Actions

| Tool | Parameters | Returns |
|------|-----------|---------|
| `click(selector)` | CSS selector | `{clicked: bool}` |
| `click_visual(description)` | natural-language description of the element | `{clicked: bool, bbox}` — VLM resolves to bbox then clicks center |
| `fill(selector, value)` | CSS selector + literal value | `{filled: bool}` |
| `fill_visual(description, value)` | NL description + literal value | `{filled: bool}` |
| `fill_credentials(selector, field)` | CSS selector + `"email"\|"password"\|"mfa"` | `{filled: bool}` — runtime injects credential, LLM never sees the secret |
| `press(key)` | `Enter\|Tab\|Escape\|…` | — |
| `wait(seconds, reason)` | float + string | — |

### State & notifications

| Tool | Parameters | Returns |
|------|-----------|---------|
| `report_state(state, reason=None, meta=None)` | PodState enum + meta | — validated transition |
| `notify_user(kind, message, mfa_code=None, chat_id=None, sender=None, preview=None)` | see §7 + §17 | `{task_id?}` |
| `query_user_activity()` | — | `{last_active_seconds: int}` |
| `is_work_hours()` | — | `bool` |

### Storage primitives (write-only into Mongo)

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `store_chat_row(chat_id, chat_name, is_direct, is_group, last_message_at, unread_count, unread_direct_count)` | ledger upsert | `o365_message_ledger` |
| `store_message(chat_id, message_id, sender, content, timestamp, is_mention, attachment_kind=None)` | new message row | `o365_scrape_messages` (state=NEW) |
| `store_discovered_resource(resource_type, external_id, display_name, team_name=None, description=None)` | upsert | `o365_discovered_resources` |
| `store_calendar_event(external_id, title, start, end, organizer, join_url=None)` | upsert | `scraped_calendar` |
| `store_mail_header(external_id, sender, subject, received_at, preview, is_unread)` | upsert (metadata only, no body) | `scraped_mail` |
| `mark_seen(chat_id)` | — | Ledger `lastSeenAt=now, unreadCount=0, unreadDirectCount=0` |

### Meeting recording

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `meeting_presence_report(present, meeting_stage_visible)` | bool + bool | Presence signal for server (§10a) |
| `start_meeting_recording(meeting_id=None, title=None, joined_by="agent")` | optional calendar-linked id; title for ad-hoc; `joined_by` = "user" for VNC-joined, "agent" for /instruction/-joined | Allocates MeetingDocument if `meeting_id` is None; starts ffmpeg WebM chunk pipeline (audio + video @ 5 fps) streaming to server |
| `stop_meeting_recording(meeting_id)` | — | Flushes remaining chunks, posts finalize, transitions server-side `status` to FINALIZING |
| `leave_meeting(meeting_id, reason)` | id + short reason string | (1) `stop_meeting_recording`, (2) click "Leave" (`[data-tid="call-end"]`, VLM fallback), (3) verify `meeting_stage=false` within 10 s, (4) `meeting_presence_report(present=false)` |

### Control

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `done(summary)` | str | Current goal complete — drop out of the tool loop |
| `error(reason, screenshot=False)` | str | Hard error; agent waits for instruction |

All tools take their browser / storage / meeting dependencies from a
`ContextVar` (one pod = one connection = one context) — tool signatures carry
only the semantic args, not `Page` / `MongoClient` / etc.

---

## 6. Message ledger — "what's new?" state

Collection `o365_message_ledger`, one document per `(connectionId, chatId)`:

```json
{
  "connectionId": "…",
  "clientId": "…",
  "chatId": "chat_mamka",
  "chatName": "Mamka",
  "isDirect": true,
  "isGroup": false,
  "lastSeenAt": "2026-04-16T14:32:00Z",
  "lastMessageAt": "2026-04-16T14:40:00Z",
  "unreadCount": 3,
  "unreadDirectCount": 3,
  "lastUrgentAt": "2026-04-16T14:40:00Z",
  "lastNotifiedAt": "2026-04-16T14:40:02Z"
}
```

**Invariants:**
- `scrape_chat` writes one ledger row per observed chat.
- `mark_seen(chatId)` sets `lastSeenAt=now`, `unreadCount=0`, `unreadDirectCount=0`.
- Server indexes new messages from `o365_scrape_messages` (state `NEW→INDEXED`)
  and reads the ledger for UI badges + urgency triggers.
- Pod never deletes ledger rows — soft lifecycle via `lastSeenAt`.

---

## 7. Notifications — direct message = urgent

Endpoint `POST /internal/o365/notify` on Kotlin server. Fire-and-forget from
the pod.

```json
{
  "connectionId": "…",
  "kind": "urgent_message | meeting_invite | meeting_alone_check | auth_request | mfa | error | info",
  "chatId": "chat_mamka",                // urgent_message only
  "chatName": "Mamka",                   // urgent_message only
  "sender": "Mamka",                     // urgent_message only
  "preview": "Ahoj, můžeš se podívat…",  // urgent_message only
  "message": "Nová direct zpráva od Mamka",
  "mfa_code": "42",                      // mfa only, see §17
  "meeting_id": "…",                     // meeting_alone_check only
  "screenshot": "/data/…/screenshot.jpg" // optional
}
```

| Kind | Server behavior |
|------|-----------------|
| `urgent_message` | USER_TASK `priorityScore=95`, `alwaysPush=true`, FCM+APNs, kRPC emit. Direct messages + @mentions + incoming direct calls (see §15). |
| `meeting_invite` | USER_TASK 80, push+kRPC, expects approval via `POST /instruction/{connectionId}` (`join-meeting:<target>` / `skip-meeting`). |
| `meeting_alone_check` | USER_TASK 65, `alwaysPush=true`, chat bubble with `[Odejít] [Zůstat]` buttons. Emitted when user-joined meeting has been alone > 1 min (see §10a). Orchestrator routes button clicks / chat intents to MCP `meeting_alone_leave(meeting_id)` / `meeting_alone_stay(meeting_id)`. |
| `auth_request` | USER_TASK 75, outside work hours login consent. |
| `mfa` | USER_TASK 70 with MFA `mfa_code` in metadata — push body shows the 2-digit number (see §17). |
| `error` | USER_TASK 60, stuck agent, includes screenshot. |
| `info` | kRPC only, no push. |

**De-dup:** the server deduplicates `urgent_message` per `(connectionId, chatId)`
within a 60s window — if the pod keeps observing the same unread chat, the
server fires at most one push per chat per minute. Server-side dedup uses the
ledger's `lastNotifiedAt`.

**Per-context once rule:** the agent emits `notify_user` at most once per
context transition. After notifying, the agent transitions to `wait`/observe
until the context changes (new sender, new chat, state transition).

---

## 8. Work hours + user activity

```python
TIMEZONE = "Europe/Prague"
WORK_DAYS = {0, 1, 2, 3, 4}  # Mon–Fri
WORK_START_HOUR, WORK_END_HOUR = 9, 16
RECENT_ACTIVITY_THRESHOLD_S = 300
```

- `is_work_hours_now()` — local check, no server round-trip
- `query_user_activity(client_id)` — `GET /internal/user/last-activity?clientId=X`
  → `{last_active_seconds: int}`. Server derives this from last kRPC ping,
  last HTTP request, or UI focus event.

**Outside work hours + user idle >5 min → `auth_request` before any credential
submission.** The pod never submits credentials without a fresh window of consent
off-hours.

---

## 9. State machine

```
STARTING ──┬→ AUTHENTICATING ──┬→ AWAITING_MFA ──→ ACTIVE
           │                   ├→ ACTIVE
           │                   └→ ERROR
           ├→ ACTIVE
           └→ ERROR

ACTIVE ──┬→ RECOVERING ──→ AUTHENTICATING
         ├→ ERROR
         └→ EXECUTING_INSTRUCTION ──→ ACTIVE

ERROR ──→ EXECUTING_INSTRUCTION ──→ AUTHENTICATING / ACTIVE / ERROR
```

Validated by `pod_state.py:_TRANSITIONS`. Agent sees current state + valid next
states in the prompt and mutates only via `report_state`.

---

## 10. Pod ↔ server endpoints

| Direction | Endpoint | Purpose |
|-----------|----------|---------|
| Pod → Server | `POST /internal/o365/session-event` | State change (AWAITING_MFA, EXPIRED) |
| Pod → Server | `POST /internal/o365/notify` | Kind-aware push (see §7) |
| Pod → Server | `POST /internal/o365/capabilities-discovered` | Capabilities per Connection |
| Pod → Server | `GET /internal/user/last-activity?clientId=X` | User activity check |
| Pod → Mongo | direct write | `o365_scrape_messages`, `o365_discovered_resources`, `o365_message_ledger` |
| Server → Pod | `POST /instruction/{connectionId}` | JERVIS instruction (join/skip meeting, recover, …) |
| Server → Pod | `POST /session/{connectionId}/mfa` | Submit MFA code |
| Server → UI | `GET /internal/o365/discovered-resources?connectionId=X` | List discovered chats/channels for project assignment |
| UI → Server | `PUT /project/{projectId}/resources` | Attach a discovered resource to a project |

---

## 10a. Meeting attendance & recording

### Overview

The pod never decides to join a meeting on its own. Two entry paths:

- **Scheduled (agent-joined):** calendar approval → `POST /instruction/{id}`
  with `join_meeting` payload → agent composes navigate + join + record.
- **Ad-hoc (user-joined):** user clicks Join in Teams via VNC → background
  watcher detects `meeting_stage` rising → agent starts recording.

Recording streams WebM (audio + video at 5 fps) in 10-second chunks with a
local disk buffer + indefinite retry. The server archives the WebM and
runs indexation (Whisper + pyannote diarization + scene-detection + VLM
frame descriptions) after finalize. Pod meetings are visible in the UI
Meeting list with a live status stream.

Hard rule (§15): **agent never accepts an incoming direct call**, only
notifies the user.

### Approval flow (scheduled meetings)

1. `CalendarContinuousIndexer` creates a `CALENDAR_PROCESSING` task with
   `meetingMetadata { joinUrl, startTime, endTime, organizer }`.
2. `MeetingAttendApprovalService` polls every 60 s. In the 10-minute
   preroll window it fires push + chat ALERT bubble ("Schválit připojení
   Jervise?").
3. **At-start fallback** fires a second push when the meeting is starting
   and the approval is still `PENDING` — **unless** the pod has reported
   `meeting_presence_report(present=true, meeting_stage_visible=true)` in
   the last 120 s (user joined manually → no redundant push).
4. User approves via the chat bubble button **or** by writing intent in
   the chat (orchestrator agent calls MCP `meeting_attend_approve(task_id)`).

### Join flow (agent-driven, scheduled)

On `APPROVED` the server posts:

```
POST /instruction/{connectionId}
{
  "instruction": "join_meeting",
  "meeting_id": "<MeetingDocument._id>",
  "title": "<title>",
  "join_url": "<teams meeting url>",
  "start_time": "<ISO>",
  "end_time": "<ISO>"
}
```

The pod appends a `HumanMessage` spelling out the expected tool chain
(navigate, mute, click Join, start recording, monitor until end). The
agent then composes the tool calls itself:

1. `navigate(join_url)` + `look_at_screen(reason="teams_prejoin")`.
2. `click_visual("mic off")` if not already muted; same for camera.
3. `click_visual("Join now")` or
   `click("[data-tid='prejoin-join-button']")` — whichever resolves
   reliably.
4. Poll `inspect_dom("[data-tid='meeting-stage']")` every 2 s; escalate
   to `look_at_screen` on timeout.
5. On stage visible:
   `start_meeting_recording(meeting_id=<M>, joined_by="agent")` +
   `meeting_presence_report(present=true, meeting_stage_visible=true)`.
6. Agent keeps cycling at §4 cadences. End-detection signals (below)
   drive the `leave_meeting` decision.

### Ad-hoc join (user via VNC)

No calendar task, no instruction. The background watcher (below) detects
`meeting_stage` rising and alerts the agent, which calls:

```
start_meeting_recording(
  meeting_id=None,              # tool allocates new MeetingDocument
  title=<derived from VLM/tab>,
  joined_by="user",
)
```

### Background watcher

Runs in `app/agent/runner.py` independently of LangGraph ticks. Every
`O365_POOL_WATCHER_INTERVAL_S` (default 2 s) it runs **one pure JS check**
per registered tab — no LLM, no router, ~5 ms per tab:

```js
({
  meeting_stage:   !!document.querySelector('[data-tid="meeting-stage"], [data-tid="calling-screen"]'),
  incoming_call:   !!document.querySelector('[data-tid="call-toast"], [role="dialog"][aria-label*="incoming" i]'),
  // Only relevant while an active meeting is being recorded:
  participant_count: parseInt(document.querySelector('[data-tid="roster-button"] .count')?.textContent || '0', 10) || null,
  alone_banner:    !!document.querySelector('[data-tid="alone-in-meeting-banner"]'),
  meeting_ended_banner: !!document.querySelector('[data-tid="meeting-ended"]'),
})
```

Plus audio silence — ffmpeg `silencedetect` filter on the meeting audio
stream keeps `last_speech_at` updated.

The watcher is a **sensor, not a controller.** It never calls tools,
never clicks, never touches Playwright beyond `page.evaluate()`. It
pushes priority `HumanMessage`s into `PodAgent._pending_inputs` — the
agent consumes them on the next outer-loop entry (0.5–2 s idle, 2–5 s
during an in-flight LLM call). The agent decides what to do.

### Watcher signals — reference

**`meeting_stage` rising edge:**
> ALERT: meeting_stage appeared on tab `<name>`. If there is no active
> meeting in state, the user joined ad-hoc: call
> `start_meeting_recording(meeting_id=None, joined_by="user",
> title=<VLM/tab>)` + `meeting_presence_report(present=true,
> meeting_stage_visible=true)`.

**`meeting_stage` falling edge:**
> ALERT: meeting_stage disappeared on tab `<name>`. If there is an active
> meeting in state, the user / pod disconnected: call
> `stop_meeting_recording(meeting_id=<active>)` +
> `meeting_presence_report(present=false, meeting_stage_visible=false)`.

**`incoming_call` rising edge:**
> ALERT: incoming call toast on tab `<name>`. NEVER click accept /
> answer / join (§15). Read caller name via scoped DOM first (patterns
> from §20, fall back to `[data-tid="call-toast"] [class*="caller"]`).
> VLM only on DOM `count=0`. Call `notify_user(kind='urgent_message',
> sender=<name>, preview="Příchozí hovor od <name>")`.

**`incoming_call` falling edge:** no-op.

**Participant / banner / silence signals** drive end-detection (below)
and are only meaningful while `active_meeting` is set.

### Meeting end detection

Alone ≠ end. The interpretation depends on **how the pod joined** and on
the **history of participants**.

**joined_by = "user" (ad-hoc):**

| Trigger | Action |
|---------|--------|
| `alone_since ≥ 1 min` (first time) | `notify_user(kind='meeting_alone_check', meeting_id=<M>, preview="Pořád jsi v meetingu '<title>'. Ještě ho potřebuješ?")` + chat bubble with `[Odejít] [Zůstat]` |
| Reaction: button "Odejít" / chat intent | Orchestrator posts `/instruction/{id} leave_meeting` → agent calls `leave_meeting(meeting_id, reason="user_asked_to_leave")` |
| Reaction: button "Zůstat" / chat intent | Orchestrator posts `/instruction/{id} meeting_stay` → reset `alone_since`, suppress further `meeting_alone_check` for 30 min |
| No reaction in `O365_POOL_MEETING_USER_ALONE_NOTIFY_WAIT_MIN` (default 5 min) | Agent calls `leave_meeting(meeting_id, reason="no_user_response")` + `notify_user(kind='info', message="Odešel jsem z prázdného meetingu, 5 min bez reakce.")` |
| Audio speech / `participant_count > 1` / user activity ping | Reset `alone_since` silently |

**joined_by = "agent" (scheduled):**

| Trigger | Action |
|---------|--------|
| `max_participants_seen_since_join ≤ 1` AND `now < scheduled_start + O365_POOL_MEETING_PRESTART_WAIT_MIN` (default 15) | No action — pre-meeting wait |
| `max_participants_seen_since_join ≤ 1` AND pod joined > 5 min after `scheduled_start` AND `alone_since ≥ O365_POOL_MEETING_LATE_ARRIVAL_ALONE_MIN` (default 1) | Alert agent: "Late arrival, nobody here — probably already ended." → `leave_meeting(meeting_id, reason="late_arrival_empty")` |
| `max_participants_seen_since_join ≤ 1` AND `now ≥ scheduled_start + PRESTART_WAIT_MIN` | Alert agent: "15 min past start, nobody joined — leave." → `leave_meeting(meeting_id, reason="no_show")` |
| `max_participants_seen_since_join > 1` AND `alone_since ≥ O365_POOL_MEETING_ALONE_AFTER_ACTIVITY_MIN` (default 2) | Alert agent: "Everyone left, meeting ended." → `leave_meeting(meeting_id, reason="post_activity_alone")` |
| `meeting_ended_banner` rising edge | Immediate `leave_meeting(meeting_id, reason="meeting_ended_banner")` |

**Server hard ceiling (both join modes):**
`MeetingRecordingMonitor` job (new, sibling of `MeetingAttendApprovalService`)
runs every 60 s. If `MeetingDocument.status=RECORDING` and `now >
scheduledEndAt + 30 min`, it posts:

```
POST /instruction/{connectionId}
{ "instruction": "leave_meeting", "meeting_id": "<M>", "reason": "scheduled_overrun" }
```

If the pod still has `meeting_stage=true` for this `meeting_id` 5 min
later, the server fires `notify_user(kind='error')` to the user ("Jervis
uvízl v meetingu, opusť manuálně").

### `leave_meeting` tool contract

The agent — never the watcher, never the server directly — calls
`leave_meeting(meeting_id, reason)`. The tool:

1. `stop_meeting_recording(meeting_id)` — flush remaining chunks, POST
   `/internal/meeting/{id}/finalize`.
2. Click "Leave": `click("[data-tid='call-end']")`, VLM fallback
   `click_visual("Leave")`.
3. Wait up to 10 s for `meeting_stage=false` (scoped DOM poll).
4. `meeting_presence_report(present=false, meeting_stage_visible=false)`.

If step 3 times out, the tool returns `{left: false}` and the agent
retries or emits `notify_user(kind='error', message="Couldn't leave
meeting — manual intervention needed")`.

### Recording pipeline — WebM chunks with disk buffer + retry

Pod-side encoding (inside `start_meeting_recording`):

- Single ffmpeg pipeline:
  - Video: `x11grab` the Teams window on the pod's Xvfb → VP9 encode at
    `O365_POOL_MEETING_FPS` (default 5) frames per second.
  - Audio: PulseAudio `jervis_audio.monitor` → Opus encode.
  - Container: WebM, 10-second chunks (`O365_POOL_MEETING_CHUNK_SECONDS`).
  - Output: rolling file named `{meeting_id}_{chunkIndex}.webm` in the
    pod's chunk queue directory.

Upload loop (separate async task, mirrors
`shared/ui-common/.../meeting/RecordingUploadService.kt`):

- Disk FIFO queue (`{meeting_id}_{chunkIndex}.webm` + `pending.json`
  index) in the pod's chunk dir. Survives restart.
- Poll every 3 s; for each pending chunk in order:
  - Check connection health (last successful POST within 30 s OR probe
    `GET /health`). If server unreachable, wait.
  - `POST /internal/meeting/{id}/video-chunk?chunkIndex=<N>` with the
    WebM bytes as body. Server idempotency: duplicate `chunkIndex`
    returns 200 without re-appending.
  - On 2xx: unlink file, update `pending.json`, advance.
  - On failure: `await asyncio.sleep(2)` then continue with next chunk
    (indefinite retry, no max-fail pause unlike UI — pod is headless,
    can't show a retry button).
- No buffer size cap; pod relies on PVC space. If the queue grows > 500
  MB, emit `notify_user(kind='error', message="Meeting chunk queue
  backlog > 500 MB — server unreachable?")` once per hour.

Server-side stuck detector (new job, hourly-ish):

- `status=RECORDING` and `lastChunkAt > 5 min ago` → emit urgent
  USER_TASK (`priorityScore=90`): "Meeting `<title>` upload stuck —
  `<N>` chunks pending since `<lastChunkAt>`. Check pod health."

### `MeetingDocument` lifecycle

```
status: RECORDING → FINALIZING → INDEXING → DONE
                               ↘ FAILED
```

Schema additions over the existing `MeetingDocument`:

| Field | Type | Notes |
|-------|------|-------|
| `status` | enum | see above |
| `joinedByAgent` | bool | default false; UI label |
| `chunksReceived` | int | bumped on each accepted chunk POST |
| `lastChunkAt` | ISODate | for stuck detector + UI staleness |
| `webmPath` | str | server filesystem path after FINALIZING |
| `videoRetentionUntil` | ISODate | `createdAt + O365_POOL_MEETING_VIDEO_RETENTION_DAYS` (default 365); cleanup job drops the WebM after this, keeps metadata + transcript + frames indefinitely |
| `timeline[]` | array | `{ts, diarizedSegment?, frameThumbPath?, frameDescription?}` — assembled during INDEXING |

### Indexation pipeline (server-side, post-FINALIZE)

Mirror of the existing audio-meeting pipeline (`architecture-whisper-diarization.md`)
plus new frame-extraction step:

1. **Audio:** ffmpeg extract `audio.opus` from WebM → Whisper + pyannote
   diarization on VD GPU (same as current audio meetings). Output:
   diarized transcript with timestamps.
2. **Frames:** ffmpeg scene detection + min 1 frame per 2 s:
   ```
   ffmpeg -i meeting.webm \
     -vf "select='gt(scene,0.1)+gt(mod(t,2),0)'" \
     -vsync vfr frames/frame_%04d.jpg
   ```
   Per extracted frame → VLM via router (`capability="vision"`, prompt:
   "describe what changed on screen since the previous frame") → short
   description string + timestamp.
3. **Timeline assembly:** merge diarized segments + scene entries by
   timestamp into `MeetingDocument.timeline[]`.
4. **KB indexing:** transcript + scene descriptions into the KB graph
   (standard meeting-indexation path).
5. `status=DONE`, emit `MeetingDocument` push on the meeting stream.

### UI visibility

Pod-recorded meetings appear in the same `subscribeMeetings` stream as
UI-recorded meetings. The UI row shows:

- Title
- `status` (RECORDING / FINALIZING / INDEXING / DONE / FAILED)
- `chunksReceived` + `lastChunkAt` during RECORDING
- `joinedByAgent` icon (Jervis auto-joined vs user started)

The live stream is push-only per guideline #9 (`subscribeMeeting(id)`
`Flow<MeetingSnapshot>` with replay=1).

In the Meeting view (§21 items 5–7):

- Embedded `<video src="/meeting/{id}/stream.webm">` player.
- Timeline strip of scene-change thumbnails under the video; hover shows
  frame description, click jumps video + scrolls transcript.
- Transcript panel synced to audio timecode.

Pod meetings get **no manual retry button** — the upload loop is
indefinite. The UI only reports status; action is server-driven via the
stuck detector's USER_TASK.

### Miss windows

- Meeting start captured within ~2–5 s (watcher poll + agent tick).
- Meetings < 2 s may be missed; acceptable.
- Meeting already in progress at pod restart: cold-start probe (§16)
  picks up `meeting_stage=true` on the first VLM observation.

### Chat commands (orchestrator MCP)

Scheduled approval:
- `meetings_upcoming(hours_ahead=24)`
- `meeting_attend_approve(task_id)` / `meeting_attend_deny(task_id, reason?)` / `meeting_attend_status(task_id)`

User-joined alone check (new):
- `meeting_alone_leave(meeting_id)` — user said "odejdi"
- `meeting_alone_stay(meeting_id)` — user said "zůstaň ještě"

Intent examples:
- "připoj se na ten meeting za chvíli" → `meetings_upcoming` → nearest →
  `meeting_attend_approve`
- "vypadni z meetingu" / "ten meeting už je prázdný" →
  `meeting_alone_leave`
- "nech to ještě běžet" → `meeting_alone_stay`

---

## 11. Discovered resources — UI project assignment

The pod writes to `o365_discovered_resources` whenever it sees a new chat,
channel, team, or calendar. The server exposes:

```
GET /internal/o365/discovered-resources?connectionId=<id>&resourceType=chat
→ [{ externalId, resourceType, displayName, description, teamName, active }, …]
```

The UI (Settings → Connection → Resources) reads this list and lets the user
attach each resource to a specific `Project` via `ProjectResource` mapping.
Nothing is auto-mapped; multi-project overlap is allowed.

The polling handler uses `ResourceFilter` on `ProjectResource` links to decide
which chats to index per project — the pod keeps scraping everything; filtering
happens at index time, not scrape time.

---

## 12. Router-first LLM/VLM

All LLM and VLM calls go through `jervis-ollama-router` via `/route-decision`
(returns `target: local | openrouter`, model id, api_base). No pod code ever
calls a provider directly. The router holds client-tier policy — the pod passes
`client_id` and `capability` (`vision` / `tool-calling`), nothing else.

Stream + heartbeat only — no hard HTTP timeouts on LLM calls. This mirrors the
project-wide principle.

---

## 13. Implementation layout

The agent is built on **LangGraph + MongoDBSaver** (same stack as the
`service-orchestrator`). Full design: `docs/teams-pod-agent-langgraph.md`.

| # | Component | File | Status |
|---|-----------|------|--------|
| 1 | LangGraph state + graph | `app/agent/state.py`, `app/agent/graph.py` | implement |
| 2 | Router-backed LLM (`BaseChatModel`) | `app/agent/llm.py` | implement |
| 3 | Tools (`@tool` decorators) | `app/agent/tools.py` | implement |
| 4 | ContextVar for tool dependencies | `app/agent/context.py` | implement |
| 5 | MongoDB checkpointer | `app/agent/persistence.py` | implement |
| 6 | Agent runner (outer loop + restart recovery) | `app/agent/runner.py` | implement |
| 7 | System prompts | `app/agent/prompts.py` | implement |
| 8 | Work hours + activity query | `app/agent/work_hours.py` | implement |
| 9 | DOM probe JS + dataclass | `app/agent/dom_probe.py` | **keep** |
| 10 | Ledger + scrape storage (Mongo ObjectId) | `app/scrape_storage.py` | **keep** |
| 11 | Server endpoints (notify, discovered-resources, user-activity, meeting-presence) | Kotlin | **done** |
| 12 | Keep as-is | `pod_state.py`, `browser_manager.py`, `tab_manager.py`, `kotlin_callback.py`, `routes/instruction.py`, `vnc_*`, `meeting_recorder.py` | — |

No legacy, no deprecated markers. Raw ReAct loop in `app/agent/loop.py`
is replaced by LangGraph (`graph.py` + `runner.py`).

---

## 15. Hard rules — only path is LangGraph → tools

Absolute bans (breaking these = legacy code to delete, not "fix"):

- **No regex / no HTML parsing / no hand-written string extraction** anywhere in
  pod code. All observation goes through `inspect_dom` (scoped CSS query
  returning structured `{matches, count, url}`) or `look_at_screen` (VLM via
  router). Downstream code consumes structured fields, never raw HTML/text.
- **No hardcoded semantic extractors in the DOM probe.** `inspect_dom` is a
  generic query tool — it never returns `chat_rows`, `calendar_events`, or
  any other field named after a product concept. Those were the exact shape
  of the historical `dom_probe.py` walker, which broke silently on every
  Microsoft markup update and is why DOM-first observation did not work.
- **No hardcoded URL lists, tab types, app-role enums in runtime code.**
  `TabRegistry` is a dumb `{name: Page}` map. The agent decides what each tab
  is for via its own observation + prompt, not via `_BUSINESS_URLS`,
  `TabType`, or `setup_tabs()`.
- **No bootstrap retry loops** (`_bootstrap_tabs_if_authenticated`,
  `/force-setup-tabs`, `/rediscover`). The agent owns recovery.
- **No admin-override HTTP endpoints** that bypass the agent (`/force-*`,
  `/refresh`, `/meeting/join` direct RPC). The only control path is
  `/instruction/{connectionId}` (server → agent as a HumanMessage) and
  `/session/{id}/mfa` (server resumes a `interrupt()`). Meeting join is
  server→agent→tools, not server→RPC→ffmpeg (§10a).
- **No `[:N]` slicing / truncation.** Full lists go to the router; context
  budget is managed by LangGraph `trim_messages` at message-boundary granularity.
- **No provider SDK imports** — all LLM/VLM via router with
  `capability="chat"` or `capability="vision"`. No model names in pod code.
- **No direct Playwright calls outside `@tool` functions** (and outside
  narrow server-side helpers like `token_extractor.py` that run offline,
  never during agent ticks, plus the read-only `page.evaluate()` in the
  background watcher — §10a). Agent never touches `page.*` directly.
- **Agent NEVER accepts / answers / joins an incoming direct call.**
  Incoming call toast → `notify_user(kind='urgent_message', ...)` and
  nothing else. The user handles the call themselves. No `click("Accept")`,
  no `click_visual("answer")`, no keyboard shortcut. This rule is stronger
  than the general "never auto-join meeting" rule because a direct call is
  a live human waiting — accidentally clicking accept would expose an open
  mic / unexpected presence.

The path is exactly **agent ↔ tools**: the LLM emits `tool_calls`, `ToolNode`
executes them, results flow back. Anything that isn't a tool call or an
observation is out of the loop by construction.

---

## 16. Cold-start — agent starts from an empty context

On fresh start (new pod, restart, checkpoint resume with trimmed history) the
agent MUST probe before acting. No assumption that tabs are arranged, session
valid, or login completed. Flow:

1. `list_tabs()` → what tabs exist? (TabRegistry auto-registered any Pages the
   browser restored from PVC profile.)
2. `look_at_screen(reason="cold_start")` on the active tab → VLM returns
   `app_state` (login / mfa / chat_list / conversation / meeting_stage /
   loading / unknown). Cold start = unknown state = VLM default (§3).
3. Based on `app_state`, the agent may call `inspect_dom(selector, attrs)`
   to pick up precise IDs or attributes it needs for the next step (e.g.
   chat IDs after VLM confirmed "chat list visible"). Scoped, never full
   walker.
4. Decide state via `report_state(...)`:
   - `ACTIVE` → proceed to scrape cycle (§4).
   - `AUTHENTICATING` → credential entry (§17).
   - `AWAITING_MFA` → MFA flow (§17).
   - `ERROR` → notify + wait for instruction.
5. Only after state is known, open/switch tabs via `open_tab` / `switch_tab`.
   The agent never opens a new tab "just in case" — only on demand for a
   specific capability (chat / mail / calendar).

After cold start, **subsequent turns do NOT need VLM** unless the agent has
no expectation about the screen. In the steady state (ACTIVE with a known
chat list view), the agent polls scoped DOM and only falls back to VLM when
DOM returns empty / unexpected shape (self-correction rule, §3).

Context cleanup is NOT the agent's job. LangGraph `trim_messages(strategy="last",
max_tokens=…)` at the entry of the agent node drops old messages whole,
preserving `tool_call_id` consistency. On restart, `MongoDBSaver` replays the
trimmed history; probe (1)–(4) still runs because the prompt instructs it as
the first step of every "re-observe" turn.

**Browser-state-first**, not action-first. The agent answers "what is on the
screen?" before "what should I click?" — every turn, not just the first. But
"what is on the screen?" is usually a fast scoped DOM check, not a VLM call.

---

## 17. MFA policy — Microsoft Authenticator only + code push

**Only Microsoft Authenticator (push-based "approve this sign-in" + 2-digit
number match) is an allowed second factor.** All others are forbidden:

- No SMS code entry
- No voice-call code entry
- No email code entry
- No security key / FIDO
- No TOTP from a generic authenticator app

If the DOM shows a method chooser with multiple options, the agent clicks the
Microsoft Authenticator option. If Microsoft Authenticator is not offered
(account forced to another factor), the agent emits `notify_user(kind='error',
message="MFA method unsupported: <observed>")` and transitions to `ERROR`
— user must resolve at their M365 tenant.

### Code propagation

Modern Authenticator sign-in shows a **2-digit number** on the login page
that the user must tap on their phone. Flow:

1. Agent detects the MFA prompt. First choice is `look_at_screen(
   reason="mfa_detect", ask="is this Microsoft Authenticator number-match?")`
   — cold-start of a sign-in screen is always VLM (§3).
2. Agent reads the number. Scoped DOM first:
   `inspect_dom("[data-display-sign-in-code], [aria-live] .number,
   .sign-in-number", attrs=["aria-label","data-display-sign-in-code"])`.
   If DOM `count=0` or the match has no numeric text → fall back to
   `look_at_screen(reason="mfa_code", ask="return the 2-digit number shown
   on the page, nothing else")`.
3. Agent calls `notify_user(kind='mfa', mfa_code="42", preview="Potvrď 42
   v Microsoft Authenticatoru.")` immediately. Server fires
   `USER_TASK priorityScore=70 alwaysPush=true` with the code in the
   metadata so the UI + mobile push show **the number** itself (not just
   "approve login").
4. Agent transitions to `AWAITING_MFA` and enters a LangGraph `interrupt()`
   (or re-polls scoped DOM on the number element every 5s waiting for state
   change).
5. If the code rotates (Microsoft regenerates after timeout or re-challenge),
   the agent re-reads and emits a **new** `notify_user(kind='mfa',
   mfa_code=…)` with the current number. The old push is superseded
   client-side by code equality — no server-side dedup on MFA because the
   number itself is the dedup key.
6. On successful sign-in — DOM `[data-tid='meeting-stage']`, `[data-tid=
   'chat-list']`, or any `app_state != login/mfa` from VLM — transition to
   `ACTIVE`. On timeout/denial → `ERROR` with reason.

**Never** does the agent try to "read and submit" the code itself. The user
approves on their phone Authenticator; the pod only mirrors what the login
screen shows.

---

## 18. Relogin window — work hours + server-confirmed UI activity

Relogin (credential submission after a session expiry or forced re-challenge)
is disruptive — it surfaces an MFA push to the user's phone. Rule:

- **Trigger allowed if** `is_work_hours()` returns `true` (Mon–Fri 09:00–16:00
  Europe/Prague) **OR** `query_user_activity()` returns
  `last_active_seconds <= 300` (server confirms UI focus / kRPC ping in the
  last 5 minutes).
- **Neither condition** → agent stays in `AUTHENTICATING` or `ERROR`, emits
  `notify_user(kind='auth_request', message="Session expired. Approve relogin?")`
  at most once per 60 min, and waits for either: work-hours window to open,
  user activity ping, or explicit `/instruction/{id}` payload
  `approve-relogin`.

The agent **remembers in its checkpoint** that relogin is pending (state stays
`AUTHENTICATING`, note in last AIMessage). Because the checkpoint is on
`thread_id=connection_id` in `MongoDBSaver`, this survives pod restarts — the
agent does not re-ask if it already asked within the 60-min window.

User activity signal is authoritative from server (`/internal/user/last-activity`)
— pod never infers activity from browser focus events (another tab in Chromium
being focused does not imply the user is at their computer).

---

## 19. Data flow split — MongoDB is the buffer, push is only for urgent

**Default path (everything scraped):**

```
Pod tools (scrape_chat, scrape_mail, scrape_calendar)
  → MongoDB (o365_scrape_messages, o365_message_ledger,
             o365_discovered_resources, scraped_mail, scraped_calendar)
  → Indexer (Kotlin server, polling)
  → Tasks (CHAT_INDEX, EMAIL_INDEX, CALENDAR_INDEX)
  → UI (via task stream / sidebar push)
```

The pod is the **sole writer** to these Mongo collections. The Kotlin server
is the **sole reader + indexer** — it transforms scrape rows into typed Tasks,
applies `ResourceFilter` + project mapping, and surfaces them through normal
UI pipelines. The pod never creates Tasks, never calls task-creation RPCs,
never dedupes at scrape time.

**Urgent exception (one and only one bypass):**

```
Direct message OR @mention detected
  → notify_user(kind='urgent_message', chat_id=…, sender=…, preview=…)
  → POST /internal/o365/notify (Kotlin)
  → priorityScore=95, alwaysPush=true, FCM+APNs + kRPC push
  → User's phone + chat bubble, immediately
```

Criteria for `urgent_message`:
- `isDirect=true` on the chat row (1:1 DM), **or**
- `@mention` of the logged-in user observed in the message content (DOM
  marker `data-tid="at-mention"` matching the account's display name), **or**
- **Incoming direct call toast detected by the background watcher (§10a)** —
  agent reads caller name + preview, fires `urgent_message`, and per §15
  MUST NOT click accept / answer.

Nothing else is urgent — group chat messages without a mention, mail,
calendar events, discovered resources, capability changes all go through
the MongoDB → Indexer → Task path. The server-side 60s de-dup window
(`O365_URGENT_DEDUP_WINDOW_S`) prevents flooding when the same chat stays
unread.

**No scraped content is ever sent over `notify_user`.** The push carries
ledger metadata (chat_id, sender name, short preview) — the full message
body is only in Mongo, to be pulled by the indexer.

---

## 20. Agent context persistence + cleanup

The LangGraph `MongoDBSaver` checkpointer persists raw message history per
`thread_id=connection_id`. That is correct for resume-after-restart but
grows unbounded, and plain `trim_messages` drops old triples whole — losing
any pattern the agent had learned (working selectors, tenant-specific
quirks, action templates). A **per-pod knowledge layer** supplements the
raw checkpoint so the agent does not re-discover the same facts every time
it restarts or hits the trim threshold.

### Three types of agent memory

| Type | Storage | Retention | Purpose |
|------|---------|-----------|---------|
| **Raw messages** | `langgraph_checkpoints_pod` (existing) | Rolling window (see cleanup rules) | LangGraph state for resume; trimmed at `agent` node entry |
| **Learned patterns** | `pod_agent_patterns` (new) | Forever, per `connectionId` + URL pattern | Stable selectors, `app_state` → working action templates, tenant-specific quirks (MFA selector, pre-join layout) |
| **Session memory** | `pod_agent_memory` (new) | Forever per `connectionId`, compressed as it grows | Distilled narrative of past sessions; injected into the SystemMessage on cold start |

### `pod_agent_patterns` schema

```json
{
  "_id": ObjectId,
  "connectionId": ObjectId,
  "urlPattern": "teams.microsoft.com/v2/*/prejoin",
  "appState": "prejoin",
  "workingSelectors": {
    "join_button": "[data-tid='prejoin-join-button']",
    "mic_toggle": "[data-tid='toggle-mute']",
    "caller_name": "[data-tid='call-toast'] .caller-name"
  },
  "actionTemplate": ["mic_toggle", "cam_toggle", "join_button"],
  "notes": "Pre-join: mute via toggle-mute, then click prejoin-join-button. Microsoft sometimes replaces data-tid; fall back to visible text 'Join now'.",
  "observedCount": 14,
  "successCount": 13,
  "lastUsedAt": ISODate,
  "lastSuccessAt": ISODate
}
```

- Indexed by `{connectionId, urlPattern}`.
- A selector is promoted to `workingSelectors` after **3 distinct successful
  uses** across sessions. One-shot matches stay in raw messages.
- On cold start the runner loads patterns for the current URL pattern and
  injects them into the SystemMessage. The agent then uses them as its
  first guess, skipping re-discovery.

### `pod_agent_memory` schema

```json
{
  "_id": ObjectId,
  "connectionId": ObjectId,
  "kind": "session_summary" | "learned_rule" | "anomaly",
  "content": "2026-04-15 08:00–11:30 — resumed session after restart, scraped 23 chats (3 direct unreads), joined scheduled meeting via VLM click (Teams A/B test on join UI).",
  "compressedFromRange": {
    "start": ISODate,
    "end": ISODate,
    "messageCount": 47
  },
  "createdAt": ISODate
}
```

- `session_summary` — one doc per cleanup pass, 2–3 sentences.
- `learned_rule` — durable generalizations ("MFA code rotates every 30 s
  on this tenant", "Outlook web UI breaks scoped DOM 2× per week, needs
  VLM").
- `anomaly` — unresolved stuck events for later human review.

### Cleanup triggers

Two signals, both run inside the pod (no external scheduler):

1. **Size-triggered:** message count > `O365_POOL_CONTEXT_MAX_MSGS`
   (default 100) **or** estimated tokens > `O365_POOL_CONTEXT_MAX_TOKENS`
   (default 40k). Runs before the next agent tick if exceeded.
2. **Nightly:** at 02:00 Europe/Prague if the pod is idle (no agent ticks
   in the last 10 min). Disabled during AWAITING_MFA / active meeting
   recording.

### Cleanup algorithm

Runs inside `runner.py` as an async task, single LLM pass via router
(`capability="chat"`, short context budget):

1. **Freeze** the agent (async lock against the outer LangGraph loop).
2. **Partition** messages:
   - KEEP: last 20 messages, all open `tool_call_id` pairs, anything newer
     than 4 h, anything tied to active PodState transition.
   - SUMMARIZE: everything else.
3. **Extract patterns** from the SUMMARIZE block. For every selector that
   appears in ≥ 3 successful `ToolMessage` results across the history:
   upsert into `pod_agent_patterns` with role inferred from adjacent tool
   calls (`join_button`, `mic_toggle`, …). Bump `observedCount` /
   `successCount`.
4. **Distill summary** via single LLM call: input = SUMMARIZE block +
   existing session memory (last 5 docs); output = 2–3 sentence narrative
   plus a short list of any new `learned_rule` / `anomaly` items.
5. **Write** summary + rules to `pod_agent_memory`.
6. **Replace** the LangGraph state: KEEP messages plus one new
   `SystemMessage("Prior session summary: <distilled>")` inserted before
   the KEEP window.
7. **Unlock** the agent.

### Rules — what to compress, what to keep

**Never drop / never compress:**
- Open `tool_call_id` pairs (LangGraph would break).
- `pending_mfa_code`, `AWAITING_MFA` state, any `interrupt()` marker.
- Last 20 messages (rolling).
- Any message within the current PodState transition chain that has not
  yet reached `ACTIVE`.
- Messages tied to an active meeting recording (until
  `stop_meeting_recording` completes).

**Compress into summary:**
- Closed scrape cycles (`done(summary)` reached and all downstream
  messages drained).
- Successful login flows (STARTING → ACTIVE transition chain).
- Heartbeat `look_at_screen` observations older than 1 h.
- Old `inspect_dom` observations where the selector has already been
  promoted to a pattern.

**Extract into patterns:**
- A selector used successfully in ≥ 3 distinct tool calls → promoted to
  `workingSelectors[role]`.
- A repeated `app_state` → `[action, action, …]` sequence → promoted to
  `actionTemplate`.
- A tenant-specific quirk confirmed across ≥ 2 sessions → new
  `learned_rule`.

**Drop entirely:**
- Raw DOM `matches` arrays older than 1 h (the extracted pattern
  survives).
- VLM bounding-box and screenshot detail older than 1 h (the summary
  sentence survives).
- Failed tool-call retries that were later superseded by a successful
  attempt at the same step.

### Cold-start integration

On pod start, before the first agent LLM call, `runner.py` composes the
`SystemMessage` as:

```
<static system prompt §3 + §15 + §17 + §18 rules>

Previous-session summary:
<pod_agent_memory where kind='session_summary' order by createdAt desc limit 1>

Learned patterns for this connection (top 10 by lastUsedAt):
- teams.microsoft.com/v2/*/prejoin: join_button=[data-tid='prejoin-join-button'], mic_toggle=[data-tid='toggle-mute']
- login.microsoftonline.com/*/mfa: sign_in_number=[data-display-sign-in-code]
- teams.microsoft.com/v2/chat: chat_list_item=[data-tid='chat-list-item']
…

Learned rules (kind='learned_rule', most recent 5):
- "MFA code rotates every 30 s on tenant <id> — retry notify_user on each new code read."
- "Outlook web UI requires VLM during first 10 s after navigate — DOM selectors load late."
```

The agent tries patterns first; fresh selectors are discovered only when
the URL pattern does not match a known entry. Patterns that fail twice in
a row are demoted (bump `failureCount`; once `failureCount >= 3` the
entry is dropped from the cold-start injection).

### No cross-connection sharing

Patterns and memory are scoped to a single `connectionId`. Two connections
to the same tenant do not share learned selectors — each pod learns
independently. Tenant-wide sharing is an anti-pattern: different accounts
can hit different A/B UI variants, and leaking patterns would mix them.

---

## 21. Open items

1. **Router policy for pod traffic:** model selection is the router's concern
   (`capability="chat"` for tool-calling, `capability="vision"` for VLM). Pod
   code never names a model.
2. **Urgent notify burst damping:** server-side window configured in
   `configmap.yaml` (`O365_URGENT_DEDUP_WINDOW_S=60`).
3. **MFA code UI surface:** confirm that the mobile push payload includes
   `mfa_code` so the user sees the number without opening the app (Android
   notification body + iOS notification subtitle).
4. **Relogin approval via chat:** orchestrator agent tool
   `connection_approve_relogin(connection_id)` — out of scope for the pod, but
   needed so users can say "ano přihlas to znova" in chat off-hours.
5. **Meeting recording + indexation + view** — canonical design is in §10a
   (recording pipeline, `MeetingDocument` lifecycle, indexation) and §20
   (context persistence). Remaining implementation items:
   - Server-side `MeetingRecordingMonitor` job (stuck detector + hard
     ceiling `scheduledEnd+30min`).
   - `MeetingRecordingIndexer` job (Whisper + pyannote + scene detection
     + VLM frame descriptions → `timeline[]`).
   - Nightly retention job (drop WebM after `videoRetentionUntil`).
   - UI `MeetingScreen`: video player, timeline strip with scene
     thumbnails, synced transcript panel.
   - Orchestrator MCP tools `meeting_alone_leave` /
     `meeting_alone_stay` (new) + existing `meeting_attend_*`.
6. **Router vision routing fix:** `route-decision` for `capability=vision`
   currently returns `qwen3-coder-tool:30b` — must return a VLM
   (`qwen3-vl-tool` or cloud equivalent). Prerequisite for any VLM work.
   Tracked in `project-next-session-todos.md` item 6.


---

# Teams Pod — LangGraph Agent

> (Dříve docs/teams-pod-agent-langgraph.md)

# Teams Pod Agent — LangGraph Migration

**Status:** Design (ready for review)
**Date:** 2026-04-16
**Supersedes:** raw ReAct loop in `backend/service-o365-browser-pool/app/agent/{loop,tools,llm,prompts}.py`
**Reference:** `backend/service-orchestrator/app/agent/langgraph_runner.py`

---

## 1. Motivation

The current pod agent is a hand-rolled ReAct loop (~400 lines) that:
- Posts directly to `router-admin/decide` + Ollama `/api/chat` with OpenAI-shaped
  tool schemas built by hand.
- Hand-manages message history, trimming, tool-call ID matching.
- Has no persistence — if the pod restarts, the conversation is lost.
- Gets `400 Bad Request` from Ollama intermittently because tool-call response
  handling isn't bullet-proof (stale `tool_call_id`s, tool count vs. context,
  malformed messages array).

The Jervis orchestrator already runs on **LangGraph** (`backend/service-orchestrator/app/agent/langgraph_runner.py`) — MongoDB-checkpointed `StateGraph` with router-backed LLM calls. Per `feedback-kotlin-first.md` memory: *"Python jen pro LLM frameworky (LangChain/LangGraph/ML)"* — running two different agent frameworks in one codebase is an anti-pattern.

### What LangGraph buys us
- Battle-tested tool-call loop (`tool_call_id` matching, Ollama + OpenAI format
  variance) — no more 400s from malformed requests.
- `MessagesState` with `trim_messages` for context budget without losing
  `tool_call_id` consistency.
- `MongoDBSaver` checkpointer → pod restart resumes where it left off.
- Native `interrupt()` for MFA / meeting approval without custom state flags.
- Streaming via `astream()` for live logs.
- Same stack as orchestrator — shared patterns, shared lessons.

---

## 2. Dependency contract

Mirror orchestrator (`backend/service-orchestrator/requirements.txt`):

```
langgraph>=0.4.0
langgraph-checkpoint-mongodb>=0.3.0
langchain-core>=0.3.0
motor>=3.6.0        # already used
httpx>=0.27         # already used
```

No `langchain-ollama`, no `litellm`. LLM calls go through a thin **router-backed** adapter we write (see §4).

---

## 3. State schema

State is split into **three layers** matching persistence lifetime:

| Layer | Where | Persists | Read by |
|-------|-------|----------|---------|
| **A. LangGraph `PodAgentState`** | `MongoDBSaver` checkpoints per `thread_id=connection_id` | Yes (across pod restart) | Agent `agent` / `tools` nodes every tick |
| **B. Runner in-memory runtime** | Python process | No (rebuilt on restart from observation + Mongo) | Runner watcher / upload loop / ffmpeg supervisor |
| **C. Mongo persistent stores** | Separate collections | Yes, indefinitely | Cold-start injector, storage tools, cleanup task, UI |

### Layer A — `PodAgentState`

```python
# app/agent/state.py
from typing import Literal, TypedDict
from langgraph.graph import MessagesState

class ActiveMeeting(TypedDict, total=False):
    meeting_id: str                   # MeetingDocument._id
    title: str | None
    joined_by: Literal["user", "agent"]
    scheduled_start_at: str | None    # ISO; only set for scheduled
    scheduled_end_at: str | None
    meeting_stage_appeared_at: str
    max_participants_seen: int
    alone_since: str | None           # ISO when participants hit 1
    user_notify_sent_at: str | None   # last meeting_alone_check push
    last_speech_at: str | None
    recording_status: Literal["RECORDING", "FINALIZING"]
    chunks_uploaded: int
    chunks_pending: int
    last_chunk_acked_at: str | None
    last_user_response: dict | None   # {action, at, actor}

class PendingInstruction(TypedDict):
    id: str
    kind: str                         # "join_meeting" | "leave_meeting" | "meeting_stay" | …
    payload: dict
    received_at: str

class PodAgentState(MessagesState):
    """LangGraph state for one Teams pod agent.

    Inherits `messages` (list[BaseMessage]) from MessagesState. Tool-call IDs
    flow through AIMessage.tool_calls ↔ ToolMessage.tool_call_id and MUST be
    preserved across trim_messages passes — see §16.
    """
    # Identity (stable for pod lifetime)
    client_id: str
    connection_id: str
    login_url: str
    capabilities: list[str]

    # Pod state machine (see product §9)
    pod_state: str

    # Login / MFA bookkeeping
    pending_mfa_code: str | None
    last_auth_request_at: str | None  # ISO; 60-min cooldown gate for product §18

    # Observation snapshot — last known, for reasoning without re-observe
    last_url: str
    last_app_state: str               # login|mfa|chat_list|conversation|meeting_stage|loading|unknown
    last_observation_at: str
    last_observation_kind: Literal["dom", "vlm"]

    # Per-context notify dedup (product §7)
    notified_contexts: list[str]      # e.g. "urgent_message:<chat_id>", "mfa:42", "meeting_alone_check:<meeting_id>"

    # Active meeting (at most one per pod)
    active_meeting: ActiveMeeting | None

    # Pending server instructions — drained at tick start → HumanMessages
    pending_instructions: list[PendingInstruction]
```

**Rationale for the three design-question defaults locked 2026-04-16:**

- `pending_instructions` lives in state (A), not in runner memory. Survives
  restart — an instruction that arrived just before a checkpoint is not
  lost.
- `last_observation_*` lives in state (A), not derived from the last
  `ToolMessage` in `messages`. Messages get trimmed by `trim_messages`;
  state fields are not.
- `active_meeting` is an inline dict (A), not a pointer to
  `MeetingDocument._id`. The Mongo document is canonical; the inline dict
  is the agent-facing hot subset so reasoning doesn't re-fetch.

### Layer B — Runner in-memory runtime

Not a single dataclass; distributed across `runner.py` and helper modules:

```python
# app/agent/runner.py — PodAgent instance fields
watcher_state: dict[str, dict]      # { meeting_id: {participants, alone_since, last_speech_at},
                                    #   tab_name:   {meeting_stage, incoming_call} }
chunk_queues: dict[str, DiskChunkQueue]   # per meeting_id, disk-backed FIFO
ffmpeg_procs: dict[str, asyncio.subprocess.Process]
last_server_ack_at: datetime | None
consecutive_upload_errors: int
```

Rebuilt on pod start:
- `watcher_state` from current `inspect_dom` observation + Mongo
  `MeetingDocument.status == RECORDING`.
- `chunk_queues` from existing disk files in the chunk directory.
- `ffmpeg_procs` empty (any crashed ffmpeg means the chunk pipeline is
  stopped; agent must call `start_meeting_recording` again, not resume
  silently).

### Layer C — Mongo persistent stores

| Collection | Purpose | Spec |
|------------|---------|------|
| `langgraph_checkpoints_pod` | Raw LangGraph state history, per thread | existing |
| `pod_agent_patterns` | Learned selectors + action templates | product §20 |
| `pod_agent_memory` | Session summaries, learned rules, anomalies | product §20 |
| `MeetingDocument` | Meeting metadata + chunks + timeline | product §10a (extended) |
| `o365_scrape_messages` / `o365_message_ledger` / `o365_discovered_resources` / `scraped_mail` / `scraped_calendar` | Scraped content → Indexer → Tasks | product §19 |

Tools that write Layer C: storage primitives (`store_chat_row`,
`store_message`, `store_discovered_resource`, `store_calendar_event`,
`store_mail_header`, `mark_seen`). Context persistence cleanup writes
`pod_agent_patterns` + `pod_agent_memory`. Meeting chunks are written by
the server on receipt of `POST /internal/meeting/{id}/video-chunk`.

### Cold-start SystemMessage composition

On pod start (or after a cleanup pass), the runner composes the
`SystemMessage` as:

```
<static prompt — §3 decision table, §15 hard rules, §17 MFA rules, §18 relogin gating, §10a meeting rules>

Previous-session summary:
<pod_agent_memory where kind='session_summary' order by createdAt desc limit 1>

Learned patterns for this connection (top 10 by lastUsedAt):
<pod_agent_patterns where connectionId=<C> order by lastUsedAt desc limit 10>

Learned rules (kind='learned_rule', most recent 5):
<pod_agent_memory where kind='learned_rule' order by createdAt desc limit 5>

CURRENT STATE (as of <now>):
  pod_state: <state["pod_state"]>
  last_url: <state["last_url"]>
  last_app_state: <state["last_app_state"]>
  last_observation_at: <state["last_observation_at"]> (<kind>)
  tab_state: <state["tab_state"] — short listing>
  last_scrape_state: <fingerprints for chats / mail / calendar — coarse counts + most_recent_timestamp>
  active_meeting: <state["active_meeting"] — compact dict or "none">
  pending_instructions: <queue snapshot, drained to HumanMessages on tick>
```

This SystemMessage is **regenerated every outer-loop entry**, not stored
in the checkpoint — prompt improvements + pattern promotions + state
updates roll out without checkpoint rewrites.

## 3b. LLM context window — what goes into every invocation

The `agent` node does NOT ship `state["messages"]` verbatim to the LLM.
Each invocation composes four parts; the in-memory context window is
bounded to a fixed budget so `session` size never drives up LLM latency
or cost.

### The four parts of every `llm.ainvoke(...)` call

| # | Part | Source | Budget | Regenerated each tick? |
|---|------|--------|--------|------------------------|
| 1 | `SystemMessage` | composed from static prompt + Mongo (`pod_agent_memory`, `pod_agent_patterns`) + `PodAgentState` snapshot | **6 000 tokens** hard cap (`O365_POOL_CONTEXT_SYSTEM_CAP`) | yes, every tick |
| 2 | Trimmed message history | `trim_messages(state["messages"], strategy="last", max_tokens=12000, include_system=False, start_on="human", allow_partial=False)` | **12 000 tokens** (`O365_POOL_CONTEXT_TRIM_TOKENS`) | yes, every tick |
| 3 | Tool schemas | `bind_tools(ALL_TOOLS)` serialized by LangChain | **~4 000 tokens** fixed (~20 tools × ~200 tokens) | no, bound once |
| 4 | Agent reply (output) | LLM generation | **500–2 000 tokens** | n/a |

**Total per invocation: ~17–24 k input + 0.5–2 k output ≈ 26 k working
set.** Fits comfortably in a 32 k local context, trivial in 128 k cloud.

### Why `trim_messages` is safe

`allow_partial=False` + `start_on="human"` mean the trim drops whole
Human → AI-tool_calls → Tool → … → AI-final triples from the head. It
never orphans a `tool_call_id` pair — LangGraph would otherwise error on
the next agent step.

Because part 1 (SystemMessage) is regenerated from the Mongo persistent
layer + `PodAgentState` snapshot, the LLM always sees **current** state +
distilled history even when part 2 has dropped 90 %+ of raw messages.
The agent never loses track of:

- Pod state machine position (`pod_state`)
- Tab layout (`tab_state`)
- What changed since last scrape (`last_scrape_state` fingerprints)
- Active meeting context (`active_meeting` inline)
- Learned selectors (`pod_agent_patterns` top 10)
- Past-session achievements (`pod_agent_memory` latest `session_summary`)

Raw message trim is then **safe by construction** — the SystemMessage
carries the durable state summary.

### Growth dynamics

| Metric | Value |
|--------|-------|
| Per react cycle appended to `state["messages"]` | 1–2 k tokens (AIMessage + N ToolMessages, avg 4–8 KB) |
| `ACTIVE` tick cadence | 30 s (§4) |
| Growth in `state["messages"]` at `ACTIVE` | ~2–4 k tokens / min |
| Growth in 1 hour uninterrupted (no cleanup yet) | 120–240 k tokens on disk, **still only ~12 k tokens sent to LLM** |
| Cleanup trigger fires at | 100 msgs (≈ 25 min active) or 40 k tokens |
| Post-cleanup `state["messages"]` size | ~20 msgs (~12 k tokens) + one injected `SystemMessage("Prior session summary…")` |

The LLM never sees more than ~24 k tokens of context regardless of how
long the pod has been running.

### Adaptive budget for long reasoning chains

If a single action (login with many cascaded tool calls, meeting join
with mic/cam/Join cascade) produces a chain longer than 12 k tokens,
`trim_messages(allow_partial=False)` cannot split the chain — the whole
trimmed window stretches until the chain fits. If it still exceeds the
local model's window:

- Router escalates to a cloud model with 128 k+ context
  (`capability="chat"` + `context_hint="large"` → routing policy picks
  the appropriate backend).
- Pod code does not change — it is a routing decision.

This path is rare in steady state; routine scrape cycles stay well
under 6–8 k tokens per react turn.

### Configuration (configmap)

```
O365_POOL_CONTEXT_TRIM_TOKENS=12000    # trim_messages budget per invocation
O365_POOL_CONTEXT_SYSTEM_CAP=6000      # SystemMessage hard cap
O365_POOL_CONTEXT_MAX_MSGS=100         # cleanup size trigger (product §20)
O365_POOL_CONTEXT_MAX_TOKENS=40000     # cleanup size trigger (product §20)
```

### What we explicitly do NOT do

- **No gzip / zstd / codec compression** on the message payload.
  `trim_messages` + `session_summary` distillation + pattern promotion
  keep the per-invocation context bounded well under the LLM window
  indefinitely. Adding a codec would buy nothing.
- **No sliding window smaller than 12 k.** Tests with 6 k windows lost
  tool-call context mid-login and the agent started re-observing from
  scratch. 12 k is the floor for reliable multi-step flows.
- **No "last N messages" count-based trim.** Token-based trim handles
  the long-tail ToolMessage (a 20 KB `inspect_dom` result counts as
  one message but ~5 k tokens); count-based trim would let context
  balloon or over-shrink.
- **No per-tool context stripping.** Tool schemas are bound once at
  graph compile time; dropping tools per-turn would break the react
  pattern and is unnecessary at 4 k tokens total.

### Verification (after A-section lands)

Instrument `runner.py` to log per-invocation token counts: SystemMessage
tokens, trimmed-messages tokens, tool-schemas tokens, sum, LLM response
tokens. If any single invocation exceeds 28 k tokens input, log a
warning and sample the offending state for diagnosis. Budget creep
should be caught at the invocation level, not only at the cleanup
level.

---

## 4. Router-backed LLM adapter

Pod agent cannot use `ChatOllama` directly — the router decides `target=local|openrouter` and the model per request. We write a `BaseChatModel` subclass:

```python
# app/agent/llm.py (new)
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import BaseMessage, AIMessage
import httpx

class RouterChatModel(BaseChatModel):
    """LangChain chat model that dispatches through jervis-ollama-router.

    Per call:
      1. POST /router/admin/decide → { target, model, api_base/api_key }
      2. If target=openrouter → POST <OpenRouter>/v1/chat/completions
         If target=local     → POST <api_base>/api/chat (Ollama native)
      3. Normalize reply to AIMessage with tool_calls list.
    """
    client_id: str
    capability: str = "tool-calling"

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        route = await self._decide(messages, **kwargs)
        reply = await self._dispatch(route, messages, **kwargs)
        return ChatResult(generations=[ChatGeneration(message=reply)])
```

This class mirrors `llm_with_cloud_fallback()` + `llm_provider.completion()` from the orchestrator's `_helpers.py` + `llm/provider.py`, but wrapped as a LangChain `BaseChatModel` so `bind_tools()` and streaming work as expected.

**Important**: we pass `tools` through LangChain's `bind_tools()` → LangChain serializes the tool schemas to the format Ollama expects (no hand-written dicts). This is the single biggest win over the current implementation.

---

## 5. Tools

Tools become Python functions with `@tool` decorator — schema is generated from type hints + docstring:

```python
# app/agent/tools.py (new)
from langchain_core.tools import tool
from app.agent.context import get_pod_context   # contextvars-based

@tool
async def inspect_dom() -> dict:
    """Primary observation — walk the DOM with shadow-root pierce and return
    structured JSON with app shells, chat rows, conversation messages, etc."""
    ctx = get_pod_context()
    return (await dom_probe.probe(ctx.page())).__dict__

@tool
async def scrape_chat() -> dict:
    """Walk Teams sidebar, store chat rows into discovered_resources + ledger.
    Fires urgent_message notify for each new direct message."""
    ctx = get_pod_context()
    return await _scrape_chat_impl(ctx)

@tool
async def notify_user(
    kind: Literal["urgent_message", "meeting_invite", "auth_request", "mfa", "error", "info"],
    message: str,
    chat_id: str | None = None,
    chat_name: str | None = None,
    sender: str | None = None,
    preview: str | None = None,
) -> dict:
    """Send kind-aware notification to the Kotlin server. Direct messages MUST
    use kind='urgent_message'. At most one notify per context."""
    ctx = get_pod_context()
    return await _notify_impl(ctx, kind=kind, message=message, ...)
```

**ToolContext is resolved via `contextvars.ContextVar`**, not passed through the graph state. This keeps the graph state JSON-serializable (checkpointer requirement) while giving tools access to the live Playwright `Page`, `TabManager`, `ScrapeStorage`, `MeetingRecorder`.

Tool list (Phase 1 read-only):
- `inspect_dom`, `look_at_screen`
- `click`, `fill`, `press`, `navigate`, `wait`
- `report_state`, `notify_user`
- `scrape_chat`, `scrape_mail`, `scrape_calendar`, `mark_seen`
- `meeting_presence_report`, `start_adhoc_meeting_recording`, `stop_adhoc_meeting_recording`
- `done`, `error`

~17 tools — same surface as current, but auto-schema'd.

---

## 6. Graph

```python
# app/agent/graph.py (new)
from langgraph.graph import StateGraph, END
from langgraph.prebuilt import ToolNode, tools_condition

def build_pod_graph():
    llm = RouterChatModel(client_id=<injected>).bind_tools(TOOLS)

    def agent_node(state: PodAgentState) -> dict:
        reply = llm.invoke(state["messages"])  # OR async _ainvoke
        return {"messages": [reply]}

    sg = StateGraph(PodAgentState)
    sg.add_node("agent", agent_node)
    sg.add_node("tools", ToolNode(TOOLS))
    sg.set_entry_point("agent")
    sg.add_conditional_edges("agent", tools_condition)  # → "tools" or END
    sg.add_edge("tools", "agent")
    return sg
```

Standard `create_react_agent` pattern (explicit graph for clarity + future expansion):
- `agent` node calls the LLM with bound tools.
- `tools` node executes the tool calls (`ToolNode` from `langgraph.prebuilt`).
- Conditional edge: if `AIMessage.tool_calls` present → go to `tools`, else END.
- After `tools`, loop back to `agent`.

---

## 7. Checkpointer + thread_id

Same pattern as orchestrator:

```python
# app/agent/persistence.py (new)
from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver

_checkpointer: MongoDBSaver | None = None

def init_checkpointer():
    global _checkpointer
    client = MongoClient(settings.mongodb_url)
    _checkpointer = MongoDBSaver(client, db_name="jervis")

def get_compiled_graph():
    sg = build_pod_graph()
    return sg.compile(checkpointer=_checkpointer)
```

**thread_id = connection_id** (not task_id — pod is long-running per-connection, not per-task). State persists across pod restarts, so after a restart we resume with the full chat history instead of bootstrapping from zero.

Collection: `langgraph_checkpoints_pod` (separate namespace from orchestrator to prevent collisions). TTL: 30 days.

---

## 8. Lifecycle / loop

The agent no longer self-drives `while True`. Instead:

```python
# app/agent/runner.py (new)
async def run_pod_agent(state: PodAgentState):
    graph = get_compiled_graph()
    config = {"configurable": {"thread_id": state["connection_id"]}}

    while not stop_event.is_set():
        # One step: stream events until interrupt or tool_calls exhausted
        async for event in graph.astream(state, config=config):
            log_event(event)

        # After stream completes, decide cadence based on PodState + last obs
        await asyncio.sleep(adaptive_sleep())

        # Re-enter with fresh observation prompt
        state = {"messages": [HumanMessage("Re-observe and continue.")]}
```

Or simpler: the agent handles its own loop via a final "should I continue?" conditional. But keeping the outer `while` gives us clean entry points for `/session/{id}/refresh`, `/instruction/{id}`, and bootstrap.

---

## 9. Interrupts (MFA + meeting approval)

LangGraph 0.2.27+ has native `interrupt()`:

```python
from langgraph.types import interrupt, Command

@tool
async def request_mfa_code() -> str:
    """Wait for the user to supply an MFA code via POST /session/{id}/mfa."""
    code = interrupt({"kind": "mfa_code_needed"})
    return code   # resume payload
```

The graph pauses; the HTTP endpoint `POST /session/{id}/mfa` calls
`graph.stream(Command(resume=code), config=...)` to unblock.

Same pattern for `meeting_approval_required`.

---

## 10. Bootstrap (PVC session pre-auth) keeps working

The bootstrap task in `main.py` stays — LangGraph doesn't replace it. Rationale:
- Bootstrap is DOM-only (no LLM) — it succeeds even when the router is saturated.
- It sets up tabs + pushes capabilities before the agent even starts its first
  LLM call, so the UI resource flow works immediately.

The agent assumes tabs are already set up and focuses on scraping + autonomous
behavior. First iteration prompt → "Taby jsou připraveny. Zkontroluj DOM a
rozhodni, co udělat."

---

## 11. Migration plan

| # | Step | Files |
|---|------|-------|
| 1 | Add deps | `requirements.txt` |
| 2 | `RouterChatModel` wrapper | `app/agent/llm.py` (rewrite) |
| 3 | Tools as `@tool` | `app/agent/tools.py` (rewrite) |
| 4 | ContextVar for ToolContext | `app/agent/context.py` (new) |
| 5 | Graph builder | `app/agent/graph.py` (new) |
| 6 | Checkpointer | `app/agent/persistence.py` (new) |
| 7 | Runner | `app/agent/runner.py` (new) |
| 8 | System prompts | `app/agent/prompts.py` (edit — keep content) |
| 9 | Delete raw loop | `app/agent/loop.py` (DELETE) |
| 10 | Rewire session + main | `app/routes/session.py`, `app/main.py` |
| 11 | Tab priority fix | `app/tab_manager.py` (separate commit, same release) |

---

## 12. Non-goals (this migration)

- Does **not** introduce LangChain-wide adoption. Only LangGraph + `langchain-core` (BaseChatModel base class + message types + tool decorator).
- Does **not** change the router protocol (`/router/admin/decide`, `/api/chat`).
- Does **not** change `MeetingRecorder`, `TabManager`, `ScrapeStorage`, `TokenExtractor`, VNC, DOM probe JS.
- Does **not** replace orchestrator's agent — this is pod-side only.

---

## 13. Risks + mitigations

| Risk | Mitigation |
|------|-----------|
| `MongoDBSaver` connection contention with orchestrator | separate collection (`langgraph_checkpoints_pod`) |
| `BaseChatModel` serialization breaks (it's Pydantic) | match orchestrator's pattern exactly; `.invoke()` with raw messages |
| Tool context leakage between concurrent clients in same pod | one pod = one client, single contextvar is fine |
| Checkpoint bloat per connection | 30-day TTL + message trimming (`trim_messages(strategy="last", max_tokens=…)`) |
| Router 400 on bound tool schemas | LangChain's `bind_tools()` serializer is mature; validated by orchestrator |

---

## 14. Success criteria

1. No more `400 Bad Request` from Ollama `/api/chat` — LangGraph-managed tool call format works first try.
2. Pod restart resumes agent conversation instead of re-bootstrapping.
3. MFA / meeting approval use LangGraph `interrupt()` — no custom state flags.
4. Agent completes at least one successful `scrape_chat` → `discovered_resources`
   populated → UI shows chats in "Přidat zdroje".
5. Code size: agent module drops from ~800 LOC to ~400 LOC (measured on
   `app/agent/`).

---

## 15. Contract with product spec (`docs/teams-pod-agent.md`)

Sections §15–§19 of the product spec are **design constraints** on this
implementation — not optional. Restated here for module authors:

| Constraint | Implementation guard |
|------------|----------------------|
| No regex / HTML parsing (product §15) | No `re.*` / `bs4` / string `.split()` on HTML in `app/agent/*` or `app/scrape_*`. `inspect_dom` returns `{matches, count, url, truncated}` — generic, no named semantic fields. |
| No hardcoded semantic extractors (product §15 + §3) | `app/agent/dom_probe.py` replaced by a thin generic query helper: given `(selector, attrs, max)`, runs a shadow-piercing walk and returns raw matches. No `chat_rows`, `calendar_events`, etc. anywhere in the pod code. |
| Agent-chosen observation (product §3) | System prompt carries the decision table from §3. VLM for unknown state (cold start, after navigate, after error, heartbeat). Scoped DOM for known-field verification. Self-correction: `inspect_dom count=0` → agent MUST call `look_at_screen`. |
| No hardcoded URL lists / TabType (product §15) | `tab_manager.py` is a `dict[str, Page]` + open/close/switch. No `_BUSINESS_URLS`, no enum, no `_DEFAULT_TAB_NAMES`. |
| No bootstrap retry / force-setup (product §15) | `main.py` only does `launch` + `agent.start()`. `routes/session.py` only has `GET /session` + `POST /init` + `POST /mfa` + `DELETE`. No `/crawl`, `/force-*`, `/rediscover`. |
| No server→pod RPC bypassing agent (product §15 + §10a) | `POST /meeting/join` removed. Server sends `POST /instruction/{id}` with `join_meeting` payload; agent composes navigate + click + start_meeting_recording itself. |
| Cold-start probe first (product §16) | System prompt opens with: *"First action after any navigate/error/cold-start is `look_at_screen(reason='cold_start')`. After that, prefer scoped `inspect_dom` for verification unless you have no state expectation."* Runner sends `HumanMessage("Re-observe and decide.")` on every outer-loop re-entry. |
| Authenticator-only MFA (product §17) | Prompt explicitly lists forbidden MFA methods. `notify_user` schema requires `kind='mfa'` to include `mfa_code: str` — Pydantic validator fails without it. Code read: scoped DOM first on `[data-display-sign-in-code], [aria-live] .number`, VLM fallback. |
| Relogin window (product §18) | `runner.py` enforces: agent MUST call `is_work_hours()` or `query_user_activity()` before any `fill_credentials(field='password')` tool call while `report_state=AUTHENTICATING`. This is enforced via prompt, not code. |
| Data flow split (product §19) | Storage-primitive tools (`store_*`, `mark_seen`) write only to Mongo. `notify_user(kind='urgent_message')` is the only tool that calls `POST /internal/o365/notify` with non-trivial payload. All other kinds are metadata-only. |

---

## 16. Cold-start checkpoint-resume semantics

After a pod restart, `MongoDBSaver` loads the last checkpoint for
`thread_id=connection_id`. Agent MUST NOT resume mid-action. Contract:

1. Outer `runner.py` loop always enters the graph with a fresh
   `HumanMessage("Observe current browser state and decide next action.")`
   appended to the resumed `messages`.
2. The system prompt (first `SystemMessage`) is **regenerated** on every
   outer-loop entry (not stored) so prompt improvements roll out without
   checkpoint rewrites.
3. `trim_messages(strategy="last", max_tokens=8000, token_counter=len_estimator)`
   runs at the `agent` node entry — drops whole Human/AI/Tool message triples
   from the head, never splits a `tool_call_id` pair.
4. If the resumed state shows `pod_state=AWAITING_MFA` with a `pending_mfa_code`,
   but no recent `notify_user(kind='mfa')` in the last 60s of wall time, the
   agent re-emits the notify. Stale checkpoints must not leave the user
   without a push.

---

## 17. Tool-set refactor (aligned with product §5)

Generic `inspect_dom(selector, attrs, text, max_matches)` replaces the old
semantic-extractor walker. No `read_mfa_code_from_screen` sub-tool needed —
agent composes the code read from `inspect_dom` + `look_at_screen` per the
decision table (§3 + product §17 step 2).

Tool list (new + renamed from the legacy surface):

| Tool | Change |
|------|--------|
| `inspect_dom(selector, attrs, text, max_matches)` | **Rewritten**: returns `{matches, count, url, truncated}`. No `chat_rows`/`calendar_events`. |
| `look_at_screen(reason, ask?)` | **Extended**: `ask` parameter for focused VLM questions ("return the 2-digit number", "is Microsoft Authenticator offered?"). |
| `click_visual(description)`, `fill_visual(description, value)` | **New**: VLM-resolved actions for dynamic UI without stable selectors. |
| `fill_credentials(selector, field)` | **Renamed** from `fill(field='password')` — explicit field name, runtime injects, LLM never sees secret. |
| `store_chat_row`, `store_message`, `store_discovered_resource`, `store_calendar_event`, `store_mail_header`, `mark_seen` | **New storage primitives**: agent composes its own scraping loop. |
| `start_meeting_recording(meeting_id?, title?)`, `stop_meeting_recording(meeting_id)` | **Merged**: unifies `start_adhoc_meeting_recording` + server-triggered join (the latter now goes through `/instruction/` → agent → these tools). |
| `scrape_chat`, `scrape_mail`, `scrape_calendar` | **Removed**: compound semantic tools are anti-pattern — agent composes from primitives. |
| `meeting_presence_report(present, meeting_stage_visible)` | **Extended**: second flag distinguishes stage-visible from background presence. |
| `query_user_activity`, `is_work_hours` | Unchanged. |

`notify_user(kind='mfa', ...)` keeps its signature but requires
`mfa_code: str` when `kind='mfa'`. Pydantic validator on the tool schema
rejects empty.

Deletion list (legacy to remove before step 15 lands):
- `app/agent/dom_probe.py` — full-page semantic walker, replaced by new
  `inspect_dom`. Keep the shadow-pierce JS helper as a private function
  inside the new tool, nothing else.
- `app/teams_crawler.py` — regex-based sidebar walker with direct `page.*`
  calls outside `@tool`. Replaced by agent-composed `inspect_dom` +
  `click(selector)` + `store_message` loop.
- `app/routes/scrape.py` `/crawl` endpoints (lines 70–135) + `_DEFAULT_TAB_NAMES`
  dict (lines 34–38).
- `app/routes/session.py` + `app/main.py` references to `TeamsCrawler`.
- `app/meeting_recorder.py` `join()` direct RPC path — collapse into the
  agent-driven flow via `start_meeting_recording` tool.

---

## 18. Migration plan (post spec rewrite 2026-04-16 PM)

Five sections. Each section ships in one rolling rebuild (the prompt, tool
surface, endpoints, and schemas land together) and verifies on the
Unicorn (no-MFA) pod before the next begins. **Step 12 (per-tool-call
logging in `runner.py`) landed during the PM session.** Everything below
is fresh.

### Section A — Tool surface rewrite (pod-only)

Replaces legacy `dom_probe.py` + `teams_crawler.py` + compound scrape
tools with generic `inspect_dom`, storage primitives, `click_visual` /
`fill_visual`, `fill_credentials`, and the unified
`start_meeting_recording` / `stop_meeting_recording` / `leave_meeting`
trio. New system prompt carries product §3 decision table + §15 hard
rules + §17 MFA rules + §18 relogin gating + §10a meeting-end table.

Key files: `app/agent/prompts.py` (rewrite), `app/agent/tools.py`
(rewrite), `app/agent/_dom_query.js` (new). Delete:
`app/agent/dom_probe.py`, `app/teams_crawler.py`; trim `/crawl` +
`_DEFAULT_TAB_NAMES` from `app/routes/scrape.py`; remove `TeamsCrawler`
refs from `app/routes/session.py` and `app/main.py`.

Verification: `grep 'teams_crawler\|dom_probe\|_DEFAULT_TAB_NAMES\|\bre\.' app/`
returns nothing. One Unicorn scrape cycle produces at least one
`store_chat_row` Mongo document from scoped `inspect_dom` output.

### Section B — MFA + relogin wiring (pod + server + orchestrator)

- B1: Pydantic validator on `notify_user(kind='mfa')` requires `mfa_code`
  (`app/agent/tools.py`).
- B2: Kotlin push payload carries `mfa_code`; mobile notification body
  shows the 2-digit number (`InternalO365SessionRouting.kt`,
  `NotificationRpcImpl.kt`).
- B3: Orchestrator MCP tool `connection_approve_relogin(connection_id)`
  so users can chat-approve off-hours relogin
  (`backend/service-orchestrator/app/tools/definitions.py`).

### Section C — Meeting pipeline (pod + server + orchestrator)

Largest section. Six logical chunks, all required end-to-end for product
§10a. Lands together in one rebuild.

**C-pod** (`backend/service-o365-browser-pool/app/`):

- C-pod-1: Unify `start_meeting_recording(meeting_id?, title?, joined_by)`
  + `stop_meeting_recording(meeting_id)` + `leave_meeting(meeting_id,
  reason)`. Drop `MeetingRecorder.join()` and the `/meeting/join` HTTP
  route.
- C-pod-2: WebM pipeline inside `start_meeting_recording`: ffmpeg
  `x11grab` (5 fps VP9 from `O365_POOL_MEETING_FPS`) + PulseAudio
  `jervis_audio.monitor` (Opus) → 10-second WebM chunks →
  `{meeting_id}_{chunkIndex}.webm` in the pod chunk dir.
- C-pod-3: Disk chunk queue + upload loop mirroring
  `shared/ui-common/.../meeting/RecordingUploadService.kt` +
  `AudioChunkQueue.kt`. 3 s poll, 2 s per-chunk failure delay,
  indefinite backoff (no max-fail pause — pod is headless).
- C-pod-4: Extend background watcher (`runner.py`) with
  `participant_count`, `alone_banner`, `meeting_ended_banner`, plus
  audio silence via ffmpeg `silencedetect`. End-detection HumanMessages
  with thresholds from configmap (`O365_POOL_MEETING_PRESTART_WAIT_MIN`,
  `_LATE_ARRIVAL_ALONE_MIN`, `_ALONE_AFTER_ACTIVITY_MIN`,
  `_USER_ALONE_NOTIFY_WAIT_MIN`).
- C-pod-5: `leave_meeting` tool body — scoped DOM click on
  `[data-tid="call-end"]` + VLM fallback + verify stage disappears.

**C-srv** (`backend/server/`):

- C-srv-1: `MeetingRecordingDispatcher` posts
  `/instruction/{id} join_meeting` instead of `POST /meeting/join`. Drop
  the direct-RPC path.
- C-srv-2: `MeetingDocument` schema additions — `status` (RECORDING /
  FINALIZING / INDEXING / DONE / FAILED), `joinedByAgent`,
  `chunksReceived`, `lastChunkAt`, `webmPath`, `videoRetentionUntil`,
  `timeline[]`. Push `subscribeMeeting(id)` snapshot on every mutation
  (guideline #9).
- C-srv-3: `POST /internal/meeting/{id}/video-chunk?chunkIndex=<N>`
  idempotent endpoint (dedup by `chunkIndex`). `POST
  /internal/meeting/{id}/finalize` closes the WebM and triggers
  indexer.
- C-srv-4: `MeetingRecordingMonitor` periodic job (60 s). Stuck
  detector (`status=RECORDING` + `lastChunkAt > 5 min`) emits urgent
  USER_TASK. Hard ceiling (`scheduledEndAt + 30 min` still recording)
  posts `/instruction/{id} leave_meeting`; 5 min more without stop
  emits `notify_user(kind='error')`.
- C-srv-5: `meeting_alone_check` notify kind + chat bubble routing
  (`[Odejít] [Zůstat]` buttons → orchestrator MCP calls).
- C-srv-6: `MeetingRecordingIndexer` — ffmpeg extracts `audio.opus` →
  Whisper + pyannote on VD GPU pipeline (existing); scene-detect frames
  (`ffmpeg -vf 'select=gt(scene,0.1)+gt(mod(t,2),0)'`); per-frame VLM
  description via router `capability="vision"`; merge into `timeline[]`;
  KB index transcript + descriptions.
- C-srv-7: Nightly retention job — drop WebM files past
  `videoRetentionUntil` (default 365 days); keep metadata + transcript
  + frames + descriptions indefinitely.

**C-orch** (`backend/service-orchestrator/app/tools/definitions.py`):

- C-orch-1: MCP tools `meeting_alone_leave(meeting_id)` and
  `meeting_alone_stay(meeting_id)`. Button clicks + chat intents
  ("ten meeting už je prázdný", "nech to ještě běžet") resolve through
  these.

Verification gates:

- C-pod alone: ad-hoc meeting on Unicorn → WebM chunks arrive in
  `MeetingDocument.chunksReceived`; `stop_meeting_recording` triggers
  FINALIZING.
- C-srv full: scheduled approval → pod joins → records → FINALIZE →
  INDEXING → `timeline[]` populated → DONE.
- C-orch: user types "vypadni z meetingu" in chat → orchestrator
  resolves to `meeting_alone_leave` → pod exits.

### Section D — Agent context persistence + cleanup (pod-only)

Product §20. New Mongo collections `pod_agent_patterns` +
`pod_agent_memory`. Cold-start SystemMessage composer loads top-10
patterns + last session summary. Size-trigger (100 msgs / 40k tokens)
and nightly (02:00 Prague, idle only) cleanup. Pattern promotion at ≥ 3
successes, demotion at 3 failures, no cross-connection sharing.

Key files: `app/context_store.py` (new), `app/agent/context_cleanup.py`
(new), `app/agent/runner.py`, `app/agent/prompts.py`. D is **additive**,
not invasive — can land after A/B/C stabilize.

### Section E — Meeting View UI (Kotlin/Compose, parallel track)

Product §10a "UI visibility" + §21 item 5. Can proceed in parallel with
D once C-srv-3 is live (chunks accumulating).

- E1: `MeetingScreen` video player using `/meeting/{id}/stream.webm`.
- E2: Timeline strip with scene-change thumbnails
  (`GET /meeting/{id}/frames`), hover description, click-jumps video +
  scrolls transcript.
- E3: Transcript panel synced to audio timecode.
- E4: Live recording status row (status, `chunksReceived`, stale alert)
  driven by `subscribeMeeting(id)` push stream.

Key files: `shared/ui-common/.../meeting/MeetingScreen.kt`,
`shared/common-api/.../meeting/IMeetingService.kt`,
`shared/common-dto/.../meeting/`.

**Ordering:** A → B → C → (D || E). A unblocks observation; B unblocks
real-tenant MFA on non-Unicorn pods; C lands the meeting capture +
indexation. D and E are independent.
