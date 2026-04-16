# Teams Pod Agent — LangGraph Migration

**Status:** Design (ready for review)
**Date:** 2026-04-16
**Supersedes:** raw ReAct loop in `backend/service-o365-browser-pool/app/agent/{loop,tools,llm,prompts}.py`
**Reference:** `backend/service-orchestrator/app/agent/langgraph_runner.py`

---

## 1. Motivation

The current pod agent is a hand-rolled ReAct loop (~400 lines) that:
- Posts directly to `router-admin/decide` + Ollama `/api/chat` with OpenAI-shaped
  tool schemas built by hand.
- Hand-manages message history, trimming, tool-call ID matching.
- Has no persistence — if the pod restarts, the conversation is lost.
- Gets `400 Bad Request` from Ollama intermittently because tool-call response
  handling isn't bullet-proof (stale `tool_call_id`s, tool count vs. context,
  malformed messages array).

The Jervis orchestrator already runs on **LangGraph** (`backend/service-orchestrator/app/agent/langgraph_runner.py`) — MongoDB-checkpointed `StateGraph` with router-backed LLM calls. Per `feedback-kotlin-first.md` memory: *"Python jen pro LLM frameworky (LangChain/LangGraph/ML)"* — running two different agent frameworks in one codebase is an anti-pattern.

### What LangGraph buys us
- Battle-tested tool-call loop (`tool_call_id` matching, Ollama + OpenAI format
  variance) — no more 400s from malformed requests.
- `MessagesState` with `trim_messages` for context budget without losing
  `tool_call_id` consistency.
- `MongoDBSaver` checkpointer → pod restart resumes where it left off.
- Native `interrupt()` for MFA / meeting approval without custom state flags.
- Streaming via `astream()` for live logs.
- Same stack as orchestrator — shared patterns, shared lessons.

---

## 2. Dependency contract

Mirror orchestrator (`backend/service-orchestrator/requirements.txt`):

```
langgraph>=0.4.0
langgraph-checkpoint-mongodb>=0.3.0
langchain-core>=0.3.0
motor>=3.6.0        # already used
httpx>=0.27         # already used
```

No `langchain-ollama`, no `litellm`. LLM calls go through a thin **router-backed** adapter we write (see §4).

---

## 3. State schema

State is split into **three layers** matching persistence lifetime:

| Layer | Where | Persists | Read by |
|-------|-------|----------|---------|
| **A. LangGraph `PodAgentState`** | `MongoDBSaver` checkpoints per `thread_id=connection_id` | Yes (across pod restart) | Agent `agent` / `tools` nodes every tick |
| **B. Runner in-memory runtime** | Python process | No (rebuilt on restart from observation + Mongo) | Runner watcher / upload loop / ffmpeg supervisor |
| **C. Mongo persistent stores** | Separate collections | Yes, indefinitely | Cold-start injector, storage tools, cleanup task, UI |

### Layer A — `PodAgentState`

```python
# app/agent/state.py
from typing import Literal, TypedDict
from langgraph.graph import MessagesState

class ActiveMeeting(TypedDict, total=False):
    meeting_id: str                   # MeetingDocument._id
    title: str | None
    joined_by: Literal["user", "agent"]
    scheduled_start_at: str | None    # ISO; only set for scheduled
    scheduled_end_at: str | None
    meeting_stage_appeared_at: str
    max_participants_seen: int
    alone_since: str | None           # ISO when participants hit 1
    user_notify_sent_at: str | None   # last meeting_alone_check push
    last_speech_at: str | None
    recording_status: Literal["RECORDING", "FINALIZING"]
    chunks_uploaded: int
    chunks_pending: int
    last_chunk_acked_at: str | None
    last_user_response: dict | None   # {action, at, actor}

class PendingInstruction(TypedDict):
    id: str
    kind: str                         # "join_meeting" | "leave_meeting" | "meeting_stay" | …
    payload: dict
    received_at: str

class PodAgentState(MessagesState):
    """LangGraph state for one Teams pod agent.

    Inherits `messages` (list[BaseMessage]) from MessagesState. Tool-call IDs
    flow through AIMessage.tool_calls ↔ ToolMessage.tool_call_id and MUST be
    preserved across trim_messages passes — see §16.
    """
    # Identity (stable for pod lifetime)
    client_id: str
    connection_id: str
    login_url: str
    capabilities: list[str]

    # Pod state machine (see product §9)
    pod_state: str

    # Login / MFA bookkeeping
    pending_mfa_code: str | None
    last_auth_request_at: str | None  # ISO; 60-min cooldown gate for product §18

    # Observation snapshot — last known, for reasoning without re-observe
    last_url: str
    last_app_state: str               # login|mfa|chat_list|conversation|meeting_stage|loading|unknown
    last_observation_at: str
    last_observation_kind: Literal["dom", "vlm"]

    # Per-context notify dedup (product §7)
    notified_contexts: list[str]      # e.g. "urgent_message:<chat_id>", "mfa:42", "meeting_alone_check:<meeting_id>"

    # Active meeting (at most one per pod)
    active_meeting: ActiveMeeting | None

    # Pending server instructions — drained at tick start → HumanMessages
    pending_instructions: list[PendingInstruction]
```

