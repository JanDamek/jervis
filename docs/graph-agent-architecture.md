# Agent Architecture — Paměťová mapa + Myšlenková mapa

> SSOT for how the unified Agent and the Memory/Thinking Maps work in Jervis.
> All interactions (chat AND background) run through this architecture.

---

## Core Concept

**Paměťová mapa (Memory Map)** = global persistent DAG — everything Jervis knows and has worked on.
One per user, lives in RAM with periodic DB flush.

**Myšlenková mapa (Thinking Map)** = per-task decomposition sub-graph linked to the Memory Map.
Each vertex solves a focused part of a problem with limited context (48k GPU).
Edges carry processed results (summaries), not raw data.

**Agent** = ONE unified system for everything. No separate "chat handler" or "orchestrator".

### Key Principles

1. **Chat = assistant** — continues seamlessly where we left off
2. **One global Paměťová mapa** (one per user, never changes) — everything is part of it
3. **Tasks are solved by moving through the map** (vertex by vertex), not as a whole
4. **Complete graph persisted to DB** per task — task can be BLOCKED, info stays in the map
5. **After resolution, returns to where it was** in the map
6. **Small context per vertex + summaries on edges** = consistent solutions to large problems
7. **With 128k or 256k context**, we reach extremely complex solutions that remain consistent

### Context Management (CRITICAL)

- **Background = ALWAYS GPU = 48k context max**
- Start in the map MUST fit into 48k (memory map summary + vertex context)
- By decomposing into sub-vertices, context per vertex DECREASES (focus)
- **Edge = SUMMARY** — reduced result, not raw data
- Large contexts → coding agents (Claude CLI, future Kilo Code)
- Coding agent gets a focused task from vertex, not the entire map
- **Persistence is for restart only** — agent holds graph in RAM
- Loads from DB only the individual TaskDocuments that edges reference

---

## Architecture Overview

```
PAMĚŤOVÁ MAPA (Memory Map — global singleton, in RAM, DB = backup)
│
├─ [REQUEST] "How does auth work?" → response summary (COMPLETED)
│
├─ [REQUEST] "Add logout button" → dispatch coding agent
│   └─ [TASK_REF] → Myšlenková mapa: task-69a98... (PROCESSING)
│       ├─ [PLANNER] decompose → 3 steps
│       ├─ [EXECUTOR] implementation → code
│       ├─ [ASK_USER] "Where to add button?" → BLOCKED
│       └─ [VALIDATOR] ...waiting
│
├─ [REQUEST] "In the header, right side" → unlocks ASK_USER → sub-graph continues
│
├─ [INCOMING] Bug from indexation → qualifier prepared context (READY)
│
└─ [REQUEST] "How's the logout going?" → check sub-graph state
```

---

## Graph Types

| Type | Code | Description |
|------|------|-------------|
| Paměťová mapa | `GraphType.MEMORY_MAP` | One global map per user — all interactions are vertices |
| Myšlenková mapa | `GraphType.THINKING_MAP` | Sub-graph for a specific background task, linked to Paměťová mapa |

## Vertex Types

| Type | Purpose | Context |
|------|---------|---------|
| `ROOT` | Entry point, task description | Minimal |
| `PLANNER` | Decompose into sub-vertices | Memory map summary + task context |
| `INVESTIGATOR` | Research, KB search, web search | Focused research tools |
| `EXECUTOR` | Implementation via coding agent | Focused task from vertex |
| `VALIDATOR` | Verify results, tests | Validator tools |
| `REVIEWER` | Code review | Review tools |
| `SYNTHESIS` | Combine terminal vertex results | All terminal summaries |
| `GATE` | Decision point (go/no-go) | Gate criteria |
| `SETUP` | Project scaffolding, repo creation, environment | Setup tools |
| `ASK_USER` | Blocked — needs user input via chat | Question + context |
| `REQUEST` | Chat message → agent execution → response (dynamic status from trace) | Message + tools + streaming |
| `TASK_REF` | Reference to a Myšlenková mapa | task_id + sub_graph_id |
| `INCOMING` | Qualified item from indexation | Prepared context from qualifier |
| `CLIENT` | Client hierarchy node in Memory Map | Client metadata |
| `PROJECT` | Project hierarchy node in Memory Map | Project metadata |

