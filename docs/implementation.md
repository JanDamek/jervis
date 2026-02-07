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

## Step 2 Verification Checklist (REMOVED)

> **HISTORICAL** - This section documented Koog qualifier pipeline verification.
> Koog framework was removed on 2026-02-07. All orchestration now uses Python orchestrator (LangGraph).
> Qualifier simplified to `SimpleQualifierAgent` that calls KB microservice directly.
> See `docs/koog-audit.md` for removal details.

~~**Status:** Production Documentation~~
~~**Last updated:** 2026-02-04~~

### 1. Compilation Check

```bash
cd /Users/damekjan/git/jervis
./gradlew backend:server:compileKotlin
```

**Expected Result:**
- ✅ Compilation succeeds for Phase 0-1 changes
- ⚠️ **Expected warnings/errors:** Phase 2 edges have type mismatch (`QualifierPipelineState` vs `ContentTypeContext`)
- This is **normal** - Phase 2 will be refactored in Step 3

**What to look for:**
- `QualifierPipelineState` class compiles
- `nodeInitState`, `nodeVisionStage1`, `nodeStoreStateBeforeDetection`, `nodeMergeDetectionResult` compile
- `onToolNotCalled` import resolves (Koog 0.5.4)

### 2. State Model Verification

**Check:** All state model files exist and compile

```bash
ls -la backend/server/src/main/kotlin/com/jervis/koog/qualifier/state/
```

**Expected files:**
- ✅ `QualifierPipelineState.kt`
- ✅ `TaskMetadata.kt`
- ✅ `ProcessingMetrics.kt`
- ✅ `IndexingState.kt`
- ✅ `RoutingDecision.kt`
- ✅ `ChunkLoopState.kt`

**Check:** State model API

```kotlin
// Initial state creation
val state = QualifierPipelineState.initial(task)

// State updates (immutable)
val updatedState = state
    .withVision(visionContext)
    .withContentType(detection, contentType)
    .withError("Some error")
    .withMetrics { it.withPhase0Duration(1000) }

// State fields accessible
val correlationId = state.taskMeta.correlationId
val visionSummary = state.vision.generalVisionSummary
val detectedType = state.contentType
```

### 3. Critical Fixes Verification

**Fix 1: Vision Context Preservation**

**What was broken (v1):**
```kotlin
// OLD nodeBuildContentTypeContext
VisionContext(
    originalText = task.content,
    generalVisionSummary = null,  // ❌ LOST Stage 1 vision!
    typeSpecificVisionDetails = null,
    attachments = task.attachments,
)
```

**What is fixed (v2):**
```kotlin
// NEW nodeMergeDetectionResult
val state = currentState ?: error("State lost!")  // ✅ Restored from strategy-scoped var
state.withContentType(detection, contentType)     // ✅ Vision preserved!
```

**How to verify:**
1. Run qualifier on task with image attachment
2. Check logs: `VISION_STAGE1_COMPLETE | hasGeneralSummary=true`
3. Check logs: `CONTENT_TYPE_DETECTED | contentType=JIRA`
4. **Assert:** State after Phase 1 has `state.vision.generalVisionSummary != null`

**Fix 2: No Dummy State**

**What was broken (v1):**
- No explicit initialization → unclear state origin
- Nodes returned isolated objects (`VisionContext`, `ContentTypeContext`)

**What is fixed (v2):**
- `nodeInitState` explicitly creates initial state
- All nodes return `QualifierPipelineState` (or prepare for it)
- No dummy objects

**How to verify:**
- Trace through nodes: each node receives state, returns state
- No `State(baseNodeKey = "dummy", ...)` anywhere in Phase 0-1

### 4. Logging Verification

**Run qualifier agent on test task:**

**Expected log flow:**
```
🏁 PIPELINE_INIT | correlationId=abc-123 | contentLength=500 | attachments=2
🔍 VISION_STAGE1_START | correlationId=abc-123 | totalAttachments=2 | visualAttachments=1
🔍 VISION_MODEL_SELECTED | correlationId=abc-123 | model=qwen3-vl:latest
🔍 VISION_STAGE1_COMPLETE | correlationId=abc-123 | hasGeneralSummary=true | duration=2500ms
📋 CONTENT_TYPE_DETECTED | correlationId=abc-123 | contentType=JIRA | reason='Contains JIRA key...'
```

**What to check:**
- ✅ All logs use `correlationId` from `state.taskMeta.correlationId`
- ✅ Duration tracking: `duration=XXXms` appears
- ✅ Vision skip case: If no images → `VISION_SKIP | reason=no_visual_attachments`
- ✅ Detection fallback: If LLM fails → `CONTENT_TYPE_DETECTION_FAILED | error=...`

### 5. Edge Flow Verification

**Check:** Edges are defined in correct order

```kotlin
// Expected edge sequence:
edge(nodeStart forwardTo nodeInitState)
edge(nodeInitState forwardTo nodeVisionStage1)
edge(nodeVisionStage1 forwardTo nodeStoreStateBeforeDetection)
edge(nodeStoreStateBeforeDetection forwardTo nodePrepareContentTypePrompt)
edge(nodePrepareContentTypePrompt forwardTo nodeDetectContentType)
edge(nodeDetectContentType forwardTo nodeMergeDetectionResult)
```

