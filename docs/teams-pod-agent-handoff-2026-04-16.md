# Teams Pod Agent — Session Handoff 2026-04-16

**Purpose:** Clean restart point. Previous session ended confused — this file
captures the current state of the per-connection browser pod + LangGraph
agent so the next session can pick up without re-discovering everything.

> **UPDATE 2026-04-16 EVENING — Section A–E land-&-verify pass.**
>
> Sections A, B, C-pod, C-srv (partial), D (partial), E (partial) are
> **committed**. Browser-pool image rebuilt + pushed; Unicorn pod runs the
> new image. Remaining work per section:
>
> - **A** — deployed, verified via debug `/scrape/.../inspect/tab-1` which
>   returns the new generic `{matches, count, url, truncated}` shape.
>   Full agent LLM cycle blocked by ongoing router work (500
>   `NameError priority`). Agent backs off 30 s and retries — nothing to
>   fix in pod code.
> - **B** — code complete (pod mfa_code validator + Kotlin push payload +
>   MCP `connection_approve_relogin`). **Not deployed** (needs server
>   Gradle build + rollout + MCP rebuild).
> - **C-pod** — deployed: ffmpeg WebM segment pipeline (VP9 + Opus, 10 s
>   chunks on `/browser-profiles/meeting-chunks/`), async upload loop,
>   `watcher.py` end-detection (participant_count, alone_banner,
>   meeting_ended_banner, user/agent alone thresholds).
> - **C-srv** — code complete, **not deployed**: MeetingDocument schema
>   extension (joinedByAgent, chunksReceived, lastChunkAt, webmPath,
>   videoRetentionUntil, timeline), `InternalMeetingVideoRouting`
>   (video-chunk + finalize), `InternalMeetingAloneRouting` (leave/stay +
>   suppression), `BrowserPodMeetingClient`, `MeetingRecordingDispatcher`
>   O365_BROWSER_POD route, `MeetingRecordingMonitor` (stuck + hard
>   ceiling), `MeetingVideoRetentionJob` (nightly).
> - **C-srv TODO** — `MeetingRecordingIndexer` (chunk concat + Whisper +
>   pyannote + scene-detect + VLM per frame → timeline[]) is the one big
>   remaining piece. Also: calendar indexers hooking
>   `MeetingMetadata.connectionId`.
> - **C-orch** — code complete in MCP (`meeting_alone_leave`,
>   `meeting_alone_stay`, `connection_approve_relogin`); **not deployed**
>   (MCP image rebuild).
> - **D** — deployed partial: `context_store.py` + cold-start preamble
>   loader (`compose_cold_start_preamble`) injected as HumanMessage every
>   tick. Current-state block included (`pod_state`, tabs,
>   `last_observation_*`). **LLM-driven cleanup distill pass** (compress
>   old messages → session_summary, promote selectors → workingSelectors)
>   is TODO.
> - **E** — MeetingDto + server mapper + UI badge deployed in the
>   code-base. Video player + timeline strip (E1–E3 of the design doc)
>   are deferred — they need `MeetingRecordingIndexer` to produce the
>   scene frames first and a platform-specific VideoPlayer component.
>
> **Deploy order for the next session:**
>
> 1. `./k8s/build_server.sh` — picks up MeetingDocument, dispatcher
>    routing, video endpoints, alone-check, monitor, retention job, MCP
>    C-srv-5 suppress cache.
> 2. `./k8s/build_mcp.sh` — picks up MCP tools `meeting_alone_*` +
>    `connection_approve_relogin`.
> 3. Pod image already current — no rebuild needed.
> 4. Verify first reasoning cycle on Unicorn once router is healthy.
>
> The original PM spec-rewrite block is kept below for reference.
>
> **UPDATE 2026-04-16 PM — spec rewrite session complete.**
>
> The whole architecture was critically reviewed and rewritten in one
> continuous design session. **Start the implementation from the current
> product spec + design doc + §18 migration plan — not from the morning
> state described below in §§1–5.** The morning state is kept as-is for
> archaeological reference only.
>
> **Canonical sources for the next (coding) session — read in order:**
>
> 1. `docs/teams-pod-agent.md` (product spec, 21 sections). Critical:
>    - §3 agent-chosen observation (VLM when unknown, scoped DOM when
>      verifying; supersedes the old "DOM-first" walker).
>    - §5 tool registry (generic `inspect_dom`, no `scrape_chat/mail/
>      calendar`, storage primitives, `click_visual`/`fill_visual`,
>      `leave_meeting`).
>    - §10a meeting attendance & recording (join via `/instruction/`,
>      background watcher, end-detection multi-signal with `joined_by`
>      branching, WebM chunk streaming with disk buffer + retry,
>      indexation, 365-day video retention, UI visibility).
>    - §15 hard rules (no regex / no hardcoded extractors / no direct
>      `page.*` in agent path / no `/meeting/join` RPC / no answering
>      incoming calls).
>    - §16 cold-start probe (VLM default unknown, scoped DOM default
>      known, self-correction rule).
>    - §17 Authenticator-only MFA (DOM read first, VLM fallback, code
>      propagation).
>    - §18 relogin work-hours window + UI-activity gate.
>    - §19 urgent push = DM + @mention + incoming direct call only.
>    - §20 three-layer agent memory (raw checkpoints / `pod_agent_patterns`
>      / `pod_agent_memory`) with cleanup rules.
>    - §21 open items.
>
> 2. `docs/teams-pod-agent-langgraph.md` (design doc). Critical:
>    - §3 three-layer state schema (`PodAgentState` + runner runtime +
>      Mongo) with the `ActiveMeeting` sub-state.
>    - §15 design constraints mapping.
>    - §17 tool-set refactor + deletion list.
>    - §18 migration plan, sections A (tool surface rewrite), B (MFA
>      wiring), C (meeting join + leave + recording), D (context
>      persistence), E (meeting view expansion).
>
> 3. Memory: `architecture-teams-pod-langgraph.md` — nine numbered hard
>    rules distilled from the product spec.
>
> 4. KB decisions (recent, in Jervis project):
>    `teams-pod-agent-chosen-observation` · `teams-pod-meeting-join-via-
>    instruction` · `teams-pod-background-watcher` · `teams-pod-context-
>    persistence` · `teams-pod-mfa-authenticator-only` · `teams-pod-
>    relogin-window` · `teams-pod-data-flow-split` · `teams-pod-react-spec-
>    superseded`.
>
> **Do NOT re-use the morning migration list below (§7).** The up-to-date
> sequence is design §18 A1→A8 → B1→B3 → C1→C? → D1→D6 → E (future).
> Step 12 (per-tool-call logging in `runner.py`) was landed during the PM
> session — the rest is fresh.

