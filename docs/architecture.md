# Architecture - Complete System Overview

**Status:** Production Documentation (2026-02-25)
**Purpose:** Comprehensive architecture guide for all major components and frameworks

---

## Table of Contents

1. [Framework Overview](#framework-overview)
2. [Workspace & Directory Architecture](#workspace--directory-architecture)
3. [GPU/CPU Routing & Ollama Router](#gpucpu-routing--ollama-router)
4. [Kotlin RPC (kRPC) Architecture](#kotlin-rpc-krpc-architecture)
5. [Polling & Indexing Pipeline](#polling--indexing-pipeline)
6. [Knowledge Graph Design](#knowledge-graph-design)
7. [Vision Processing Pipeline](#vision-processing-pipeline)
8. [Transcript Correction Pipeline](#transcript-correction-pipeline)
9. [Smart Model Selector](#smart-model-selector)
10. [Security Architecture](#security-architecture)
11. [Resilience Patterns](#resilience-patterns)
12. [Coding Agents](#coding-agents)
13. [Python Orchestrator](#python-orchestrator)
14. [Dual-Queue System & Inline Message Delivery](#dual-queue-system--inline-message-delivery)
15. [Notification System](#notification-system)
16. [Foreground Chat (ChatSession)](#foreground-chat-chatsession)
17. [Guidelines Engine](#guidelines-engine)
18. [Intent Router](#intent-router-feature-flagged)
19. [Hierarchical Task System](#hierarchical-task-system)
20. [Unified Chat Stream](#unified-chat-stream)

---

## Framework Overview

The Jervis system is built on several key architectural patterns:

- **Python Orchestrator (LangGraph)**: Agent runtime for coding workflows and complex task execution
- **SimpleQualifierAgent**: CPU-based indexing agent calling KB microservice directly, with optional LLM qualification for complex items
- **Kotlin RPC (kRPC)**: Type-safe, cross-platform messaging framework for client-server communication
- **3-Stage Polling Pipeline**: Polling → Indexing → Pending Tasks → Qualifier Agent
- **Knowledge Graph (ArangoDB)**: Centralized structured relationships between all entities
- **Vision Processing**: Two-stage vision analysis for document understanding
- **Multi-Agent Delegation**: 19 specialist agents orchestrated via delegation DAG (feature-flagged)

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
   - For each project with REPOSITORY resources:
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

## GPU/CPU Routing & Ollama Router

### Overview

**Ollama Router** is a transparent proxy service that intelligently routes LLM requests between GPU and CPU backends based on priority, resource availability, and model requirements.

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
                │ • GPU/CPU selection   │
                │ • Model loading       │
                │ • Request queuing     │
                └───────────┬───────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
              ▼             ▼             ▼
    ┌─────────────────┐ ┌──────────────┐ ┌─────────────────┐
    │ GPU_BACKENDS[0] │ │GPU_BACKENDS[1]│ │ CPU_BACKEND_URL │
    │                 │ │              │ │                 │
    │  • P40 24GB     │ │ • P40 24GB   │ │  • 200GB RAM    │
    │  • Fast         │ │ • Fast       │ │  • Unlimited    │
    │  • Limited VRAM │ │ • LAN link   │ │  • Slow         │
    └─────────────────┘ └──────────────┘ └─────────────────┘
```

### Priority Levels (2 levels)

| Priority | Value | Header | Source | Behavior |
|----------|-------|--------|--------|----------|
| CRITICAL | 0 | `X-Ollama-Priority: 0` | Orchestrator FOREGROUND, jervis_mcp | Always GPU, auto-reserves, preempts NORMAL |
| NORMAL | 1 | No header (default) | Correction, KB ingest, background tasks | GPU when free, CPU fallback |

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

# NORMAL: no header → GPU if free, else CPU
headers = {}  # BACKGROUND tasks, correction, KB ingest
```

### Configuration

All services use Ollama Router (K8s service `jervis-ollama-router:11430`):

- **Orchestrator**: `OLLAMA_API_BASE=http://jervis-ollama-router:11430`
- **KB (read/write)**: `OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_BASE_URL`, `OLLAMA_INGEST_BASE_URL` all → `http://jervis-ollama-router:11430`
- **Correction**: `OLLAMA_BASE_URL=http://jervis-ollama-router:11430`

### Key Features

- **Transparent proxy** - Services call router like standard Ollama
- **2-level priority** - CRITICAL gets guaranteed GPU, NORMAL falls back to CPU
- **Auto-reservation** - GPU reserved/released automatically based on CRITICAL activity
- **Model management** - Auto-loads/unloads model sets (orchestrator ↔ background)
- **Embedding coexistence** - CRITICAL embeddings load alongside :30b on GPU (both fit in VRAM)

### Deployment

- K8s deployment: `k8s/app_ollama_router.yaml`
- ConfigMap: `k8s/configmap.yaml` (jervis-ollama-router-config)
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

**Problem**: Apache Tika is blind - extracts text, but doesn't see **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

**Solution**: Integration of **Qwen2.5-VL** (vision model) into Qualifier Agent as **LLM node**, not as Tool.

### Vision Architecture

### Vision Integration

- **Vision as a pipeline stage**: Separate processing step in indexing pipeline
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

---

## Whisper Transcription Pipeline

### Overview

Audio recordings are transcribed using **faster-whisper** (CTranslate2-optimized OpenAI Whisper).
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
| `backend/service-whisper/whisper_rest_server.py` | FastAPI REST wrapper around whisper_runner (REST_REMOTE mode) |
| `backend/service-whisper/entrypoint-whisper-job.sh` | K8s Job entrypoint — env parsing, error handling |
| `backend/service-whisper/Dockerfile` | Docker image for K8s Job mode |
| `backend/service-whisper/Dockerfile.rest` | Docker image for REST server mode (FastAPI + uvicorn) |
| `backend/server/.../service/meeting/WhisperJobRunner.kt` | Orchestration — routes to K8s Job, REST, or local subprocess |
| `backend/server/.../service/meeting/WhisperRestClient.kt` | Ktor HTTP client for REST_REMOTE mode |
| `backend/server/.../service/meeting/MeetingTranscriptionService.kt` | High-level transcription API |
| `backend/server/.../service/meeting/MeetingContinuousIndexer.kt` | 4 pipelines: transcribe → correct → index → purge |
| `backend/server/.../entity/WhisperSettingsDocument.kt` | MongoDB singleton settings document |
| `backend/server/.../rpc/WhisperSettingsRpcImpl.kt` | RPC service for settings CRUD |
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
| `backend/server/.../service/meeting/TranscriptCorrectionService.kt` | Kotlin delegation to Python orchestrator, question handling, retranscribe+correct flow |
| `backend/server/.../service/meeting/WhisperJobRunner.kt` | Whisper orchestration (K8s Job / REST / local) — includes `retranscribe()` for audio extraction + high-accuracy re-transcription |
| `backend/service-whisper/whisper_runner.py` | Whisper transcription script — supports `extraction_ranges` for partial re-transcription |
| `backend/server/.../configuration/PythonOrchestratorClient.kt` | REST client for Python correction endpoints incl. `correctTargeted()` |
| `shared/common-api/.../service/ITranscriptCorrectionService.kt` | RPC interface for correction CRUD |
| `shared/common-dto/.../dto/meeting/MeetingDtos.kt` | `MeetingStateEnum` (incl. CORRECTION_REVIEW), `CorrectionQuestionDto`, `CorrectionAnswerDto` |
| `shared/ui-common/.../meeting/CorrectionsScreen.kt` | Corrections management UI |
| `shared/ui-common/.../meeting/CorrectionViewModel.kt` | Corrections UI state management |
| `backend/server/.../service/meeting/MeetingContinuousIndexer.kt` | Pipeline 5 stuck detection via `stateChangedAt` timestamp (STUCK_CORRECTING_THRESHOLD_MINUTES = 15) |

---

## GPU Model Routing

### Overview

GPU routing uses **capability-based routing** with the Ollama Router as the central routing service. The orchestrator/chat declares a **capability** (thinking, coding, chat, embedding, visual) and the router decides local GPU vs cloud. Fixed `num_ctx` per GPU prevents costly model reloads.

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

| GPU | Models | num_ctx | Role |
|-----|--------|---------|------|
| GPU1 (P40) | `qwen3-coder-tool:30b` | 48,000 | Primary — stable, never swaps |
| GPU2 (P40) | `qwen3-coder-tool:30b` + `qwen3-embedding:8b` | 32,000 | Secondary + embedding |

- **Embedding → GPU2 only** — p40-1 doesn't have embedding model
- **VLM → GPU2 only** — on-demand, temporarily replaces coder model
- **1 concurrent request per GPU** — serial is faster than parallel when VRAM spills
- **No CPU Ollama** — all LLM/embedding on GPU only
- Default for new clients: `maxOpenRouterTier = FREE`

### Capability Model Catalog

```python
LOCAL_MODEL_CAPABILITIES = {
    "qwen3-coder-tool:30b": ["thinking", "coding", "chat"],
    "qwen3-embedding:8b": ["embedding"],
    "qwen3-vl:latest": ["visual"],
}
```

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
| VRAM usage (model only) | 19GB / 24GB | |

**Critical rules:**
- **NEVER change num_ctx between requests** — causes 2-5× slowdown (Ollama restarts runner)
- **NEVER unload models** — especially on p40-2 where reload takes >200s
- **Fixed num_ctx per GPU**: p40-1=48k, p40-2=32k (embedding coexists)
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
- **backend/service-***: Ktor microservices (github, gitlab, atlassian, joern, tika, whisper, aider, coding-engine, junie, claude)
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
- `OfflineMeetingStorage` (expect/actual, 3 platforms): persists offline meeting metadata
- `OfflineMeetingSyncService` (`shared/ui-common/.../meeting/OfflineMeetingSyncService.kt`): auto-syncs offline meetings when connection restored

**Behavior:**
- `JervisApp.kt` creates repository eagerly (lambda-based, not blocking on connect)
- Desktop `ConnectionManager.repository` is non-nullable val
- No blocking overlay on disconnect — replaced by "Offline" chip in `PersistentTopBar`
- `ConnectionViewModel.isOffline: StateFlow<Boolean>` derives from connection state
- Chat input disabled when offline; meeting recording works offline (chunks saved to disk)
- `OfflineMeetingSyncService` watches connection state and uploads offline meetings on reconnect

### Ad-hoc Recording (Quick Record from Top Bar)

One-tap recording from `PersistentTopBar` — no dialog, no client/project selection.

**Key changes:**
- `MeetingDocument.clientId` is nullable (`ClientId?`) — null means unclassified
- `MeetingDto.clientId` and `MeetingCreateDto.clientId` are nullable (`String?`)
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

---

## Python Orchestrator

> Authoritative spec: [orchestrator-final-spec.md](orchestrator-final-spec.md).

### Overview

The Python Orchestrator (`backend/service-orchestrator/`) is a FastAPI service using LangGraph
that centrally manages coding workflows. It runs as a separate K8s Deployment and communicates
with the Kotlin server via REST.

**Key principle**: Orchestrator = brain, coding agents = hands. The orchestrator decides WHAT
to do and WHEN; agents just execute.

### Architecture (KB-First, Push-Based Communication)

```
Kotlin Server (BackgroundEngine)
    │
    ├── POST /orchestrate/stream ──► Python Orchestrator (LangGraph)
    │   (fire-and-forget,               │
    │    returns thread_id)              ├── intake → evidence → route
    │                                    │   ├── ADVICE → respond → finalize
    │                                    │   ├── SINGLE_TASK → plan → execute loop → finalize
    │                                    │   ├── EPIC → plan_epic → execution waves
    │                                    │   └── GENERATIVE → design → execution
    │                                    │
    │   ◄── POST /internal/             │   Python pushes progress on each node:
    │       orchestrator-progress ──────│   - node name, message, goal/step indices
    │   (real-time node progress)        │   - updates stateChangedAt for stuck detection
    │                                    │
    │   ◄── POST /internal/             │   Python pushes status on completion:
    │       orchestrator-status ────────│   - done/error/interrupted + details
    │   (completion/error/interrupt)      │
    │                                    └── interrupt() for approval (commit/push/epic plan)
    ├── POST /approve/{thread_id} ──► resume from checkpoint
    │   (after USER_TASK response)
    │
    ├── GET /status/{thread_id} ◄─── safety-net polling (60s, NOT primary)
    │
    ├── OrchestratorStatusHandler       (task state transitions)
    └── TaskDocument (MongoDB) = SSOT for lifecycle state + stuck detection timestamps
```

**4 task categories** with intelligent routing:
- **ADVICE**: Direct LLM + KB answer (no coding, no K8s Jobs)
- **SINGLE_TASK**: May or may not code — step types: respond, code, tracker_ops, mixed
- **EPIC**: Batch execution in waves from tracker issues
- **GENERATIVE**: Design full structure from high-level goal, then execute

**Communication model**: Push-based (Python → Kotlin) with 60s safety-net polling.
- **Primary**: Python pushes `orchestrator-progress` on each node transition and `orchestrator-status` on completion
- **Safety net**: BackgroundEngine polls every 60s to catch missed callbacks (network failure, process restart)
- **Stuck detection**: Timestamp-based — uses `orchestrationStartedAt` / `stateChangedAt` fields from DB; 15 min without progress = stuck (STUCK_THRESHOLD_MINUTES = 15)
- **UI**: Kotlin broadcasts events via Flow-based subscriptions (no UI polling)
- **task_id convention**: `task_id` sent to Python in `OrchestrateRequestDto` is `task.id.toString()` (MongoDB document `_id`). Python sends this same `task_id` back in all callbacks. `OrchestratorStatusHandler` resolves it via `taskRepository.findById(TaskId(ObjectId(taskId)))`. The `correlationId` field on `TaskDocument` is a separate identifier used for idempotency/deduplication, NOT sent to Python.

**JERVIS Internal Project**: Each client has max 1 `isJervisInternal=true` project. Auto-created on first orchestration for tracker/wiki operations.

### State Persistence

- **TaskDocument** (Kotlin/MongoDB): SSOT for task lifecycle, `orchestratorThreadId`, USER_TASK state
- **LangGraph checkpoints** (Python/MongoDB): Graph execution state, auto-saved after every node
- **Checkpointer**: `AsyncMongoDBSaver` from `langgraph-checkpoint-mongodb` (same MongoDB instance)
- Thread ID is the link between TaskDocument and LangGraph checkpoint

### Chat Context Persistence

Agent memory across conversations. Two paths:

**Foreground chat:** Python `ChatContextAssembler` reads MongoDB directly (motor) to build LLM context.
Messages keyed by `conversationId` (= `ChatSessionDocument._id`).

**Background orchestration:** Kotlin prepares context payload and sends with dispatch request.

**Three layers:**
1. **Recent messages** (verbatim): Last 20 `ChatMessageDocument` records
2. **Rolling summaries** (compressed): `ChatSummaryDocument` collection — LLM-compressed blocks of 20 messages each
3. **Search** (Phase 2): MongoDB full-text search for on-demand old context retrieval

**Data flow (background orchestration):**
```
AgentOrchestratorService.dispatchToPythonOrchestrator()
    → ChatHistoryService.prepareChatHistoryPayload(conversationId)
    → OrchestrateRequestDto.chat_history = { recent_messages, summary_blocks, total_message_count }
    ↓
Python orchestrator uses chat_history in nodes:
    - intake.py: last 5 messages for classification context
    - respond.py: full conversation context in LLM prompt
    - evidence.py: populates EvidencePack.chat_history_summary
    ↓
After orchestration completes (handleDone()):
    → async: ChatHistoryService.compressIfNeeded(conversationId)
    → if >20 unsummarized messages → POST /internal/compress-chat (Python LLM)
    → Store ChatSummaryDocument in MongoDB
```

**Data flow (foreground chat):**
```
User sends message → Kotlin ChatService saves to chat_messages (conversationId = session._id)
    → PythonChatClient POST /chat with session_id
    → Python ChatContextAssembler reads chat_messages + chat_summaries from MongoDB (motor)
    → Agentic loop with full context
    → Fire-and-forget compression after response
```

**Token budget:** ~4000 tokens total (2000 recent + 1500 summaries + 500 decisions)

**MongoDB collections:**
- `chat_messages` — individual messages (`conversationId` field, was `taskId`)
- `chat_summaries` — compressed summary blocks (`conversationId` field, was `taskId`)
- `chat_sessions` — session lifecycle (new, one active per user)

### Task State Machine (Python orchestrator path)

```
QUEUED → PROCESSING → done → DONE / DELETE
                    │                → error → ERROR
                    └── interrupted → USER_TASK → user responds → QUEUED (loop)
```

### Approval Flow (USER_TASK)

1. LangGraph hits `interrupt()` at `git_operations` node (commit/push approval)
2. Checkpoint saved to MongoDB automatically
3. Python pushes `orchestrator-status` with `status=interrupted` to Kotlin
4. `OrchestratorStatusHandler` creates USER_TASK with notification
5. UI receives `OrchestratorTaskStatusChange` event via Flow subscription
6. User responds via UI → `UserTaskRpcImpl.sendToAgent()` → state = QUEUED
7. BackgroundEngine picks up task → `resumePythonOrchestrator()` → POST /approve/{thread_id}
8. LangGraph resumes from MongoDB checkpoint → continues from interrupt point

### Concurrency Control

Only **one orchestration at a time** (LLM cannot handle concurrent requests efficiently).

Two layers:
1. **Kotlin** (early guard): `countByState(PROCESSING) > 0` → skip dispatch
2. **Python**: No artificial concurrency limits — router manages GPU queue

`/approve/{thread_id}` is fire-and-forget: returns immediately, Python resumes graph in background.

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/main.py` | FastAPI endpoints, concurrency, MongoDB lifecycle, crash handler (atexit + SIGTERM) |
| `backend/service-orchestrator/app/graph/orchestrator.py` | LangGraph StateGraph, 4-category routing, checkpointing |
| `backend/service-orchestrator/app/graph/nodes/` | Modular nodes: intake, evidence, respond, plan, execute, evaluate, git_ops, finalize, coding, epic, design |
| `backend/service-orchestrator/app/context/context_store.py` | MongoDB hierarchical context store (orchestrator_context) |
| `backend/service-orchestrator/app/context/context_assembler.py` | Per-node LLM context assembly (step/goal/epic levels) |
| `backend/service-orchestrator/app/config.py` | Configuration (MongoDB URL, K8s, LLM providers) |
| `backend/server/.../AgentOrchestratorService.kt` | Dispatch + resume logic, JERVIS project resolution, concurrency guard |
| `backend/server/.../BackgroundEngine.kt` | Safety-net polling (60s), timestamp-based stuck detection (STUCK_THRESHOLD_MINUTES = 15) |
| `backend/server/.../OrchestratorStatusHandler.kt` | Task state transitions (push-based from Python callbacks) |
| `backend/server/.../PythonOrchestratorClient.kt` | REST client for Python orchestrator (429 handling) |
| `backend/service-orchestrator/app/tools/kotlin_client.py` | Push client: progress + status callbacks to Kotlin |
| `backend/service-orchestrator/app/llm/provider.py` | LLM provider with streaming + token-arrival liveness |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | Workspace preparation (instructions, KB, environment context) |
| `backend/service-orchestrator/app/agents/base.py` | BaseAgent abstract class, communication protocol, agentic loop |
| `backend/service-orchestrator/app/agents/registry.py` | AgentRegistry singleton — agent discovery and capability listing |
| `backend/service-orchestrator/app/agents/specialists/` | 19 specialist agents (code, git, review, test, research, tracker, wiki, docs, devops, PM, security, communication, email, calendar, admin, legal, financial, personal, learning) |
| `backend/service-orchestrator/app/graph/nodes/plan_delegations.py` | LLM-driven agent selection and ExecutionPlan construction |
| `backend/service-orchestrator/app/graph/nodes/execute_delegation.py` | Delegation dispatch and DAG parallel execution |
| `backend/service-orchestrator/app/graph/nodes/synthesize.py` | Result merging, RAG cross-check, translation |
| `backend/service-orchestrator/app/graph/dag_executor.py` | DAG executor for parallel delegation groups |
| `backend/service-orchestrator/app/context/session_memory.py` | MongoDB session memory (7-day TTL, per-client/project) |
| `backend/service-orchestrator/app/context/procedural_memory.py` | ArangoDB procedural memory (learned workflows) |
| `backend/service-orchestrator/app/monitoring/delegation_metrics.py` | Per-agent delegation metrics collection |

### Multi-Agent Delegation System (New)

The orchestrator now supports a second execution path: the **7-node delegation graph** for multi-agent orchestration. This is controlled by the `use_delegation_graph` feature flag (default: `False`).

**Delegation graph (7 nodes):**
```
intake → evidence_pack → plan_delegations → execute_delegation(s) → synthesize → finalize → END
```

**Key concepts:**
- **plan_delegations**: LLM selects agents from AgentRegistry and builds an ExecutionPlan (DAG of delegations)
- **execute_delegation**: Dispatches DelegationMessage to agents, supports parallel execution via DAG executor
- **synthesize**: Merges AgentOutput results, performs RAG cross-check, translates to response language

**19 Specialist Agents** across 4 tiers:

| Tier | Agents | Purpose |
|------|--------|---------|
| **Tier 1 — Core** | CodingAgent, GitAgent, CodeReviewAgent, TestAgent, ResearchAgent | Code, git, review, testing, KB/web research |
| **Tier 2 — DevOps & PM** | IssueTrackerAgent, WikiAgent, DocumentationAgent, DevOpsAgent, ProjectManagementAgent, SecurityAgent | Issue tracking, wiki, docs, CI/CD, project management, security |
| **Tier 3 — Communication** | CommunicationAgent, EmailAgent, CalendarAgent, AdministrativeAgent | Communication hub, email, calendar, admin |
| **Tier 4 — Business** | LegalAgent, FinancialAgent, PersonalAgent, LearningAgent | Legal, financial, personal assistance, learning |

**Agent Communication Protocol:**
Agents respond in a structured compact format (STATUS/RESULT/ARTIFACTS/ISSUES/CONFIDENCE/NEEDS_VERIFICATION). No hard truncation of agent outputs — agents are instructed to be maximally compact but include ALL substantive content.

**Memory Layers:**

| Layer | Storage | TTL | Purpose |
|-------|---------|-----|---------|
| Working Memory | LangGraph state | Per-orchestration | Current delegation stack, intermediate results |
| Session Memory | MongoDB `session_memory` | 7 days | Per-client/project recent decisions |
| Semantic Memory | KB (Weaviate + ArangoDB) | Permanent | Facts, conventions, decisions |
| Procedural Memory | ArangoDB `ProcedureNode` | Permanent (usage-decay) | Learned workflow procedures |

**Delegation protocol:**
- Max depth: 4 (agents can sub-delegate recursively)
- Cycle detection prevents infinite delegation loops
- Token budgets per depth: 48k → 16k → 8k → 4k
- Internal chain runs in English, final response translated to detected input language

**Feature flags (all default False):**
- `use_delegation_graph` — New 7-node graph vs legacy 14-node graph
- `use_specialist_agents` — Specialist agents vs LegacyAgent fallback
- `use_dag_execution` — Parallel DAG delegation execution
- `use_procedural_memory` — Learning from successful orchestrations

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
| `backend/server/.../entity/EnvironmentDocument.kt` | MongoDB document + embedded types |
| `backend/server/.../service/environment/EnvironmentService.kt` | CRUD + resolve inheritance |
| `backend/server/.../service/environment/EnvironmentK8sService.kt` | K8s namespace provisioning |
| `backend/server/.../service/environment/ComponentDefaults.kt` | Default Docker images per type |
| `backend/server/.../mapper/EnvironmentMapper.kt` | Document ↔ DTO + toAgentContextJson() |
| `shared/common-dto/.../dto/environment/EnvironmentDtos.kt` | Cross-platform DTOs |

### Environment MCP Integration (Runtime K8s Access for Agents)

Coding agents (Claude, OpenHands, Junie) can inspect and manage the K8s environment
associated with their project via the unified `jervis-mcp` HTTP server (port 8100).

**Architecture:**

```
Agent (Claude Code)
  └─ MCP HTTP: jervis-mcp:8100/mcp (FastMCP, Bearer token auth)
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
- ClusterRole `jervis-server-environment-role` grants cross-namespace K8s access
- MCP server authenticated via Bearer token (`MCP_API_TOKENS`)

**Workspace Integration:**
- `workspace_manager.py` writes `.claude/mcp.json` with HTTP MCP server URL
- `CLAUDE.md` includes tool descriptions, namespace hint, connection strings, credentials, how-to-run
- `.env` / `.env.{component}` files with resolved property mappings for `source .env` usage
- Namespace passed as tool parameter (not env var)

**Key Files:**

| File | Purpose |
|------|---------|
| `backend/service-mcp/app/main.py` | Unified HTTP MCP server (KB + env CRUD + resource inspection + mongo + orchestrator) |
| `backend/server/.../environment/EnvironmentResourceService.kt` | K8s resource inspection via fabric8 |
| `backend/server/.../environment/EnvironmentK8sService.kt` | Namespace/deployment/service lifecycle |
| `backend/server/.../rpc/internal/InternalEnvironmentRouting.kt` | Internal REST endpoints for environment CRUD |
| `backend/server/.../rpc/KtorRpcServer.kt` | Internal REST endpoints routing + resource inspection |
| `backend/service-orchestrator/app/tools/definitions.py` | Tool definitions (ENVIRONMENT_TOOLS, DEVOPS_AGENT_TOOLS) |
| `backend/service-orchestrator/app/tools/executor.py` | Tool execution (environment_* handlers) |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | MCP config injection |
| `k8s/orchestrator-rbac.yaml` | ClusterRole for cross-namespace access (jervis-environment-manager) |

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
```

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../entity/ProjectGroupDocument.kt` | MongoDB document |
| `backend/server/.../service/projectgroup/ProjectGroupService.kt` | CRUD |
| `backend/server/.../mapper/ProjectGroupMapper.kt` | Document ↔ DTO |
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
| `backend/server/.../service/notification/FcmPushService.kt` | Firebase Cloud Messaging sender (Android) |
| `backend/server/.../service/notification/ApnsPushService.kt` | APNs HTTP/2 push sender (iOS, Pushy) |
| `backend/server/.../entity/DeviceTokenDocument.kt` | Device token storage (platform: android/ios) |
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
| `build_image.sh` | Generic (Job-only images) | Docker only (no K8s Deployment) |

---

## LLM Qualification Agent

### Overview

After KB ingestion, complex actionable content goes through an LLM qualification step
before reaching the orchestrator. This replaces the removed Brain/Jira/Confluence integration.

**Purpose:**
- **Smart routing**: LLM agent searches KB for context, assesses urgency, decides routing
- **Noise reduction**: Filters out content that doesn't need full orchestration
- **Urgent alerts**: Pushes time-sensitive items directly to user's chat

### Flow

```
KB /internal/kb-done → KbResultRouter.routeTask()
  └─ needsQualification=true (Step 5: complex_actionable)
      → QUALIFYING state
      → Kotlin POST /qualify (fire-and-forget)
      → Python qualification_handler.py:
          1. kb_search — existing context
          2. Urgency/relevance analysis
          3. Decision: QUEUED | DONE | URGENT_ALERT
      → Python POST /internal/qualification-done (callback)
      → Kotlin updates task state based on decision
```

### Configuration

- **Tools:** CORE tier (kb_search, web_search, store_knowledge, memory_store/recall, get_kb_stats, get_indexed_items)
- **Max iterations:** 5
- **Fail-safe:** If `/qualify` unavailable → direct QUEUED (no data loss)
- **Chat context:** Kotlin provides recent chat messages — agent detects if incoming data relates to active conversation

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/unified/qualification_handler.py` | LLM qualification agent |
| `backend/server/.../qualifier/KbResultRouter.kt` | Routing decisions (needsQualification flag) |
| `backend/server/.../rpc/KtorRpcServer.kt` | `/internal/qualification-done` callback + `/internal/active-chat-topics` |
| `backend/server/.../configuration/PythonOrchestratorClient.kt` | `qualify()` HTTP client method |
| `backend/server/.../service/agent/coordinator/AgentOrchestratorService.kt` | `dispatchQualification()` |
| `shared/common-dto/.../dto/SystemConfigDto.kt` | System config DTO |
| `shared/common-api/.../service/ISystemConfigService.kt` | RPC interface |

---

## Foreground Chat (ChatSession)

### Overview

Foreground chat uses a **direct agentic loop** (LLM + tools) instead of the LangGraph StateGraph.
The user chats with Jervis like iMessage/WhatsApp — one global conversation (not per client/project).
Jervis acts as a personal assistant with access to tools (KB search, task creation, meeting classification, etc.).

**Key difference from background orchestration:**
- Background tasks still flow through LangGraph StateGraph (intake → evidence → plan → execute → finalize)
- Foreground chat is a simple agentic loop: LLM decides → tool call → result → repeat (max 15 iterations)

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

Centrální modul pro **LLM-based content reduction** — nahrazuje veškeré hard-coded `[:N]` truncation v memory a context modulech.

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

### Python Chat Tools

Available tools in the agentic loop, organized by tier:

**CORE tools** (available in all modes):

| Tool | Purpose |
|------|---------|
| `kb_search` | Search knowledge base (Weaviate RAG) |
| `kb_delete` | Delete KB items |
| `web_search` | Search the internet |
| `store_knowledge` | Store new knowledge into KB |
| `get_kb_stats` | KB statistics (document counts, types) |
| `get_indexed_items` | List indexed items for client/project |
| `memory_store` | Store fact/decision for later recall |
| `memory_recall` | Recall previously stored facts |
| `push_urgent_alert` | Push urgent alert to chat |
| `get_active_chat_topics` | Get recent chat topics for context |
| `select_tier` | Request different LLM tier for next call |

**EXTENDED tools** (CORE + background/research):

| Tool | Purpose |
|------|---------|
| `dispatch_coding_agent` | Dispatch coding agent |
| `create_background_task` | Create task for background processing |
| `search_tasks` | Search all tasks by text + optional state filter |
| `respond_to_user_task` | Respond to approval/clarification |
| `list_recent_tasks` | Recent tasks with time/state/client filters |
| `classify_meeting` | Classify ad-hoc meeting |
| `list_unclassified_meetings` | List unclassified meetings |
| `set_filter_rule` / `list_filter_rules` / `remove_filter_rule` | Manage content filter rules |

**FULL tools** (EXTENDED + chat-only):

| Tool | Purpose |
|------|---------|
| `switch_context` | Switch client/project context |
| `list_affairs` | List ongoing affairs/matters |
| Work plan tools | `create_work_plan`, etc. |
| Guidelines tools | Manage project guidelines |
| Environment tools | Environment CRUD |
| Action logging | Record actions taken |

**Chat-specific tools** (Kotlin internal API):

| Tool | Endpoint | Purpose |
|------|----------|---------|
| `create_background_task` | `POST /internal/create-background-task` | Create task for background processing |
| `dispatch_coding_agent` | `POST /internal/dispatch-coding-agent` | Dispatch coding agent |
| `search_tasks` | `GET /internal/tasks/search` | Search all tasks by text + optional state filter |
| `get_task_status` | `GET /internal/tasks/{id}/status` | Get task detail (state, content, error) |
| `list_recent_tasks` | `GET /internal/tasks/recent` | Recent tasks with time/state/client filters |
| `respond_to_user_task` | `POST /internal/respond-to-user-task` | Respond to approval/clarification |
| `classify_meeting` | `POST /internal/classify-meeting` | Classify ad-hoc meeting |
| `list_unclassified_meetings` | `GET /internal/unclassified-meetings` | List unclassified meetings |
| `list_affairs` | Direct Python | List ongoing affairs/matters |

### Internal API Routing (Ktor Modules)

Chat-specific Kotlin internal endpoints are organized as **Ktor routing modules** (not in KtorRpcServer directly — SOLID/SRP):

| Module | File | Endpoints |
|--------|------|-----------|
| `installInternalChatContextApi()` | `rpc/internal/InternalChatContextRouting.kt` | `/internal/clients-projects`, `/internal/pending-user-tasks/summary`, `/internal/unclassified-meetings/count` |
| `installInternalTaskApi()` | `rpc/internal/InternalTaskApiRouting.kt` | `/internal/tasks/{id}/status`, `/internal/tasks/search`, `/internal/tasks/recent` |

Installed in `KtorRpcServer` routing block via extension functions on `Routing`. Dependencies injected as function parameters (clientService, projectService, taskRepository, userTaskService).

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../service/chat/ChatService.kt` | Session management + message coordination |
| `backend/server/.../service/chat/PythonChatClient.kt` | Ktor HTTP SSE client for Python /chat + stopChat() |
| `backend/server/.../service/chat/ChatStreamEvent.kt` | SSE event data class |
| `backend/server/.../rpc/ChatRpcImpl.kt` | kRPC bridge: UI ↔ ChatService ↔ Python |
| `backend/server/.../rpc/internal/InternalChatContextRouting.kt` | Ktor routing: clients-projects, pending tasks, meetings count |
| `backend/server/.../rpc/internal/InternalTaskApiRouting.kt` | Ktor routing: task status, search, recent tasks |
| `shared/common-api/.../service/IChatService.kt` | kRPC interface (subscribeToChatEvents, sendMessage, getChatHistory, archiveSession). `getChatHistory` has `excludeBackground: Boolean = true` for filtering |
| `backend/server/.../entity/ChatSessionDocument.kt` | MongoDB session entity |
| `backend/server/.../repository/ChatSessionRepository.kt` | Spring Data repo |
| `backend/service-orchestrator/app/chat/handler.py` | Chat entry-point: context, intent, decompose, agentic loop |
| `backend/service-orchestrator/app/chat/handler_agentic.py` | Main agentic loop (LLM → tools → iterate) |
| `backend/service-orchestrator/app/chat/handler_decompose.py` | Long message processing: summarize, decompose, sub-topics |
| `backend/service-orchestrator/app/chat/handler_tools.py` | Tool execution, descriptions, switch_context resolution |
| `backend/service-orchestrator/app/chat/handler_streaming.py` | LLM calls, token streaming, message saving |
| `backend/service-orchestrator/app/chat/handler_context.py` | Runtime context loading, message building |
| `backend/service-orchestrator/app/chat/drift.py` | Multi-signal drift detection (shared by agentic + decompose) |
| `backend/service-orchestrator/app/chat/models.py` | ChatRequest, ChatStreamEvent models |
| `backend/service-orchestrator/app/chat/tools.py` | Chat tool definitions (26 tools) |
| `backend/service-orchestrator/app/chat/context.py` | ChatContextAssembler (MongoDB motor) |
| `backend/service-orchestrator/app/chat/system_prompt.py` | System prompt builder + RuntimeContext |
| `backend/service-orchestrator/app/chat/router.py` | Tool routing |
| `backend/service-orchestrator/app/tools/ollama_parsing.py` | Shared Ollama JSON tool-call parsing |
| `backend/service-orchestrator/app/tools/kotlin_client.py` | Python HTTP client for Kotlin internal API |
| `backend/service-orchestrator/app/main.py` | FastAPI endpoints incl. /chat, /chat/stop |

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

## Intent Router (feature-flagged)

**Status:** Implemented, disabled by default (`use_intent_router=False`)

Two-pass intent classification that routes chat messages to focused specialist agents with category-specific tools and prompts.

### Architecture

```
User message → classify_intent() (regex)
                     │
              ┌──────▼──────┐
              │ Pass 1:     │ Regex fast-path (0ms, ~60% of messages)
              │ CORE only?  │──► DIRECT (no tools, P40)
              │ Single hit? │──► Map directly to category
              └──────┬──────┘
                     │ Multiple regex hits (ambiguous)
              ┌──────▼──────┐
              │ Pass 2:     │ LLM call (LOCAL_FAST, ~2-3s)
              │ P40 classify│──► Category + confidence
              └──────┬──────┘
                     │
              ┌──────▼──────┐
              │ Route       │ Category-specific prompt + tools + cloud routing
              └─────────────┘
```

### Categories

| Category | Tools | Max Iterations | Routing |
|----------|-------|----------------|---------|
| DIRECT | 0 | 1 | P40 (LOCAL_FAST) |
| RESEARCH | 5 (kb_search, web_search, memory_recall, switch_context) | 3 | Cloud-first |
| TASK_MGMT | 11 (task lifecycle + meetings + KB) | 4 | Cloud-first |
| COMPLEX | 7 (work plans, coding, research) | 6 | Cloud-first |
| MEMORY | 6 (kb_search, kb_delete, memory_store, store_knowledge, memory_recall, code_search) | 3 | Cloud-first |

### Files

- `app/chat/intent_router.py` — Two-pass classification, `_CATEGORY_TOOL_NAMES` mapping
- `app/chat/prompts/` — Per-category focused prompts (core.py, direct.py, research.py, task_mgmt.py, complex.py, memory.py, builder.py)
- `app/chat/tools.py` — `select_tools_by_names()`, `_TOOL_BY_NAME` lookup
- `app/llm/openrouter_resolver.py` — `CHAT_CLOUD` queue (claude-sonnet-4 → gpt-4o → p40 fallback)

### Feature Flag

When `use_intent_router=False` (default), the original monolithic flow is used unchanged.
When `True`: handler.py calls `route_intent()` → builds routed prompt → selects focused tools → runs agentic loop with `max_iterations_override` and `use_case_override="chat_cloud"`.

---

## Hierarchical Task System

### Overview

Tasks can form parent-child hierarchies for work plan decomposition. The `create_work_plan` chat tool creates a root task (BLOCKED state) with child tasks organized in phases with dependency tracking.

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
**KbResultRouter** pushes URGENT_ALERT for urgent KB results.

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