**Verify:** No cycles, no missing edges

**Check:** Edges to Phase 2 (temporary, expected to fail)
```kotlin
edge((nodeMergeDetectionResult forwardTo nodePrepareEmailPrompt).onCondition { state ->
    state.contentType == ContentType.EMAIL
})
// ... etc
```

**Expected:** Type error (will be fixed in Step 3)

### 6. Documentation Verification

**Files created/updated:**
- ✅ `docs/qualifier/README.md` - Architecture overview (Koog 0.5.4)
- ✅ `docs/qualifier/koog-notes.md` - Condition semantics, state preservation patterns
- ✅ `docs/qualifier/strategy-graph.md` - Phase 0-1 detail, edge evaluation order
- ✅ `docs/qualifier/STEP2_CHECKLIST.md` (this file)

**Check:** All docs reference Koog 0.5.4 (not 0.5.3)

**Check:** `koog-notes.md` has correct condition semantics:
- ✅ `onToolCall` - LLM called tool
- ✅ `onToolNotCalled` - LLM did NOT call tool (Koog 0.5.4)
- ✅ `onAssistantMessage` - text response
- ✅ `onMultipleToolCalls` - multiple tools

### 7. Known Issues (Expected, To Be Fixed Later)

#### Issue 1: Phase 2 Edges Will Fail

**Problem:** Phase 2 nodes (`nodePrepareEmailPrompt`, etc.) expect `ContentTypeContext`, but receive `QualifierPipelineState`.

**Error Example:**
```
Type mismatch: inferred type is QualifierPipelineState but ContentTypeContext was expected
```

**Fix:** Step 3 (Phase 2 refactoring)

**Workaround:** None needed - this is expected.

#### Issue 2: Vision Stage 2 Not Implemented

**Problem:** Type-specific vision details not extracted yet.

**Impact:** `state.vision.typeSpecificVisionDetails` stays `null` for all content types.

**Fix:** Step 4 (add `nodeVisionStage2` after content type detection)

#### Issue 3: Chunk Loop Has Dummy State

**Problem:** `nodeAdvanceToNextChunk` returns dummy `ChunkProcessingState`.

**Impact:** Chunk processing will fail after first chunk.

**Fix:** Step 7 (refactor chunk loop with `ChunkLoopState` wrapper)

#### Issue 4: Routing Uses Wrong Conditions

**Problem:** Routing subgraph uses `onAssistantMessage` instead of `onToolNotCalled`.

**Impact:** Non-deterministic routing behavior.

**Fix:** Step 8 (refactor routing with `onToolNotCalled`)

### 8. Success Criteria (Phase 0-1 Only)

- ✅ **Compilation:** Phase 0-1 code compiles without errors
- ✅ **State Model:** All state classes exist and are used correctly
- ✅ **Vision Context:** NEVER lost after Stage 1 (verified in logs)
- ✅ **Logging:** All Phase 0-1 logs use `correlationId` from state
- ✅ **Duration Tracking:** Phase 0 and Phase 1 durations recorded
- ✅ **Documentation:** All docs updated with Koog 0.5.4 references
- ✅ **Edges:** Main flow edges defined correctly
- ⚠️ **Phase 2-4:** Expected to fail (not yet refactored)

### 9. What Happens Next

**Step 3 (Phase 2):**
- Refactor all `nodePrepare*` nodes to accept `QualifierPipelineState`
- Fix all `nodeBuild*IndexingContext` nodes to preserve vision
- Update edges from `nodeMergeDetectionResult` to Phase 2

**Step 4 (Vision Stage 2):**
- Add `nodeVisionStage2` after content type detection
- Edge: `nodeMergeDetectionResult → nodeVisionStage2 → Phase 2`
- Condition: Only if visual attachments + contentType != GENERIC

**Steps 5-10:**
- Phase 3 (Indexing), Phase 4 (Routing), History Compression, Final Docs

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
   - `asyncio.Semaphore(1)` – `/orchestrate/stream` returns 429 if busy
   - `/approve/{thread_id}` fire-and-forget: `asyncio.create_task()` + semaphore
   - `/health` returns `{"busy": true/false}` for diagnostics

3. **`PythonOrchestratorClient.approve()`** is now fire-and-forget (returns `Unit`).
   Python returns `{"status": "resuming"}` immediately. Result polled via GET /status.

### Key Technical Details

- `TaskStateEnum.PYTHON_ORCHESTRATING`: New state for dispatched tasks
- `TaskDocument.orchestratorThreadId`: Links to LangGraph checkpoint
- MongoDB collections: `checkpoints` (LangGraph), `tasks` (TaskDocument)
- K8s env: `MONGODB_URL` in `k8s/app_orchestrator.yaml` (from secrets)

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

### Benefits

1. **Production-ready:** Fault-tolerance + tracing
2. **Type-safe:** Compile-time checks prevent runtime errors
3. **Flexible:** Graph-based for complex workflows, functional for prototyping
4. **Observable:** Rich event system for monitoring
5. **Scalable:** Iteration limits and context window management
6. **Cost efficient:** GPU models only when necessary
7. **User priority:** Preemption ensures immediate response