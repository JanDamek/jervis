# Implementation Notes - Technical Design Decisions

**Status:** Production Documentation (2026-02-05)
**Purpose:** Technical decisions, problem solving, and architecture rationale

---

## Table of Contents

1. [Sealed Classes & MongoDB](#sealed-classes--mongodb)
2. [Smart Model Selector](#smart-model-selector)
3. [Security Implementation](#security-implementation)
4. [OAuth2 Implementation & Connection Testing](#oauth2-implementation--connection-testing)
5. [Troubleshooting Guide](#troubleshooting-guide)
6. [Python Orchestrator Implementation](#python-orchestrator-implementation)

---

## ~~Step 2 Verification Checklist~~ (REMOVED)

> **Historical (2026-02-07):** This section documented the Koog-based qualifier pipeline
> verification (strategy graph, node/edge DSL, `QualifierPipelineState`, vision stages).
> The Koog framework has been completely removed. Qualification is now handled by
> `SimpleQualifierAgent` which calls the KB microservice directly. Complex task execution
> goes through the Python Orchestrator (LangGraph). See [koog-audit.md](koog-audit.md).

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

## Security Implementation

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

#### Security Plugin Implementation

```kotlin
// KtorRpcServer.kt
private fun createClientSecurityPlugin(securityProperties: SecurityProperties, logger: Logger): Plugin<ClientSecurityConfiguration> {
    return ClientSecurityPlugin {
        token = securityProperties.clientToken
        platformWhitelist = setOf(PLATFORM_IOS, PLATFORM_ANDROID, PLATFORM_DESKTOP)
        logger = logger
    }
}

// Installation
install(createClientSecurityPlugin(securityProperties, logger))
```

---

## OAuth2 Implementation & Connection Testing

### Phase 1: Basic OAuth2 Flow

#### Pre-Deployment Checklist: OAuth2

- [ ] **Backend OAuth2 Configuration**
  - [ ] `OAuth2Service.kt` uses `getAuthorizationUrl(connectionId)` method
  - [ ] `OAuth2Service.kt` implements `handleCallback(code, state)` method
  - [ ] `OAuth2CallbackResult` sealed class includes `Success(connectionId, userId, message)`
  - [ ] State tokens are generated as UUID
  - [ ] State tokens expire after 10 minutes (or configured TTL)

- [ ] **RPC Endpoint: `/oauth2/authorize/{connectionId}`**
  - [ ] Route is accessible without RPC security headers (intentionally public)
  - [ ] Generates authorization URL via `OAuth2Service`
  - [ ] Redirects user to provider (GitHub/GitLab/etc)
  - [ ] Proper error handling for invalid connectionId

- [ ] **RPC Endpoint: `/oauth2/callback`**
  - [ ] Route handles `code` and `state` query parameters
  - [ ] Route handles error responses from provider
  - [ ] Calls `OAuth2Service.handleCallback(code, state)`
  - [ ] Returns HTML success/error/failure pages
  - [ ] HTML pages include user-friendly messages
  - [ ] Success page auto-closes after 2 seconds

- [ ] **Connection Test Endpoint**
  - [ ] `repository.connections.testConnection(connectionId)` implemented
  - [ ] Returns `ConnectionTestResultDto` with `success: Boolean` + `message: String?`
  - [ ] Works for all connection types (HTTP, IMAP, POP3, SMTP, OAuth2)
  - [ ] Error messages are descriptive (timeout, auth failed, etc)

- [ ] **UI Components in ConnectionEditDialog**
  - [ ] "▶ Test Connection" button visible
  - [ ] Test button calls `repository.connections.testConnection(...)`
  - [ ] Test result shows ✓ or ✗ with color coding
  - [ ] "🔐 Authorize with OAuth2" button shows only for `type == "OAUTH2"`
  - [ ] OAuth2 button is disabled during authorization flow
  - [ ] OAuth2 button opens browser via `BrowserHelper.openOAuth2Authorization(connectionId)`

- [ ] **BrowserHelper Implementation**
  - [ ] Platform-specific `openUrlInBrowser(url)` implemented (expect function)
  - [ ] Platform-specific `getServerBaseUrl()` returns server URL (expect function)
  - [ ] Desktop implementation opens default browser
  - [ ] Mobile implementations handle deep links (or polling fallback)

#### Functional Testing: OAuth2 Flow

#### Test 1: Test Connection Button

```
Steps:
1. Settings → Connections
2. Edit any HTTP/IMAP/SMTP connection
3. Click "▶ Test Connection"

Expected Results:
✓ Button shows loading indicator
✓ After 2-5 seconds: ✓ Test OK (if valid) or ✗ Test failed (if invalid)
✓ Error message shows reason (timeout, auth failed, etc)
✓ Can click button multiple times (stateless)
```

#### Test 2: OAuth2 Authorization (Requires GitHub/GitLab Setup)

```
Prerequisites:
- OAuth app registered on provider (GitHub/GitLab/Bitbucket)
- Client ID and Secret configured in application.yml
- Callback URL matches: https://{server}/oauth2/callback

Steps:
1. Settings → Connections → Edit OAuth2 connection
2. Click "🔐 Authorize with OAuth2"
3. Browser opens to provider login page
4. User logs in and grants permissions
5. Redirected to /oauth2/callback
6. See success page: "✓ Authorization Successful"
7. Browser closes automatically
8. Return to app

Expected Results:
✓ Browser opens to GitHub/GitLab login
✓ User grants permissions
✓ /oauth2/callback receives code + state
✓ Server validates state (exists in-memory map)
✓ Server exchanges code for access_token
✓ Connection.credentials updated with token
✓ HTML success page displayed
✓ Connection is now in VALID state
✓ Desktop app polls and refreshes connection
✓ Mobile app refreshes on resume/return

Failure Cases:
- User denies permissions → Error page shown
- State token expired → "State not found or expired" message
- Provider error → Error details shown
- Browser closed before completion → App detects timeout
```

#### Test 3: Connection States

```
States:
- NEW: Connection created, no token yet
- VALID: Connection tested/authorized successfully
- INVALID: Connection failed test, auth failed, or expired

Visibility:
✓ Connection card shows state badge
✓ State colors: NEW (gray), VALID (green), INVALID (red)
✓ Test/Auth buttons work from any state
✓ Results update state in real-time
```

#### Test 4: Multi-Platform Testing

**Desktop (Kotlin/macOS/Windows/Linux):**
- [ ] Test button works
- [ ] OAuth2 button opens browser
- [ ] App polls for token (can check by watching connection list)
- [ ] Clicking back in browser returns to app

**Mobile (iOS/Android):**
- [ ] Test button works
- [ ] OAuth2 button opens browser (or in-app web view)
- [ ] Returning to app triggers refresh
- [ ] Connection updates without manual refresh

### OAuth2 Token Refresh in Git Operations

`GitRepositoryService.syncRepository()` calls `oauth2Service.refreshAccessToken(connection)` before
any git clone/fetch. If the token was refreshed (returns `true`), the connection is re-read from DB
to pick up the new `bearerToken`. This prevents "HTTP Basic: Access denied" errors from GitLab when
OAuth2 tokens expire (typically 2 hours).

### Git Auth Failure → Connection Invalidation

When `GitAuthenticationException` is thrown during git sync (clone or fetch), both `syncAllProjects()`
and `syncProjectRepositories()` now:
1. Log the auth error
2. Mark the connection as `INVALID` via `invalidateConnection()`
3. Record an error log via `ErrorLogService.recordError()` for UI visibility
4. Skip INVALID connections on subsequent sync attempts (early return in `syncRepository()`)

### Post-Deployment: OAuth2 Monitoring

#### Logs to Monitor

```
✓ Normal:
- "Initiating OAuth2 flow for connectionId=..."
- "Successfully authorized connection: {connectionId}"
- "Testing connection {connectionId}: success"

✗ Problems:
- "OAuth2 authorization failed" → Check logs for provider error
- "Connection test failed: [error message]" → Check network/credentials
- "State not found or expired" → State token lost (likely restart)
- "BeanInstantiationException" → MongoDB sealed class issue (run migration)
- "Auth failed for {repo}: ..." → OAuth2 token expired and refresh failed, connection marked INVALID
- "Connection {id} ({name}) marked INVALID" → Credentials no longer valid
```

#### Metrics to Track

```yaml
oauth2:
  authorizations_initiated: counter (OAuth2 flows started)
  authorizations_completed: counter (OAuth2 flows succeeded)
  authorizations_failed: counter (OAuth2 flows failed)
  connection_tests_total: counter (Test button clicks)
  connection_tests_failed: counter (Test failures)

connection:
  state_transitions: [NEW → VALID], [VALID → INVALID], etc
  average_test_duration_ms: histogram
```

---

## MongoDB Migration Required

**Status:** Production Documentation
**Last updated:** 2026-02-04

#### Current Issue

Jira polling handler is failing due to sealed class refactoring and missing MongoDB fields.

#### Immediate Action Required

**Run this command to fix MongoDB:**

```bash
mongosh "mongodb://root:qusre5-mYfpox-dikpef@192.168.100.117:27017/jervis?authSource=admin" \
  --file scripts/mongodb/fix-all-issues.js
```

This will:

1. Add `_class` discriminator to all sealed class documents
2. Fix missing/null `projectKey` fields in Jira documents
3. Fix missing/null List fields (labels, comments, attachments, linkedIssues)

#### After Migration

1. **Restart Jervis server**
2. **Verify logs** - should see successful Jira polling
3. **Delete this file** once migration is complete

#### What Was Fixed in Code

✅ **BugTrackerIssueIndexDocument.kt**

- Made `projectKey` nullable
- Added default empty lists for `labels`, `comments`, `attachments`, `linkedIssues`

✅ **BugTrackerPollingHandler.kt**

- Changed from `repository.save()` to `mongoTemplate.findAndReplace()` with upsert
- Fixes DuplicateKeyException with sealed classes

#### Detailed Documentation

- **Complete Guide**: `docs/troubleshooting/jira-polling-complete-fix.md`
- **DuplicateKey Issue**: `docs/troubleshooting/jira-duplicate-key-fix.md`
- **Sealed Class Issues**: `docs/troubleshooting/sealed-class-mongodb-errors.md`

#### Migration Scripts

- **Complete**: `scripts/mongodb/fix-all-issues.js` (recommended)
- **Partial**: `scripts/mongodb/fix-all-sealed-classes.js` (only _class)
- **Jira Only**: `scripts/mongodb/fix-jira-missing-fields.js` (only missing fields)

---

**Status**: Code changes applied ✅ | Migration pending ⏳ | Server restart needed 🔄

---

## Troubleshooting Guide

### Common Issues and Solutions

#### Issue: All Clients Get HTTP 401

**Possible causes:**
1. Client token in `application.yml` doesn't match constant
2. Plugin not installed
3. Wrong hook used

**Solution:**
```yaml
# Check application.yml
jervis:
  security:
    client-token: a7f3c9e2-4b8d-11ef-9a1c-0242ac120002  # Match SecurityConstants

# Verify plugin installation in KtorRpcServer
install(createClientSecurityPlugin(securityProperties, logger))
```

#### Issue: iOS Clients Get HTTP 400

**Possible causes:**
1. Platform string has typo (e.g., "IOS" instead of "iOS")
2. Platform value not in whitelist

**Solution:**
```kotlin
// Verify exact casing
const val PLATFORM_IOS = "iOS"      // Not "IOS"
const val PLATFORM_ANDROID = "Android"
const val PLATFORM_DESKTOP = "Desktop"

// Verify platform sent from client
headers[PLATFORM_HEADER] = "iOS"    // Correct
```

#### Issue: Connection Latency Increased

**Possible causes:**
1. Plugin adds too much overhead
2. Network issue unrelated to security

**Solution:**
```bash
# Measure latency before/after
# Should be < 5ms overhead for validation

# Profile the application
# Check if plugin is the bottleneck
```

#### Issue: WARN Logs Flooded with Unverified Connections

**Possible causes:**
1. Old client version not sending headers
2. Reverse proxy/load balancer not forwarding headers
3. Security misconfiguration

**Solution:**
1. Force all clients to update
2. Verify reverse proxy forwards custom headers:
   ```nginx
   proxy_pass_request_headers on;
   ```
3. Check client code sends headers correctly

---

## Monitoring and Maintenance

### Post-Deployment Monitoring

#### Logs to Monitor

```
✓ Normal:
- "Initiating OAuth2 flow for connectionId=..."
- "Successfully authorized connection: {connectionId}"
- "Testing connection {connectionId}: success"

✗ Problems:
- "OAuth2 authorization failed" → Check logs for provider error
- "Connection test failed: [error message]" → Check network/credentials
- "State not found or expired" → State token lost (likely restart)
- "BeanInstantiationException" → MongoDB sealed class issue (run migration)
```

#### Metrics to Track

```yaml
oauth2:
  authorizations_initiated: counter (OAuth2 flows started)
  authorizations_completed: counter (OAuth2 flows succeeded)
  authorizations_failed: counter (OAuth2 flows failed)
  connection_tests_total: counter (Test button clicks)
  connection_tests_failed: counter (Test failures)

connection:
  state_transitions: [NEW → VALID], [VALID → INVALID], etc
  average_test_duration_ms: histogram
```

---

## Development Mode Rules

### Security Rules

- **No secrets masking:** Passwords, tokens, keys and other "secrets" are always visible (private app)
- **No encryption:** DocumentDB (Mongo): nothing encrypted; store in "plain text" (private dev instance)
- **No deprecations:** No @Deprecated code: all changes refactored immediately

### UI Rules

- **No secrets masking in UI:** passwords, tokens, keys always visible
- **DocumentDB storage:** nothing encrypted - store in plain text
- **Fail-fast in UI:** errors displayed openly via `JErrorState`, don't hide

---

## Python Orchestrator Implementation

> Authoritative spec: [orchestrator-final-spec.md](orchestrator-final-spec.md).

### KB-First Architecture (Redesign)

The orchestrator has been redesigned with a KB-first architecture:

**4 task categories** with intelligent routing:
- **ADVICE**: Direct LLM + KB answer (no coding, no K8s Jobs)
- **SINGLE_TASK**: May or may not code — step types: respond, code, tracker_ops, mixed
- **EPIC**: Batch execution in waves from tracker issues
- **GENERATIVE**: Design full structure from high-level goal, then execute

**Key principle**: SINGLE_TASK is NOT just coding. It can be managerial, analytical, or planning.

**Modular node files** (`app/graph/nodes/`):
- `intake.py` — 4-category classification, mandatory clarification via `interrupt()`
- `evidence.py` — Parallel KB + tracker artifact fetch (EvidencePack)
- `respond.py` — ADVICE + analytical response (LLM + KB, no K8s)
- `plan.py` — Multi-type planning (respond/code/tracker/mixed)
- `execute.py` — Step dispatch by type (respond/code/tracker)
- `evaluate.py` — Evaluation + routing
- `git_ops.py` — Git operations with approval gates
- `finalize.py` — Final report generation
- `coding.py` — Coding pipeline (decompose, select_goal, plan_steps)
- `epic.py` — EPIC: plan_epic, execute_wave, verify_wave
- `design.py` — GENERATIVE: design (generate epic structure from goal)

**Hierarchical context management** (`app/context/`):
- `context_store.py` — MongoDB `orchestrator_context` collection, 30-day TTL
- `context_assembler.py` — Per-node LLM context assembly (step/goal/epic levels)
- `agent_result_parser.py` — Normalize variable agent responses
- `distributed_lock.py` — MongoDB distributed lock for multi-pod concurrency

**JERVIS Internal Project**: Each client gets max 1 `isJervisInternal=true` project (auto-created on first orchestration) for orchestrator tracker/wiki operations. `ProjectDocument.isJervisInternal` field, `ProjectRepository.findByClientIdAndIsJervisInternal()`, `ProjectService.getOrCreateJervisProject()`.

### State Persistence Design Decision

**Problem**: MemorySaver (in-memory) loses all graph state on Python restart.

**Solution**: `AsyncMongoDBSaver` from `langgraph-checkpoint-mongodb`.

**Rationale**:
- Same MongoDB instance as Kotlin server (no new infrastructure)
- LangGraph manages serialization/deserialization automatically
- Thread ID links TaskDocument (Kotlin) ↔ LangGraph checkpoint (Python)
- Supports interrupt/resume across process restarts

### Fire-and-Forget Dispatch Pattern

**Problem**: Original `delegateToPythonOrchestrator()` was blocking (5min timeout),
freezing the entire BackgroundEngine execution loop.

**Solution**: Async dispatch with separate result polling loop.

1. `POST /orchestrate/stream` returns `thread_id` immediately (non-blocking)
2. Task state → `PYTHON_ORCHESTRATING`, execution slot released
3. `runOrchestratorResultLoop()` polls `GET /status/{thread_id}` every 5s
4. Results handled asynchronously without blocking other tasks

### USER_TASK Integration

**Problem**: Interrupt handling was not using `UserTaskService`, missing notifications and type change.

**Solution**: `BackgroundEngine.checkOrchestratorTaskStatus()` calls
`userTaskService.failAndEscalateToUserTask()` for interrupts, which:
- Changes `state` to `USER_TASK` and `type` to `USER_TASK`
- Sends UI notification via `notificationRpc.emitUserTaskCreated()`
- Sets `pendingUserQuestion` and `userQuestionContext`

**Resume path**: When user responds via `sendToAgent()`:
- `task.content` is updated to user's response (line 105 of UserTaskRpcImpl.kt)
- `orchestratorThreadId` is preserved through USER_TASK cycle
- BackgroundEngine picks up task → `resumePythonOrchestrator()` → `POST /approve/{thread_id}`
- LangGraph resumes from MongoDB checkpoint with user's approval

### Concurrency Control

Only one Python orchestration runs at a time:

1. **Kotlin guard** (`AgentOrchestratorService.dispatchToPythonOrchestrator()`):
   - `taskRepository.countByState(PYTHON_ORCHESTRATING) > 0` → return false, skip dispatch
   - Handles HTTP 429 from `orchestrateStream()` → return false

2. **Python guard** (`main.py`):
   - `asyncio.Semaphore(1)` – single-pod fallback (kept for local dev)
   - `DistributedLock` – MongoDB-based lock for multi-pod deployments
   - `/approve/{thread_id}` fire-and-forget: `asyncio.create_task()` + semaphore
   - `/health` returns `{"busy": true/false}` for diagnostics

3. **`PythonOrchestratorClient.approve()`** is now fire-and-forget (returns `Unit`).
   Python returns `{"status": "resuming"}` immediately. Result polled via GET /status.

### Key Technical Details

- `TaskStateEnum.PYTHON_ORCHESTRATING`: New state for dispatched tasks
- `TaskDocument.orchestratorThreadId`: Links to LangGraph checkpoint
- `OrchestrateRequestDto.jervisProjectId`: JERVIS internal project for tracker ops
- `OrchestrateRequestDto.chatHistory`: Conversation context (recent messages + compressed summaries)
- MongoDB collections: `checkpoints` (LangGraph), `tasks` (TaskDocument), `orchestrator_context` (hierarchical context), `orchestrator_locks` (distributed lock), `chat_messages` (conversation messages), `chat_summaries` (compressed history blocks)
- K8s env: `MONGODB_URL` in `k8s/app_orchestrator.yaml` (from secrets)
- Kotlin internal API endpoints: `/internal/tracker/create-issue`, `/internal/tracker/update-issue` (placeholders, Phase 2 delegation)

### Chat Context Persistence Implementation

**Goal:** Agent maintains conversation memory across orchestration sessions.

**Kotlin side:**
- `ChatSummaryDocument` — MongoDB entity for compressed chat blocks (`chat_summaries` collection)
- `ChatSummaryRepository` — Spring Data repository with `findByTaskIdOrderBySequenceEndAsc`, `findFirstByTaskIdOrderBySequenceEndDesc`
- `ChatHistoryService` — two operations:
  - `prepareChatHistoryPayload(taskId)` — loads last 20 messages + up to 15 summary blocks → `ChatHistoryPayloadDto`
  - `compressIfNeeded(taskId, clientId)` — async after orchestration: if >20 unsummarized messages, calls Python LLM to compress
- `AgentOrchestratorService.dispatchToPythonOrchestrator()` — attaches `chatHistory` to request
- `OrchestratorStatusHandler.handleDone()` — launches async `compressIfNeeded()` via `CoroutineScope(Dispatchers.IO)`

**Python side:**
- `ChatHistoryPayload`, `ChatHistoryMessage`, `ChatSummaryBlock` — Pydantic models in `models.py`
- `OrchestrateRequest.chat_history` — optional field, passed to `OrchestratorState`
- `POST /internal/compress-chat` — endpoint in `main.py` using `LOCAL_FAST` LLM tier
- Node usage: `intake` (last 5 for classification), `respond` (full context), `evidence` (summary), `plan` (key decisions), `finalize` (conversation stats)

**Compression algorithm:**
1. After orchestration completes → `handleDone()` → async `compressIfNeeded()`
2. Find last summarized sequence (`ChatSummaryRepository.findFirstByTaskIdOrderBySequenceEndDesc`)
3. Count unsummarized messages before recent window (total - 20)
4. If ≥20 unsummarized → POST `/internal/compress-chat` → store `ChatSummaryDocument`
5. Non-blocking: compression failure is logged but doesn't affect user

---

## Meeting Transcription: Stop & Race Condition Guard

### stopTranscription API

`IMeetingService.stopTranscription(meetingId: String): Boolean` allows the user to cancel an in-progress Whisper transcription.

**Flow (MeetingRpcImpl):**
1. Re-reads meeting from DB; returns `false` if state is not `TRANSCRIBING`
2. Calls `WhisperJobRunner.deleteJobForMeeting(meetingId)` (best-effort) -- deletes any K8s Job with label `meeting-id` matching, using `BACKGROUND` propagation policy
3. Resets meeting state to `UPLOADED`
4. Emits notification so UI refreshes

**UI:** `MeetingViewModel.stopTranscription()` calls the RPC method. `PipelineProgress` shows a red stop button (`Icons.Default.Stop`) when `state == TRANSCRIBING`.

### Race Condition Guard in MeetingTranscriptionService

When a Whisper job completes or fails, the result handler in `MeetingTranscriptionService` re-reads the meeting from DB before writing the outcome. If the state has already changed (e.g., user stopped transcription and reset to `UPLOADED`), the handler skips the `FAILED` / result transition to avoid overwriting the user's action.

---

## Recent Changes & Deployments (2026-02)

### Ollama Router – Priority-Based GPU/CPU Routing

**Deployed:** 2026-02-13

**What changed:**
- Added new service `jervis-ollama-router` (port 11430) as transparent LLM proxy
- All Ollama requests now route through Ollama Router instead of direct Ollama
- Priority-based routing: Orchestrator gets reserved GPU, background tasks use CPU fallback

**Services updated:**
- Orchestrator: `OLLAMA_API_BASE=http://192.168.100.117:11430`
- KB (read/write): All 3 URLs point to router (BASE_URL, EMBEDDING_BASE_URL, INGEST_BASE_URL)

**Benefits:**
- Guaranteed GPU for user-facing requests (orchestrator)
- Automatic CPU fallback for background tasks
- Better resource utilization (no GPU blocking on embeddings during orchestration)

**Files:**
- Service: `backend/service-ollama-router/`
- K8s deployment: `k8s/app_ollama_router.yaml`
- Build script: `k8s/build_ollama_router.sh`
- ConfigMap: `k8s/configmap.yaml` (jervis-ollama-router-config)

---

### kRPC Resilient WebSocket Connection

**Deployed:** 2026-02-13

**What changed:**
- Added automatic WebSocket reconnection logic in `RpcConnectionManager.kt`
- Connection state management with exponential backoff retry
- Graceful handling of connection drops and server restarts

**Files:**
- `shared/domain/.../di/RpcConnectionManager.kt` - New connection manager
- `apps/desktop/.../ConnectionState.kt` - Simplified state tracking
- `backend/server/.../rpc/KtorRpcServer.kt` - WebSocket config (pingPeriodMillis, timeoutMillis)

**Benefits:**
- UI no longer loses connection on server restart
- Automatic reconnection without user intervention
- Cleaner connection state management

---

### Whisper REST In-Memory Queue

**Deployed:** 2026-02-13

**What changed:**
- Replaced file-based progress tracking with in-memory queue
- Real-time progress updates via SSE streaming
- Reduced I/O overhead in Whisper REST mode

**Files:**
- `backend/service-whisper/whisper_rest_server.py` - In-memory progress queue
- `backend/server/.../service/meeting/WhisperRestClient.kt` - SSE client updates

**Benefits:**
- Lower latency for progress updates
- No file system cleanup needed
- Better suited for containerized environment

---

### Chat History Error Filtering

**Deployed:** 2026-02-13

**What changed:**
- Added error message filtering in chat history preparation
- Prevents error messages from being sent to LLM as context
- Breaks feedback loop where errors generate more errors

**Implementation:**
- Kotlin: `ChatHistoryService.isErrorMessage()` - filters at DB read level (PRIMARY)
- Python intake: `is_error_message()` in `_helpers.py` - filters in intent classification (SECONDARY)
- Python respond: Same filter in response generation (TERTIARY)

**Filters:**
- JSON error objects: `{"error": {"type": "...", "message": "..."}}`
- Plain text: "Error:", "Chyba:", "llm_call_failed", "Operation not allowed"

**Files:**
- `backend/server/.../service/chat/ChatHistoryService.kt`
- `backend/service-orchestrator/app/graph/nodes/_helpers.py`
- `backend/service-orchestrator/app/graph/nodes/intake.py`
- `backend/service-orchestrator/app/graph/nodes/respond.py`

---

### Enhanced Logging & Health Check Suppression

**Deployed:** 2026-02-13

**What changed:**
- Suppressed MongoDB DEBUG logs (pymongo heartbeat spam) in Orchestrator
- Suppressed health check logs in Orchestrator and Ollama Router
- Added detailed orchestration progress logging (NODE_START, NODE_END, DONE, ERROR)

**Logging added:**
- `ORCHESTRATION_START` - task begins (with query preview)
- `ORCHESTRATION_NODE_START` - each node execution (with human-readable message)
- `ORCHESTRATION_NODE_END` - node completion
- `ORCHESTRATION_DONE` - final result (with metrics)
- `ORCHESTRATION_INTERRUPTED` - approval requests
- `ORCHESTRATION_ERROR` - full exception details with type

**Files:**
- `backend/service-orchestrator/app/main.py` - Logging filters + progress events
- `backend/service-ollama-router/app/main.py` - Health check filter

**Benefits:**
- Clean logs without noise (no MongoDB/health spam)
- Detailed orchestration flow visibility for debugging
- Easier to trace errors to specific nodes

---

### Deployment Scripts

All services have dedicated build scripts in `k8s/`:

| Script | Service | Type |
|--------|---------|------|
| `build_server.sh` | Kotlin server | Gradle + Docker + K8s deploy |
| `build_orchestrator.sh` | Orchestrator (Python) | Docker + K8s deploy |
| `build_kb.sh` | Knowledge Base (Python) | Docker + K8s deploy |
| `build_whisper.sh` | Whisper (Python) | Docker push only (Job-only) |
| `build_ollama_router.sh` | Ollama Router (Python) | Docker + K8s deploy (NEW) |

**Helper scripts:**
- `build_image.sh` - Generic image builder for Job-only services
- `build_service.sh` - Generic builder for persistent deployments
- `redeploy_all.sh` - Restart all without rebuild
- `redeploy_service.sh` - Restart single service

---

## Summary

### Key Implementation Principles

1. **Fail-fast errors, fail-fast validation**
2. **Type-safe, idiomatic Kotlin**
3. **HTTP client selection by use case**
4. **String-based LLM integration with templating**
5. **Consistent, responsive UI with shared components**
6. **Clear module boundaries and contracts**
7. **Documentation in existing files ONLY - no status/summary files**
8. **No hard timeouts — streaming + heartbeat everywhere**: All LLM/GPU operations (Ollama, correction agent, orchestrator) MUST use streaming. Liveness is determined by whether tokens keep arriving, not by a fixed timeout. If tokens stop arriving for an extended period, the operation is considered dead and retried/reset. This principle applies to ALL LLM calls in the project.
9. **Push-based communication — no polling as primary**: All real-time progress and status changes use push callbacks (Python → Kotlin POST endpoints → Flow-based UI subscriptions). Polling is reduced to safety-net only (60s interval). Heartbeat trackers (CorrectionHeartbeatTracker, OrchestratorHeartbeatTracker) detect dead processes. Connection errors reset to retryable state, not FAILED.

### Benefits

1. **Production-ready:** Fault-tolerance + tracing
2. **Type-safe:** Compile-time checks prevent runtime errors
3. **Flexible:** Graph-based for complex workflows, functional for prototyping
4. **Observable:** Rich event system for monitoring
5. **Scalable:** Iteration limits and context window management
6. **Cost efficient:** GPU models only when necessary
7. **User priority:** Preemption ensures immediate response