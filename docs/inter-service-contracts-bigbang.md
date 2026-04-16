# Inter-Service Contracts — Big-Bang Migration Plan

> **Status (2026-04-16)**: READY — awaiting transport decision.
> Companion to `docs/inter-service-contracts.md` (overall SSOT).
> This document enumerates every endpoint being migrated, the order of
> implementation, and the open transport-stack decision surfaced by the
> maturity audit.

---

## 0. Open decision — transport stack

The maturity audit flagged the originally chosen **ConnectRPC** as not production-ready in April 2026:

| Component | State | Verdict |
|---|---|---|
| **Wire 6.2.0** | March 2026 stable, KMP full | **PROD** |
| **Buf CLI 1.68.1** | April 2026 stable | **PROD** |
| **build.buf Gradle plugin 0.11.0** | Jan 2026 stable | **PROD** |
| **connect-kotlin 0.8.0** | Last functional release **March 2024**, dependabot-only since | **STALE** |
| **connect-python 0.9.0** | Beta; `connect-python` package renaming to `connectrpc`; **1.0 rewrite by Buf is in-flight** | **PRE-1.0 RISK** |
| **betterproto v1** | Last release May 2021 | **DEAD** |
| **betterproto2 0.9.1** | Sub-1.0, "breaking changes expected" | **NOT PRIMARY** |

### Three options (pick one, then we execute)

**Option A — gRPC over H2c (plain gRPC in cluster).** Replace ConnectRPC with standard gRPC. Kotlin uses `grpc-java` + `grpc-kotlin` (Google, rock-solid, coroutines). Python uses `grpcio` + `grpcio-tools` (Google, bulletproof, async-native). Schema still Protobuf + Buf. Debugging via `grpcurl` instead of `curl`. HTTP/2 cleartext inside K8s — ingress and every Python ASGI server supports it.
- **Pros**: Zero beta dependencies; server-streaming is boring and reliable; enforcement identical to Connect (same `buf breaking`).
- **Cons**: No JSON fallback (but `grpcurl -d @ -plaintext ...` works; equivalent debuggability). Slightly heavier Docker images (`grpcio` native wheel).

**Option B — ConnectRPC as originally speced.** Keep HTTP/1.1 + JSON body. Use `connect-kotlin 0.8.0` + `connect-python 0.9.0`.
- **Pros**: JSON wire, `curl`-debuggable.
- **Cons**: connect-kotlin has been maintenance-only since March 2024 (2 years inactive); connect-python 1.0 rewrite will force a migration mid-year with breaking changes. Starting big-bang on a pre-1.0 runtime with a silent Kotlin client is a risk the project will inherit.

**Option C — FastAPI + SSE + OpenAPI-generated clients.** Drop Protobuf. Pydantic v2 is SSOT. FastAPI exposes `/openapi.json`. `openapi-kotlin-generator` produces the Kotlin client in `shared/service-contracts/`. CI runs every service's OpenAPI export vs committed snapshot, blocks drift.
- **Pros**: No new binary protocol. FastAPI stays. Pydantic is our most-used Python runtime. SSE already works (whisper, chat).
- **Cons**: Weaker enforcement — OpenAPI `breaking` detection is less canonical than `buf breaking`; schema expressiveness (oneof, well-known types) is weaker than proto; no true streaming typing.

### Recommendation: **Option A (gRPC H2c)**

It preserves the single-SSOT, breaking-change-enforcement core of the original decision while replacing the beta runtime with the battle-tested reference implementation. The change is minimal at the schema layer — the exact same `.proto` files work under both Connect and gRPC. The change affects **only the generated stub layer** (`grpc-kotlin` vs `connect-kotlin`, `grpcio` vs `connect-python`).

If you confirm Option A, `docs/inter-service-contracts.md` §2 (Stack) and §4 (Transport) are updated with a single commit. Everything else in that SSOT stays as written.

**I need your pick on Option A / B / C before starting work.**

---

## 1. Scope

Big-bang replaces every HTTP contract between service pods in one coordinated change. Across the audit we counted **~170 distinct HTTP call sites**:

| Source | Target | Surface |
|---|---|---|
| Kotlin server `/internal/*` | consumed by Python orchestrator + 7 other Python pods | **67** endpoints across 13 Ktor routing files |
| Kotlin server `/api/v1/*` | public voice API (Siri, Watch) | **4** endpoints |
| Python `service-knowledgebase` FastAPI | consumed by Kotlin server + orchestrator + correction + mcp | **~25** endpoints, 40+ Pydantic models |
| Python `service-orchestrator` FastAPI | consumed by Kotlin server + UI voice | **~20** endpoints incl. 3 SSE streams |
| Python `service-ollama-router` FastAPI | consumed by Kotlin + orchestrator + whisper | **~22** endpoints (proxy + admin) |
| Python `service-whisper` FastAPI | consumed by Kotlin + orchestrator | **3** endpoints incl. SSE multipart |
| Python `service-correction` FastAPI | consumed by Kotlin | **8** endpoints, untyped dicts |
| Python `service-document-extraction` FastAPI | consumed by Kotlin | **3** multipart endpoints |
| Python `service-meeting-attender` FastAPI | consumed by Kotlin | **4** endpoints |
| Python `service-o365-browser-pool` FastAPI | consumed by Kotlin + UI VNC proxy | **~15** endpoints + WebSocket VNC |
| Python `service-whatsapp-browser` FastAPI | consumed by Kotlin + UI VNC proxy | **~15** endpoints + WebSocket VNC |
| Python `service-visual-capture` FastAPI | consumed by Kotlin meeting flow | **7** endpoints |
| Python `service-tts` | consumed by UI directly + orchestrator | **2** endpoints (batch + SSE) |
| Python `service-meeting-attender` / pod callbacks | → Kotlin `/internal/meeting/*` | **3** recording bridge endpoints |
| Browser-pool pod callbacks | → Kotlin `/internal/o365/*`, `/internal/whatsapp/*` | **4** session-event + capability endpoints |

**Explicitly out of scope** (stays as is):

- kRPC/CBOR for Kotlin UI ↔ Kotlin server (`shared/common-api` + `shared/common-dto`).
- MCP SSE endpoint (protocol requirement).
- Joern K8s Job orchestration (stdio + file exchange, not HTTP).
- Claude SDK runners (K8s Jobs, CLI + file exchange).
- GitHub/GitLab/Atlassian external APIs (vendor-owned contracts).
- OAuth2 callbacks (`/oauth2/*`, vendor redirect requirements).
- Public voice API multipart (`/api/v1/chat/voice`, `/api/v1/voice/stream`) — stays REST for Siri/Watch compatibility; will be wrapped with a proto-typed internal handler.
- WebSocket VNC proxies in browser pools (binary frames, websocket native).

---

## 2. Proto file catalog

One `.proto` file per logical domain; one `service XxxService` per server. All messages and services live under `jervis.<domain>`. Totals: **24 service bundles, ~170 RPCs, ~200 message types** (projection, exact count after step 1).

### 2.1 Core — router & server (Phase 1 jádro)

