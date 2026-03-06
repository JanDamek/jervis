# Graph Agent Architecture — Thinking Map as Orchestration Core

> SSOT for how Graph Agent and the Thinking Map (master map) work in Jervis.
> All background tasks and (future) chat interactions run through this architecture.

---

## Core Concept

**Thinking Map (TaskGraph)** = DAG where each vertex solves a small part of a problem
with focused context. Edges carry processed results (summaries), not raw data.
A model with 48k GPU context can solve extremely complex problems because each node
works only with a relevant slice.

### Key Principles

1. **Chat = assistant** — continues seamlessly where we left off
2. **One global master map** (chat is one per user, never changes) — everything is part of it
3. **Tasks are solved by moving through the map** (vertex by vertex), not as a whole
4. **Complete graph persisted to DB** per task — task can be BLOCKED, info stays in the map
5. **After resolution, returns to where it was** in the map
6. **Small context per vertex + summaries on edges** = consistent solutions to large problems
7. **With 128k or 256k context**, we reach extremely complex solutions that remain consistent

### Context Management (CRITICAL)

- **Background = ALWAYS GPU = 48k context max**
- Start in the map MUST fit into 48k (master summary + vertex context)
- By decomposing into sub-vertices, context per vertex DECREASES (focus)
- **Edge = SUMMARY** — reduced result, not raw data
- Large contexts → coding agents (Claude CLI, future Kilo Code)
- Coding agent gets a focused task from vertex, not the entire map
- **Persistence is for restart only** — orchestrator holds graph in RAM
- Loads from DB only the individual TaskDocuments that edges reference

---

## Architecture Overview

```
MASTER MAP (global singleton, in RAM, DB = backup)
│
├─ [CHAT] "How does auth work?" → response summary (COMPLETED)
│
├─ [CHAT] "Add logout button" → dispatch coding agent
│   └─ [TASK_REF] → Sub-graph: task-69a98... (PROCESSING)
│       ├─ [PLANNER] decompose → 3 steps
│       ├─ [EXECUTOR] implementation → code
│       ├─ [ASK_USER] "Where to add button?" → BLOCKED
│       └─ [VALIDATOR] ...waiting
│
├─ [CHAT] "In the header, right side" → unlocks ASK_USER → sub-graph continues
│
└─ [CHAT] "How's the logout going?" → check sub-graph state
```

---

## Graph Types

| Type | Description |
|------|-------------|
| `MASTER` | One global master map per user — all interactions are vertices |
| `TASK_SUBGRAPH` | Sub-graph for a specific background task, linked to master map |

## Vertex Types

| Type | Purpose | Context |
|------|---------|---------|
| `ROOT` | Entry point, task description | Minimal |
| `PLANNER` | Decompose into sub-vertices | Master summary + task context |
| `INVESTIGATOR` | Research, KB search, web search | Focused research tools |
| `EXECUTOR` | Implementation via coding agent | Focused task from vertex |
| `VALIDATOR` | Verify results, tests | Validator tools |
| `REVIEWER` | Code review | Review tools |
| `SYNTHESIS` | Combine terminal vertex results | All terminal summaries |
| `GATE` | Decision point (go/no-go) | Gate criteria |
| `SETUP` | Project scaffolding, repo creation, environment | Setup tools |
| `ASK_USER` | Blocked — needs user input via chat | Question + context |
| `CHAT_EXCHANGE` | Chat message → response pair | Message + response |
| `TASK_REF` | Reference to a task sub-graph | task_id + sub_graph_id |

## Vertex Statuses

`PENDING` → `READY` → `RUNNING` → `COMPLETED` / `FAILED` / `BLOCKED`

- `BLOCKED` = waiting for external input (ASK_USER, dependency not met)
- A BLOCKED vertex stays in the map — info preserved until resolved

---

## Client Isolation (Security)

- **Master map is global** — one per orchestrator instance, no client_id restriction
- **Task sub-graphs have client_id** — set from OrchestrateRequest, enforced in KB access
- **Cross-client edges impossible** — all vertices in a sub-graph share client_id
- **KB access scoped by client_id** — vertex execution passes client_id to all KB calls
- **Sub-graphs reference by TASK_REF** — no data leaks between client contexts
- Client A's vertex CANNOT see Client B's KB data (enforced at execution, not graph level)

