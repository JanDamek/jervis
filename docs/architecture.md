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
12. [Coding Agents](#coding-agents)
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
- **Capability routing** - extraction capability routes to qwen3:8b on p40-2

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
Email processing includes a pre-classification step before LLM qualification:

```
Email → ContentClassifier → ClientResolver → QualificationHandler
```

### Components

| File | Purpose |
|------|---------|
| `backend/server/.../email/ClientResolver.kt` | Multi-step client resolution (sender mapping → domain mapping → thread history → fallback) |
| `backend/service-orchestrator/app/unified/content_classifier.py` | Rule-based + LLM content type detection (NEWSLETTER, INVOICE, JOB_OFFER, etc.) |
| `backend/service-orchestrator/app/unified/job_offer_analyzer.py` | Extracts job details, matches skills, scores opportunities |
| `backend/service-orchestrator/app/unified/invoice_processor.py` | Extracts invoice fields (amount, VS, due date), flags urgent |

### Content Types & Routing
- **NEWSLETTER** → auto-DONE (no action needed)
- **JOB_OFFER** → always QUEUED as USER_TASK with skill analysis and scoring
- **INVOICE** → QUEUED (or URGENT_ALERT if due < 7 days)
- **CONTRACT, BUG_REPORT, SUPPORT_REQUEST, MEETING_REQUEST** → QUEUED
- **OTHER** → standard LLM qualification

### Client Resolution Pipeline
1. **Sender mapping** (exact + pattern): `ConnectionDocument.senderClientMappings`
2. **Domain mapping**: `ConnectionDocument.domainClientMappings`
3. **Thread history**: same thread → same client
4. **Fallback**: connection's default client

### UI
Connection edit dialog shows sender/domain → client ID mapping editor for email connections.

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
2. **Task creation (15s):** Picks up NEW MR documents → creates SCHEDULED_TASK in QUEUED state (bypasses KB indexation — MR content IS the task) → marks REVIEW_DISPATCHED
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

Jervis integrates coding agents running as standalone kRPC microservices. All implement the shared `ICodingClient` interface (`execute(CodingRequest): CodingResult`) and communicate with the server over WebSocket/CBOR.

### Agent Overview

| Agent | Service | Port | Purpose | Default Provider |
|-------|---------|------|---------|-----------------|
| **Claude** | `service-claude` | 3400 | Agentic coding with strong reasoning | Anthropic (claude-sonnet-4) |
| **Kilo** | `service-kilo` | — | Future coding agent (placeholder) | TBD |

Previous agents (Aider, OpenHands, Junie) have been removed.

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

### Cross-Platform Architecture

```
expect class PlatformNotificationManager
├── jvmMain:    macOS osascript, Windows SystemTray, Linux notify-send
├── androidMain: NotificationCompat + BroadcastReceiver + action buttons
└── iosMain:    UNUserNotificationCenter + UNNotificationAction

expect object PushTokenRegistrar
├── androidMain: FCM token → registerToken(platform="android")
├── iosMain:    IosTokenHolder.apnsToken → registerToken(platform="ios")
└── jvmMain:    no-op (desktop uses kRPC streams)

NotificationActionChannel (MutableSharedFlow)
├── Android: NotificationActionReceiver → emits
├── iOS:     NotificationDelegate.swift → NotificationBridge.kt → emits
└── NotificationViewModel: collects → approveTask/denyTask/replyToTask
```

### Key Files

| File | Purpose |
|------|---------|
| `shared/common-dto/.../events/JervisEvent.kt` | Event model with approval metadata |
| `shared/ui-common/.../notification/PlatformNotificationManager.kt` | expect class |
| `shared/ui-common/.../notification/NotificationActionChannel.kt` | Cross-platform action callback |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | In-app approve/deny dialog |
| `backend/server/.../infrastructure/notification/FcmPushService.kt` | Firebase Cloud Messaging sender (Android) |
| `backend/server/.../infrastructure/notification/ApnsPushService.kt` | APNs HTTP/2 push sender (iOS, Pushy) |
| `backend/server/.../preferences/DeviceTokenDocument.kt` | Device token storage (platform: android/ios) |
| `shared/common-api/.../IDeviceTokenService.kt` | Token registration RPC |
| `shared/ui-common/.../notification/IosTokenHolder.kt` | APNs token holder (Swift → Kotlin bridge) |
| `shared/ui-common/.../notification/PushTokenRegistrar.kt` | expect/actual token registration |

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

Multi-tenant Microsoft 365 integration via browser-session relay. Two services:

1. **`jervis-o365-gateway`** (Kotlin/Ktor) — Stateless API. Receives tool calls from MCP/orchestrator, fetches Bearer tokens from browser pool, calls Microsoft Graph API.
2. **`jervis-o365-browser-pool`** (Python/FastAPI/Playwright) — Stateful service. Manages one Playwright Chromium context per client. Intercepts network requests to extract Bearer tokens from live O365 web sessions.

### Architecture Flow

```
MCP Tool Call → O365 Gateway → Token Service (cache → browser pool) → Graph API
                                     ↓
                              Browser Pool (Playwright)
                              ├── Client A context (persistent profile)
                              ├── Client B context
                              └── Client C context
```

### Key Design Decisions

- **Raw HTTP over Graph SDK**: Uses Ktor HTTP client for Graph API calls instead of the heavy Graph SDK. Simpler, fewer dependencies.
- **Browser-based token relay**: For tenants that don't allow OAuth app registration in Azure AD. Playwright intercepts `Authorization: Bearer` headers from `graph.microsoft.com` requests.
- **Per-client rate limiting**: 4 req/s safety margin under Graph API's 5 req/s Teams limit.
- **Persistent browser profiles**: Cookies and local storage persisted to PVC, surviving pod restarts.

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
- Browser Pool: `StatefulSet` with PVC (stateful, 1-4Gi, ~500MB per browser context)

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
| Slack | `SlackPollingHandler` | Slack Web API (`conversations.list`, `conversations.history`) | Bot Token (`xoxb-...`) via BEARER | `channelId` |
| Discord | `DiscordPollingHandler` | Discord REST API v10 (`/guilds`, `/channels`, `/messages`) | Bot Token via BEARER (`Bot` prefix) | `guildId/channelId` |

### Continuous Indexers

| Platform | Indexer | Task Type | Topic ID Format |
|----------|---------|-----------|-----------------|
| Teams | `TeamsContinuousIndexer` | `CHAT_PROCESSING` | `teams-channel:{teamId}/{channelId}` or `teams-chat:{chatId}` |
| Slack | `SlackContinuousIndexer` | `SLACK_PROCESSING` | `slack-channel:{channelId}` |
| Discord | `DiscordContinuousIndexer` | `DISCORD_PROCESSING` | `discord-channel:{guildId}/{channelId}` |

### Resource Routing (same hierarchy as email/git)

- **Client has "all"** → everything indexes to client level
- **Project claims specific channels** → those channels index to project, rest to client
- **Chats (Teams 1:1/group)** → always client level (not channel-specific)

### SourceUrn Factories

- `SourceUrn.teams(connectionId, messageId, channelId?, chatId?)`
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