## Vertex Statuses

`PENDING` → `READY` → `RUNNING` → `COMPLETED` / `FAILED` / `BLOCKED`

- `BLOCKED` = waiting for external input (ASK_USER, dependency not met)
- A BLOCKED vertex stays in the map — info preserved until resolved

### REQUEST Vertex Dynamic Status

REQUEST vertices (chat interactions) get their final status from **trace analysis** in `sse_handler.py`,
not from the default PENDING→COMPLETED lifecycle. After the agentic loop finishes, the trace is inspected:

| Status | Condition | Example |
|--------|-----------|---------|
| `FAILED` | Any tool result contains error markers (`Error:`, `Chyba:`, `error:`) | KB search failed, API error |
| `RUNNING` | `create_background_task` or `dispatch_coding_agent` was called (no errors) | User asked to implement a feature |
| `COMPLETED` | Tool calls succeeded without background dispatch, or simple Q&A with no tool calls | Simple question, KB lookup |

This allows the memory map to accurately reflect which chat interactions spawned ongoing work
(RUNNING), which had problems (FAILED), and which were fully resolved (COMPLETED).
`add_request_vertex()` accepts the status parameter — default is COMPLETED.

## Timestamps

All timestamps are **ISO 8601 UTC** strings (e.g. `2026-03-08T14:30:00+00:00`).

Fields: `created_at`, `started_at`, `completed_at` on vertices; `created_at` on graphs.

UI displays time as `HH:mm:ss` + duration (e.g. "13:15:30 (trvání: 1m 19s)").

Legacy epoch timestamps (e.g. "1772969660") are auto-migrated to ISO on graph load.

## Real-time Push Updates

Vertex status changes in Paměťová mapa trigger real-time UI refresh:

```
Python: vertex_started/completed (progress.py)
  → if graph.graph_type == MEMORY_MAP:
    → kotlin_client.notify_memory_map_changed()
      → POST /internal/memory-map-changed
        → Kotlin broadcasts MemoryMapChanged event to ALL clients
          → MainViewModel → ChatViewModel.loadMemoryMap() (500ms debounce)
```

Also fires after `link_thinking_map()` (persistence.py) — when TASK_REF vertices are added/updated.

---

## Unified Agent — ONE system for chat + background

### Key Change (Phase 4)

Previously there were 3 separate agentic loops:
1. `chat/handler_agentic.py` (~550 lines) — foreground chat
2. `graph_agent/langgraph_runner.py::_agentic_vertex()` (~170 lines) — background
3. `qualification/handler.py` — qualifier

Now there is ONE: `agent/vertex_executor.py`

### vertex_executor.py — shared agentic loop

```python
@dataclass
class VertexEvent:
    type: str       # "thinking" | "tool_call" | "tool_result" | "token" | "done" | "error" | "approval_request" | "scope_change"
    content: str = ""
    metadata: dict = field(default_factory=dict)

async def execute_vertex(
    vertex: GraphVertex,
    context: str,
    tools: list[dict],
    client_id: str,
    project_id: str | None,
    group_id: str | None = None,
    processing_mode: str = "BACKGROUND",
    max_tier: str = "NONE",
    session_id: str | None = None,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[VertexEvent]:
```

Shared logic:
- LLM + capability routing (local GPU / OpenRouter)
- Tool execution + `request_tools` meta-tool
- Loop/drift detection
- Approval flow (FOREGROUND=SSE, BACKGROUND=interrupt)
- Per-iteration save to `vertex.agent_messages`

### Adapters