**Rationale for the three design-question defaults locked 2026-04-16:**

- `pending_instructions` lives in state (A), not in runner memory. Survives
  restart — an instruction that arrived just before a checkpoint is not
  lost.
- `last_observation_*` lives in state (A), not derived from the last
  `ToolMessage` in `messages`. Messages get trimmed by `trim_messages`;
  state fields are not.
- `active_meeting` is an inline dict (A), not a pointer to
  `MeetingDocument._id`. The Mongo document is canonical; the inline dict
  is the agent-facing hot subset so reasoning doesn't re-fetch.

### Layer B — Runner in-memory runtime

Not a single dataclass; distributed across `runner.py` and helper modules:

```python
# app/agent/runner.py — PodAgent instance fields
watcher_state: dict[str, dict]      # { meeting_id: {participants, alone_since, last_speech_at},
                                    #   tab_name:   {meeting_stage, incoming_call} }
chunk_queues: dict[str, DiskChunkQueue]   # per meeting_id, disk-backed FIFO
ffmpeg_procs: dict[str, asyncio.subprocess.Process]
last_server_ack_at: datetime | None
consecutive_upload_errors: int
```

Rebuilt on pod start:
- `watcher_state` from current `inspect_dom` observation + Mongo
  `MeetingDocument.status == RECORDING`.
- `chunk_queues` from existing disk files in the chunk directory.
- `ffmpeg_procs` empty (any crashed ffmpeg means the chunk pipeline is
  stopped; agent must call `start_meeting_recording` again, not resume
  silently).

### Layer C — Mongo persistent stores

| Collection | Purpose | Spec |
|------------|---------|------|
| `langgraph_checkpoints_pod` | Raw LangGraph state history, per thread | existing |
| `pod_agent_patterns` | Learned selectors + action templates | product §20 |
| `pod_agent_memory` | Session summaries, learned rules, anomalies | product §20 |
| `MeetingDocument` | Meeting metadata + chunks + timeline | product §10a (extended) |
| `o365_scrape_messages` / `o365_message_ledger` / `o365_discovered_resources` / `scraped_mail` / `scraped_calendar` | Scraped content → Indexer → Tasks | product §19 |

Tools that write Layer C: storage primitives (`store_chat_row`,
`store_message`, `store_discovered_resource`, `store_calendar_event`,
`store_mail_header`, `mark_seen`). Context persistence cleanup writes
`pod_agent_patterns` + `pod_agent_memory`. Meeting chunks are written by
the server on receipt of `POST /internal/meeting/{id}/video-chunk`.

### Cold-start SystemMessage composition

On pod start (or after a cleanup pass), the runner composes the
`SystemMessage` as:

```
<static prompt — §3 decision table, §15 hard rules, §17 MFA rules, §18 relogin gating, §10a meeting rules>

Previous-session summary:
<pod_agent_memory where kind='session_summary' order by createdAt desc limit 1>

Learned patterns for this connection (top 10 by lastUsedAt):
<pod_agent_patterns where connectionId=<C> order by lastUsedAt desc limit 10>

Learned rules (kind='learned_rule', most recent 5):
<pod_agent_memory where kind='learned_rule' order by createdAt desc limit 5>

CURRENT STATE (as of <now>):
  pod_state: <state["pod_state"]>
  last_url: <state["last_url"]>
  last_app_state: <state["last_app_state"]>
  last_observation_at: <state["last_observation_at"]> (<kind>)
  tab_state: <state["tab_state"] — short listing>
  last_scrape_state: <fingerprints for chats / mail / calendar — coarse counts + most_recent_timestamp>
  active_meeting: <state["active_meeting"] — compact dict or "none">
  pending_instructions: <queue snapshot, drained to HumanMessages on tick>
```

This SystemMessage is **regenerated every outer-loop entry**, not stored
in the checkpoint — prompt improvements + pattern promotions + state
updates roll out without checkpoint rewrites.

## 3b. LLM context window — what goes into every invocation

The `agent` node does NOT ship `state["messages"]` verbatim to the LLM.
Each invocation composes four parts; the in-memory context window is
bounded to a fixed budget so `session` size never drives up LLM latency
or cost.

### The four parts of every `llm.ainvoke(...)` call

| # | Part | Source | Budget | Regenerated each tick? |
|---|------|--------|--------|------------------------|
| 1 | `SystemMessage` | composed from static prompt + Mongo (`pod_agent_memory`, `pod_agent_patterns`) + `PodAgentState` snapshot | **6 000 tokens** hard cap (`O365_POOL_CONTEXT_SYSTEM_CAP`) | yes, every tick |
| 2 | Trimmed message history | `trim_messages(state["messages"], strategy="last", max_tokens=12000, include_system=False, start_on="human", allow_partial=False)` | **12 000 tokens** (`O365_POOL_CONTEXT_TRIM_TOKENS`) | yes, every tick |
| 3 | Tool schemas | `bind_tools(ALL_TOOLS)` serialized by LangChain | **~4 000 tokens** fixed (~20 tools × ~200 tokens) | no, bound once |
| 4 | Agent reply (output) | LLM generation | **500–2 000 tokens** | n/a |