---

## Implementation Phases

### Phase 1: Enable Graph Agent for Background Tasks [DONE]

- `config.py`: `use_graph_agent = True` (always on)
- `background/handler.py`: Legacy handler deleted, `handle_background()` delegates
  directly to `_run_graph_agent_background()`
- No fallback — PoC, correct implementation from start

### Phase 2: Master Map — Persistent Conversational Graph in RAM [DONE]

**Goal**: One global master map in orchestrator RAM. Everything the user works on =
vertices in the map. Background tasks = sub-graphs on edges.

**Changes**:
- `graph_agent/models.py`: `GraphType` enum (MASTER, TASK_SUBGRAPH), new vertex types
  (ASK_USER, CHAT_EXCHANGE, TASK_REF)
- `graph_agent/persistence.py`: In-memory singleton `_master_graph`, `get_or_create_master_graph()`,
  `link_task_subgraph()`, `find_ask_user_vertices()`, `resume_vertex()`, periodic DB flush
- `graph_agent/graph.py`: `add_chat_vertex()`, `add_task_ref_vertex()`,
  `create_ask_user_vertex()`, `master_map_summary()` (max 2000 tokens)

**Context budget for master map**: Master map summary must be SMALL (max 2000 tokens):
- Last N chat vertices (title + status)
- Active sub-graphs (task_id + status + current vertex)
- BLOCKED vertices (what's waiting for answer)
- Fits into 48k GPU context with room for actual vertex work

### Phase 3: Chat↔Graph Integration [DONE]

**Goal**: Chat is the interface to the map. Model navigates graph, responds with context,
unlocks blocked vertices.

**Changes**:
- `chat/handler.py`: Every chat request loads master map, injects summary into system
  message, adds CHAT_EXCHANGE vertex, checks ASK_USER vertices
- `chat/tools.py`: Thinking map tools + dispatch tools move to CORE (always available)
- `chat/intent.py`: Graph/task tools always in CORE
- `background/handler.py`: Link sub-graph to master map, ASK_USER → BLOCKED → USER_TASK
- UI: `ThinkingMapPanel.kt` — dropdown of graphs, ASK_USER highlighting, TASK_REF navigation

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
  → Chat handler finds matching ASK_USER vertex
  → resume_vertex(graph_id, vertex_id, answer)
  → Vertex → COMPLETED, graph continues
```

---

## File Map

| File | Phase | Action |
|------|-------|--------|
| `service-orchestrator/app/config.py` | 1 | `use_graph_agent=True` |
| `service-orchestrator/app/background/handler.py` | 1+3 | always graph, ASK_USER, link master |
| `service-orchestrator/app/graph_agent/models.py` | 2 | +GraphType, +ASK_USER, +CHAT_EXCHANGE, +TASK_REF |
| `service-orchestrator/app/graph_agent/graph.py` | 2 | +chat/task/ask_user vertices, +summary |
| `service-orchestrator/app/graph_agent/persistence.py` | 2 | +RAM cache, +master graph, +link, +resume |
| `service-orchestrator/app/chat/handler.py` | 3 | master map in chat |
| `service-orchestrator/app/chat/tools.py` | 3 | map+task tools → CORE |
| `service-orchestrator/app/chat/intent.py` | 3 | graph tools always available |
| `service-orchestrator/app/graph_agent/langgraph_runner.py` | 3 | ASK_USER → BLOCKED |
| `shared/common-api/.../ITaskGraphService.kt` | 3 | +getMasterGraph, +listGraphs, +resumeVertex |
| `backend/server/.../rpc/TaskGraphRpcImpl.kt` | 3 | implementation |
| `shared/common-dto/.../graph/TaskGraphDtos.kt` | 3 | +GraphType, +summary DTO |
| `shared/ui-common/.../chat/ThinkingMapPanel.kt` | 3 | graph list, ASK_USER UI |

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

## Deploy

- Phase 1: `k8s/build_orchestrator.sh`
- Phase 2: `k8s/build_orchestrator.sh`
- Phase 3: `k8s/build_orchestrator.sh` + `k8s/build_server.sh`