- **Foreground (chat)**: `agent/sse_handler.py` (~100 lines) consumes `VertexEvent` → `ChatStreamEvent` (SSE)
- **Background**: `agent/langgraph_runner.py::_agentic_vertex()` delegates to `execute_vertex()`

### Per-vertex state persistence

```python
class GraphVertex(BaseModel):
    # ... existing fields ...
    agent_messages: list[dict] = Field(default_factory=list)  # LLM message history for resume
    agent_iteration: int = 0                                   # How many iterations completed
```

- DB flush: `agent_messages` saved only for RUNNING/BLOCKED vertices
- COMPLETED: `agent_messages` cleared (save space)

---

## Chat Routing — message → vertex in Paměťová mapa

### chat_router.py

```python
@dataclass
class ChatRoute:
    action: str          # "new_vertex" | "resume_vertex" | "answer_ask_user" | "direct_response"
    vertex_id: str | None = None
    parent_id: str | None = None

async def route_chat_message(message, memory_map, context_task_id, client_id, project_id) -> ChatRoute:
```

Routing (regex/heuristic):
1. `context_task_id` → ASK_USER vertex → `answer_ask_user`
2. Greeting → `direct_response`
3. RUNNING/BLOCKED vertex for client/project → `resume_vertex`
4. Default → `new_vertex`

### sse_handler.py (~100 lines)

```
1. Load Paměťová mapa
2. route_chat_message() → ChatRoute
3. match route.action:
   "direct_response" → fast LLM, stream, done
   "answer_ask_user" → resume_vertex(), stream
   "new_vertex"      → create RUNNING REQUEST vertex, execute_vertex(), stream
   "resume_vertex"   → load vertex state, execute_vertex(), stream
4. (finally) vertex → COMPLETED/FAILED
```

---

## REQUEST Vertex Tools

Base tools (always available) + `request_tools` meta-tool for on-demand categories:

**Base tools (REQUEST vertex):**
- kb_search, kb_delete, web_search, memory_recall, store_knowledge
- dispatch_coding_agent, create_background_task, respond_to_user_task
- check_task_graph, answer_blocked_vertex, get_kb_stats
- memory_store, push_urgent_alert, get_active_chat_topics
- request_tools (meta-tool for on-demand categories)

**On-demand categories (via request_tools):**

| Category | Tools |
|----------|-------|
| `calendar` | gcal_list_events, gcal_create_event, gcal_find_free_time |
| `email` | gmail_search, gmail_read, gmail_draft |
| `slack` | slack_search, slack_read_channel, slack_send_message |
| `settings` | mongo_list_collections, mongo_get_document, mongo_update_document |
| `project_management` | create_client, create_project, create_connection, create_git_repository |
| `environment` | environment_list, environment_create, environment_deploy, etc. |
| `git` | repo_info, file_listing, commits, branches |
| `code` | file listing, structure, tech stack |
| `meetings` | classify_meeting, list_unclassified_meetings |
| `guidelines` | get/update guidelines |
| `queue` | task_queue_inspect, task_queue_set_priority |

### MongoDB Self-Management Tools

Agent has full read/write access to ALL MongoDB collections:

- `mongo_list_collections` — list all collections
- `mongo_get_document` — read document by filter
- `mongo_update_document` — write/update document (requires approval in FOREGROUND)

**Cache invalidation (CRITICAL):** After every `mongo_update_document`:
1. Tool executor calls Kotlin `POST /internal/cache/invalidate` with collection name
2. Kotlin invalidates in-memory cache for that collection
3. Change reflects IMMEDIATELY in next request

Collections with cache (require invalidation): `clients`, `projects`, `project_groups`, `cloud_model_policies`, `openrouter_settings`, `polling_intervals`, `whisper_settings`, `guidelines`

Collections without cache (direct MongoDB): `task_graphs`, `chat_messages`, `chat_sessions`, `tasks`

### MCP Bridge