---

## 1. What the pod is supposed to do (product spec)

- **One pod per Microsoft 365 Connection** — runs Chromium via Playwright,
  restores session from a PVC profile, and scrapes Teams chat / Outlook mail /
  Calendar into MongoDB. WhatsApp has its own pod with the same pattern.
- **LangGraph agent** (`create_react_agent`-shaped StateGraph) drives *all*
  behavior through tools + a Czech/English system prompt.
- **DOM-first** observation (`inspect_dom` with shadow + iframe pierce). VLM
  (`look_at_screen`) is a fallback.
- **Router-only LLM** — every call goes through `jervis-ollama-router`
  (`/router/admin/decide` → `/api/chat`). No provider SDKs.
- **Read-only phase 1**: `CHAT_READ` / `EMAIL_READ` / `CALENDAR_READ`. No
  sending, no calendar writes.
- **Direct messages = urgent** — server receives `kind='urgent_message'` and
  fires a priority-95 push.
- **Discovered resources** (chats, channels, teams) propagate via
  `o365_discovered_resources` (ObjectId `connectionId`) → UI maps them to
  projects via `ProjectResource`.

---

## 2. Architecture (final)

```
Chromium (Playwright, PVC profile)
  │
  ▼
TabRegistry     — dumb {name: Page} map, auto-registers pages, no URL logic
  │
  ▼
LangGraph StateGraph
  • state:  PodAgentState (MessagesState + pod bookkeeping)
  • nodes:  agent  ↔  tools  (ToolNode from langgraph.prebuilt)
  • edges:  tools_condition
  • LLM:    RouterChatModel (BaseChatModel, bind_tools override)
  • store:  MongoDBSaver (collection `langgraph_checkpoints_pod`,
            thread_id = connection_id → survives restart)
  │
  ▼
Tools (@tool)
  inspect_dom, look_at_screen,
  list_tabs, open_tab, switch_tab, close_tab, report_capabilities,
  click, fill, press, navigate, wait,
  report_state, notify_user,
  query_user_activity, is_work_hours,
  scrape_chat, scrape_mail, scrape_calendar, mark_seen,
  meeting_presence_report, start_adhoc_meeting_recording,
  stop_adhoc_meeting_recording,
  done, error
  │
  ▼
ToolContext (contextvars.ContextVar)
  → BrowserContext, TabRegistry, ScrapeStorage, MeetingRecorder, credentials
```