```
proto/jervis/
├── common/
│   ├── types.proto              # Scope, RequestContext (§"No contract data in HTTP headers"), Urn, Timestamp, IDs
│   ├── errors.proto             # ErrorDetail message for details field
│   ├── pagination.proto         # PageRequest, PageCursor
│   ├── enums.proto              # Capability, Priority, TierCap, SourceType, SourceCredibility, TaskState, GraphType
│   └── attachment.proto         # AttachmentRef (filename, mime, size, storage_path)
├── router/
│   ├── decide.proto             # RouterService.Decide (RouterDecideRequest → RouterDecision)
│   ├── chat.proto               # RouterService.Chat (streaming tokens) + Complete (non-stream)
│   ├── admin.proto              # RouterAdminService (ModelError, ModelSuccess, MaxContext, ModelErrors, ModelStats, RateLimits, TestModel, ModelReset, InvalidateClientTier)
│   ├── whisper_coord.proto      # RouterWhisperService.WhisperNotify / WhisperDone (GPU coord)
│   └── queue_status.proto       # RouterQueueService.GetQueueStatus, GpuIdle
└── server/
    ├── orchestrator_callback.proto
    │   # ServerOrchestratorCallbackService:
    │   #   - OrchestratorProgress(ProgressEvent) → Ack
    │   #   - OrchestratorStatus(StatusEvent) → Ack
    │   #   - CorrectionProgress(CorrectionProgressEvent) → Ack
    │   #   - ThinkingGraphUpdate(ThinkingUpdateEvent) → Ack
    │   #   - MemoryGraphChanged(Empty) → Ack
    │   #   - StreamingToken(TokenEvent) → Ack
    │   #   - KbProgress(KbProgressEvent) → Ack
    │   #   - KbDone(KbCompletionEvent) → Ack
    │   #   - ForegroundStart/End(Empty) → Ack
    │   #   - GpuIdle(Empty) → Ack
    │   #   - QualificationDone(QualificationEvent) → Ack
    ├── task_management.proto
    │   # ServerTaskService:
    │   #   - CreateTask, GetTask, GetTaskStatus, SearchTasks, RecentTasks
    │   #   - GetQueue, RespondToTask, CreateWorkPlan, Retry, Cancel
    │   #   - SetPriority, MarkDone, Reopen, PushNotification
    │   #   - AgentDispatched, AgentCompleted, BackgroundResult
    │   #   - TasksByState, DispatchCodingAgent, DismissUserTasks
    │   #   - CreateBackgroundTask, CreateMergeRequest, GetMergeRequestDiff
    │   #   - PostMrInlineComments, PostMrComment, ResolveReviewLanguage
    ├── chat_approval.proto
    │   # ServerChatApprovalService:
    │   #   - Broadcast(ApprovalBroadcast) → Ack
    │   #   - Resolved(ApprovalResolved) → Ack
    ├── meetings.proto
    │   # ServerMeetingService:
    │   #   - ListUpcoming, Approve, Deny, SetPresence
    │   #   - GetTranscript, ListMeetings, UnclassifiedCount
    │   #   - Classify
    │   # ServerMeetingRecordingBridge:
    │   #   - StartRecording, UploadChunk, FinalizeRecording
    ├── o365.proto
    │   # ServerO365SessionService (pod callbacks):
    │   #   - SessionEvent, CapabilitiesDiscovered, Notify
    │   # ServerO365DiscoveredResourcesService (UI):
    │   #   - ListDiscoveredResources
    │   #   - GetUserLastActivity
    ├── whatsapp.proto
    │   # ServerWhatsappSessionService: SessionEvent, CapabilitiesDiscovered
    ├── clients_projects.proto
    │   # ServerClientProjectService:
    │   #   - ListClientsProjects, ListClients, CreateClient
    │   #   - ListProjects, CreateProject, UpdateProject
    │   #   - ListConnections, CreateConnection
    ├── environments.proto
    │   # ServerEnvironmentService: 13 RPCs (List, Get, Create, Delete, AddComponent, ConfigureComponent, Deploy, Stop, Sync, Status, Clone, AddPropertyMapping)
    ├── git.proto
    │   # ServerGitService:
    │   #   - CreateRepo, InitWorkspace, WorkspaceStatus
    │   #   - GetGpgKey
    ├── bugtracker.proto
    │   # ServerBugTrackerService: CreateIssue, AddComment, UpdateIssue
    ├── guidelines.proto
    │   # ServerGuidelinesService: GetMerged, Get, Set
    ├── filter_rules.proto
    │   # ServerFilterRulesService: List, Create, Delete
    ├── context.proto
    │   # ServerContextService:
    │   #   - PendingUserTasksSummary
    │   #   - UserTimezone
    │   #   - UnclassifiedMeetingsCount
    ├── finance.proto
    │   # ServerFinanceService:
    │   #   - GetSummary, ListRecords, CreateRecord
    │   #   - ListContracts
    ├── time.proto
    │   # ServerTimeService: LogTime, GetCapacity, GetSummary
    ├── user_tasks.proto
    │   # ServerUserTaskService: Search, Get, Respond, Dismiss
    ├── proactive.proto
    │   # ServerProactiveService: MorningBriefing, OverdueCheck, WeeklySummary, VipAlert
    ├── cache.proto
    │   # ServerCacheService: Invalidate
    └── k8s.proto
        # ServerK8sService:
        #   - ListNamespaceResources, GetPodLogs
        #   - GetDeployment, ScaleDeployment, RestartDeployment
        #   - GetNamespaceStatus
```

### 2.2 Other Python services (Phase 2+)