Calendar, Gmail, Slack tools are proxied through Kotlin:
- Kotlin exposes `POST /internal/mcp/{tool_name}` — proxy to MCP servers
- Kotlin is authenticated via OAuth
- Python calls internal endpoint, Kotlin forwards to MCP server

---

## Qualifier → Paměťová mapa

After qualification, if escalated to user:

```python
from app.agent.persistence import agent_store
from app.agent.graph import add_incoming_vertex

memory_map = await agent_store.get_or_create_memory_map()
add_incoming_vertex(
    memory_map,
    task_id=task_id,
    title=qualifier_result.title,
    prepared_context=qualifier_result.context,
    client_id=client_id,
    project_id=project_id,
    urgency=qualifier_result.urgency,
)
agent_store.mark_dirty(memory_map.task_id)
```

INCOMING vertex:
- Status = READY
- `local_context` = qualifier prepared context
- Under client/project hierarchy in Paměťová mapa
- Agent can process it when user asks or automatically in background

---

## Client Isolation (Security)

- **Paměťová mapa is global** — one per orchestrator instance, no client_id restriction
- **Myšlenková mapy have client_id** — set from request, enforced in KB access
- **Cross-client edges impossible** — all vertices in a sub-graph share client_id
- **KB access scoped by client_id** — vertex execution passes client_id to all KB calls
- **Sub-graphs reference by TASK_REF** — no data leaks between client contexts
- Client A's vertex CANNOT see Client B's KB data (enforced at execution, not graph level)

---

## ASK_USER Flow

```
Graph Agent vertex processing
  → Model doesn't have enough info
  → Creates ASK_USER vertex (BLOCKED)
  → Pushes state to Kotlin (task → USER_TASK)
  → Graph pauses at this vertex

User sees question in UI / chat
  → Answers via chat
  → Chat router finds matching ASK_USER vertex
  → resume_vertex(graph_id, vertex_id, answer)
  → Vertex → COMPLETED, graph continues
```

---

## Implementation Phases

### Phase 1: Enable Graph Agent for Background Tasks [DONE]

- `config.py`: `use_graph_agent = True` (always on)
- `background/handler.py`: Legacy handler deleted, delegates to graph agent
- No fallback — PoC, correct implementation from start

### Phase 2: Paměťová mapa — Persistent Conversational Graph in RAM [DONE]

- `agent/models.py`: `GraphType` enum (MEMORY_MAP, THINKING_MAP), vertex types
  (ASK_USER, REQUEST, TASK_REF, INCOMING)
- `agent/persistence.py`: In-memory singleton, `get_or_create_memory_map()`,
  `link_thinking_map()`, `find_ask_user_vertices()`, `resume_vertex()`, periodic DB flush
- `agent/graph.py`: `add_request_vertex()`, `add_task_ref_vertex()`,
  `add_incoming_vertex()`, `memory_map_summary()` (max 2000 tokens)