**Total per invocation: ~17–24 k input + 0.5–2 k output ≈ 26 k working
set.** Fits comfortably in a 32 k local context, trivial in 128 k cloud.

### Why `trim_messages` is safe

`allow_partial=False` + `start_on="human"` mean the trim drops whole
Human → AI-tool_calls → Tool → … → AI-final triples from the head. It
never orphans a `tool_call_id` pair — LangGraph would otherwise error on
the next agent step.

Because part 1 (SystemMessage) is regenerated from the Mongo persistent
layer + `PodAgentState` snapshot, the LLM always sees **current** state +
distilled history even when part 2 has dropped 90 %+ of raw messages.
The agent never loses track of:

- Pod state machine position (`pod_state`)
- Tab layout (`tab_state`)
- What changed since last scrape (`last_scrape_state` fingerprints)
- Active meeting context (`active_meeting` inline)
- Learned selectors (`pod_agent_patterns` top 10)
- Past-session achievements (`pod_agent_memory` latest `session_summary`)

Raw message trim is then **safe by construction** — the SystemMessage
carries the durable state summary.

### Growth dynamics

| Metric | Value |
|--------|-------|
| Per react cycle appended to `state["messages"]` | 1–2 k tokens (AIMessage + N ToolMessages, avg 4–8 KB) |
| `ACTIVE` tick cadence | 30 s (§4) |
| Growth in `state["messages"]` at `ACTIVE` | ~2–4 k tokens / min |
| Growth in 1 hour uninterrupted (no cleanup yet) | 120–240 k tokens on disk, **still only ~12 k tokens sent to LLM** |
| Cleanup trigger fires at | 100 msgs (≈ 25 min active) or 40 k tokens |
| Post-cleanup `state["messages"]` size | ~20 msgs (~12 k tokens) + one injected `SystemMessage("Prior session summary…")` |

The LLM never sees more than ~24 k tokens of context regardless of how
long the pod has been running.

### Adaptive budget for long reasoning chains

If a single action (login with many cascaded tool calls, meeting join
with mic/cam/Join cascade) produces a chain longer than 12 k tokens,
`trim_messages(allow_partial=False)` cannot split the chain — the whole
trimmed window stretches until the chain fits. If it still exceeds the
local model's window:

- Router escalates to a cloud model with 128 k+ context
  (`capability="chat"` + `context_hint="large"` → routing policy picks
  the appropriate backend).
- Pod code does not change — it is a routing decision.

This path is rare in steady state; routine scrape cycles stay well
under 6–8 k tokens per react turn.

### Configuration (configmap)

```
O365_POOL_CONTEXT_TRIM_TOKENS=12000    # trim_messages budget per invocation
O365_POOL_CONTEXT_SYSTEM_CAP=6000      # SystemMessage hard cap
O365_POOL_CONTEXT_MAX_MSGS=100         # cleanup size trigger (product §20)
O365_POOL_CONTEXT_MAX_TOKENS=40000     # cleanup size trigger (product §20)
```

### What we explicitly do NOT do

- **No gzip / zstd / codec compression** on the message payload.
  `trim_messages` + `session_summary` distillation + pattern promotion
  keep the per-invocation context bounded well under the LLM window
  indefinitely. Adding a codec would buy nothing.
- **No sliding window smaller than 12 k.** Tests with 6 k windows lost
  tool-call context mid-login and the agent started re-observing from
  scratch. 12 k is the floor for reliable multi-step flows.
- **No "last N messages" count-based trim.** Token-based trim handles
  the long-tail ToolMessage (a 20 KB `inspect_dom` result counts as
  one message but ~5 k tokens); count-based trim would let context
  balloon or over-shrink.
- **No per-tool context stripping.** Tool schemas are bound once at
  graph compile time; dropping tools per-turn would break the react
  pattern and is unnecessary at 4 k tokens total.

### Verification (after A-section lands)

Instrument `runner.py` to log per-invocation token counts: SystemMessage
tokens, trimmed-messages tokens, tool-schemas tokens, sum, LLM response
tokens. If any single invocation exceeds 28 k tokens input, log a
warning and sample the offending state for diagnosis. Budget creep
should be caught at the invocation level, not only at the cleanup
level.

---

## 4. Router-backed LLM adapter

Pod agent cannot use `ChatOllama` directly — the router decides `target=local|openrouter` and the model per request. We write a `BaseChatModel` subclass:

```python
# app/agent/llm.py (new)
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import BaseMessage, AIMessage
import httpx

class RouterChatModel(BaseChatModel):
    """LangChain chat model that dispatches through jervis-ollama-router.

    Per call:
      1. POST /router/admin/decide → { target, model, api_base/api_key }
      2. If target=openrouter → POST <OpenRouter>/v1/chat/completions
         If target=local     → POST <api_base>/api/chat (Ollama native)
      3. Normalize reply to AIMessage with tool_calls list.
    """
    client_id: str
    capability: str = "tool-calling"

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        route = await self._decide(messages, **kwargs)
        reply = await self._dispatch(route, messages, **kwargs)
        return ChatResult(generations=[ChatGeneration(message=reply)])
```

This class mirrors `llm_with_cloud_fallback()` + `llm_provider.completion()` from the orchestrator's `_helpers.py` + `llm/provider.py`, but wrapped as a LangChain `BaseChatModel` so `bind_tools()` and streaming work as expected.

**Important**: we pass `tools` through LangChain's `bind_tools()` → LangChain serializes the tool schemas to the format Ollama expects (no hand-written dicts). This is the single biggest win over the current implementation.

---

## 5. Tools

Tools become Python functions with `@tool` decorator — schema is generated from type hints + docstring:

```python
# app/agent/tools.py (new)
from langchain_core.tools import tool
from app.agent.context import get_pod_context   # contextvars-based

@tool
async def inspect_dom() -> dict:
    """Primary observation — walk the DOM with shadow-root pierce and return
    structured JSON with app shells, chat rows, conversation messages, etc."""
    ctx = get_pod_context()
    return (await dom_probe.probe(ctx.page())).__dict__

@tool
async def scrape_chat() -> dict:
    """Walk Teams sidebar, store chat rows into discovered_resources + ledger.
    Fires urgent_message notify for each new direct message."""
    ctx = get_pod_context()
    return await _scrape_chat_impl(ctx)

@tool
async def notify_user(
    kind: Literal["urgent_message", "meeting_invite", "auth_request", "mfa", "error", "info"],
    message: str,
    chat_id: str | None = None,
    chat_name: str | None = None,
    sender: str | None = None,
    preview: str | None = None,
) -> dict:
    """Send kind-aware notification to the Kotlin server. Direct messages MUST
    use kind='urgent_message'. At most one notify per context."""
    ctx = get_pod_context()
    return await _notify_impl(ctx, kind=kind, message=message, ...)
```

**ToolContext is resolved via `contextvars.ContextVar`**, not passed through the graph state. This keeps the graph state JSON-serializable (checkpointer requirement) while giving tools access to the live Playwright `Page`, `TabManager`, `ScrapeStorage`, `MeetingRecorder`.

Tool list (Phase 1 read-only):
- `inspect_dom`, `look_at_screen`
- `click`, `fill`, `press`, `navigate`, `wait`
- `report_state`, `notify_user`
- `scrape_chat`, `scrape_mail`, `scrape_calendar`, `mark_seen`
- `meeting_presence_report`, `start_adhoc_meeting_recording`, `stop_adhoc_meeting_recording`
- `done`, `error`

~17 tools — same surface as current, but auto-schema'd.

---

## 6. Graph

```python
# app/agent/graph.py (new)
from langgraph.graph import StateGraph, END
from langgraph.prebuilt import ToolNode, tools_condition

def build_pod_graph():
    llm = RouterChatModel(client_id=<injected>).bind_tools(TOOLS)

    def agent_node(state: PodAgentState) -> dict:
        reply = llm.invoke(state["messages"])  # OR async _ainvoke
        return {"messages": [reply]}

    sg = StateGraph(PodAgentState)
    sg.add_node("agent", agent_node)
    sg.add_node("tools", ToolNode(TOOLS))
    sg.set_entry_point("agent")
    sg.add_conditional_edges("agent", tools_condition)  # → "tools" or END
    sg.add_edge("tools", "agent")
    return sg
```

Standard `create_react_agent` pattern (explicit graph for clarity + future expansion):
- `agent` node calls the LLM with bound tools.
- `tools` node executes the tool calls (`ToolNode` from `langgraph.prebuilt`).
- Conditional edge: if `AIMessage.tool_calls` present → go to `tools`, else END.
- After `tools`, loop back to `agent`.

---

## 7. Checkpointer + thread_id

Same pattern as orchestrator:

```python
# app/agent/persistence.py (new)
from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver

_checkpointer: MongoDBSaver | None = None

def init_checkpointer():
    global _checkpointer
    client = MongoClient(settings.mongodb_url)
    _checkpointer = MongoDBSaver(client, db_name="jervis")

def get_compiled_graph():
    sg = build_pod_graph()
    return sg.compile(checkpointer=_checkpointer)
```

**thread_id = connection_id** (not task_id — pod is long-running per-connection, not per-task). State persists across pod restarts, so after a restart we resume with the full chat history instead of bootstrapping from zero.

Collection: `langgraph_checkpoints_pod` (separate namespace from orchestrator to prevent collisions). TTL: 30 days.