```
proto/jervis/
├── orchestrator/
│   ├── orchestrate.proto        # Orchestrate (streaming events), Qualify, Status, Approve, Resume, Cancel, Interrupt, Health, Maintenance
│   ├── graph.proto              # GetGraph, DeleteVertex, UpdateVertex, CreateVertex, GraphCleanup, PurgeStale
│   ├── chat.proto               # OrchestratorChatService.Chat (streaming tokens, tool_call, approval), Stop
│   ├── voice.proto              # VoiceService.Process (streaming), Hint
│   ├── companion.proto          # CompanionService.Adhoc, AdhocStatus, StartSession, SessionEvent, StopSession, StreamSession (server-streaming)
│   ├── memory.proto             # MemoryService.Search
│   └── meeting.proto            # OrchestratorMeetingService.Start, Stop, Chunk, Status
├── knowledgebase/
│   ├── retrieve.proto           # KnowledgeRetrieveService.Retrieve, RetrieveSimple, RetrieveHybrid, Traverse, AnalyzeCode
│   ├── graph.proto              # KnowledgeGraphService.GetNode, SearchNodes, GetNodeEvidence, ListQueryEntities, ThoughtTraverse
│   ├── ingest.proto             # KnowledgeIngestService.Ingest, IngestFull (attachments split to binary), IngestFullAsync, IngestGitStructure, IngestGitCommits, IngestCpg, Purge, JoernScan
│   ├── documents.proto          # KnowledgeDocumentService.Upload (blob via side-channel), Register, List, Get, Update, Delete, Reindex, ExtractText
│   ├── maintenance.proto        # KnowledgeMaintenanceService.RunBatch, RetagGroup, RetagProject
│   └── queue.proto              # KnowledgeQueueService.ListQueue, IngestQueue
├── whisper/
│   └── transcribe.proto         # WhisperService.Transcribe (server-streaming: progress + final), Health, GpuRelease (payload-free Transcribe uses ChunkRef from blob upload path)
├── correction/
│   └── correct.proto            # CorrectionService.Submit, Correct, List, Answer, Instruct, CorrectTargeted, Delete
├── document_extraction/
│   └── extract.proto            # DocumentExtractionService.Extract (streaming? no — one-shot; multipart handled via side-channel blob ref)
├── meeting_attender/
│   └── attend.proto             # MeetingAttenderService.Attend, Stop, ListSessions, Health
├── o365_browser_pool/
│   ├── session.proto            # O365BrowserSessionService.Get, Init, SubmitMfa, Stop
│   ├── token.proto              # O365BrowserTokenService.Get
│   ├── scrape.proto             # O365BrowserScrapeService.ListTabs, Inspect; Screenshot via blob ref
│   └── meeting.proto            # O365BrowserMeetingService.Join, Stop, ListSessions
├── whatsapp_browser/
│   ├── session.proto            # (same shape as O365)
│   └── scrape.proto             # (same shape as O365)
├── tts/
│   └── speak.proto              # TtsService.Speak (batch WAV bytes), SpeakStream (server-streaming PCM chunks)
├── visual_capture/
│   ├── capture.proto            # VisualCaptureService.Start, Stop, Snapshot
│   └── ptz.proto                # VisualCapturePtzService.Goto, ListPresets, SetPreset
└── tooling/
    └── orchestrator_tools.proto # Ollama Router proxy: transparent Ollama API stays REST (vendor contract); only router admin endpoints go via proto
```

### 2.3 Binary / blob handling

Protobuf messages are **not** for files > ~1 MiB. Every blob stays on a side channel:

| Blob source | Side channel |
|---|---|
| KB document upload (PDF/DOCX/XLSX) | `PUT /blob/kb-doc/{hash}` (REST, raw bytes) + `KnowledgeDocumentService.Register(hash, metadata)` |
| Audio recording chunk (meeting bridge) | Stays as `UploadChunk(base64)` inside proto **for now** (chunks are ~64 KiB PCM, OK) |
| Whisper input audio | `PUT /blob/audio/{id}` + `WhisperService.Transcribe(blob_id, options)` |
| Document extraction file | `PUT /blob/doc/{id}` + `DocumentExtractionService.Extract(blob_id, …)` |
| Screenshot (browser scrape) | `GET /blob/screenshot/{session}/{tab}` (response is JPEG bytes) |

The blob side channel is a thin Ktor/FastAPI handler on each pod that the proto service's message references by `blob_ref`. Not contract-bearing (schema is just `string blob_ref`), so no proto-side governance risk.

### 2.4 Streaming inventory

RPCs that require server-streaming (client-streaming/bidi: none, unless voice WS migrates):

| RPC | Purpose | Event shape |
|---|---|---|
| `RouterService.Chat` | LLM token stream | `oneof { token, done, error }` |
| `OrchestratorService.Orchestrate` | Task execution progress + final | `oneof { node_start, node_end, status_change, result, error }` |
| `OrchestratorChatService.Chat` | Chat tokens + tool calls + approvals | `oneof { token, tool_call, tool_result, approval_request, done, error }` |
| `VoiceService.Process` | Voice pipeline | `oneof { preliminary_answer, responding, token, response, stored, done, error }` |
| `CompanionService.StreamSession` | Claude companion events | `oneof { ts, content, final }` |
| `WhisperService.Transcribe` | Progress + final result | `oneof { progress, result, error }` |
| `TtsService.SpeakStream` | PCM chunks | `bytes chunk` repeated with `is_last` |

All are **unary request → stream response**. No client-streaming needed.

### 2.5 Voice WebSocket (special case)

`/api/v1/voice/ws` — bidirectional binary audio + JSON control. gRPC client-streaming / bidi works, but Apple Watch + Siri WS clients need plain WebSocket. **Stays as WebSocket**, but the frame payload JSON becomes a proto-encoded message (`jervis.orchestrator.VoiceWsFrame`) so the contract is still typed. The transport wrapper is WS, the payload schema is proto.

---

## 3. Phase order

**Phase 0 — infrastructure** (1 PR)

