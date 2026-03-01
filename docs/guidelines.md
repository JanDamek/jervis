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

### 2. FAIL-FAST Philosophy

**Exceptions must propagate. Never hide errors.**

❌ **DON'T:**
- Try/catch inside services (except I/O boundaries)
- Generic `Result<T>` wrappers everywhere

✅ **DO:**
- Try/catch ONLY at boundaries (REST, tools, top-level handler)
- Validate input, fail fast
- Background/batch jobs: skip + warn on stale IDs, don't crash entire batch

### 3. IF-LESS Pattern

Replace `if/when` with polymorphism/sealed classes/routing tables where code might expand.

**OK for trivial cases**: `if/when` is fine for simple decisions.

### 4. Documentation is Part of the Deliverable

**After every code change, update docs BEFORE committing.**

| Changed Code | Update These Docs |
|--------------|-------------------|
| UI component/pattern | `ui-design.md` + `GUIDELINES.md` |
| Data processing/routing | `architecture.md` + `structures.md` |
| KB/graph schema | `knowledge-base.md` |
| Architecture/modules | `architecture.md` |
| Orchestrator behavior | `orchestrator-final-spec.md` or `orchestrator-detailed.md` |

### 5. Kotlin-First & Idiomatic Code

- Use coroutines + `Flow` for async work
- No "Java in Kotlin" patterns
- Prefer streaming over `List`
- Extension functions, not utils classes
- No `@Deprecated` code — refactor immediately

### 6. Language

- **Code/comments/logs**: English
- **UI text**: Czech
- **Documentation**: English

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
3. **Qualification**: SimpleQualifierAgent decides: actionable? complex? → route to orchestrator
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
