# Architecture - Complete System Overview

**Status:** Production Documentation (2026-02-05)
**Purpose:** Comprehensive architecture guide for all major components and frameworks

---

## Table of Contents

1. [Framework Overview](#framework-overview)
2. [Kotlin RPC (kRPC) Architecture](#kotlin-rpc-krpc-architecture)
3. [Polling & Indexing Pipeline](#polling--indexing-pipeline)
4. [Knowledge Graph Design](#knowledge-graph-design)
5. [Vision Processing Pipeline](#vision-processing-pipeline)
6. [Transcript Correction Pipeline](#transcript-correction-pipeline)
7. [Smart Model Selector](#smart-model-selector)
8. [Security Architecture](#security-architecture)
9. [Coding Agents](#coding-agents)
10. [Python Orchestrator](#python-orchestrator)
11. [Dual-Queue System & Inline Message Delivery](#dual-queue-system--inline-message-delivery)
12. [Notification System](#notification-system)

---

## Framework Overview

The Jervis system is built on several key architectural patterns:

- **Python Orchestrator (LangGraph)**: Agent runtime for coding workflows and complex task execution
- **SimpleQualifierAgent**: CPU-based qualification agent calling KB microservice directly
- **Kotlin RPC (kRPC)**: Type-safe, cross-platform messaging framework for client-server communication
- **3-Stage Polling Pipeline**: Polling → Indexing → Pending Tasks → Qualifier Agent
- **Knowledge Graph (ArangoDB)**: Centralized structured relationships between all entities
- **Vision Processing**: Two-stage vision analysis for document understanding

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

## Vision Processing Pipeline

### Problem Statement

**Problem**: Apache Tika is blind - extracts text, but doesn't see **meaning** of screenshots, graphs, diagrams, and scanned PDFs.

**Solution**: Integration of **Qwen2.5-VL** (vision model) into Qualifier Agent as **LLM node**, not as Tool.

### Vision Architecture

### Vision Integration

- **Vision as a pipeline stage**: Separate processing step in qualification pipeline
- **Model selection**: Automatic selection of appropriate vision model
- **Context preservation**: Vision context preserved through all phases

---

## Whisper Transcription Pipeline

### Overview

Audio recordings are transcribed using **faster-whisper** (CTranslate2-optimized OpenAI Whisper) running
as K8s Jobs (in-cluster) or Python subprocess (local dev). Settings are stored in MongoDB (`whisper_settings`
collection, singleton document) and configurable via UI (**Settings → Whisper**).

### Configurable Parameters (UI)

| Parameter | Default | Description |
|-----------|---------|-------------|
| Model | `base` | tiny, base, small, medium, large-v3 |
| Task | `transcribe` | transcribe (keep language) or translate (to English) |
| Language | auto-detect | ISO 639-1 code (cs, en, de...) |
| Beam size | 5 | 1-10, higher = more accurate but slower |
| VAD filter | true | Silero VAD skips silence — significant speedup |
| Word timestamps | false | Per-word timing in segments |
| Initial prompt | null | Vocabulary hints (names, abbreviations) |
| Condition on previous | true | Use previous segment as context |
| No-speech threshold | 0.6 | Skip segments above this silence probability |
| Max parallel jobs | 3 | Concurrent K8s Whisper Jobs (Semaphore) |
| Timeout multiplier | 3 | Timeout = audio_duration × multiplier |
| Min timeout | 600s | Minimum job timeout |

### Progress Tracking

The Whisper container writes a progress file on PVC (`meeting_{id}_progress.json`) updated every 5 seconds:
```json
{"percent": 45.2, "segments_done": 128, "elapsed_seconds": 340, "updated_at": 1738000000.0}
```
The server-side `WhisperJobRunner` reads this file during K8s Job polling and logs detailed progress.

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-whisper/whisper_runner.py` | Python entry point — faster-whisper with progress tracking |
| `backend/service-whisper/entrypoint-whisper-job.sh` | K8s Job entrypoint — env parsing, error handling |
| `backend/server/.../service/meeting/WhisperJobRunner.kt` | K8s Job orchestration, polling, settings integration |
| `backend/server/.../service/meeting/MeetingTranscriptionService.kt` | High-level transcription API |
| `backend/server/.../service/meeting/MeetingContinuousIndexer.kt` | 4 pipelines: transcribe → correct → index → purge |
| `backend/server/.../entity/WhisperSettingsDocument.kt` | MongoDB singleton settings document |
| `backend/server/.../rpc/WhisperSettingsRpcImpl.kt` | RPC service for settings CRUD |
| `shared/common-api/.../service/IWhisperSettingsService.kt` | kRPC interface |
| `shared/common-dto/.../dto/whisper/WhisperSettingsDtos.kt` | Settings DTOs + enums |
| `shared/ui-common/.../settings/sections/WhisperSettings.kt` | Settings UI composable |

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
                                                                     │    ↑         │
                                                                     │    │         │
                                                              CORRECTION_REVIEW    │
                                                              (questions pending)   │
                                                                     │              │
                                                                     └──── FAILED ──┘
```

### Correction Flow

1. `MeetingContinuousIndexer` picks up TRANSCRIBED meetings
2. `TranscriptCorrectionService.correct()` sets state to CORRECTING
3. Delegates to Python orchestrator via `PythonOrchestratorClient.correctTranscript()`
4. Python `CorrectionAgent` loads per-client/project correction rules from KB (Weaviate)
5. Transcript segments chunked (20/chunk) and sent to Ollama GPU (`qwen3-coder-tool:30b`)
6. System prompt includes correction rules grouped by category + instructions for interactive questions
7. LLM returns corrections + optional questions when uncertain about proper nouns/terminology
8. If questions exist: state → CORRECTION_REVIEW (best-effort corrections + questions stored)
9. If no questions: state → CORRECTED
10. User answers questions in UI → answers saved as KB correction rules → state reset to TRANSCRIBED → re-run
11. Downstream indexing picks up CORRECTED meetings for KB ingestion

### Correction Rules Management

- **Storage**: KB (Weaviate) chunks with `kind="transcript_correction"`, per-client/project
- **RPC interface**: `ITranscriptCorrectionService` in `shared/common-api/` — `submitCorrection()`, `listCorrections()`, `deleteCorrection()`
- **Categories**: person_name, company_name, department, terminology, abbreviation, general
- **UI**: `CorrectionsScreen` composable accessible from MeetingDetailView (book icon)
- **Interactive**: `CorrectionQuestionsCard` in MeetingDetailView shows agent questions when state == CORRECTION_REVIEW

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/whisper/correction_agent.py` | Python correction agent — KB loading, LLM calls, interactive questions |
| `backend/server/.../service/meeting/TranscriptCorrectionService.kt` | Kotlin delegation to Python orchestrator, question handling |
| `backend/server/.../configuration/PythonOrchestratorClient.kt` | REST client for Python correction endpoints |
| `shared/common-api/.../service/ITranscriptCorrectionService.kt` | RPC interface for correction CRUD |
| `shared/common-dto/.../dto/meeting/MeetingDtos.kt` | `MeetingStateEnum` (incl. CORRECTION_REVIEW), `CorrectionQuestionDto`, `CorrectionAnswerDto` |
| `shared/ui-common/.../meeting/CorrectionsScreen.kt` | Corrections management UI |
| `shared/ui-common/.../meeting/CorrectionViewModel.kt` | Corrections UI state management |

---

## Smart Model Selector

### Overview

SmartModelSelector is a Spring service that dynamically selects optimal Ollama LLM models based on input content length. It prevents context truncation for large documents while avoiding RAM/VRAM waste on small tasks.

### Problem Statement

#### Before SmartModelSelector:
- **Hardcoded models**: All tasks use same model (e.g., `qwen3-coder:30b` with 128k context)
- **Small tasks** (1k tokens): Waste RAM/VRAM allocating 128k context
- **Large tasks** (100k tokens): Get truncated at 128k limit

#### After SmartModelSelector:
- **Dynamic selection**: Automatically chooses optimal tier based on content length
- **Efficient resource usage**: Small tasks use small context (4k-16k)
- **No truncation**: Large tasks get appropriate context (64k-256k)

### Model Naming Convention

All models on Ollama server follow this pattern:
```
qwen3-coder-tool-{SIZE}k:30b
```

### Model Selection Logic

| Content Length | Model | Context | Use Case |
|----------------|-------|---------|----------|
| 0-4,000 tokens | qwen3-coder-tool-4k:30b | 4k | Small tasks, quick queries |
| 4,001-16,000 tokens | qwen3-coder-tool-16k:30b | 16k | Medium tasks, documents |
| 16,001-64,000 tokens | qwen3-coder-tool-64k:30b | 64k | Large documents, codebases |
| 64,001+ tokens | qwen3-coder-tool-256k:30b | 256k | Very large documents |

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
- **shared/ui-common**: Compose Multiplatform UI screens
- **apps/desktop**: Primary desktop application
- **apps/mobile**: iOS/Android port from desktop

### Shared Infrastructure (`backend/common-services`)

- **`com.jervis.common.http`**: Typed exception hierarchy (`ProviderApiException`), response validation (`checkProviderResponse()`), pagination helpers (Link header, offset-based)
- **`com.jervis.common.ratelimit`**: `DomainRateLimiter` (per-domain sliding window), `ProviderRateLimits` (centralized configs for GitHub/GitLab/Atlassian), `UrlUtils`
- **`com.jervis.common.client`**: kRPC service interfaces (`IBugTrackerClient`, `IRepositoryClient`, `IWikiClient`, `IProviderService`)

### Communication Patterns

- **UI ↔ Server**: ONLY `@HttpExchange` interfaces in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

---

## Benefits of Architecture

1. **Cost efficiency**: Expensive GPU models only when necessary
2. **Scalability**: Parallel CPU qualification, GPU execution on idle
3. **Explainability**: Evidence links for all relationships
4. **Flexibility**: Schema-less graph for new entity types
5. **Performance**: Hybrid search combining semantic + structured
6. **Type safety**: Compile-time checks prevent runtime errors
7. **Fail-fast design**: Errors propagate, no silent failures
8. **Multi-tenancy**: Per-client isolation in all storage layers

---

## Coding Agents

Jervis integrates four autonomous coding agents, each running as a standalone kRPC microservice. All implement the shared `ICodingClient` interface (`execute(CodingRequest): CodingResult`) and communicate with the server over WebSocket/CBOR.

### Agent Overview

| Agent | Service | Port | Purpose | Default Provider |
|-------|---------|------|---------|-----------------|
| **Aider** | `service-aider` | 3100 | Fast, localized changes (1-3 files) | Ollama (qwen3-coder-tool:30b) |
| **OpenHands** | `service-coding-engine` | 3200 | Complex multi-file refactoring | Ollama (qwen3-coder-tool:30b) |
| **Junie** | `service-junie` | 3300 | Premium, ultra-fast (JetBrains) | Anthropic (claude-3-5-sonnet) |
| **Claude** | `service-claude` | 3400 | Agentic coding with strong reasoning | Anthropic (claude-sonnet-4) |

### Decision Matrix (CodingTools.kt)

The `execute()` tool auto-selects the agent based on strategy hints:

- **FAST** -> Aider (small, localized edits)
- **THOROUGH** -> OpenHands (deep multi-file refactoring)
- **REASONING** -> Claude (complex reasoning and planning)
- **PREMIUM** -> Junie (last resort, expensive, fastest)
- **AUTO** -> Heuristic: few files -> Aider, else Claude

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

### Architecture

```
Kotlin Server (BackgroundEngine)
    │
    ├── POST /orchestrate/stream ──► Python Orchestrator (LangGraph)
    │   (fire-and-forget,               │
    │    returns thread_id)              ├── decompose → plan → execute → evaluate
    │                                    │   (K8s Jobs for coding agents)
    ├── GET /status/{thread_id} ◄──────│
    │   (polling every 5s)               │
    │                                    └── interrupt() for commit/push approval
    ├── POST /approve/{thread_id} ──► resume from checkpoint
    │   (after USER_TASK response)
    │
    └── TaskDocument (MongoDB) = SSOT for lifecycle state
```

### State Persistence

- **TaskDocument** (Kotlin/MongoDB): SSOT for task lifecycle, `orchestratorThreadId`, USER_TASK state
- **LangGraph checkpoints** (Python/MongoDB): Graph execution state, auto-saved after every node
- **Checkpointer**: `AsyncMongoDBSaver` from `langgraph-checkpoint-mongodb` (same MongoDB instance)
- Thread ID is the link between TaskDocument and LangGraph checkpoint

### Task State Machine (Python orchestrator path)

```
READY_FOR_GPU → PYTHON_ORCHESTRATING → done → DISPATCHED_GPU / DELETE
                    │                → error → ERROR
                    └── interrupted → USER_TASK → user responds → READY_FOR_GPU (loop)
```

### Approval Flow (USER_TASK)

1. LangGraph hits `interrupt()` at `git_operations` node (commit/push approval)
2. Checkpoint saved to MongoDB automatically
3. `BackgroundEngine.runOrchestratorResultLoop()` detects "interrupted" status
4. `UserTaskService.failAndEscalateToUserTask()` creates USER_TASK with notification
5. User responds via UI → `UserTaskRpcImpl.sendToAgent()` → state = READY_FOR_GPU
6. BackgroundEngine picks up task → `resumePythonOrchestrator()` → POST /approve/{thread_id}
7. LangGraph resumes from MongoDB checkpoint → continues from interrupt point

### Concurrency Control

Only **one orchestration at a time** (LLM cannot handle concurrent requests efficiently).

Two layers:
1. **Kotlin** (early guard): `countByState(PYTHON_ORCHESTRATING) > 0` → skip dispatch
2. **Python** (definitive): `asyncio.Semaphore(1)` → HTTP 429 if busy

`/approve/{thread_id}` is fire-and-forget: returns immediately, Python resumes graph in background with semaphore.

### Key Files

| File | Purpose |
|------|---------|
| `backend/service-orchestrator/app/main.py` | FastAPI endpoints, SSE, concurrency (Semaphore), MongoDB lifecycle |
| `backend/service-orchestrator/app/graph/orchestrator.py` | LangGraph StateGraph, checkpointing |
| `backend/service-orchestrator/app/graph/nodes.py` | Node functions: decompose, plan, execute, evaluate, git_ops |
| `backend/service-orchestrator/app/config.py` | Configuration (MongoDB URL, K8s, LLM providers) |
| `backend/server/.../AgentOrchestratorService.kt` | Dispatch + resume logic, concurrency guard (Kotlin side) |
| `backend/server/.../BackgroundEngine.kt` | Result polling loop, USER_TASK escalation |
| `backend/server/.../PythonOrchestratorClient.kt` | REST client for Python orchestrator (429 handling) |
| `backend/service-orchestrator/app/agents/workspace_manager.py` | Workspace preparation (instructions, KB, environment context) |

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

When a user sends a message to a project whose task is currently in `PYTHON_ORCHESTRATING` state, the message cannot be processed immediately (the orchestrator is busy). Instead of dropping or blocking the message:

1. The message is saved to `ChatMessageDocument` (MongoDB) as usual -- it is persisted
2. `TaskDocument` has an `orchestrationStartedAt: Instant?` field set when orchestration begins
3. When orchestration completes ("done"), `BackgroundEngine` checks if any new USER messages arrived after `orchestrationStartedAt` (via `ChatMessageRepository.countByTimestamp`)
4. If new messages found: the task is auto-requeued to `READY_FOR_GPU` (not `DISPATCHED_GPU`), so the agent re-processes with full context including the new messages
5. If no new messages: normal completion flow continues

```
User sends message while PYTHON_ORCHESTRATING
    │
    ├── Message saved to ChatMessageDocument (persisted)
    │
    └── Orchestration completes ("done")
         │
         ├── New USER messages after orchestrationStartedAt?
         │   YES → auto-requeue to READY_FOR_GPU (re-process with new context)
         │   NO  → normal completion (DISPATCHED_GPU or DELETE)
         │
         └── TaskDocument.orchestrationStartedAt reset
```

### Key Files

| File | Purpose |
|------|---------|
| `TaskDocument.kt` | `orchestrationStartedAt` field for tracking orchestration start time |
| `AgentOrchestratorService.kt` | Sets `orchestrationStartedAt` on dispatch |
| `AgentOrchestratorRpcImpl.kt` | PYTHON_ORCHESTRATING handling, 3 new queue RPC methods |
| `BackgroundEngine.kt` | Auto-requeue logic, dual-queue status emissions |
| `ChatMessageRepository.kt` | Count messages by timestamp query |
| `TaskService.kt` | `getPendingBackgroundTasks()`, `reorderTaskInQueue()`, `moveTaskToQueue()` |
| `TaskRepository.kt` | Background queue query |
| `PendingTasksDto.kt` | Shared DTO for queue items |
| `AgentActivityEntry.kt` | Enhanced `PendingQueueItem` with `taskId`, `processingMode`, `queuePosition` |
| `MainViewModel.kt` | Dual-queue state (`foregroundQueue`, `backgroundQueue`), action methods |
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
├── namespace: String            ← K8s namespace
├── components: List<EnvironmentComponent>
│   ├── type: ComponentType      ← POSTGRESQL, REDIS, PROJECT, etc.
│   ├── image: String?           ← Docker image (infra) or null (project)
│   ├── ports, envVars, autoStart, startOrder
├── componentLinks: List<ComponentLink>
│   ├── sourceComponentId → targetComponentId
├── propertyMappings: List<PropertyMapping>
│   ├── projectComponentId, propertyName, targetComponentId, valueTemplate
├── agentInstructions: String?
└── state: EnvironmentState      ← PENDING, CREATING, RUNNING, etc.
```

### Inheritance (Client → Group → Project)

- Environment at **client level** applies to all groups and projects
- Environment at **group level** overrides/extends for that group's projects
- Environment at **project level** is most specific
- Resolution: query most specific first (project → group → client)

### Agent Environment Context

When a coding task is dispatched to the Python orchestrator:

1. `AgentOrchestratorService` resolves environment via `EnvironmentService.resolveEnvironmentForProject()`
2. `EnvironmentMapper.toAgentContextJson()` converts to `JsonObject`
3. Passed in `OrchestrateRequestDto.environment` field
4. Python orchestrator stores in LangGraph state as `environment` dict
5. `workspace_manager.prepare_workspace()` writes:
   - `.jervis/environment.json` – raw JSON for programmatic access
   - `.jervis/environment.md` – human-readable markdown
6. `CLAUDE.md` includes environment section with:
   - Infrastructure endpoints (host:port)
   - Project components with ENV vars
   - Agent instructions
   - Component topology

### Key Files

| File | Purpose |
|------|---------|
| `backend/server/.../entity/EnvironmentDocument.kt` | MongoDB document + embedded types |
| `backend/server/.../service/environment/EnvironmentService.kt` | CRUD + resolve inheritance |
| `backend/server/.../service/environment/EnvironmentK8sService.kt` | K8s namespace provisioning |
| `backend/server/.../service/environment/ComponentDefaults.kt` | Default Docker images per type |
| `backend/server/.../mapper/EnvironmentMapper.kt` | Document ↔ DTO + toAgentContextJson() |
| `shared/common-dto/.../dto/environment/EnvironmentDtos.kt` | Cross-platform DTOs |

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
| **FCM Data Messages** | App killed or not connected | Remote push, Android + iOS |
| **Desktop OS** | macOS/Windows/Linux, app running | osascript / SystemTray / notify-send |
| **In-app Dialog** | Approval events, any platform | Approve/Deny buttons with reason input |

### Notification Types

| Type | Event | Urgency | Actions |
|------|-------|---------|---------|
| **Approval** | `UserTaskCreated(isApproval=true)` | HIGH | Approve / Deny (with reason) |
| **User Task** | `UserTaskCreated(isApproval=false)` | NORMAL | Tap to open app |
| **Error** | `ErrorNotification` | NORMAL | Informational |

### Event Flow

```
Python Orchestrator interrupt
  → BackgroundEngine.checkOrchestratorTaskStatus()
    → UserTaskService.failAndEscalateToUserTask(interruptAction, isApproval)
      → NotificationRpcImpl.emitUserTaskCreated() [kRPC stream]
      → FcmPushService.sendPushNotification() [FCM data message]
        → Mobile receives push even if app killed
  → MainViewModel.handleGlobalEvent()
    → PlatformNotificationManager.showNotification()
    → ApprovalNotificationDialog (if isApproval)
```

### Cross-Platform Architecture

```
expect class PlatformNotificationManager
├── jvmMain:    macOS osascript, Windows SystemTray, Linux notify-send
├── androidMain: NotificationCompat + BroadcastReceiver + action buttons
└── iosMain:    UNUserNotificationCenter + UNNotificationAction

NotificationActionChannel (MutableSharedFlow)
├── Android: NotificationActionReceiver → emits
├── iOS:     NotificationDelegate.swift → NotificationBridge.kt → emits
└── MainViewModel: collects → sendToAgent(taskId, routingMode, input)
```

### Key Files

| File | Purpose |
|------|---------|
| `shared/common-dto/.../events/JervisEvent.kt` | Event model with approval metadata |
| `shared/ui-common/.../notification/PlatformNotificationManager.kt` | expect class |
| `shared/ui-common/.../notification/NotificationActionChannel.kt` | Cross-platform action callback |
| `shared/ui-common/.../notification/ApprovalNotificationDialog.kt` | In-app approve/deny dialog |
| `backend/server/.../service/notification/FcmPushService.kt` | Firebase Cloud Messaging sender |
| `backend/server/.../entity/DeviceTokenDocument.kt` | FCM token storage |
| `shared/common-api/.../IDeviceTokenService.kt` | Token registration RPC |