- `proto/` skeleton, `common/*.proto` only.
- `shared:service-contracts` Gradle module, empty except `common`.
- `libs/jervis_contracts/` Python package, empty except `common`.
- Buf config (`buf.yaml`, `buf.gen.yaml`, `buf.lock`).
- Top-level `Makefile` with `proto-lint`, `proto-breaking`, `proto-generate`, `proto-verify`.
- CI workflow `.github/workflows/proto.yml` (or equivalent existing CI) enforcing the four checks.
- All current services keep working — zero functional change.
- **Exit criteria**: `make proto-verify` passes; Kotlin and Python builds green; `./k8s/build_all.sh` unchanged and green.

**Phase 1 — jádro: router + server**

Per user directive: **router a server jsou základ, vše ostatní na tom staví.**

- `proto/jervis/router/*.proto` — every router endpoint (decide, chat, admin, whisper coord, queue status).
- `proto/jervis/server/*.proto` — every Kotlin server `/internal/*` endpoint used by Python pods (17 service bundles, see §2.1).
- Kotlin: `ServerXxxService` handlers implemented in `backend/server/src/main/kotlin/com/jervis/rpc/internal/<domain>/`. The existing `InternalXxxRouting.kt` files are deleted in the same PR.
- Kotlin: `RouterService` client consumed by `backend/server/src/main/kotlin/com/jervis/infrastructure/llm/…` (replaces `CascadeLlmClient.kt`).
- Python: `service-ollama-router/app/main.py` switches from FastAPI routes to gRPC service impl (ASGI stays for the transparent Ollama proxy which is vendor REST).
- Python: `service-orchestrator/app/tools/kotlin_client.py` fully rewritten to call every server RPC via generated stubs. All `payload = {…}` dicts deleted.
- **Exit criteria**: router calls and all `/internal/*` traffic flow over gRPC. Python orchestrator can still execute every task. `k8s/build_server.sh` + `k8s/build_orchestrator.sh` + `k8s/redeploy_service.sh ollama-router` succeed. End-to-end: a chat message gets routed, task dispatched, progress streamed back, status finalized — no dicts on wire.

**Phase 2 — KB**

- `proto/jervis/knowledgebase/*.proto`.
- `backend/service-knowledgebase/app/api/routes.py` → gRPC service impl.
- Blob side channel for `IngestFull` and `Upload` (see §2.3).
- Kotlin: `KnowledgeServiceRestClient.kt` (all 800+ lines) deleted, replaced with generated client.
- Python: `service-orchestrator/app/kb/prefetch.py`, correction agent's KB calls, MCP server's `kb_search` proxy — all rewritten.
- **Exit criteria**: every KB retrieve/ingest/traverse in prod goes via gRPC. No Pydantic KB DTOs remain in non-KB services.

**Phase 3 — orchestrator surface**

- `proto/jervis/orchestrator/*.proto`.
- Kotlin: `PythonOrchestratorClient.kt`, `PythonChatClient.kt`, `OrchestratorCompanionClient.kt` deleted.
- Python: `service-orchestrator/app/main.py` + `app/chat/router.py` + `app/voice/…` + `app/companion/routes.py` switch to gRPC.
- Streaming: `OrchestratorService.Orchestrate` replaces the push-back pattern. The `ServerOrchestratorCallbackService` from Phase 1 is kept as a fallback but consumers start preferring the stream.
- **Exit criteria**: chat UI still streams tokens; orchestrator task flow unchanged; voice still works; companion session streaming still works.

**Phase 4 — whisper + correction + document-extraction + tts + visual-capture**

- `proto/jervis/whisper|correction|document_extraction|tts|visual_capture/*.proto`.
- Each Python service migrates one-by-one in the same PR.
- Kotlin: `WhisperRestClient.kt`, `WhisperTranscriptionClient.kt`, `CorrectionClient.kt`, `DocumentExtractionClient.kt`, `TtsClient.kt` deleted.

**Phase 5 — browser pools + meeting-attender**

- `proto/jervis/o365_browser_pool|whatsapp_browser|meeting_attender/*.proto`.
- Pod-to-server callbacks migrate.
- VNC WebSocket proxy stays as plain WS with JSON control (VNC protocol requirement).

**Phase 6 — cleanup + enforcement hardening**

- Audit: no `payload = {…}` dict → HTTP anywhere. `grep -r 'httpx.AsyncClient' backend/service-*/app/` shows only blob side channels and vendor APIs.
- Audit: no `HttpClient` / `WebClient` in `backend/server` except for GitHub/GitLab/Atlassian/Ollama transparent proxy.
- `shared:common-dto` retains no Python-contract-only DTOs.
- CI adds one more check: `./gradlew :shared:service-contracts:build :backend:server:build` + `make python-build-all` must be green on every PR.

---

## 4. Build integration

Unchanged from `docs/inter-service-contracts.md` §6–§8 **except**:

- Plugins (Option A / gRPC):
  - Kotlin messages + service stubs: `protoc-gen-grpc-kotlin-stub` (Google) + `protoc-gen-java` for messages, OR `protoc-gen-grpc-java` + `protoc-gen-grpc-kotlin`. Buf plugin: `buf.build/grpc/kotlin`.
  - Python: `protoc-gen-python` (Google) + `protoc-gen-grpc-python`. Buf plugins: `buf.build/protocolbuffers/python` + `buf.build/grpc/python`.
- Kotlin runtime deps: `io.grpc:grpc-netty-shaded`, `io.grpc:grpc-kotlin-stub`, `io.grpc:grpc-protobuf`.
- Python runtime deps: `grpcio`, `grpcio-tools`, `grpcio-reflection`.
- Server container port: add `gRPC :5501` to Kotlin server alongside existing Ktor `:5500` for kRPC. Or multiplex via h2c on `:5500` with Ktor 3 `installHttp2` + gRPC Netty shared handler; simplest is separate port.

---

## 5. CI enforcement (unchanged)

Exactly as `docs/inter-service-contracts.md` §9:
1. `buf lint`
2. `buf breaking --against master`
3. `buf generate && git diff --exit-code`
4. Full Kotlin + Python build

Additional for gRPC:
5. Smoke test: spin up `shared:service-contracts` Netty in-process server with every service registered, make one round-trip per RPC using generated Python client (pytest). Catches binary-compat bugs from proto changes even when codegen is current.

---

## 6. Deployment strategy

Each phase = one merged PR = one coordinated rollout via existing `k8s/build_*.sh` scripts.

Big-bang **within a phase** (all consumers + providers redeployed together), **not across phases** (Phase 1 ships before Phase 2 starts). Rationale: Phase 1 alone touches ~17 server routing files + 1 router service + 1 orchestrator file — that is already a max-size review. Splitting jádro from KB keeps each PR reviewable.

Rolling restart: K8s `rolling` strategy on each Deployment. For ~5 seconds during rollout a pod receives a gRPC request while its client still sends REST; unavoidable without blue/green. Acceptable because (a) orchestrator retries, (b) traffic is internal, (c) phase-PR merges happen at low-traffic windows.

Rollback: single-commit revert of the phase PR + `k8s/redeploy_service.sh` for each affected service. Estimated RTO: 10 minutes.

---

## 7. Per-phase effort estimate

| Phase | Kotlin LOC moved | Python LOC moved | New proto LOC | Estimated session hours |
|---|---|---|---|---|
| 0 | ~200 (module skeleton) | ~100 (package skeleton) | ~300 (common) | 4 |
| 1 — router + server | ~4500 (17 routing files + CascadeLlmClient) | ~2500 (router main + kotlin_client.py) | ~2000 | 30–40 |
| 2 — KB | ~900 (KnowledgeServiceRestClient) | ~1500 (KB api/routes + models) | ~1500 | 15 |
| 3 — orchestrator | ~1000 (3 client files) | ~2000 (chat, voice, companion, orchestrate) | ~1500 | 15 |
| 4 — whisper+correction+ext+tts+visual | ~600 | ~1500 | ~800 | 12 |
| 5 — browser pools + attender | ~500 | ~1000 | ~600 | 10 |
| 6 — cleanup + enforcement | audit only | audit only | 0 | 4 |

Total: **~90–100 focused session hours across 6 PRs**. Assuming one PR ships per working day with review cycle, **wall-clock ~2–3 weeks**. Matches the user's direction ("big-bang"), constrained only by reviewer attention.

---

## 8. Risk ledger

| Risk | Mitigation |
|---|---|
| gRPC H2c incompatibility inside K8s ingress | Internal pod-to-pod never crosses ingress. Cluster service mesh (ClusterIP) is h2c-native. |
| grpc-kotlin Netty classloader clash with Ktor | Run gRPC on separate port; keep classloaders isolated via `grpc-netty-shaded`. |
| Python async event loop contention (FastAPI + grpc.aio same process) | Services that keep a FastAPI blob side channel run both in one asyncio loop; tested pattern in grpcio examples. |
| Whisper GPU coord via proto loses HTTP `X-Ollama-Priority` header semantics | Every `X-*` header is replaced by a `RequestContext` payload field — mandatory rule (see `inter-service-contracts.md` §"No contract data in HTTP headers"). |
| Blob side channel has its own contract that can drift | Thin handler, single route per pod, no data classes — drift cost is low and `blob_ref` → `PUT /blob/{id}` is documented in `inter-service-contracts.md` §new. |
| MCP tools calling proto services need stub copies | `libs/jervis_contracts/` editable install in `service-mcp/Dockerfile`. No extra work. |
| Two protocols in server (kRPC `:5500` + gRPC `:5501`) | Accepted. Isolated runtimes (`grpc-netty-shaded`). UI ↔ server stays Flow-native kRPC for KMP targets incl. iOS/watchOS where `grpc-kotlin` is not available. Pod ↔ pod is gRPC. Different audiences, different protocols, by design. |

