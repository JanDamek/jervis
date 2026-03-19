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
| **Diarization** | pyannote-audio 4.x (requires HF_TOKEN), returns 256-dim speaker embeddings |
| **Idle timeout** | 60s → auto-unloads GPU |
| **Startup** | Lazy — no model pre-loaded |

Key files:

| File | Purpose |
|------|---------|
| `backend/service-whisper/whisper_rest_server.py` | REST server — GPU coordination, lazy-load, auto-unload |
| `backend/service-whisper/whisper_runner.py` | Whisper transcription engine (range extraction, segment mapping) |
| `k8s/deploy_whisper_gpu.sh` | SSH deployment to GPU VM (systemd service) |

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
| `backend/server/.../entity/meeting/MeetingDocument.kt` | Meeting entity with `qualified` flag |

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
| `backend/server/.../service/meeting/WhisperJobRunner.kt` | Parses `speaker_embeddings` from whisper result |
| `backend/server/.../service/meeting/MeetingTranscriptionService.kt` | Auto-matching after transcription (cosine similarity) |
| `backend/server/.../entity/meeting/MeetingDocument.kt` | Stores `speakerEmbeddings` per meeting |
| `backend/server/.../entity/SpeakerDocument.kt` | Stores `voiceEmbedding` per speaker profile |
| `backend/server/.../rpc/MeetingRpcImpl.kt` | Builds `autoSpeakerMapping` for UI |
| `backend/server/.../rpc/SpeakerRpcImpl.kt` | `setVoiceEmbedding` endpoint |
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

**TaskTypeEnum values** (each carries a `sourceKey` used as wire-format identifier for KB):

| Enum value | `sourceKey` | Description |
|------------|-------------|-------------|
| `EMAIL_PROCESSING` | `email` | Email content from IMAP/POP3 (thread-consolidated via topicId) |
| `WIKI_PROCESSING` | `confluence` | Confluence pages |
| `BUGTRACKER_PROCESSING` | `jira` | Jira/GitHub/GitLab issues |
| `GIT_PROCESSING` | `git` | Git commits, diffs, and merge request code reviews |
| `USER_INPUT_PROCESSING` | `chat` | User chat messages |
| `USER_TASK` | `user_task` | User-created tasks |
| `SCHEDULED_TASK` | `scheduled` | Cron-scheduled tasks |
| `LINK_PROCESSING` | `link` | URLs extracted from other documents |
| `MEETING_PROCESSING` | `meeting` | Transcribed meeting recordings |
| `CHAT_PROCESSING` | `teams` | Microsoft Teams messages (via O365 Gateway) |
| `SLACK_PROCESSING` | `slack` | Slack channel messages (via Web API) |
| `DISCORD_PROCESSING` | `discord` | Discord guild/channel messages (via REST API) |
| `IDLE_REVIEW` | `idle_review` | System-generated proactive review task |

The `sourceKey` is sent to KB via multipart form field `sourceType` and stored in graph metadata.
Python KB service uses matching `SourceType(str, Enum)` in `app/api/models.py`.

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
  │    └─ deadline >= scheduleLeadDays away → create SCHEDULED_TASK copy
  │         scheduledAt = deadline - scheduleLeadDays
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
6. **Scalability:** CPU indexing can run in parallel on multiple tasks

---

## Background Engine & Task Processing

### Indexing Loop (CPU) — Fire-and-Forget Dispatch

- **Interval:** 30 seconds
- **Process:** Reads INDEXING tasks from MongoDB, ordered by `queuePosition ASC NULLS LAST, createdAt ASC`
- **Dispatch:** `TaskQualificationService` dispatches to KB microservice (fire-and-forget)
- **Concurrency:** 1 (dispatch is fast — text extraction + HTTP POST, not blocking on KB)
- **Dispatch flow:** `claimForIndexing()` (atomic claim via `indexingClaimedAt`, state stays INDEXING) → `TaskQualificationService.dispatch()` (text extraction, attachment loading, HTTP POST to `/ingest/full/async`) → returns immediately. Task stays in INDEXING until KB calls back.
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
- **Preconditions:** No active FG/BG tasks, no existing IDLE_REVIEW task
- **ProcessingMode:** `IDLE` (lowest priority — preempted by both FOREGROUND and BACKGROUND)
- **Creates:** At most ONE `IDLE_REVIEW` task at a time with `ProcessingMode.IDLE`
- **Task selection:** `IdleTaskRegistry` returns highest-priority due check (priority-ordered, interval-based)
- **Lifecycle:** Task created → QUEUED → executed → DONE (deleted) → next iteration picks next due check
- **Task type:** `TaskTypeEnum.IDLE_REVIEW`
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
2. **Task creation (15s):** Pick up NEW MRs → create SCHEDULED_TASK in QUEUED state with MR metadata
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
- `backend/server/.../service/indexing/git/MergeRequestContinuousIndexer.kt` (polling + task creation — Path B)
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
| IntentDecomposer | 9-S3 | REMOVED — replaced by `agent/chat_router.py` |
| SourceAttribution | 14-S2 | `backend/service-orchestrator/app/chat/source_attribution.py` |
| ApprovalQueueDocument | 4-S3 | `backend/server/.../entity/ApprovalQueueDocument.kt` |
| ApprovalStatisticsDocument | 4-S5 | `backend/server/.../entity/ApprovalStatisticsDocument.kt` |
| TeamsContinuousIndexer | 11-S4 | `backend/server/.../service/teams/TeamsContinuousIndexer.kt` |
| SlackContinuousIndexer | 11-S4 | `backend/server/.../service/slack/SlackContinuousIndexer.kt` |
| DiscordContinuousIndexer | 11-S4 | `backend/server/.../service/discord/DiscordContinuousIndexer.kt` |
| O365PollingHandler | 11-S3 | `backend/server/.../service/polling/handler/teams/O365PollingHandler.kt` |
| SlackPollingHandler | 11-S2 | `backend/server/.../service/polling/handler/slack/SlackPollingHandler.kt` |
| DiscordPollingHandler | 11-S2 | `backend/server/.../service/polling/handler/discord/DiscordPollingHandler.kt` |
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
