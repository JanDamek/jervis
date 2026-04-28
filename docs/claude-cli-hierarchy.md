# Claude CLI hierarchy — SSOT

Single source of truth for the per-(client, project) Claude SDK session
hierarchy that replaced the legacy LangGraph chat path.

> **Status (2026-04-28):** SHIPPED. 8 commits on master from
> `a06dfc8db..f12dd58b4`. Background brief:
> `~/.claude/plans/dazzling-gathering-nygaard.md`.

## Why a hierarchy

The orchestrator carries no agentic state itself — every reasoning step
that needs continuity lives in a long-running `ClaudeSDKClient` session
attached to a specific scope:

| Layer | Scope key | Purpose | Spawn trigger |
|---|---|---|---|
| Klient session | `client:<cid>` | Per-client overview, prioritisation, lazy delegation to Project | First chat with `active_client_id` |
| Project session | `project:<cid>:<pid>` | Per-(client,project) planning brain, draft-proposal author | Chat with both `active_client_id` and `active_project_id`, or qualifier delegation |
| Coding agent | K8s Job | Ephemeral execution per approved CODING proposal | `dispatch_agent_job` (`dispatchTriggeredBy=ui_approval`/`in_chat_consent`) |

**No persistent Global Claude session.** Cross-client "co dnes?" lives
in the future `service-calendar` (out-of-scope, see
`project-calendar-service-todo.md`). Identity is the future
`service-identity` (`project-identity-service-todo.md`).

## Layers

### Klient session (`ClientSessionManager`)
- Token limits: soft 150k / hard 400k.
- Subscribers: `AgentJobStateChanged` filtered by `client_id` —
  `[agent-update]` system messages surface in the next UI turn.

### Project session (`ProjectSessionManager`)
- Token limits: soft 100k / hard 300k.
- Subscribers: same push channel filtered by both `client_id` and
  `project_id`.
- Owns the proposal flow: `propose_task` → `update_proposed_task` →
  `send_for_approval`.

Both inherit `BaseClaudeSessionManager`, which owns the SDK loop, the
`out_queue`/`in_queue` machinery, periodic compact, token tracking, and
the per-session compact lock.

## SessionBroker

Process-wide singleton in `app/sessions/session_broker.py`:

- `_registry`: scope → session, gated by `_registry_lock`.
- `_lru` + cap (`MAX_ACTIVE_CLAUDE_SESSIONS`, default 20). Eviction
  picks the least-recently-touched scope that holds neither a child
  Project nor an in-flight agent job.
- `_parent_extension`: `client:<cid>` → `{project:<cid>:<pid>, …}`. A
  Klient cannot expire while one of its Projects is alive.
- `_agent_job_holds`: `agent_job_id` → holder scope. Reaper migrates
  the hold to the parent if the holder dies before the K8s Job
  completes.
- `_pause_event` for rate-limit back-pressure (`pause_all` /
  `resume_all`). Compact retry stays per-session; pauses only block
  user-driven turns.
- Audit goes into `claude_scratchpad` (scope=`broker`,
  namespace=`audit`, ttl 30d). **No new Mongo collection.**

`shutdown_all` is wired into the FastAPI lifespan SIGTERM hook;
parallel force-compacts every session within K8s 25s grace (5s buffer
before SIGKILL).

## Compact protocol

Compact is **never another turn in the host SDK conversation**. Per
session:

1. Acquire `session._compact_lock` (asyncio).
2. Build the conversation transcript via
   `chat_context_assembler._load_all_messages`.
3. Make a separate Anthropic API call with the *compaction agent*
   system prompt; retry-forever on rate-limit / connection errors
   (Core Principles).
4. Persist the markdown to `compact_snapshots`.
5. Only after the snapshot lands, signal `COMPACT_IN_PLACE` to the SDK
   to drop prior history — restart-safe even if (5) fails.

Soft trigger (cumulative tokens ≥ soft limit) schedules compact
post-response (non-blocking). Hard trigger (≥ hard limit) blocks the
next turn until compact lands. Periodic compact (420s) emits a midflight
snapshot when the session has been idle ≥ 180s.

## Proposal lifecycle

| Stage | Transitions | Mutability |
|---|---|---|
| `null` | legacy / user-created task | always pickup-eligible |
| `DRAFT` | created by `propose_task`; auto-flipped from `REJECTED` on update | mutable via `update_proposed_task` |
| `AWAITING_APPROVAL` | `send_for_approval` | **immutable** — update returns `INVALID_STATE` |
| `APPROVED` | `task_approve` from UI; sets `state=QUEUED` so BackgroundEngine picks up immediately | terminal for proposal flow |
| `REJECTED` | `task_reject(reason)` from UI; `proposalRejectionReason` populated | mutable; on update returns to `DRAFT` and clears reason |