---

## 9. Go/no-go checklist — status

- [x] Transport stack decision confirmed: **Protobuf + Buf + gRPC over h2c**.
- [x] `docs/inter-service-contracts.md` §2, §4, §5, §6, §7, §12, §14, §18 updated.
- [x] No contract data in HTTP headers — `RequestContext` is mandatory request field 1.
- [x] Two-port server accepted (Ktor `:5500` for kRPC UI + gRPC `:5501` for pods).
- [x] User confirmed big-bang, router + server as the core, everything else builds on top.
- [ ] Phase 0 executed (next session).

**Status: READY. Next session starts at §11.**

---

## 10. Non-goals, restated

- No coexistence of old and new HTTP per service. Each phase is a hard cut.
- No feature flags, no dual-path runtime.
- No versioning (single namespace `jervis.*`).
- No proto publishing (stays in-repo).
- No change to kRPC UI contract (`shared/common-api` + `shared/common-dto` stay authoritative).
- No rewrite of vendor adapters (GitHub/GitLab/Atlassian remain REST against external hosts).
- No attempt to unify UI and pod protocols — iOS lacks `grpc-kotlin`; migrating `common-dto` to proto is ~100h refactor for zero functional benefit.

---

## 11. Next-session start — hard-wired instructions

> This section is written for the agent that picks up the next session.
> Everything required is on disk or in memory — nothing else is needed
> from the previous session's conversation.

### 11.1 Bootstrap reading (in order)

1. `docs/inter-service-contracts.md` — architecture SSOT (stack, transport, schema, codegen, CI).
2. `docs/inter-service-contracts-bigbang.md` — this file (scope, phase order, catalog).
3. Memory: `architecture-inter-service-contracts.md` + `project-inter-service-contracts-bigbang.md`.
4. KB: `mcp__jervis-mcp__kb_search` query `"inter-service contracts big-bang"` for the stored decision record.
5. `CLAUDE.md` — project rules (deployment via `k8s/build_*.sh`, no Gradle/Docker/kubectl directly, etc.).

### 11.2 First user interaction

The user is expected to say **"jdi"** (or equivalent green-light). Before work, confirm:

- User is ready for ~4 session hours of Phase 0 work (infrastructure PR, no functional change).
- No concurrent large refactor on `backend/server` or `service-orchestrator` (run `git status` and `git log --oneline -20` to verify quiet master).
- `./gradlew :backend:server:build` is green on master before changes.

If any check fails, **stop and report** — do not start Phase 0 on a red tree.

### 11.3 Phase 0 execution — exact steps

Create these files, in this order, in one PR:

1. **`proto/buf.yaml`** — Buf module config (v2, breaking=FILE, lint=STANDARD, `PACKAGE_VERSION_SUFFIX` excepted, `enum_zero_value_suffix=_UNSPECIFIED`).
2. **`proto/buf.gen.yaml`** — codegen plugins for Java messages, grpc-java stubs, grpc-kotlin stubs, Python messages, grpc-python stubs. Output paths as spec'd in `inter-service-contracts.md` §7.
3. **`proto/buf.lock`** — pinned plugin versions (created by `buf dep update`).
4. **`proto/jervis/common/types.proto`** — `Scope`, `RequestContext` (field 1 of every request), `Urn`, `Timestamp`, ID aliases.
5. **`proto/jervis/common/enums.proto`** — `Capability`, `Priority`, `TierCap`, `SourceType`, `SourceCredibility`, `TaskState`, `GraphType` (all with `_UNSPECIFIED = 0`).
6. **`proto/jervis/common/errors.proto`** — `ErrorDetail`.
7. **`proto/jervis/common/pagination.proto`** — `PageRequest`, `PageCursor`.
8. **`proto/jervis/common/attachment.proto`** — `AttachmentRef` (filename, mime, size, storage_path/blob_ref).
9. **`shared/service-contracts/build.gradle.kts`** — Gradle module per `inter-service-contracts.md` §5 (grpc-java + grpc-kotlin + grpc-netty-shaded + grpc-services for reflection).
10. **`shared/service-contracts/src/main/kotlin/com/jervis/contracts/interceptors/ClientContextInterceptor.kt`** — fills `ctx.request_id` (UUIDv4), `ctx.issued_at_unix_ms`, converts `ctx.deadline_iso` to gRPC deadline.
11. **`shared/service-contracts/src/main/kotlin/com/jervis/contracts/interceptors/ServerContextInterceptor.kt`** — validates `ctx.scope.client_id` non-empty (except health-marked RPCs), logs RPC name + request_id.
12. **`settings.gradle.kts`** — register `:shared:service-contracts`.
13. **`libs/jervis_contracts/pyproject.toml`** — Python package with `grpcio`, `grpcio-tools`, `grpcio-reflection`, `protobuf` deps.
14. **`libs/jervis_contracts/jervis_contracts/__init__.py`** — empty package marker.
15. **`libs/jervis_contracts/jervis_contracts/interceptors/client.py`** — async unary + streaming interceptor, same semantics as Kotlin ClientContextInterceptor.
16. **`libs/jervis_contracts/jervis_contracts/interceptors/server.py`** — server-side interceptor, same semantics as Kotlin ServerContextInterceptor.
17. **`Makefile`** (top-level) — targets `proto-lint`, `proto-breaking`, `proto-generate`, `proto-verify`, `python-install-contracts`.
18. **`.github/workflows/proto.yml`** (or equivalent existing CI file) — four checks per `inter-service-contracts.md` §9.
19. **`k8s/server-deployment.yaml`** edit — expose container port `5501` for gRPC, add `grpc_health_probe`-based readiness probe on `:5501`.
20. **Dockerfile edits** for every `service-*` that will become a consumer (`service-orchestrator`, `service-ollama-router`, `service-knowledgebase`, `service-correction`, `service-whisper`, `service-mcp`) — copy `libs/jervis_contracts/` + `pip install -e`.