---

## 8. Lifecycle / loop

The agent no longer self-drives `while True`. Instead:

```python
# app/agent/runner.py (new)
async def run_pod_agent(state: PodAgentState):
    graph = get_compiled_graph()
    config = {"configurable": {"thread_id": state["connection_id"]}}

    while not stop_event.is_set():
        # One step: stream events until interrupt or tool_calls exhausted
        async for event in graph.astream(state, config=config):
            log_event(event)

        # After stream completes, decide cadence based on PodState + last obs
        await asyncio.sleep(adaptive_sleep())

        # Re-enter with fresh observation prompt
        state = {"messages": [HumanMessage("Re-observe and continue.")]}
```

Or simpler: the agent handles its own loop via a final "should I continue?" conditional. But keeping the outer `while` gives us clean entry points for `/session/{id}/refresh`, `/instruction/{id}`, and bootstrap.

---

## 9. Interrupts (MFA + meeting approval)

LangGraph 0.2.27+ has native `interrupt()`:

```python
from langgraph.types import interrupt, Command

@tool
async def request_mfa_code() -> str:
    """Wait for the user to supply an MFA code via POST /session/{id}/mfa."""
    code = interrupt({"kind": "mfa_code_needed"})
    return code   # resume payload
```

The graph pauses; the HTTP endpoint `POST /session/{id}/mfa` calls
`graph.stream(Command(resume=code), config=...)` to unblock.

Same pattern for `meeting_approval_required`.

---

## 10. Bootstrap (PVC session pre-auth) keeps working

The bootstrap task in `main.py` stays — LangGraph doesn't replace it. Rationale:
- Bootstrap is DOM-only (no LLM) — it succeeds even when the router is saturated.
- It sets up tabs + pushes capabilities before the agent even starts its first
  LLM call, so the UI resource flow works immediately.

The agent assumes tabs are already set up and focuses on scraping + autonomous
behavior. First iteration prompt → "Taby jsou připraveny. Zkontroluj DOM a
rozhodni, co udělat."

---

## 11. Migration plan

| # | Step | Files |
|---|------|-------|
| 1 | Add deps | `requirements.txt` |
| 2 | `RouterChatModel` wrapper | `app/agent/llm.py` (rewrite) |
| 3 | Tools as `@tool` | `app/agent/tools.py` (rewrite) |
| 4 | ContextVar for ToolContext | `app/agent/context.py` (new) |
| 5 | Graph builder | `app/agent/graph.py` (new) |
| 6 | Checkpointer | `app/agent/persistence.py` (new) |
| 7 | Runner | `app/agent/runner.py` (new) |
| 8 | System prompts | `app/agent/prompts.py` (edit — keep content) |
| 9 | Delete raw loop | `app/agent/loop.py` (DELETE) |
| 10 | Rewire session + main | `app/routes/session.py`, `app/main.py` |
| 11 | Tab priority fix | `app/tab_manager.py` (separate commit, same release) |

---

## 12. Non-goals (this migration)

- Does **not** introduce LangChain-wide adoption. Only LangGraph + `langchain-core` (BaseChatModel base class + message types + tool decorator).
- Does **not** change the router protocol (`/router/admin/decide`, `/api/chat`).
- Does **not** change `MeetingRecorder`, `TabManager`, `ScrapeStorage`, `TokenExtractor`, VNC, DOM probe JS.
- Does **not** replace orchestrator's agent — this is pod-side only.

---

## 13. Risks + mitigations