**SSOT:** `docs/teams-pod-agent-langgraph.md` (design),
`docs/teams-pod-agent.md` (product spec),
`MEMORY.md → architecture-teams-pod-langgraph.md`.

---

## 3. What is on disk (uncommitted, as of 2026-04-16 morning)

New files (LangGraph agent stack):

```
backend/service-o365-browser-pool/app/agent/
  context.py      ToolContext + contextvars
  dom_probe.py    DOM walker w/ shadow + iframe pierce + data_tids diagnostic
  graph.py        StateGraph builder (agent + ToolNode + tools_condition)
  llm.py          RouterChatModel(BaseChatModel) with bind_tools override
  persistence.py  MongoDBSaver init
  prompts.py      System prompt (English body, Czech notify_user text)
  runner.py       PodAgent outer loop (astream + backoff + MFA nudge)
  state.py        PodAgentState (MessagesState subclass)
  tools.py        20+ @tool decorated functions
  work_hours.py   Mon–Fri 09–16 Europe/Prague + last-activity query
```

Rewritten to be dumb:

```
backend/service-o365-browser-pool/app/
  tab_manager.py        TabRegistry (name→Page), ~80 LOC, no business logic
  main.py               _try_self_restore = launch + agent.start(), nothing else
  routes/session.py     GET /session, POST /init, POST /mfa, DELETE — nothing else
  routes/scrape.py      tab_name-based endpoints, no TabType enum
  scrape_storage.py     tab_name: str (was TabType enum)
  config.py             meeting_screenshot_interval default 30s
  meeting_recorder.py   start_adhoc() + stop_adhoc_for_client() added
  teams_crawler.py      comment cleanup
```

Deleted (legacy raw ReAct loop):

```
backend/service-o365-browser-pool/app/agent/executor.py
backend/service-o365-browser-pool/app/agent/goals.py
backend/service-o365-browser-pool/app/agent/observer.py
backend/service-o365-browser-pool/app/agent/pod_agent.py
backend/service-o365-browser-pool/app/agent/reasoner.py
backend/service-o365-browser-pool/app/screen_scraper.py
```

Kotlin (server) changes:

```
backend/server/src/main/kotlin/com/jervis/
  rpc/internal/InternalO365SessionRouting.kt       kind-aware notify, Czech desc
  rpc/internal/InternalO365DiscoveredResourcesRouting.kt   new (GET + user-activity)
  rpc/internal/InternalMeetingAttendRouting.kt     presence endpoint
  rpc/KtorRpcServer.kt                             wire new endpoints
  rpc/NotificationRpcImpl.kt                       markActive + lastActiveAt
  meeting/MeetingAttendApprovalService.kt          recordUserPresence, suppress at-start
  teams/O365MessageLedgerDocument.kt               new
  teams/O365MessageLedgerRepository.kt             new
  connection/ConnectionRpcImpl.kt                  return cached resources + async refresh
```

Docs/memory:

```
docs/teams-pod-agent-langgraph.md           new — design SSOT
docs/teams-pod-agent.md                     §13 updated for LangGraph stack
docs/whatsapp-connection-design.md          updated for DOM-first + push
docs/guidelines.md                          §9 (push-only) + §10 (pods) added
docs/teams-pod-agent-handoff-2026-04-16.md  (this file)
MEMORY.md                                   architecture-teams-pod-langgraph.md entry
```

Nothing committed yet. `git status` shows ~35 modified + new files, plus a
few that belonged to other work (companion_sdk_runner, execute.py,
whatsapp config) that arrived from elsewhere.

---

## 4. What is deployed to K8s

- `jervis-server:latest` — rebuilt several times today, last build after
  adding `/internal/o365/notify` kind-aware + Czech description + ClientId
  FQN cleanup.
- `jervis-o365-browser-pool:latest` — rebuilt after the agent-driven
  refactor (TabRegistry, deleted bootstrap, new prompts).
- Unicorn pod (`jervis-browser-69db8fc9ff8b1eaefbbd27b1`) restarted with
  the refactored image.

Other browser pods were intentionally **not restarted** to avoid MFA ban
risk. Unicorn has no MFA — it's the safe canary.

---

## 5. Blockers right now

### 5.1 Router LLM queue saturation
`queue_depth llm = 28` (as of 09:12 UTC). Each `qwen3-coder-tool:30b` request
waits ~50 s before it runs. Agent makes 1–2 tool cycles per minute, so any
end-to-end path (open 3 tabs + verify + scrape) takes 5–10 min. Nothing in
pod code can fix this — it's upstream GPU contention.