All transitions are atomic via `MongoTemplate.findOneAndUpdate` filtered
by the source stage — no optimistic locking, no version field.

`BackgroundEngine.getNextBackgroundTask` runs through
`findApprovedByProcessingModeAndStateOrderByDeadlineAscPriorityScoreDescCreatedAtAsc`
which filters `proposalStage IN (null, APPROVED)`; DRAFT /
AWAITING_APPROVAL / REJECTED tasks never reach the executor.

### Dedup

Before insert, `OrchestratorProposalService.ProposeTask` embeds title
and description (router.Embed), pulls the last 7 days of
DRAFT/AWAITING_APPROVAL by the same `proposed_by` scope, and runs
3-tier cosine match:

- title cosine ≥ 0.85 **and** description cosine ≥ 0.80, **same
  `(clientId, projectId)`** → `REJECT` with a hint to update the
  conflicting task.
- same client, different project → `SUGGEST_CONSOLIDATE` (warning only;
  caller decides).
- different client → `ALLOW`.

Embedding failure logs a warning and falls through to `ALLOW`
(degrade-open).

## Executor — handler routing

After approval, `BackgroundEngine.executionLoop` delegates to
`ProposalDispatchHandler.dispatch(task)` based on `proposalTaskType`:

| ProposalTaskType | Handler |
|---|---|
| `CODING` | `AgentJobDispatcher.dispatch(..., dispatchTriggeredBy="ui_approval")` |
| `MAIL_REPLY` | O365 browser-pool `pushInstruction` (LangGraph agent draft) |
| `TEAMS_REPLY` | same browser-pool path with chat target metadata |
| `CALENDAR_RESPONSE` | browser-pool calendar accept/decline/tentative |
| `BUGTRACKER_ENTRY` | `BugTrackerService.createIssue` (Atlassian/GitLab/GitHub gateway) |
| `MEETING_ATTEND` | `MeetingAttendApprovalService.handleApproval` |
| `OTHER` / null | task → `state=USER_TASK` for manual review |

When the qualifier draft is missing structured metadata (calendar
event_id, bugtracker `Project: <KEY>`, coding `resourceId`), the
handler escalates to `USER_TASK` rather than guessing.

## Coding agent restart

K8s Jobs use `restartPolicy=OnFailure` with `backoffLimit=3`; SIGKILL
re-spawns on the same workspace.

- `claude-stream.jsonl` is the existing Claude CLI native stream-json
  (consumed by `ClaudeStreamJsonlParser.kt`); we add a single annotation
  event `compact_checkpoint` written when the agent reaches 180k
  tokens.
- `restart_state.parse_restart_state` pivots on the last
  `compact_checkpoint`, replays completed turns, and classifies the
  tail (incomplete tool_use / pending tool_result / non-idempotent
  Write/Edit/MultiEdit warning).
- `compact_writer.write_compact_atomic` writes `.jervis/compact.md`
  via tmp+fsync+rename, then appends the checkpoint event.
- `compact_writer.emergency_mid_restart_compact` covers the case where
  the stream grew past 150k tokens without a prior checkpoint.
- `AgentJobWatcher.capturePodRestartCount` records pod restart count
  on terminal events into `AgentJobRecord.retryCount`.

Workspace cleanup runs daily via
`k8s/delete_old_agent_workspaces.sh` (DONE/CANCELLED 24h, ERROR 7d,
RUNNING/QUEUED never).

## Audit invariant — `dispatchTriggeredBy`

`AgentJobRecord.dispatchTriggeredBy` is **non-null**; accepted values
`in_chat_consent` (user said "ano spusť" in chat), `ui_approval`
(BackgroundEngine after `task_approve`), `scheduler_cron`,
`manual`. `AgentJobDispatcher.dispatch` validates against
`ALLOWED_DISPATCH_TRIGGERS` and surfaces `IllegalArgumentException` →
`INVALID_ARGUMENT` from MCP. **No legacy default in code** — historical
records are patched once via
`k8s/migrations/2026-04-28-add-dispatch-triggered-by.sh` before the new
server image observes them.

## Qualifier integration

`backend/service-orchestrator/app/qualifier/` sits at the Jervis edge:

1. `classify_event` → `AUTO_HANDLE` (spam, ACK, calendar noise) or
   `NON_ROUTINE`. Sender = string MVP (email / Teams principalId /
   speaker label); routing via existing
   `connections.senderClientMappings` / `domainClientMappings`. No
   identity service dependency.
2. `audit_qualifier_decision` → `claude_scratchpad` (scope=qualifier,
   ns=audit, ttl 90d).
3. `persist_hint` (scope=target, ns=inbox, ttl 14d) +
   `push_into_live_session` (out_queue dict; same channel as
   `[agent-update]`, surfaces in UI immediately).
