# Jervis – Quick Start Guidelines

**Status:** Production (2026-03-01)
**Purpose:** Essential rules and patterns for coding agents

---

## What is Jervis?

**Jervis** is an autonomous AI assistant built on Kotlin Multiplatform + Python LangGraph orchestrator.

- **UI**: Compose Multiplatform (Desktop/Android/iOS), Czech language
- **Backend**: Kotlin Spring Boot + Python microservices
- **Data**: MongoDB (tasks/clients/projects), ArangoDB (knowledge graph), Weaviate (RAG)
- **AI**: LiteLLM + Ollama, GPU/CPU routing, 20+ specialized agents

**Core mission**: Autonomously manage software projects — indexing code/JIRA/Confluence/Git/emails, qualifying tasks, dispatching agents, executing code changes, creating PRs, tracking deadlines.

---

## Critical Rules

### 1. Database Query Principles

**ALWAYS filter in DB, NEVER in application code.**

❌ **DON'T:**
```kotlin
repository.findAll().filter { it.archived }  // Fetches ALL, filters in Kotlin
```

✅ **DO:**
```kotlin
repository.findByArchivedTrue()  // DB does the filtering
```

**Rules:**
- Spring Data derived queries first (`findByArchivedTrue`, `findByStateAndClientIdNotIn`)
- `@Query` only when Spring Data can't express it
- Never `MongoTemplate` when Spring Data can do it
- Prefer `Flow` over `List` (don't `.toList()` unless needed)

### 2. NO TRUNCATION — Results Must Never Be Trimmed

**NEVER use `[:N]` slicing or any truncation on stored results, tool outputs, KB writes, or LLM prompt content.**

The routing system handles context limits — content exceeding 48k GPU context is automatically routed to OpenRouter. There is NO reason to truncate data at the application level.

**What is FORBIDDEN:**
- `result[:500]`, `content[:2000]`, `summary[:8000]` — any hard-coded string slicing
- `_truncate_result()` functions that cut content
- Per-message character limits before storage or LLM calls

**What is ALLOWED:**
- Whole-message removal for context budget (remove oldest messages entirely, never partial content)
- LLM-based summarization (`reduce_for_prompt`) when content truly exceeds token budget
- `trim_for_display()` ONLY for UI progress indicators and debug logging — NEVER for storage or LLM prompts

**Context overflow handling:**
```
content fits GPU (≤48k) → use as-is
content > 48k → router sends to OpenRouter (up to 200k context)
content > 200k → LLM summarization (reduce_for_prompt), original saved to KB
summarization fails → suggest background task (NEVER fall back to truncation)
```

### 3. FAIL-FAST Philosophy

**Exceptions must propagate. Never hide errors.**

❌ **DON'T:**
- Try/catch inside services (except I/O boundaries)
- Generic `Result<T>` wrappers everywhere

✅ **DO:**
- Try/catch ONLY at boundaries (REST, tools, top-level handler)
- Validate input, fail fast
- Background/batch jobs: skip + warn on stale IDs, don't crash entire batch

### 4. IF-LESS Pattern

Replace `if/when` with polymorphism/sealed classes/routing tables where code might expand.

**OK for trivial cases**: `if/when` is fine for simple decisions.

### 5. Documentation is Part of the Deliverable

**After every code change, update docs BEFORE committing.**

| Changed Code | Update These Docs |
|--------------|-------------------|
| UI component/pattern | `ui-design.md` + `GUIDELINES.md` |
| Data processing/routing | `architecture.md` + `structures.md` |
| KB/graph schema | `knowledge-base.md` |
| Architecture/modules | `architecture.md` |
| Orchestrator behavior | `orchestrator-final-spec.md` or `orchestrator-detailed.md` |

### 6. Kotlin-First & Idiomatic Code

- Use coroutines + `Flow` for async work
- No "Java in Kotlin" patterns
- Prefer streaming over `List`
- Extension functions, not utils classes
- No `@Deprecated` code — refactor immediately
- **NEVER use fully-qualified class names inline.** Always add an
  `import` statement at the top of the file and use the short name.
  Forbidden: `com.jervis.dto.chat.ChatRole.USER`,
  `org.bson.types.ObjectId(s)`, `java.time.Instant.now()`.
  Required: add `import com.jervis.dto.chat.ChatRole` and write
  `ChatRole.USER`. No "single-use exception". When two short names
  collide, use `import x.y.Foo as XFoo` aliasing — never FQN.

### 7. Language

- **Code/comments/logs**: English
- **UI text**: Czech
- **Documentation**: English

### 8. Urgency signal = deadline only

**Only `deadline: Instant?` crosses service boundaries as an urgency signal.**
No `Speed` / `Bucket` / `Urgency` enum in shared DTOs, RPC, HTTP headers, or
Mongo. If urgency matters at some layer, derive it there from `(deadline, priority, now)`.
The router has a private `_Bucket` (REALTIME / URGENT / NORMAL / BATCH) inside
`router_core.py` — it must not leak out.

Reason: two representations of the same thing (deadline + speed) drift out of
sync. Canonical SSOT: `docs/architecture.md#urgency--deadline-scheduling`
and KB `agent://claude-code/task-routing-unified-design`.

### 9. Push-only data flows — UI never pulls, server pushes ready snapshots

**Every screen opens one or more kRPC `Flow<Snapshot>` streams and `collect`s.
The server pushes a fully-rendered snapshot on every relevant state change.
No refresh buttons, no reload triggers, no event→pull round-trips.**

✅ **DO:**
```kotlin
// RPC interface (shared/common-api)
@Rpc
interface IPendingTaskService {
    fun subscribeSidebar(clientId: String?): Flow<SidebarSnapshot>
    fun subscribeTask(taskId: String): Flow<TaskSnapshot>
}

// ViewModel
init {
    scope.launch {
        repository.pendingTasks.subscribeSidebar(clientId).collect { snap ->
            _sidebar.value = snap
        }
    }
}
```

❌ **DON'T:**
- `LaunchedEffect(refreshTrigger) { loadActive() }` — pull-on-event
- `JRefreshButton` in a live data view — the stream IS the refresh
- SSE/JervisEvent handler that calls `listTasksPaged` / `getChatHistory`
- Unary RPC returning a snapshot list for a live view — must be a Flow
- Caching the snapshot on screen open via `getById` — re-read goes stale

**Server side** (`backend/server`): each scope owns a
`MutableSharedFlow<Snapshot>(replay=1)` and emits a new snapshot whenever the
underlying data changes (repository save hooks or Mongo change streams).
Subscribers get the replayed latest on connect and every subsequent change.

**Filter/scope is a stream parameter**, not a client-side filter. Changing the
filter opens a new stream; the old collector cancels.

**Write operations** (markDone, reopen, sendMessage, cancel…) remain unary
and return only an `Ack`/domain object. The UI sees the change via the
already-open subscription — never re-fetch after write.

**Reconnect**: kRPC-over-WebSocket reconnects on `RpcConnectionManager.generation++`.
ViewModels restart collectors in the reconnect handler; `replay=1` delivers the
current snapshot immediately so the UI is never empty.

**Still allowed (explicitly):**
- Unary RPC for ingestion / writes / one-shot reads (file download, "load more"
  pagination for historical data like "Hotové úlohy" archive)
- `JRefreshButton` in Settings CRUD forms (static config, not a live view)
- `LaunchedEffect(param)` that **opens** a stream — only the initial subscribe
  is a launch; the data flow itself is push

SSOT: `docs/ui-design.md` §"Reactive data streams".

### 10. gRPC everywhere, REST only at system boundaries

**Internal pod-to-pod communication is gRPC over h2c.** REST is reserved
for (a) external APIs (GitHub, Atlassian, O365…), (b) Prometheus
`/metrics`, (c) K8s `/health` readiness/liveness probes, and (d) Ollama
compatibility (`service-ollama-router` exposes `/api/*` so `ollama` CLI
and third-party libraries can route through it).

Every other hop — Kotlin server ↔ Python service, VD GPU workloads,
browser pods — speaks gRPC on the `5501` pod-to-pod family. When two
services coexist on the same host (e.g. XTTS + Whisper on the VD),
allocate the next free port (5502…) so both can bind; don't let one
silently lose the race at startup.

Changes that touch `proto/**/*.proto` or anything under
`libs/jervis_contracts/jervis/**/*_pb2*.py` are **breaking across every
consumer**. Build order matters:

1. Regenerate stubs (`make proto-generate` / `./gradlew protoGenerate`).
2. Full rebuild — `./gradlew jervisBuildAll` + `./k8s/build_all.sh` or
   at minimum every service that imports the changed stub (server,
   orchestrator, KB, MCP, ollama-router, browser pods, TTS, Whisper,
   etc.). Partial rebuilds leave half the fleet with mismatched wire
   formats and will crash at the first RPC.
3. Redeploy **all** dependents in one sweep — skipping one produces
   runtime `VersionError` / `Detected mismatched Protobuf Runtime` at
   the first import.

### 11. No dict/map shuttles between proto and domain code

**Hard limit.** Data that crosses a service boundary travels as a
**typed Protobuf message** end-to-end. Inside the service the proto
object is handed to typed helpers — repositories, Pydantic/POJO
domain models, `@dataclass`, Kotlin `data class`. Shuttle containers
(`dict[str, Any]`, `Map<String, Any>`, raw `JsonObject`, Python
`**kwargs` builders) are **forbidden** as the primary typed surface.

Why: every time we let a mapping of raw fields bridge a proto ↔
domain gap we recreate the same class of bug — a field on one side
has a default, the other side overwrites it with `None`, and the
wire validation blows up in production. Already burned twice:

- KB `observedAt` — Python `datetime` default overwritten by
  `str(...) or None` in the gRPC translator (commit `eca691293`).
- WhatsApp `SessionInitRequest.user_agent` — Pydantic `str`
  default clobbered by `request.user_agent or None`
  (commit `56518e6a5` introduced a dict workaround, cleaned up in
  this commit by removing the Pydantic mirror entirely).

#### Rules

- **Use the proto type directly** inside the handler. Proto3 scalar
  defaults (`""`, `0`, `false`, `[]`) are the wire-level "unset" —
  read them as such and fall back to a module-level constant, never
  a parallel Pydantic model with its own default that you then have
  to reconcile.
- **If you need richer behaviour** (validators, derived properties),
  add a typed wrapper around the proto (`@dataclass` from proto,
  or a Pydantic model that takes the proto in its `__init__`) — not
  a dict.
- **Constants live in `app/config.py` / Kotlin `*Properties`.**
  Never inline magic strings for defaults inside a translator.
- **No `Map<String, Any>`** across Kotlin service boundaries either.
  If a payload is polymorphic, add a proto `oneof`; if it's truly
  opaque, make the proto field `bytes` + declare the content-type in
  a sibling `string content_type` — but keep the envelope typed.
- **No `**kwargs` conditional builders** around a typed constructor.
  Build the proto, pass the proto. Conditional branches belong in
  the constants / fallback, not in the kwargs dict.

#### Review gate

If a PR introduces `dict[str, Any]`, `Map<String, Any>`, or
`init_kwargs = {...}` *anywhere* along an RPC entry/exit path, it
bounces. Same for new Pydantic/data-class mirrors of a proto message
that could just read the proto directly.

### 12. Per-connection browser pods (Teams, WhatsApp, future O365-likes)

**One pod per Connection. Autonomous ReAct agent. DOM-first. Router-only LLM.**

- **Autonomy:** one pod = one `ConnectionDocument._id`. The agent decides when
  and what to observe, scrape, notify. Server never pulls from pod — the pod
  pushes and writes directly to Mongo.
- **DOM-first observation:** `inspect_dom` is the primary observation tool
  (with shadow DOM pierce, read-only). `look_at_screen` (VLM) is a fallback
  for ambiguous DOM, login screens, and a 5-minute silence sanity scan. Never
  default to VLM when DOM works.
- **Persistent tabs:** the agent never closes and reopens tabs — login tab +
  siblings share auth cookies for the whole session.
- **Router-only LLM/VLM:** all calls go through `jervis-ollama-router`
  (`/route-decision`). No provider-direct calls, no hardcoded tiers. Pass
  `client_id` + capability; router resolves the rest.
- **Message ledger:** pod owns `<provider>_message_ledger` (per connection +
  chat): `lastSeenAt`, `unreadCount`, `unreadDirectCount`, `lastUrgentAt`. The
  server reads for UI badges and urgency triggers.
- **Direct message = urgent:** any 1:1 message fires `kind=urgent_message`
  via `POST /internal/<provider>/notify` → USER_TASK `priorityScore=95`,
  `alwaysPush=true`, FCM+APNs. Server dedups per `(connectionId, chatId)` in
  a 60s window.
- **Discovered resources → project assignment:** pod writes to
  `<provider>_discovered_resources`. UI lists them via
  `GET /internal/<provider>/discovered-resources` and the user binds each to a
  `Project` via `ProjectResource`. No auto-binding.
- **Read-only phase:** Phase 1 capabilities are `*_READ` only. No
  `CHAT_SEND` / `EMAIL_SEND` / `CALENDAR_WRITE` mappings in pod code until the
  send phase lands with approval flow.
- **No legacy:** if you rename/retire a module, delete the file. No
  `@Deprecated` shims, no re-exports, no "legacy fallback" branches.

SSOT specs: `docs/teams-pod-agent.md`, `docs/whatsapp-connection-design.md`.

---

## Architecture Quick Reference

### Module Structure

```
jervis/
├── shared/
│   ├── common-dto/           # DTOs shared by UI + server
│   ├── common-api/           # @HttpExchange interfaces (UI ↔ server)
│   ├── domain/               # Business logic (repositories, domain models)
│   └── ui-common/            # Compose UI (screens, components, design system)
├── backend/
│   ├── server/               # Kotlin Spring Boot server
│   ├── service-orchestrator/ # Python LangGraph orchestrator
│   ├── service-knowledgebase/# Python KB (Weaviate + ArangoDB)
│   ├── service-correction/   # Transcript correction
│   ├── service-whisper/      # Audio transcription
│   ├── service-mcp/          # MCP server (tools for orchestrator)
│   └── service-ollama-router/# GPU/CPU routing proxy
├── desktop/                  # Desktop app entry point
├── android-app/              # Android app entry point
└── ios-app/                  # iOS app entry point
```

### Communication Contract

- **UI ↔ Server**: ONLY `@HttpExchange` in `shared/common-api`
- **Server ↔ Microservices**: REST via `@HttpExchange` in `backend/common-services`
- **No UI access** to internal microservice contracts

### Task Pipeline (3-stage)

```
Polling → Indexing → Qualification → Orchestrator Dispatch → Execution
```

1. **Polling**: Download external data (JIRA, emails, Git) → MongoDB indexing collections
2. **Indexing**: Process → KB (graph + RAG) → create pending tasks
3. **Qualification**: KB callback (`/internal/kb-done`) evaluates filtering rules → QUEUED or DONE
4. **Orchestrator**: Python LangGraph agent executes task

### Directory Structure

**DirectoryStructureService is SINGLE SOURCE OF TRUTH for all paths.**

```
{data}/clients/{clientId}/projects/{projectId}/
├── git/{resourceId}/         # Agent/orchestrator workspace (stable)
├── git-indexing/{resourceId}/# Indexing temporary workspace (checkout any branch)
├── uploads/
├── audio/
├── documents/
└── meetings/
```

**Never hardcode paths. Always use `DirectoryStructureService`.**

---

## UI Design Quick Rules

### Adaptive Layout

```
COMPACT_BREAKPOINT_DP = 600

Compact (<600dp, phone):   full-screen list→detail, JTopBar with back arrow
Expanded (≥600dp, tablet): 240dp sidebar + content side-by-side
```

**Decision tree:**
- Category navigation → `JAdaptiveSidebarLayout`
- Entity CRUD list → `JListDetailLayout` + `JDetailScreen`
- Flat list with actions → `LazyColumn` + `JActionBar` + state components
- Edit form → `JDetailScreen` (provides back + save/cancel)

**Forbidden:**
- `Card(elevation/surfaceVariant)` → use `CardDefaults.outlinedCardBorder()` or `JCard`
- `TopAppBar` directly → use `JTopBar`
- `IconButton` without 44dp size
- Platform expect/actual for layout decisions (use `BoxWithConstraints`)
- Duplicating helpers (check `ClientsSharedHelpers.kt` first)

### Key Components

| Component | Purpose |
|-----------|---------|
| `JAdaptiveSidebarLayout` | Category navigation (settings sections) |
| `JListDetailLayout` | Master-detail for entity lists |
| `JDetailScreen` | Edit form scaffold (back + save/cancel) |
| `JTopBar` | Top bar with back/title/actions |
| `JActionBar` | Floating action button row |
| `JCenteredLoading` | Loading state |
| `JEmptyState` | Empty state |
| `JErrorState` | Error state |

**Design system**: `shared/ui-common/.../design/` (DesignTheme, DesignLayout, DesignButtons, DesignCards, DesignForms, DesignDialogs, DesignDataDisplay, DesignState)

**Full reference**: `docs/ui-design.md` (SSOT)

---

## Common Patterns

### Error Handling

```kotlin
// ❌ DON'T: catch inside service
fun fetchData() {
    try {
        val data = api.call()
    } catch (e: Exception) {
        logger.error("Failed", e)
        throw e  // Useless try/catch
    }
}

// ✅ DO: let exceptions propagate, catch at boundary
suspend fun apiHandler(call: ApplicationCall) {
    try {
        val result = service.fetchData()
        call.respond(result)
    } catch (e: Exception) {
        logger.error("API error", e)
        call.respond(HttpStatusCode.InternalServerError, ErrorDto(e.message))
    }
}
```

### Streaming vs List

```kotlin
// ❌ DON'T: toList() unless needed
val all = repository.findAll().toList()  // Loads everything into RAM

// ✅ DO: use Flow
val flow: Flow<Entity> = repository.findAll()
flow.collect { entity -> process(entity) }  // Streams, doesn't load all
```

### Repository Queries

```kotlin
// ❌ DON'T:
@Query("{ 'clientId': ?0, 'archived': false }")
fun findByClientIdAndArchivedFalse(clientId: String): Flow<Client>

// ✅ DO: use derived query
fun findByClientIdAndArchivedFalse(clientId: String): Flow<Client>  // Spring Data generates query
```

---

## Build Notes

- **No network in CI/sandbox** — cannot run Gradle
- **`DOCKER_BUILD=true`** skips Android/iOS/Compose targets
- **Verify code manually** against DTO fields and repository API signatures

---

## Pull Request Checklist

- [ ] Code changes done
- [ ] Relevant docs updated (ui-design, architecture, structures, knowledge-base, orchestrator-*)
- [ ] No duplicated helpers (check `ClientsSharedHelpers.kt`)
- [ ] All interactive elements ≥ 44dp touch target
- [ ] Cards use `CardDefaults.outlinedCardBorder()` or `JCard`
- [ ] Loading/empty/error states use `JCenteredLoading` / `JEmptyState` / `JErrorState`
- [ ] No hardcoded paths (use `DirectoryStructureService`)
- [ ] DB queries filter at DB level (no `.filter {}` in Kotlin)

---

## Read Next

- **System architecture**: `docs/architecture.md` (modules, services, workspace, pipelines)
- **Data structures**: `docs/structures.md` (task pipeline, background engine, CPU/GPU routing)
- **Orchestrator**: `docs/orchestrator-final-spec.md` + `orchestrator-detailed.md` (Python LangGraph, agents, tools)
- **Knowledge Base**: `docs/knowledge-base.md` (graph schema, RAG, ingest)
- **UI Design**: `docs/ui-design.md` (SSOT for layout, components, patterns)
- **Implementation**: `docs/implementation.md` (detailed conventions)