### 5.2 Agent's first iteration doesn't open tabs
The agent started at 09:10 and, as of 09:12, had two `/api/chat` calls
succeed but the tab list still shows only `tab-1: about:blank`. Tool-call
logging in `runner.py` is *not* implemented — we can't see what the agent
actually called. **Next session: add structured tool-call logging to
`runner.py`** (`stream_mode="updates"` + log each AIMessage.tool_calls /
ToolMessage) as the first thing.

### 5.3 Sidebar TASK_LIST_CHANGED push — linter reverted
My earlier change to `UserTaskRpcImpl` (emit TASK_LIST_CHANGED on dismiss)
was reverted by the linter. Sidebar still drops dismissed tasks in UI but
the server does not emit the push event for the sidebar scope. Live chat
bubble removal works; sidebar drop relies on the next unrelated reload.
**Probably intentional** — the user wanted a pure push-only rewrite
(guideline #9) and a partial emit-on-dismiss is an odd middle ground. Leave
it.

---

## 6. Anti-patterns we removed (do NOT re-introduce)

These are recurring mistakes from the session that MUST stay deleted:

1. **`_bootstrap_tabs_if_authenticated` in `main.py`** — 60-attempt retry
   loop clicking "Retry" and force-setting tabs. It was racing the agent
   and causing the "Oops" cycle. Agent handles all of this via its prompt.
2. **`/force-setup-tabs`, `/rediscover`, `/refresh` endpoints in
   `routes/session.py`** — admin overrides bypassing agent. Unnecessary.
3. **`TabType` enum + `_BUSINESS_URLS` + `setup_tabs()` in `tab_manager.py`** —
   hardcoded which URL belongs where, login-redirect detection, marketing
   fallback. All of this is agent territory. `TabRegistry` is dumb on
   purpose.
4. **`[:N]` slicing in observation output or scrape results** — guideline #2
   says no truncation. Router escalates long context to OpenRouter; LangGraph
   trims old messages whole. Keep full lists.
5. **FQN inline** — `com.jervis…` / `java.util.concurrent…` mid-file. Use
   `import` at top.
6. **Polling in UI sidebar on dismiss** (`_sidebarRefreshTrigger.value++`) —
   the fix is server-side push (`emitTaskListChanged`) OR full push-only
   rewrite per guideline #9.

---

## 7. Where to pick up in the next session

**Start from the spec update (2026-04-16 afternoon):**
- `docs/teams-pod-agent.md` §§15–19 — hard rules, cold-start probe,
  Authenticator-only MFA, relogin window, data-flow split.
- `docs/teams-pod-agent-langgraph.md` §§15–18 — design constraints +
  numbered migration steps 12–18.

**One narrow goal per session.** Migration order is fixed by
`docs/teams-pod-agent-langgraph.md` §18. Next up is step 12:

> Add per-tool-call logging to `app/agent/runner.py` (every AIMessage
> with `tool_calls` → INFO log with name+args, every ToolMessage → INFO
> log with name+result summary). Rebuild pool image, restart Unicorn pod,
> watch one full cycle and confirm the agent calls `open_tab` for chat,
> mail, calendar. If it doesn't, tune the prompt, NOT the runtime.

Then steps 13–18 in order. Each must land + verify on Unicorn (no-MFA
canary) before next starts. Do NOT touch legacy files (§6 of this
handoff) — they were deleted on purpose and re-adding is the failure
mode we are explicitly guarding against.

After step 18:
- Verify `report_capabilities` fires with the right subset
- Verify `/scrape/{id}/discover capability=CHAT_READ` returns resources
  (with `connectionId` as ObjectId)
- Mapping in UI Settings → Přidat zdroje (connection `Unicornuniversity - teams`)
- Only after all of that, roll out to the other 3 browser pods (with MFA).
  MFA pods test Authenticator flow end-to-end (§17 of product spec).

---

## 8. Open questions

- Should pod agent use a different LLM than `qwen3-coder-tool:30b`?
  (queue-time per call is too high). `qwen3:14b` has no tools; `gpt-oss`
  might. Needs a `min_model_size` / `require_tools` fine-tune in the
  RouterChatModel `_decide` body.
- The UserTaskRpcImpl.dismiss → TASK_LIST_CHANGED push fix was reverted.
  Leave it, or do a clean push-only sidebar rewrite?
- Orchestrator chat command ("připoj Jervis na meeting") — MCP tools exist
  but orchestrator prompt doesn't hint at intent routing. Out of scope for
  the pod work.
