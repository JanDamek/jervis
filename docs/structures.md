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