| Risk | Mitigation |
|------|-----------|
| `MongoDBSaver` connection contention with orchestrator | separate collection (`langgraph_checkpoints_pod`) |
| `BaseChatModel` serialization breaks (it's Pydantic) | match orchestrator's pattern exactly; `.invoke()` with raw messages |
| Tool context leakage between concurrent clients in same pod | one pod = one client, single contextvar is fine |
| Checkpoint bloat per connection | 30-day TTL + message trimming (`trim_messages(strategy="last", max_tokens=…)`) |
| Router 400 on bound tool schemas | LangChain's `bind_tools()` serializer is mature; validated by orchestrator |

---

## 14. Success criteria

1. No more `400 Bad Request` from Ollama `/api/chat` — LangGraph-managed tool call format works first try.
2. Pod restart resumes agent conversation instead of re-bootstrapping.
3. MFA / meeting approval use LangGraph `interrupt()` — no custom state flags.
4. Agent completes at least one successful `scrape_chat` → `discovered_resources`
   populated → UI shows chats in "Přidat zdroje".
5. Code size: agent module drops from ~800 LOC to ~400 LOC (measured on
   `app/agent/`).

---

## 15. Contract with product spec (`docs/teams-pod-agent.md`)

Sections §15–§19 of the product spec are **design constraints** on this
implementation — not optional. Restated here for module authors:

| Constraint | Implementation guard |
|------------|----------------------|
| No regex / HTML parsing (product §15) | No `re.*` / `bs4` / string `.split()` on HTML in `app/agent/*` or `app/scrape_*`. `inspect_dom` returns `{matches, count, url, truncated}` — generic, no named semantic fields. |
| No hardcoded semantic extractors (product §15 + §3) | `app/agent/dom_probe.py` replaced by a thin generic query helper: given `(selector, attrs, max)`, runs a shadow-piercing walk and returns raw matches. No `chat_rows`, `calendar_events`, etc. anywhere in the pod code. |
| Agent-chosen observation (product §3) | System prompt carries the decision table from §3. VLM for unknown state (cold start, after navigate, after error, heartbeat). Scoped DOM for known-field verification. Self-correction: `inspect_dom count=0` → agent MUST call `look_at_screen`. |
| No hardcoded URL lists / TabType (product §15) | `tab_manager.py` is a `dict[str, Page]` + open/close/switch. No `_BUSINESS_URLS`, no enum, no `_DEFAULT_TAB_NAMES`. |
| No bootstrap retry / force-setup (product §15) | `main.py` only does `launch` + `agent.start()`. `routes/session.py` only has `GET /session` + `POST /init` + `POST /mfa` + `DELETE`. No `/crawl`, `/force-*`, `/rediscover`. |
| No server→pod RPC bypassing agent (product §15 + §10a) | `POST /meeting/join` removed. Server sends `POST /instruction/{id}` with `join_meeting` payload; agent composes navigate + click + start_meeting_recording itself. |
| Cold-start probe first (product §16) | System prompt opens with: *"First action after any navigate/error/cold-start is `look_at_screen(reason='cold_start')`. After that, prefer scoped `inspect_dom` for verification unless you have no state expectation."* Runner sends `HumanMessage("Re-observe and decide.")` on every outer-loop re-entry. |
| Authenticator-only MFA (product §17) | Prompt explicitly lists forbidden MFA methods. `notify_user` schema requires `kind='mfa'` to include `mfa_code: str` — Pydantic validator fails without it. Code read: scoped DOM first on `[data-display-sign-in-code], [aria-live] .number`, VLM fallback. |
| Relogin window (product §18) | `runner.py` enforces: agent MUST call `is_work_hours()` or `query_user_activity()` before any `fill_credentials(field='password')` tool call while `report_state=AUTHENTICATING`. This is enforced via prompt, not code. |
| Data flow split (product §19) | Storage-primitive tools (`store_*`, `mark_seen`) write only to Mongo. `notify_user(kind='urgent_message')` is the only tool that calls `POST /internal/o365/notify` with non-trivial payload. All other kinds are metadata-only. |

---

## 16. Cold-start checkpoint-resume semantics

After a pod restart, `MongoDBSaver` loads the last checkpoint for
`thread_id=connection_id`. Agent MUST NOT resume mid-action. Contract:

1. Outer `runner.py` loop always enters the graph with a fresh
   `HumanMessage("Observe current browser state and decide next action.")`
   appended to the resumed `messages`.
2. The system prompt (first `SystemMessage`) is **regenerated** on every
   outer-loop entry (not stored) so prompt improvements roll out without
   checkpoint rewrites.
3. `trim_messages(strategy="last", max_tokens=8000, token_counter=len_estimator)`
   runs at the `agent` node entry — drops whole Human/AI/Tool message triples
   from the head, never splits a `tool_call_id` pair.
4. If the resumed state shows `pod_state=AWAITING_MFA` with a `pending_mfa_code`,
   but no recent `notify_user(kind='mfa')` in the last 60s of wall time, the
   agent re-emits the notify. Stale checkpoints must not leave the user
   without a push.

---

## 17. Tool-set refactor (aligned with product §5)

Generic `inspect_dom(selector, attrs, text, max_matches)` replaces the old
semantic-extractor walker. No `read_mfa_code_from_screen` sub-tool needed —
agent composes the code read from `inspect_dom` + `look_at_screen` per the
decision table (§3 + product §17 step 2).

Tool list (new + renamed from the legacy surface):

| Tool | Change |
|------|--------|
| `inspect_dom(selector, attrs, text, max_matches)` | **Rewritten**: returns `{matches, count, url, truncated}`. No `chat_rows`/`calendar_events`. |
| `look_at_screen(reason, ask?)` | **Extended**: `ask` parameter for focused VLM questions ("return the 2-digit number", "is Microsoft Authenticator offered?"). |
| `click_visual(description)`, `fill_visual(description, value)` | **New**: VLM-resolved actions for dynamic UI without stable selectors. |
| `fill_credentials(selector, field)` | **Renamed** from `fill(field='password')` — explicit field name, runtime injects, LLM never sees secret. |
| `store_chat_row`, `store_message`, `store_discovered_resource`, `store_calendar_event`, `store_mail_header`, `mark_seen` | **New storage primitives**: agent composes its own scraping loop. |
| `start_meeting_recording(meeting_id?, title?)`, `stop_meeting_recording(meeting_id)` | **Merged**: unifies `start_adhoc_meeting_recording` + server-triggered join (the latter now goes through `/instruction/` → agent → these tools). |
| `scrape_chat`, `scrape_mail`, `scrape_calendar` | **Removed**: compound semantic tools are anti-pattern — agent composes from primitives. |
| `meeting_presence_report(present, meeting_stage_visible)` | **Extended**: second flag distinguishes stage-visible from background presence. |
| `query_user_activity`, `is_work_hours` | Unchanged. |

`notify_user(kind='mfa', ...)` keeps its signature but requires
`mfa_code: str` when `kind='mfa'`. Pydantic validator on the tool schema
rejects empty.

Deletion list (legacy to remove before step 15 lands):
- `app/agent/dom_probe.py` — full-page semantic walker, replaced by new
  `inspect_dom`. Keep the shadow-pierce JS helper as a private function
  inside the new tool, nothing else.
- `app/teams_crawler.py` — regex-based sidebar walker with direct `page.*`
  calls outside `@tool`. Replaced by agent-composed `inspect_dom` +
  `click(selector)` + `store_message` loop.
- `app/routes/scrape.py` `/crawl` endpoints (lines 70–135) + `_DEFAULT_TAB_NAMES`
  dict (lines 34–38).
- `app/routes/session.py` + `app/main.py` references to `TeamsCrawler`.
- `app/meeting_recorder.py` `join()` direct RPC path — collapse into the
  agent-driven flow via `start_meeting_recording` tool.

---

## 18. Migration plan (post spec rewrite 2026-04-16 PM)

Five sections. Each section ships in one rolling rebuild (the prompt, tool
surface, endpoints, and schemas land together) and verifies on the
Unicorn (no-MFA) pod before the next begins. **Step 12 (per-tool-call
logging in `runner.py`) landed during the PM session.** Everything below
is fresh.

### Section A — Tool surface rewrite (pod-only)

Replaces legacy `dom_probe.py` + `teams_crawler.py` + compound scrape
tools with generic `inspect_dom`, storage primitives, `click_visual` /
`fill_visual`, `fill_credentials`, and the unified
`start_meeting_recording` / `stop_meeting_recording` / `leave_meeting`
trio. New system prompt carries product §3 decision table + §15 hard
rules + §17 MFA rules + §18 relogin gating + §10a meeting-end table.

Key files: `app/agent/prompts.py` (rewrite), `app/agent/tools.py`
(rewrite), `app/agent/_dom_query.js` (new). Delete:
`app/agent/dom_probe.py`, `app/teams_crawler.py`; trim `/crawl` +
`_DEFAULT_TAB_NAMES` from `app/routes/scrape.py`; remove `TeamsCrawler`
refs from `app/routes/session.py` and `app/main.py`.

Verification: `grep 'teams_crawler\|dom_probe\|_DEFAULT_TAB_NAMES\|\bre\.' app/`
returns nothing. One Unicorn scrape cycle produces at least one
`store_chat_row` Mongo document from scoped `inspect_dom` output.

### Section B — MFA + relogin wiring (pod + server + orchestrator)

- B1: Pydantic validator on `notify_user(kind='mfa')` requires `mfa_code`
  (`app/agent/tools.py`).
- B2: Kotlin push payload carries `mfa_code`; mobile notification body
  shows the 2-digit number (`InternalO365SessionRouting.kt`,
  `NotificationRpcImpl.kt`).
- B3: Orchestrator MCP tool `connection_approve_relogin(connection_id)`
  so users can chat-approve off-hours relogin
  (`backend/service-orchestrator/app/tools/definitions.py`).

### Section C — Meeting pipeline (pod + server + orchestrator)

Largest section. Six logical chunks, all required end-to-end for product
§10a. Lands together in one rebuild.

**C-pod** (`backend/service-o365-browser-pool/app/`):

- C-pod-1: Unify `start_meeting_recording(meeting_id?, title?, joined_by)`
  + `stop_meeting_recording(meeting_id)` + `leave_meeting(meeting_id,
  reason)`. Drop `MeetingRecorder.join()` and the `/meeting/join` HTTP
  route.
- C-pod-2: WebM pipeline inside `start_meeting_recording`: ffmpeg
  `x11grab` (5 fps VP9 from `O365_POOL_MEETING_FPS`) + PulseAudio
  `jervis_audio.monitor` (Opus) → 10-second WebM chunks →
  `{meeting_id}_{chunkIndex}.webm` in the pod chunk dir.
- C-pod-3: Disk chunk queue + upload loop mirroring
  `shared/ui-common/.../meeting/RecordingUploadService.kt` +
  `AudioChunkQueue.kt`. 3 s poll, 2 s per-chunk failure delay,
  indefinite backoff (no max-fail pause — pod is headless).
- C-pod-4: Extend background watcher (`runner.py`) with
  `participant_count`, `alone_banner`, `meeting_ended_banner`, plus
  audio silence via ffmpeg `silencedetect`. End-detection HumanMessages
  with thresholds from configmap (`O365_POOL_MEETING_PRESTART_WAIT_MIN`,
  `_LATE_ARRIVAL_ALONE_MIN`, `_ALONE_AFTER_ACTIVITY_MIN`,
  `_USER_ALONE_NOTIFY_WAIT_MIN`).
- C-pod-5: `leave_meeting` tool body — scoped DOM click on
  `[data-tid="call-end"]` + VLM fallback + verify stage disappears.

**C-srv** (`backend/server/`):

- C-srv-1: `MeetingRecordingDispatcher` posts
  `/instruction/{id} join_meeting` instead of `POST /meeting/join`. Drop
  the direct-RPC path.
- C-srv-2: `MeetingDocument` schema additions — `status` (RECORDING /
  FINALIZING / INDEXING / DONE / FAILED), `joinedByAgent`,
  `chunksReceived`, `lastChunkAt`, `webmPath`, `videoRetentionUntil`,
  `timeline[]`. Push `subscribeMeeting(id)` snapshot on every mutation
  (guideline #9).
- C-srv-3: `POST /internal/meeting/{id}/video-chunk?chunkIndex=<N>`
  idempotent endpoint (dedup by `chunkIndex`). `POST
  /internal/meeting/{id}/finalize` closes the WebM and triggers
  indexer.
- C-srv-4: `MeetingRecordingMonitor` periodic job (60 s). Stuck
  detector (`status=RECORDING` + `lastChunkAt > 5 min`) emits urgent
  USER_TASK. Hard ceiling (`scheduledEndAt + 30 min` still recording)
  posts `/instruction/{id} leave_meeting`; 5 min more without stop
  emits `notify_user(kind='error')`.
- C-srv-5: `meeting_alone_check` notify kind + chat bubble routing
  (`[Odejít] [Zůstat]` buttons → orchestrator MCP calls).
- C-srv-6: `MeetingRecordingIndexer` — ffmpeg extracts `audio.opus` →
  Whisper + pyannote on VD GPU pipeline (existing); scene-detect frames
  (`ffmpeg -vf 'select=gt(scene,0.1)+gt(mod(t,2),0)'`); per-frame VLM
  description via router `capability="vision"`; merge into `timeline[]`;
  KB index transcript + descriptions.
- C-srv-7: Nightly retention job — drop WebM files past
  `videoRetentionUntil` (default 365 days); keep metadata + transcript
  + frames + descriptions indefinitely.

**C-orch** (`backend/service-orchestrator/app/tools/definitions.py`):

- C-orch-1: MCP tools `meeting_alone_leave(meeting_id)` and
  `meeting_alone_stay(meeting_id)`. Button clicks + chat intents
  ("ten meeting už je prázdný", "nech to ještě běžet") resolve through
  these.

Verification gates:

- C-pod alone: ad-hoc meeting on Unicorn → WebM chunks arrive in
  `MeetingDocument.chunksReceived`; `stop_meeting_recording` triggers
  FINALIZING.
- C-srv full: scheduled approval → pod joins → records → FINALIZE →
  INDEXING → `timeline[]` populated → DONE.
- C-orch: user types "vypadni z meetingu" in chat → orchestrator
  resolves to `meeting_alone_leave` → pod exits.

### Section D — Agent context persistence + cleanup (pod-only)

Product §20. New Mongo collections `pod_agent_patterns` +
`pod_agent_memory`. Cold-start SystemMessage composer loads top-10
patterns + last session summary. Size-trigger (100 msgs / 40k tokens)
and nightly (02:00 Prague, idle only) cleanup. Pattern promotion at ≥ 3
successes, demotion at 3 failures, no cross-connection sharing.

Key files: `app/context_store.py` (new), `app/agent/context_cleanup.py`
(new), `app/agent/runner.py`, `app/agent/prompts.py`. D is **additive**,
not invasive — can land after A/B/C stabilize.

### Section E — Meeting View UI (Kotlin/Compose, parallel track)

Product §10a "UI visibility" + §21 item 5. Can proceed in parallel with
D once C-srv-3 is live (chunks accumulating).

- E1: `MeetingScreen` video player using `/meeting/{id}/stream.webm`.
- E2: Timeline strip with scene-change thumbnails
  (`GET /meeting/{id}/frames`), hover description, click-jumps video +
  scrolls transcript.
- E3: Transcript panel synced to audio timecode.
- E4: Live recording status row (status, `chunksReceived`, stale alert)
  driven by `subscribeMeeting(id)` push stream.

Key files: `shared/ui-common/.../meeting/MeetingScreen.kt`,
`shared/common-api/.../meeting/IMeetingService.kt`,
`shared/common-dto/.../meeting/`.

**Ordering:** A → B → C → (D || E). A unblocks observation; B unblocks
real-tenant MFA on non-Unicorn pods; C lands the meeting capture +
indexation. D and E are independent.