4. `proposal_emitter.draft_proposal_from_event` heuristically drafts
   `MAIL_REPLY` / `TEAMS_REPLY` / `CALENDAR_RESPONSE` /
   `BUGTRACKER_ENTRY` proposals when the source kind matches.
5. `URGENT` urgency without a live session → ad-hoc Anthropic call
   with 30s budget; on timeout, push notification "URGENT bez Claude
   reasoning" (no kill connection).

Brief builders include a `Qualifier inbox` section so the bootstrap
protocol reads pending hints first.

## UI surfaces (push-only, rule #9)

- `IProposalActionService` — approve / reject / update / sendForApproval
  + `Flow<Int>` `subscribePendingProposalsCount`. Sidebar
  `BackgroundSection` collapsible "Návrhy ke schválení (N)" subscribes.
- `IDashboardService.subscribeSessionSnapshot` — broker snapshot relayed
  via 5s server-internal poll into `MutableSharedFlow(replay=1)`. UI
  sees a true push stream; the internal pull is the only legal
  exception to rule #9 because the broker is in-process Python with no
  push channel.
- `UserTasksScreen` action bar branches by `proposalStage`
  (Schválit/Zamítnout for AWAITING_APPROVAL; Upravit + Odeslat ke
  schválení for DRAFT/REJECTED).
- `SchedulerScreen` filter chip + per-entry "Návrh Claude/Q" badge.
- `SessionDashboardScreen` (sidebar) — Aktivní sessions / Vyhozeno
  (LRU) / Tokeny.

## Critical files

**Python orchestrator:**
- `app/sessions/base_session_manager.py`
- `app/sessions/session_broker.py`
- `app/sessions/client_session_manager.py`
- `app/sessions/project_session_manager.py`
- `app/sessions/{client,project}_brief_builder.py`
- `app/agent/proposal_service.py`
- `app/qualifier/`

**Python coding agent:**
- `backend/service-coding-agent/restart_state.py`
- `backend/service-coding-agent/compact_writer.py`
- `backend/service-coding-agent/entrypoint-coding.sh`

**Python MCP:**
- `backend/service-mcp/app/main.py` — `propose_task`,
  `update_proposed_task`, `send_for_approval`, `dispatch_agent_job`
  (mandatory `dispatch_triggered_by`).

**Kotlin server:**
- `backend/server/.../task/TaskDocument.kt`
- `backend/server/.../task/ProposalEnums.kt`
- `backend/server/.../task/ProposalDispatchHandler.kt`
- `backend/server/.../task/ProposalActionRpcImpl.kt`
- `backend/server/.../infrastructure/grpc/ServerTaskProposalGrpcImpl.kt`
- `backend/server/.../dashboard/DashboardRpcImpl.kt`
- `backend/server/.../infrastructure/grpc/OrchestratorDashboardGrpcClient.kt`
- `backend/server/.../agentjob/{AgentJobRecord,AgentJobDispatcher,AgentJobWatcher}.kt`

**Kotlin/Compose UI:**
- `shared/common-api/.../service/{proposal,dashboard}/`
- `shared/common-dto/.../dto/{proposal,dashboard,task/TaskProposalInfoDto}/`
- `shared/ui-common/.../ui/UserTasksScreen.kt`
- `shared/ui-common/.../ui/SchedulerScreen.kt`
- `shared/ui-common/.../ui/dashboard/`
- `shared/ui-common/.../ui/sidebar/BackgroundSection.kt`

**Proto:**
- `proto/jervis/server/task_proposal.proto`
- `proto/jervis/orchestrator/proposal.proto`
- `proto/jervis/orchestrator/dashboard.proto`
- `proto/jervis/server/agent_job.proto` (added `dispatch_triggered_by`)

## Known follow-ups

1. **Connection per-client mapping** — `ConnectionDocument` lacks a
   per-client links field; PR-Q4 picks the first VALID
   MICROSOFT_TEAMS connection. Multi-tenant follow-up.
2. **Proposal carry structured metadata** — calendar `event_id`,
   bugtracker `Project: <KEY>`, coding `resourceId` aren't on the
   TaskDocument schema yet. Handler escalates to USER_TASK; either
   extend TaskDocument with `proposalMetadata: Map<String,String>` or
   keep relying on parser tokens in `content`.
3. **`UserTaskDto.scheduledAt`** isn't surfaced; PR-Q5 edit picker
   starts blank. Either expose the field on the DTO mapper or keep the
   "leave unchanged" semantics.
4. **iOS framework relink** — `IProposalActionService` /
   `IDashboardService` need a fresh klib build before iOS can call
   them.
5. **CMP M3 DatePicker** — iOS-flaky on Compose 1.9; PR-Q5 falls back
   to ISO textfield + presets. Replace once a stable picker ships.
6. **Token usage history chart** in `SessionDashboardScreen` is a
   placeholder — needs persistent broker stats (currently only the
   live `cumulative_tokens` per session).