**Context budget:** Memory map summary must be SMALL (max 2000 tokens):
- Last N REQUEST vertices (title + status)
- Active Myšlenkové mapy (task_id + status + current vertex)
- BLOCKED vertices (what's waiting for answer)
- INCOMING vertices (pending qualified items)
- Fits into 48k GPU context with room for actual vertex work

### Phase 3: Chat↔Graph Integration [DONE]

- `agent/sse_handler.py`: Every chat request creates/resumes vertex via chat_router
- `agent/tool_sets.py`: All tools available via REQUEST vertex + request_tools
- `background/handler.py`: Link Myšlenková mapa to Paměťová mapa, ASK_USER → BLOCKED
- UI: `TaskGraphComponents.kt` — graph visualization, ASK_USER highlighting

### Phase 4: Unified Agent [DONE]

- `agent/vertex_executor.py`: ONE agentic loop for foreground + background
- `agent/chat_router.py`: Message → vertex routing
- `agent/sse_handler.py`: Thin SSE adapter (~100 lines)
- Per-vertex state: `agent_messages` + `agent_iteration`
- REQUEST vertex: full agentic execution, not just a record
- INCOMING vertex: from qualifier with prepared context
- MongoDB self-management tools + cache invalidation
- MCP bridge for Calendar/Gmail/Slack

**Deleted in Phase 4:**
- `chat/handler_agentic.py` — logic moved to vertex_executor.py
- `chat/intent.py`, `chat/intent_router.py`, `chat/intent_decomposer.py` — replaced by chat_router.py
- `chat/handler_decompose.py` — agent decomposes on its own
- `chat/drift.py` — merged into vertex_executor.py

---

## Coding Agent Dispatch (K8s Jobs)

Background handler routes coding tasks (sourceUrn=`chat:coding-agent`) directly to K8s Jobs,
bypassing the graph agent's LLM decomposition.

### Flow
```
Chat → dispatch_coding_agent tool
  → Kotlin: task(QUEUED, sourceUrn="chat:coding-agent")
    → BackgroundEngine → POST /orchestrate/v2 (with sourceUrn)
      → handle_background() detects coding task
        → workspace_manager.prepare_workspace()
        → job_runner.dispatch_coding_agent() → K8s Job
        → kotlin_client.notify_agent_dispatched() → CODING
      → AgentTaskWatcher polls K8s Job
        → Job done → report_status_change(done) → task DONE
```

### Key Files
| File | Role |
|------|------|
| `background/handler.py` | `_run_coding_agent_background()` — workspace prep + K8s Job dispatch |
| `agents/job_runner.py` | `dispatch_coding_agent()` — K8s Job creation |
| `agents/workspace_manager.py` | `prepare_workspace()` — instructions, KB, env, CLAUDE.md |
| `agent_task_watcher.py` | Polls CODING tasks, detects completion, marks DONE |
| `tools/kotlin_client.py` | `notify_agent_dispatched()` — task state → CODING |

### Sequential Execution
Tasks execute one at a time (BackgroundEngine processes QUEUED sequentially).
Each coding step completes → user reviews/merges → next task runs.

---

## File Map

| File | Purpose |
|------|---------|
| `service-orchestrator/app/agent/models.py` | AgentGraph, GraphVertex, GraphEdge, enums |
| `service-orchestrator/app/agent/graph.py` | Graph operations, hierarchy, vertex creation |
| `service-orchestrator/app/agent/persistence.py` | AgentStore — RAM cache, DB flush, dirty tracking |
| `service-orchestrator/app/agent/vertex_executor.py` | Unified agentic loop (LLM + tools) |
| `service-orchestrator/app/agent/chat_router.py` | Chat message → vertex routing |
| `service-orchestrator/app/agent/sse_handler.py` | SSE adapter for foreground chat |
| `service-orchestrator/app/agent/langgraph_runner.py` | LangGraph execution for background |
| `service-orchestrator/app/agent/tool_sets.py` | Per-vertex-type tool sets + request_tools |
| `service-orchestrator/app/agent/decomposer.py` | LLM-driven decomposition |
| `service-orchestrator/app/agent/validation.py` | Structural validation |
| `service-orchestrator/app/agent/progress.py` | Progress reporting to Kotlin |
| `service-orchestrator/app/agent/artifact_graph.py` | ArangoDB artifact graph |
| `service-orchestrator/app/agent/impact.py` | Impact propagation |
| `service-orchestrator/app/chat/models.py` | ChatRequest, ChatStreamEvent (SSE transport) |
| `service-orchestrator/app/chat/handler_streaming.py` | LLM calls, token streaming |
| `service-orchestrator/app/chat/handler_tools.py` | Tool execution handlers |
| `service-orchestrator/app/chat/context.py` | ChatContextAssembler |
| `service-orchestrator/app/chat/system_prompt.py` | System prompt builder |

## Deploy

- `k8s/build_orchestrator.sh` (Python agent changes)
- `k8s/build_server.sh` (Kotlin DTO/RPC changes)
- Desktop rebuild (UI terminology changes)