Run `make proto-verify`. Run `./gradlew :shared:service-contracts:build`. Run `pip install -e libs/jervis_contracts && python -c 'import jervis_contracts'`.

### 11.4 Phase 0 exit criteria

- `make proto-verify` exits 0.
- `./gradlew :shared:service-contracts:build :backend:server:build` exits 0.
- `python -c 'from jervis_contracts.common import types_pb2; print(types_pb2.RequestContext)'` succeeds.
- `./k8s/build_server.sh` unchanged output, no rollout regression.
- CI workflow green on the PR.
- **No functional change to any service.** If any pod's behavior changes, you went too far — revert the offending part.

### 11.5 Phase 1 — what to do after Phase 0 merges

Follow `docs/inter-service-contracts-bigbang.md` §3 "Phase 1 — jádro: router + server":

- Write `proto/jervis/router/*.proto` for every endpoint in `CascadeLlmClient.kt` + `service-ollama-router/app/main.py` (decide, chat, admin, whisper coord, queue status).
- Write `proto/jervis/server/*.proto` for every `/internal/*` endpoint across the 13 Ktor routing files (see §2.1 for the full catalog).
- Kotlin: delete `InternalXxxRouting.kt` files one-by-one, replace with gRPC service impl classes in `backend/server/src/main/kotlin/com/jervis/rpc/internal/<domain>/`. Wire them into the gRPC `:5501` ServerBuilder.
- Python: rewrite `service-ollama-router/app/main.py` to expose gRPC `:5501` + keep FastAPI for the transparent Ollama proxy (vendor REST) on `:11430`.
- Python: rewrite `service-orchestrator/app/tools/kotlin_client.py` from top to bottom — every `payload = {…}` dict becomes a proto message built with the generated stubs.
- Delete `CascadeLlmClient.kt` at the end of the PR.

**Exit criteria Phase 1**: chat message routing end-to-end over gRPC; every `/internal/*` call in `kotlin_client.py` flows over gRPC; `k8s/build_server.sh`, `k8s/build_orchestrator.sh`, `k8s/redeploy_service.sh ollama-router` succeed.

### 11.6 What NOT to do in the next session

- Do NOT start Phase 1 until Phase 0 has merged and deployed.
- Do NOT touch `shared/common-api` or `shared/common-dto` — those are UI contracts, unchanged.
- Do NOT migrate KB, orchestrator, whisper, etc. in Phase 0 — infrastructure only, no functional change.
- Do NOT publish proto to `buf.build` registry — stays in-repo.
- Do NOT add versioning (`v1/`, `v2/`) to proto packages.
- Do NOT try to unify UI protocol with pod protocol — already evaluated, not worth it.
- Do NOT skip the CI guardrail setup — without it Phase 1 will drift on the first endpoint.
- Do NOT run `k8s/build_all.sh` in Phase 0 unless you actually need to rebuild every image; `./gradlew build` is enough to prove the contracts module compiles.
- Do NOT create documentation files beyond updating existing `docs/*.md` when behavior changes.

### 11.7 Rollback

If Phase 0 fails post-merge (CI green, but unexpected production issue):

```bash
git revert <phase-0-sha>
./k8s/redeploy_all.sh
```

RTO: ~10 minutes. Because Phase 0 has zero functional change, revert cost is near-zero. Use this freely if anything looks wrong.
