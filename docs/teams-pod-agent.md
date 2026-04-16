# Teams Browser Pod Agent — Design Spec

**Status:** Canonical SSOT
**Last updated:** 2026-04-16
**Phase:** Read-only (Phase 1). Send/write capabilities are explicitly out of scope.

---

## 1. Purpose

Autonomous agent managing **one Connection** to Microsoft 365 via Teams web.
One pod per Connection (Deployment per `ConnectionDocument._id`). The pod is
isolated, self-healing, and speaks to the Kotlin server via HTTP callbacks only.

### What the pod watches
- **Teams chat** — chat list + open conversations (direct + group)
- **Outlook mail** — inbox metadata (only if the account has Outlook access)
- **Outlook calendar** — upcoming events
- **Meeting screens** — detected in Teams; recording is strictly opt-in per user approval

### What the pod does NOT do (Phase 1)
- Never sends messages (no `CHAT_SEND`, `EMAIL_SEND`)
- Never writes calendar events (no `CALENDAR_WRITE`)
- Never auto-joins meetings — requires explicit user approval per invite
- Never closes and reopens tabs — tabs stay alive for the whole session

### Persistence
All pod output lives in MongoDB. The Kotlin server polls `o365_scrape_messages`
(state machine `NEW → INDEXED`) and `o365_message_ledger` (summary state per
chat). The pod is the sole writer; the server is the sole indexer.

---

## 2. Architecture — single ReAct loop

```
┌──────────────────────────────────────────────────────────────┐
│   PodAgent (single ReAct loop, LLM with tools)               │
│                                                              │
│   while True:                                                │
│     response = LLM.chat(messages, tools=TOOLS)               │
│     for tc in response.tool_calls:                           │
│       result = await execute_tool(tc.name, tc.args, ctx)     │
│       messages.append(tool_result)                           │
│     if response.is_done(): break                             │
└──────────┬───────────────────────────────────────────────────┘
           │
           │ uses
           ▼
┌──────────────────────────────────────────────────────────────┐
│   Tool registry                                              │
│   - inspect_dom (primary observation, pierces shadow DOM)    │
│   - look_at_screen (VLM fallback / 5-min heartbeat)          │
│   - click, fill, press, navigate                             │
│   - report_state, notify_user                                │
│   - query_user_activity, is_work_hours                       │
│   - scrape_chat, scrape_mail, scrape_calendar                │
│   - mark_seen (updates message ledger)                       │
│   - wait, done, error                                        │
└──────────────────────────────────────────────────────────────┘
```

One agent, one persistent Playwright `BrowserContext`, one login tab, and
sibling tabs (Mail, Calendar) sharing auth cookies. Tabs **never close**; the
agent navigates and switches between them.

---

## 3. Observation policy — agent chooses per turn

No fixed "DOM-first" or "VLM-first" rule. The agent picks the fastest
appropriate tool for each turn based on what it expects to find.
**VLM is the default when state is unknown; scoped DOM is the default when
verifying a known field.** Self-correcting: empty or unexpected DOM
automatically escalates to VLM.

Why not DOM-first universally: Microsoft ships markup changes regularly;
hardcoded extractors return empty silently and the agent thinks nothing
changed. Why not VLM-first universally: every VLM call is 2–10 seconds via
the router queue; a full scraping cycle would take minutes.

### Decision table (lives in the system prompt)

| Situation | Default tool | Escalation |
|-----------|--------------|------------|
| Cold start, restart, after navigate, after error, first turn of a re-observe cycle | `look_at_screen(reason)` — VLM | — |
| Known app state, checking a specific field (unread count, meeting stage, element visible) | `inspect_dom(selector, attrs=[…])` — scoped DOM, ~50–200ms | VLM when DOM returns `count=0` or unexpected shape |
| Reading MFA sign-in number | `inspect_dom("[data-display-sign-in-code],[aria-live] .number")` first | VLM `look_at_screen(reason="mfa_code")` when DOM misses it |
| Periodic sanity (ACTIVE idle > 5 min) | VLM heartbeat | — |
| Verifying an action landed (post-click) | Scoped DOM on the expected new element | VLM when DOM unchanged after 1s |
| Scraping a chat/mail/calendar list | Scoped DOM for IDs + timestamps + unread flags | VLM to disambiguate sender/content when ambiguous |

### `inspect_dom` — generic scoped query, no hardcoded extractors

The tool is a **query**, not a semantic extractor:

```
inspect_dom(
  selector: str,                 # CSS selector, shadow-DOM-piercing
  attrs: list[str] = [],         # which element attrs to return per match
  text: bool = True,             # include visible text per match
  max_matches: int = 200,
) -> {
  matches: [{text, attrs: {<k>: <v>}, bbox: {x,y,w,h}}],
  count: int,
  url: str,
  truncated: bool,
}
```

No `chat_rows`, `calendar_events`, `conversation_messages` in the return —
those were the hand-written parsers in the historical `dom_probe.py` that
broke silently when Microsoft changed markup. The agent composes higher-level
meaning turn by turn: e.g. selector `[data-tid="chat-list-item"]` with
`attrs=["data-chat-id","data-thread-id","data-unread"]` → agent reads the
JSON and decides what to open.

Shadow DOM pierce is supported transparently — the selector walks
`element.shadowRoot` recursively.

### `look_at_screen` — VLM via router

```
look_at_screen(
  reason: str,                   # e.g. "cold_start_ambiguous", "mfa_code",
                                 #      "post_action_verify", "heartbeat"
  ask: str | None = None,        # optional focused question
) -> {
  app_state: str,                # login|mfa|chat_list|conversation|meeting_stage|loading|unknown
  summary: str,                  # short natural-language description
  visible_actions: [{label, bbox}],
  detected_text: {…}             # focused extracted strings when asked
                                 # (mfa_code, error_banner, sender_name, …)
}
```

Agent passes `client_id` + `capability="vision"` to the router; the router
picks the model and backend (local VLM or cloud). No model names in pod
code.

### Self-correction rule

When `inspect_dom` returns `count=0` for a selector the agent believed
should match, the agent MUST NOT retry with a different selector guess. It
falls back to `look_at_screen` to reset its model of what is on screen.
This prevents the failure mode that made pure DOM-first unworkable: silent
empty-probe → agent thinks "nothing new" → stuck.

---

## 4. Adaptive cadence

| Event | Next tick |
|-------|-----------|
| Action taken (click/fill/navigate) | 2s |
| Page loading (spinner) | 4s |
| AUTHENTICATING | 4s |
| AWAITING_MFA | 5s scoped DOM poll on sign-in-number element; VLM once per 30s as anomaly check |
| ACTIVE, recent observation delta | 30s |
| ACTIVE, no delta for >5min | `look_at_screen` heartbeat, then 120s |
| ERROR | 60s (wait for instruction) |

Scrape cadence (agent schedules its own `wait` between cycles):
- Chat list: every 30s while unread > 0; every 5min idle
- Open conversation: every 15s while the user is reading an active chat
- Mail: every 15min
- Calendar: every 30min

Each scrape cycle is fully agent-composed from observation + storage
primitives (§5). There is no hardcoded `scrape_chat()` that walks the sidebar
on its own.

---

## 5. Tool registry

Every capability the agent has is a `@tool`-decorated Python function. No
compound "scrape_chat" that walks the whole sidebar — the agent composes
scraping from smaller primitives. Categorized below.

### Observation (§3)

| Tool | Parameters | Returns |
|------|-----------|---------|
| `inspect_dom(selector, attrs=[], text=True, max_matches=200)` | CSS selector (shadow-pierce), attrs list | `{matches: […], count, url, truncated}` |
| `look_at_screen(reason, ask=None)` | reason + optional focused question | `{app_state, summary, visible_actions, detected_text}` |

### Navigation

| Tool | Parameters | Returns |
|------|-----------|---------|
| `list_tabs()` | — | `[{name, url, active}]` |
| `open_tab(name, url)` | short name + URL | `{name}` |
| `switch_tab(name)` | — | `{url}` |
| `close_tab(name)` | — | `{closed: bool}` |
| `navigate(url)` | — | `{url}` |

### Actions

| Tool | Parameters | Returns |
|------|-----------|---------|
| `click(selector)` | CSS selector | `{clicked: bool}` |
| `click_visual(description)` | natural-language description of the element | `{clicked: bool, bbox}` — VLM resolves to bbox then clicks center |
| `fill(selector, value)` | CSS selector + literal value | `{filled: bool}` |
| `fill_visual(description, value)` | NL description + literal value | `{filled: bool}` |
| `fill_credentials(selector, field)` | CSS selector + `"email"\|"password"\|"mfa"` | `{filled: bool}` — runtime injects credential, LLM never sees the secret |
| `press(key)` | `Enter\|Tab\|Escape\|…` | — |
| `wait(seconds, reason)` | float + string | — |

### State & notifications

| Tool | Parameters | Returns |
|------|-----------|---------|
| `report_state(state, reason=None, meta=None)` | PodState enum + meta | — validated transition |
| `notify_user(kind, message, mfa_code=None, chat_id=None, sender=None, preview=None)` | see §7 + §17 | `{task_id?}` |
| `query_user_activity()` | — | `{last_active_seconds: int}` |
| `is_work_hours()` | — | `bool` |

### Storage primitives (write-only into Mongo)

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `store_chat_row(chat_id, chat_name, is_direct, is_group, last_message_at, unread_count, unread_direct_count)` | ledger upsert | `o365_message_ledger` |
| `store_message(chat_id, message_id, sender, content, timestamp, is_mention, attachment_kind=None)` | new message row | `o365_scrape_messages` (state=NEW) |
| `store_discovered_resource(resource_type, external_id, display_name, team_name=None, description=None)` | upsert | `o365_discovered_resources` |
| `store_calendar_event(external_id, title, start, end, organizer, join_url=None)` | upsert | `scraped_calendar` |
| `store_mail_header(external_id, sender, subject, received_at, preview, is_unread)` | upsert (metadata only, no body) | `scraped_mail` |
| `mark_seen(chat_id)` | — | Ledger `lastSeenAt=now, unreadCount=0, unreadDirectCount=0` |

### Meeting recording

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `meeting_presence_report(present, meeting_stage_visible)` | bool + bool | Presence signal for server (§10a) |
| `start_meeting_recording(meeting_id=None, title=None, joined_by="agent")` | optional calendar-linked id; title for ad-hoc; `joined_by` = "user" for VNC-joined, "agent" for /instruction/-joined | Allocates MeetingDocument if `meeting_id` is None; starts ffmpeg WebM chunk pipeline (audio + video @ 5 fps) streaming to server |
| `stop_meeting_recording(meeting_id)` | — | Flushes remaining chunks, posts finalize, transitions server-side `status` to FINALIZING |
| `leave_meeting(meeting_id, reason)` | id + short reason string | (1) `stop_meeting_recording`, (2) click "Leave" (`[data-tid="call-end"]`, VLM fallback), (3) verify `meeting_stage=false` within 10 s, (4) `meeting_presence_report(present=false)` |

### Control

| Tool | Parameters | Purpose |
|------|-----------|---------|
| `done(summary)` | str | Current goal complete — drop out of the tool loop |
| `error(reason, screenshot=False)` | str | Hard error; agent waits for instruction |

All tools take their browser / storage / meeting dependencies from a
`ContextVar` (one pod = one connection = one context) — tool signatures carry
only the semantic args, not `Page` / `MongoClient` / etc.

---

## 6. Message ledger — "what's new?" state

Collection `o365_message_ledger`, one document per `(connectionId, chatId)`:

```json
{
  "connectionId": "…",
  "clientId": "…",
  "chatId": "chat_mamka",
  "chatName": "Mamka",
  "isDirect": true,
  "isGroup": false,
  "lastSeenAt": "2026-04-16T14:32:00Z",
  "lastMessageAt": "2026-04-16T14:40:00Z",
  "unreadCount": 3,
  "unreadDirectCount": 3,
  "lastUrgentAt": "2026-04-16T14:40:00Z",
  "lastNotifiedAt": "2026-04-16T14:40:02Z"
}
```

**Invariants:**
- `scrape_chat` writes one ledger row per observed chat.
- `mark_seen(chatId)` sets `lastSeenAt=now`, `unreadCount=0`, `unreadDirectCount=0`.
- Server indexes new messages from `o365_scrape_messages` (state `NEW→INDEXED`)
  and reads the ledger for UI badges + urgency triggers.
- Pod never deletes ledger rows — soft lifecycle via `lastSeenAt`.

---

## 7. Notifications — direct message = urgent

Endpoint `POST /internal/o365/notify` on Kotlin server. Fire-and-forget from
the pod.

```json
{
  "connectionId": "…",
  "kind": "urgent_message | meeting_invite | meeting_alone_check | auth_request | mfa | error | info",
  "chatId": "chat_mamka",                // urgent_message only
  "chatName": "Mamka",                   // urgent_message only
  "sender": "Mamka",                     // urgent_message only
  "preview": "Ahoj, můžeš se podívat…",  // urgent_message only
  "message": "Nová direct zpráva od Mamka",
  "mfa_code": "42",                      // mfa only, see §17
  "meeting_id": "…",                     // meeting_alone_check only
  "screenshot": "/data/…/screenshot.jpg" // optional
}
```

| Kind | Server behavior |
|------|-----------------|
| `urgent_message` | USER_TASK `priorityScore=95`, `alwaysPush=true`, FCM+APNs, kRPC emit. Direct messages + @mentions + incoming direct calls (see §15). |
| `meeting_invite` | USER_TASK 80, push+kRPC, expects approval via `POST /instruction/{connectionId}` (`join-meeting:<target>` / `skip-meeting`). |
| `meeting_alone_check` | USER_TASK 65, `alwaysPush=true`, chat bubble with `[Odejít] [Zůstat]` buttons. Emitted when user-joined meeting has been alone > 1 min (see §10a). Orchestrator routes button clicks / chat intents to MCP `meeting_alone_leave(meeting_id)` / `meeting_alone_stay(meeting_id)`. |
| `auth_request` | USER_TASK 75, outside work hours login consent. |
| `mfa` | USER_TASK 70 with MFA `mfa_code` in metadata — push body shows the 2-digit number (see §17). |
| `error` | USER_TASK 60, stuck agent, includes screenshot. |
| `info` | kRPC only, no push. |

**De-dup:** the server deduplicates `urgent_message` per `(connectionId, chatId)`
within a 60s window — if the pod keeps observing the same unread chat, the
server fires at most one push per chat per minute. Server-side dedup uses the
ledger's `lastNotifiedAt`.

**Per-context once rule:** the agent emits `notify_user` at most once per
context transition. After notifying, the agent transitions to `wait`/observe
until the context changes (new sender, new chat, state transition).

---

## 8. Work hours + user activity

```python
TIMEZONE = "Europe/Prague"
WORK_DAYS = {0, 1, 2, 3, 4}  # Mon–Fri
WORK_START_HOUR, WORK_END_HOUR = 9, 16
RECENT_ACTIVITY_THRESHOLD_S = 300
```

- `is_work_hours_now()` — local check, no server round-trip
- `query_user_activity(client_id)` — `GET /internal/user/last-activity?clientId=X`
  → `{last_active_seconds: int}`. Server derives this from last kRPC ping,
  last HTTP request, or UI focus event.

**Outside work hours + user idle >5 min → `auth_request` before any credential
submission.** The pod never submits credentials without a fresh window of consent
off-hours.

---

## 9. State machine

```
STARTING ──┬→ AUTHENTICATING ──┬→ AWAITING_MFA ──→ ACTIVE
           │                   ├→ ACTIVE
           │                   └→ ERROR
           ├→ ACTIVE
           └→ ERROR

ACTIVE ──┬→ RECOVERING ──→ AUTHENTICATING
         ├→ ERROR
         └→ EXECUTING_INSTRUCTION ──→ ACTIVE

ERROR ──→ EXECUTING_INSTRUCTION ──→ AUTHENTICATING / ACTIVE / ERROR
```

Validated by `pod_state.py:_TRANSITIONS`. Agent sees current state + valid next
states in the prompt and mutates only via `report_state`.

---

## 10. Pod ↔ server endpoints

| Direction | Endpoint | Purpose |
|-----------|----------|---------|
| Pod → Server | `POST /internal/o365/session-event` | State change (AWAITING_MFA, EXPIRED) |
| Pod → Server | `POST /internal/o365/notify` | Kind-aware push (see §7) |
| Pod → Server | `POST /internal/o365/capabilities-discovered` | Capabilities per Connection |
| Pod → Server | `GET /internal/user/last-activity?clientId=X` | User activity check |
| Pod → Mongo | direct write | `o365_scrape_messages`, `o365_discovered_resources`, `o365_message_ledger` |
| Server → Pod | `POST /instruction/{connectionId}` | JERVIS instruction (join/skip meeting, recover, …) |
| Server → Pod | `POST /session/{connectionId}/mfa` | Submit MFA code |
| Server → UI | `GET /internal/o365/discovered-resources?connectionId=X` | List discovered chats/channels for project assignment |
| UI → Server | `PUT /project/{projectId}/resources` | Attach a discovered resource to a project |

---

## 10a. Meeting attendance & recording

### Overview

The pod never decides to join a meeting on its own. Two entry paths:

- **Scheduled (agent-joined):** calendar approval → `POST /instruction/{id}`
  with `join_meeting` payload → agent composes navigate + join + record.
- **Ad-hoc (user-joined):** user clicks Join in Teams via VNC → background
  watcher detects `meeting_stage` rising → agent starts recording.

Recording streams WebM (audio + video at 5 fps) in 10-second chunks with a
local disk buffer + indefinite retry. The server archives the WebM and
runs indexation (Whisper + pyannote diarization + scene-detection + VLM
frame descriptions) after finalize. Pod meetings are visible in the UI
Meeting list with a live status stream.

Hard rule (§15): **agent never accepts an incoming direct call**, only
notifies the user.

### Approval flow (scheduled meetings)

1. `CalendarContinuousIndexer` creates a `CALENDAR_PROCESSING` task with
   `meetingMetadata { joinUrl, startTime, endTime, organizer }`.
2. `MeetingAttendApprovalService` polls every 60 s. In the 10-minute
   preroll window it fires push + chat ALERT bubble ("Schválit připojení
   Jervise?").
3. **At-start fallback** fires a second push when the meeting is starting
   and the approval is still `PENDING` — **unless** the pod has reported
   `meeting_presence_report(present=true, meeting_stage_visible=true)` in
   the last 120 s (user joined manually → no redundant push).
4. User approves via the chat bubble button **or** by writing intent in
   the chat (orchestrator agent calls MCP `meeting_attend_approve(task_id)`).

### Join flow (agent-driven, scheduled)

On `APPROVED` the server posts:

```
POST /instruction/{connectionId}
{
  "instruction": "join_meeting",
  "meeting_id": "<MeetingDocument._id>",
  "title": "<title>",
  "join_url": "<teams meeting url>",
  "start_time": "<ISO>",
  "end_time": "<ISO>"
}
```

The pod appends a `HumanMessage` spelling out the expected tool chain
(navigate, mute, click Join, start recording, monitor until end). The
agent then composes the tool calls itself:

1. `navigate(join_url)` + `look_at_screen(reason="teams_prejoin")`.
2. `click_visual("mic off")` if not already muted; same for camera.
3. `click_visual("Join now")` or
   `click("[data-tid='prejoin-join-button']")` — whichever resolves
   reliably.
4. Poll `inspect_dom("[data-tid='meeting-stage']")` every 2 s; escalate
   to `look_at_screen` on timeout.
5. On stage visible:
   `start_meeting_recording(meeting_id=<M>, joined_by="agent")` +
   `meeting_presence_report(present=true, meeting_stage_visible=true)`.
6. Agent keeps cycling at §4 cadences. End-detection signals (below)
   drive the `leave_meeting` decision.

### Ad-hoc join (user via VNC)

No calendar task, no instruction. The background watcher (below) detects
`meeting_stage` rising and alerts the agent, which calls:

```
start_meeting_recording(
  meeting_id=None,              # tool allocates new MeetingDocument
  title=<derived from VLM/tab>,
  joined_by="user",
)
```

### Background watcher

Runs in `app/agent/runner.py` independently of LangGraph ticks. Every
`O365_POOL_WATCHER_INTERVAL_S` (default 2 s) it runs **one pure JS check**
per registered tab — no LLM, no router, ~5 ms per tab:

```js
({
  meeting_stage:   !!document.querySelector('[data-tid="meeting-stage"], [data-tid="calling-screen"]'),
  incoming_call:   !!document.querySelector('[data-tid="call-toast"], [role="dialog"][aria-label*="incoming" i]'),
  // Only relevant while an active meeting is being recorded:
  participant_count: parseInt(document.querySelector('[data-tid="roster-button"] .count')?.textContent || '0', 10) || null,
  alone_banner:    !!document.querySelector('[data-tid="alone-in-meeting-banner"]'),
  meeting_ended_banner: !!document.querySelector('[data-tid="meeting-ended"]'),
})
```

Plus audio silence — ffmpeg `silencedetect` filter on the meeting audio
stream keeps `last_speech_at` updated.

The watcher is a **sensor, not a controller.** It never calls tools,
never clicks, never touches Playwright beyond `page.evaluate()`. It
pushes priority `HumanMessage`s into `PodAgent._pending_inputs` — the
agent consumes them on the next outer-loop entry (0.5–2 s idle, 2–5 s
during an in-flight LLM call). The agent decides what to do.

### Watcher signals — reference

**`meeting_stage` rising edge:**
> ALERT: meeting_stage appeared on tab `<name>`. If there is no active
> meeting in state, the user joined ad-hoc: call
> `start_meeting_recording(meeting_id=None, joined_by="user",
> title=<VLM/tab>)` + `meeting_presence_report(present=true,
> meeting_stage_visible=true)`.

**`meeting_stage` falling edge:**
> ALERT: meeting_stage disappeared on tab `<name>`. If there is an active
> meeting in state, the user / pod disconnected: call
> `stop_meeting_recording(meeting_id=<active>)` +
> `meeting_presence_report(present=false, meeting_stage_visible=false)`.

**`incoming_call` rising edge:**
> ALERT: incoming call toast on tab `<name>`. NEVER click accept /
> answer / join (§15). Read caller name via scoped DOM first (patterns
> from §20, fall back to `[data-tid="call-toast"] [class*="caller"]`).
> VLM only on DOM `count=0`. Call `notify_user(kind='urgent_message',
> sender=<name>, preview="Příchozí hovor od <name>")`.

**`incoming_call` falling edge:** no-op.

**Participant / banner / silence signals** drive end-detection (below)
and are only meaningful while `active_meeting` is set.

### Meeting end detection

Alone ≠ end. The interpretation depends on **how the pod joined** and on
the **history of participants**.

**joined_by = "user" (ad-hoc):**

| Trigger | Action |
|---------|--------|
| `alone_since ≥ 1 min` (first time) | `notify_user(kind='meeting_alone_check', meeting_id=<M>, preview="Pořád jsi v meetingu '<title>'. Ještě ho potřebuješ?")` + chat bubble with `[Odejít] [Zůstat]` |
| Reaction: button "Odejít" / chat intent | Orchestrator posts `/instruction/{id} leave_meeting` → agent calls `leave_meeting(meeting_id, reason="user_asked_to_leave")` |
| Reaction: button "Zůstat" / chat intent | Orchestrator posts `/instruction/{id} meeting_stay` → reset `alone_since`, suppress further `meeting_alone_check` for 30 min |
| No reaction in `O365_POOL_MEETING_USER_ALONE_NOTIFY_WAIT_MIN` (default 5 min) | Agent calls `leave_meeting(meeting_id, reason="no_user_response")` + `notify_user(kind='info', message="Odešel jsem z prázdného meetingu, 5 min bez reakce.")` |
| Audio speech / `participant_count > 1` / user activity ping | Reset `alone_since` silently |

**joined_by = "agent" (scheduled):**

| Trigger | Action |
|---------|--------|
| `max_participants_seen_since_join ≤ 1` AND `now < scheduled_start + O365_POOL_MEETING_PRESTART_WAIT_MIN` (default 15) | No action — pre-meeting wait |
| `max_participants_seen_since_join ≤ 1` AND pod joined > 5 min after `scheduled_start` AND `alone_since ≥ O365_POOL_MEETING_LATE_ARRIVAL_ALONE_MIN` (default 1) | Alert agent: "Late arrival, nobody here — probably already ended." → `leave_meeting(meeting_id, reason="late_arrival_empty")` |
| `max_participants_seen_since_join ≤ 1` AND `now ≥ scheduled_start + PRESTART_WAIT_MIN` | Alert agent: "15 min past start, nobody joined — leave." → `leave_meeting(meeting_id, reason="no_show")` |
| `max_participants_seen_since_join > 1` AND `alone_since ≥ O365_POOL_MEETING_ALONE_AFTER_ACTIVITY_MIN` (default 2) | Alert agent: "Everyone left, meeting ended." → `leave_meeting(meeting_id, reason="post_activity_alone")` |
| `meeting_ended_banner` rising edge | Immediate `leave_meeting(meeting_id, reason="meeting_ended_banner")` |

**Server hard ceiling (both join modes):**
`MeetingRecordingMonitor` job (new, sibling of `MeetingAttendApprovalService`)
runs every 60 s. If `MeetingDocument.status=RECORDING` and `now >
scheduledEndAt + 30 min`, it posts:

```
POST /instruction/{connectionId}
{ "instruction": "leave_meeting", "meeting_id": "<M>", "reason": "scheduled_overrun" }
```

If the pod still has `meeting_stage=true` for this `meeting_id` 5 min
later, the server fires `notify_user(kind='error')` to the user ("Jervis
uvízl v meetingu, opusť manuálně").

### `leave_meeting` tool contract

The agent — never the watcher, never the server directly — calls
`leave_meeting(meeting_id, reason)`. The tool:

1. `stop_meeting_recording(meeting_id)` — flush remaining chunks, POST
   `/internal/meeting/{id}/finalize`.
2. Click "Leave": `click("[data-tid='call-end']")`, VLM fallback
   `click_visual("Leave")`.
3. Wait up to 10 s for `meeting_stage=false` (scoped DOM poll).
4. `meeting_presence_report(present=false, meeting_stage_visible=false)`.

If step 3 times out, the tool returns `{left: false}` and the agent
retries or emits `notify_user(kind='error', message="Couldn't leave
meeting — manual intervention needed")`.

### Recording pipeline — WebM chunks with disk buffer + retry

Pod-side encoding (inside `start_meeting_recording`):

- Single ffmpeg pipeline:
  - Video: `x11grab` the Teams window on the pod's Xvfb → VP9 encode at
    `O365_POOL_MEETING_FPS` (default 5) frames per second.
  - Audio: PulseAudio `jervis_audio.monitor` → Opus encode.
  - Container: WebM, 10-second chunks (`O365_POOL_MEETING_CHUNK_SECONDS`).
  - Output: rolling file named `{meeting_id}_{chunkIndex}.webm` in the
    pod's chunk queue directory.

Upload loop (separate async task, mirrors
`shared/ui-common/.../meeting/RecordingUploadService.kt`):

- Disk FIFO queue (`{meeting_id}_{chunkIndex}.webm` + `pending.json`
  index) in the pod's chunk dir. Survives restart.
- Poll every 3 s; for each pending chunk in order:
  - Check connection health (last successful POST within 30 s OR probe
    `GET /health`). If server unreachable, wait.
  - `POST /internal/meeting/{id}/video-chunk?chunkIndex=<N>` with the
    WebM bytes as body. Server idempotency: duplicate `chunkIndex`
    returns 200 without re-appending.
  - On 2xx: unlink file, update `pending.json`, advance.
  - On failure: `await asyncio.sleep(2)` then continue with next chunk
    (indefinite retry, no max-fail pause unlike UI — pod is headless,
    can't show a retry button).
- No buffer size cap; pod relies on PVC space. If the queue grows > 500
  MB, emit `notify_user(kind='error', message="Meeting chunk queue
  backlog > 500 MB — server unreachable?")` once per hour.

Server-side stuck detector (new job, hourly-ish):

- `status=RECORDING` and `lastChunkAt > 5 min ago` → emit urgent
  USER_TASK (`priorityScore=90`): "Meeting `<title>` upload stuck —
  `<N>` chunks pending since `<lastChunkAt>`. Check pod health."

### `MeetingDocument` lifecycle

```
status: RECORDING → FINALIZING → INDEXING → DONE
                               ↘ FAILED
```

Schema additions over the existing `MeetingDocument`:

| Field | Type | Notes |
|-------|------|-------|
| `status` | enum | see above |
| `joinedByAgent` | bool | default false; UI label |
| `chunksReceived` | int | bumped on each accepted chunk POST |
| `lastChunkAt` | ISODate | for stuck detector + UI staleness |
| `webmPath` | str | server filesystem path after FINALIZING |
| `videoRetentionUntil` | ISODate | `createdAt + O365_POOL_MEETING_VIDEO_RETENTION_DAYS` (default 365); cleanup job drops the WebM after this, keeps metadata + transcript + frames indefinitely |
| `timeline[]` | array | `{ts, diarizedSegment?, frameThumbPath?, frameDescription?}` — assembled during INDEXING |

### Indexation pipeline (server-side, post-FINALIZE)

Mirror of the existing audio-meeting pipeline (`architecture-whisper-diarization.md`)
plus new frame-extraction step:

1. **Audio:** ffmpeg extract `audio.opus` from WebM → Whisper + pyannote
   diarization on VD GPU (same as current audio meetings). Output:
   diarized transcript with timestamps.
2. **Frames:** ffmpeg scene detection + min 1 frame per 2 s:
   ```
   ffmpeg -i meeting.webm \
     -vf "select='gt(scene,0.1)+gt(mod(t,2),0)'" \
     -vsync vfr frames/frame_%04d.jpg
   ```
   Per extracted frame → VLM via router (`capability="vision"`, prompt:
   "describe what changed on screen since the previous frame") → short
   description string + timestamp.
3. **Timeline assembly:** merge diarized segments + scene entries by
   timestamp into `MeetingDocument.timeline[]`.
4. **KB indexing:** transcript + scene descriptions into the KB graph
   (standard meeting-indexation path).
5. `status=DONE`, emit `MeetingDocument` push on the meeting stream.

### UI visibility

Pod-recorded meetings appear in the same `subscribeMeetings` stream as
UI-recorded meetings. The UI row shows:

- Title
- `status` (RECORDING / FINALIZING / INDEXING / DONE / FAILED)
- `chunksReceived` + `lastChunkAt` during RECORDING
- `joinedByAgent` icon (Jervis auto-joined vs user started)

The live stream is push-only per guideline #9 (`subscribeMeeting(id)`
`Flow<MeetingSnapshot>` with replay=1).

In the Meeting view (§21 items 5–7):

- Embedded `<video src="/meeting/{id}/stream.webm">` player.
- Timeline strip of scene-change thumbnails under the video; hover shows
  frame description, click jumps video + scrolls transcript.
- Transcript panel synced to audio timecode.

Pod meetings get **no manual retry button** — the upload loop is
indefinite. The UI only reports status; action is server-driven via the
stuck detector's USER_TASK.

### Miss windows

- Meeting start captured within ~2–5 s (watcher poll + agent tick).
- Meetings < 2 s may be missed; acceptable.
- Meeting already in progress at pod restart: cold-start probe (§16)
  picks up `meeting_stage=true` on the first VLM observation.

### Chat commands (orchestrator MCP)

Scheduled approval:
- `meetings_upcoming(hours_ahead=24)`
- `meeting_attend_approve(task_id)` / `meeting_attend_deny(task_id, reason?)` / `meeting_attend_status(task_id)`

User-joined alone check (new):
- `meeting_alone_leave(meeting_id)` — user said "odejdi"
- `meeting_alone_stay(meeting_id)` — user said "zůstaň ještě"

Intent examples:
- "připoj se na ten meeting za chvíli" → `meetings_upcoming` → nearest →
  `meeting_attend_approve`
- "vypadni z meetingu" / "ten meeting už je prázdný" →
  `meeting_alone_leave`
- "nech to ještě běžet" → `meeting_alone_stay`

---

## 11. Discovered resources — UI project assignment

The pod writes to `o365_discovered_resources` whenever it sees a new chat,
channel, team, or calendar. The server exposes:

```
GET /internal/o365/discovered-resources?connectionId=<id>&resourceType=chat
→ [{ externalId, resourceType, displayName, description, teamName, active }, …]
```

The UI (Settings → Connection → Resources) reads this list and lets the user
attach each resource to a specific `Project` via `ProjectResource` mapping.
Nothing is auto-mapped; multi-project overlap is allowed.

The polling handler uses `ResourceFilter` on `ProjectResource` links to decide
which chats to index per project — the pod keeps scraping everything; filtering
happens at index time, not scrape time.

---

## 12. Router-first LLM/VLM

All LLM and VLM calls go through `jervis-ollama-router` via `/route-decision`
(returns `target: local | openrouter`, model id, api_base). No pod code ever
calls a provider directly. The router holds client-tier policy — the pod passes
`client_id` and `capability` (`vision` / `tool-calling`), nothing else.

Stream + heartbeat only — no hard HTTP timeouts on LLM calls. This mirrors the
project-wide principle.

---

## 13. Implementation layout

The agent is built on **LangGraph + MongoDBSaver** (same stack as the
`service-orchestrator`). Full design: `docs/teams-pod-agent-langgraph.md`.

| # | Component | File | Status |
|---|-----------|------|--------|
| 1 | LangGraph state + graph | `app/agent/state.py`, `app/agent/graph.py` | implement |
| 2 | Router-backed LLM (`BaseChatModel`) | `app/agent/llm.py` | implement |
| 3 | Tools (`@tool` decorators) | `app/agent/tools.py` | implement |
| 4 | ContextVar for tool dependencies | `app/agent/context.py` | implement |
| 5 | MongoDB checkpointer | `app/agent/persistence.py` | implement |
| 6 | Agent runner (outer loop + restart recovery) | `app/agent/runner.py` | implement |
| 7 | System prompts | `app/agent/prompts.py` | implement |
| 8 | Work hours + activity query | `app/agent/work_hours.py` | implement |
| 9 | DOM probe JS + dataclass | `app/agent/dom_probe.py` | **keep** |
| 10 | Ledger + scrape storage (Mongo ObjectId) | `app/scrape_storage.py` | **keep** |
| 11 | Server endpoints (notify, discovered-resources, user-activity, meeting-presence) | Kotlin | **done** |
| 12 | Keep as-is | `pod_state.py`, `browser_manager.py`, `tab_manager.py`, `kotlin_callback.py`, `routes/instruction.py`, `vnc_*`, `meeting_recorder.py` | — |

No legacy, no deprecated markers. Raw ReAct loop in `app/agent/loop.py`
is replaced by LangGraph (`graph.py` + `runner.py`).

---

## 15. Hard rules — only path is LangGraph → tools

Absolute bans (breaking these = legacy code to delete, not "fix"):

- **No regex / no HTML parsing / no hand-written string extraction** anywhere in
  pod code. All observation goes through `inspect_dom` (scoped CSS query
  returning structured `{matches, count, url}`) or `look_at_screen` (VLM via
  router). Downstream code consumes structured fields, never raw HTML/text.
- **No hardcoded semantic extractors in the DOM probe.** `inspect_dom` is a
  generic query tool — it never returns `chat_rows`, `calendar_events`, or
  any other field named after a product concept. Those were the exact shape
  of the historical `dom_probe.py` walker, which broke silently on every
  Microsoft markup update and is why DOM-first observation did not work.
- **No hardcoded URL lists, tab types, app-role enums in runtime code.**
  `TabRegistry` is a dumb `{name: Page}` map. The agent decides what each tab
  is for via its own observation + prompt, not via `_BUSINESS_URLS`,
  `TabType`, or `setup_tabs()`.
- **No bootstrap retry loops** (`_bootstrap_tabs_if_authenticated`,
  `/force-setup-tabs`, `/rediscover`). The agent owns recovery.
- **No admin-override HTTP endpoints** that bypass the agent (`/force-*`,
  `/refresh`, `/meeting/join` direct RPC). The only control path is
  `/instruction/{connectionId}` (server → agent as a HumanMessage) and
  `/session/{id}/mfa` (server resumes a `interrupt()`). Meeting join is
  server→agent→tools, not server→RPC→ffmpeg (§10a).
- **No `[:N]` slicing / truncation.** Full lists go to the router; context
  budget is managed by LangGraph `trim_messages` at message-boundary granularity.
- **No provider SDK imports** — all LLM/VLM via router with
  `capability="chat"` or `capability="vision"`. No model names in pod code.
- **No direct Playwright calls outside `@tool` functions** (and outside
  narrow server-side helpers like `token_extractor.py` that run offline,
  never during agent ticks, plus the read-only `page.evaluate()` in the
  background watcher — §10a). Agent never touches `page.*` directly.
- **Agent NEVER accepts / answers / joins an incoming direct call.**
  Incoming call toast → `notify_user(kind='urgent_message', ...)` and
  nothing else. The user handles the call themselves. No `click("Accept")`,
  no `click_visual("answer")`, no keyboard shortcut. This rule is stronger
  than the general "never auto-join meeting" rule because a direct call is
  a live human waiting — accidentally clicking accept would expose an open
  mic / unexpected presence.

The path is exactly **agent ↔ tools**: the LLM emits `tool_calls`, `ToolNode`
executes them, results flow back. Anything that isn't a tool call or an
observation is out of the loop by construction.

---

## 16. Cold-start — agent starts from an empty context

On fresh start (new pod, restart, checkpoint resume with trimmed history) the
agent MUST probe before acting. No assumption that tabs are arranged, session
valid, or login completed. Flow:

1. `list_tabs()` → what tabs exist? (TabRegistry auto-registered any Pages the
   browser restored from PVC profile.)
2. `look_at_screen(reason="cold_start")` on the active tab → VLM returns
   `app_state` (login / mfa / chat_list / conversation / meeting_stage /
   loading / unknown). Cold start = unknown state = VLM default (§3).
3. Based on `app_state`, the agent may call `inspect_dom(selector, attrs)`
   to pick up precise IDs or attributes it needs for the next step (e.g.
   chat IDs after VLM confirmed "chat list visible"). Scoped, never full
   walker.
4. Decide state via `report_state(...)`:
   - `ACTIVE` → proceed to scrape cycle (§4).
   - `AUTHENTICATING` → credential entry (§17).
   - `AWAITING_MFA` → MFA flow (§17).
   - `ERROR` → notify + wait for instruction.
5. Only after state is known, open/switch tabs via `open_tab` / `switch_tab`.
   The agent never opens a new tab "just in case" — only on demand for a
   specific capability (chat / mail / calendar).

After cold start, **subsequent turns do NOT need VLM** unless the agent has
no expectation about the screen. In the steady state (ACTIVE with a known
chat list view), the agent polls scoped DOM and only falls back to VLM when
DOM returns empty / unexpected shape (self-correction rule, §3).

Context cleanup is NOT the agent's job. LangGraph `trim_messages(strategy="last",
max_tokens=…)` at the entry of the agent node drops old messages whole,
preserving `tool_call_id` consistency. On restart, `MongoDBSaver` replays the
trimmed history; probe (1)–(4) still runs because the prompt instructs it as
the first step of every "re-observe" turn.

**Browser-state-first**, not action-first. The agent answers "what is on the
screen?" before "what should I click?" — every turn, not just the first. But
"what is on the screen?" is usually a fast scoped DOM check, not a VLM call.

---

## 17. MFA policy — Microsoft Authenticator only + code push

**Only Microsoft Authenticator (push-based "approve this sign-in" + 2-digit
number match) is an allowed second factor.** All others are forbidden:

- No SMS code entry
- No voice-call code entry
- No email code entry
- No security key / FIDO
- No TOTP from a generic authenticator app

If the DOM shows a method chooser with multiple options, the agent clicks the
Microsoft Authenticator option. If Microsoft Authenticator is not offered
(account forced to another factor), the agent emits `notify_user(kind='error',
message="MFA method unsupported: <observed>")` and transitions to `ERROR`
— user must resolve at their M365 tenant.

### Code propagation

Modern Authenticator sign-in shows a **2-digit number** on the login page
that the user must tap on their phone. Flow:

1. Agent detects the MFA prompt. First choice is `look_at_screen(
   reason="mfa_detect", ask="is this Microsoft Authenticator number-match?")`
   — cold-start of a sign-in screen is always VLM (§3).
2. Agent reads the number. Scoped DOM first:
   `inspect_dom("[data-display-sign-in-code], [aria-live] .number,
   .sign-in-number", attrs=["aria-label","data-display-sign-in-code"])`.
   If DOM `count=0` or the match has no numeric text → fall back to
   `look_at_screen(reason="mfa_code", ask="return the 2-digit number shown
   on the page, nothing else")`.
3. Agent calls `notify_user(kind='mfa', mfa_code="42", preview="Potvrď 42
   v Microsoft Authenticatoru.")` immediately. Server fires
   `USER_TASK priorityScore=70 alwaysPush=true` with the code in the
   metadata so the UI + mobile push show **the number** itself (not just
   "approve login").
4. Agent transitions to `AWAITING_MFA` and enters a LangGraph `interrupt()`
   (or re-polls scoped DOM on the number element every 5s waiting for state
   change).
5. If the code rotates (Microsoft regenerates after timeout or re-challenge),
   the agent re-reads and emits a **new** `notify_user(kind='mfa',
   mfa_code=…)` with the current number. The old push is superseded
   client-side by code equality — no server-side dedup on MFA because the
   number itself is the dedup key.
6. On successful sign-in — DOM `[data-tid='meeting-stage']`, `[data-tid=
   'chat-list']`, or any `app_state != login/mfa` from VLM — transition to
   `ACTIVE`. On timeout/denial → `ERROR` with reason.

**Never** does the agent try to "read and submit" the code itself. The user
approves on their phone Authenticator; the pod only mirrors what the login
screen shows.

---

## 18. Relogin window — work hours + server-confirmed UI activity

Relogin (credential submission after a session expiry or forced re-challenge)
is disruptive — it surfaces an MFA push to the user's phone. Rule:

- **Trigger allowed if** `is_work_hours()` returns `true` (Mon–Fri 09:00–16:00
  Europe/Prague) **OR** `query_user_activity()` returns
  `last_active_seconds <= 300` (server confirms UI focus / kRPC ping in the
  last 5 minutes).
- **Neither condition** → agent stays in `AUTHENTICATING` or `ERROR`, emits
  `notify_user(kind='auth_request', message="Session expired. Approve relogin?")`
  at most once per 60 min, and waits for either: work-hours window to open,
  user activity ping, or explicit `/instruction/{id}` payload
  `approve-relogin`.

The agent **remembers in its checkpoint** that relogin is pending (state stays
`AUTHENTICATING`, note in last AIMessage). Because the checkpoint is on
`thread_id=connection_id` in `MongoDBSaver`, this survives pod restarts — the
agent does not re-ask if it already asked within the 60-min window.

User activity signal is authoritative from server (`/internal/user/last-activity`)
— pod never infers activity from browser focus events (another tab in Chromium
being focused does not imply the user is at their computer).

---

## 19. Data flow split — MongoDB is the buffer, push is only for urgent

**Default path (everything scraped):**

```
Pod tools (scrape_chat, scrape_mail, scrape_calendar)
  → MongoDB (o365_scrape_messages, o365_message_ledger,
             o365_discovered_resources, scraped_mail, scraped_calendar)
  → Indexer (Kotlin server, polling)
  → Tasks (CHAT_INDEX, EMAIL_INDEX, CALENDAR_INDEX)
  → UI (via task stream / sidebar push)
```

The pod is the **sole writer** to these Mongo collections. The Kotlin server
is the **sole reader + indexer** — it transforms scrape rows into typed Tasks,
applies `ResourceFilter` + project mapping, and surfaces them through normal
UI pipelines. The pod never creates Tasks, never calls task-creation RPCs,
never dedupes at scrape time.

**Urgent exception (one and only one bypass):**

```
Direct message OR @mention detected
  → notify_user(kind='urgent_message', chat_id=…, sender=…, preview=…)
  → POST /internal/o365/notify (Kotlin)
  → priorityScore=95, alwaysPush=true, FCM+APNs + kRPC push
  → User's phone + chat bubble, immediately
```

Criteria for `urgent_message`:
- `isDirect=true` on the chat row (1:1 DM), **or**
- `@mention` of the logged-in user observed in the message content (DOM
  marker `data-tid="at-mention"` matching the account's display name), **or**
- **Incoming direct call toast detected by the background watcher (§10a)** —
  agent reads caller name + preview, fires `urgent_message`, and per §15
  MUST NOT click accept / answer.

Nothing else is urgent — group chat messages without a mention, mail,
calendar events, discovered resources, capability changes all go through
the MongoDB → Indexer → Task path. The server-side 60s de-dup window
(`O365_URGENT_DEDUP_WINDOW_S`) prevents flooding when the same chat stays
unread.

**No scraped content is ever sent over `notify_user`.** The push carries
ledger metadata (chat_id, sender name, short preview) — the full message
body is only in Mongo, to be pulled by the indexer.

---

## 20. Agent context persistence + cleanup

The LangGraph `MongoDBSaver` checkpointer persists raw message history per
`thread_id=connection_id`. That is correct for resume-after-restart but
grows unbounded, and plain `trim_messages` drops old triples whole — losing
any pattern the agent had learned (working selectors, tenant-specific
quirks, action templates). A **per-pod knowledge layer** supplements the
raw checkpoint so the agent does not re-discover the same facts every time
it restarts or hits the trim threshold.

### Three types of agent memory

| Type | Storage | Retention | Purpose |
|------|---------|-----------|---------|
| **Raw messages** | `langgraph_checkpoints_pod` (existing) | Rolling window (see cleanup rules) | LangGraph state for resume; trimmed at `agent` node entry |
| **Learned patterns** | `pod_agent_patterns` (new) | Forever, per `connectionId` + URL pattern | Stable selectors, `app_state` → working action templates, tenant-specific quirks (MFA selector, pre-join layout) |
| **Session memory** | `pod_agent_memory` (new) | Forever per `connectionId`, compressed as it grows | Distilled narrative of past sessions; injected into the SystemMessage on cold start |

### `pod_agent_patterns` schema

```json
{
  "_id": ObjectId,
  "connectionId": ObjectId,
  "urlPattern": "teams.microsoft.com/v2/*/prejoin",
  "appState": "prejoin",
  "workingSelectors": {
    "join_button": "[data-tid='prejoin-join-button']",
    "mic_toggle": "[data-tid='toggle-mute']",
    "caller_name": "[data-tid='call-toast'] .caller-name"
  },
  "actionTemplate": ["mic_toggle", "cam_toggle", "join_button"],
  "notes": "Pre-join: mute via toggle-mute, then click prejoin-join-button. Microsoft sometimes replaces data-tid; fall back to visible text 'Join now'.",
  "observedCount": 14,
  "successCount": 13,
  "lastUsedAt": ISODate,
  "lastSuccessAt": ISODate
}
```

- Indexed by `{connectionId, urlPattern}`.
- A selector is promoted to `workingSelectors` after **3 distinct successful
  uses** across sessions. One-shot matches stay in raw messages.
- On cold start the runner loads patterns for the current URL pattern and
  injects them into the SystemMessage. The agent then uses them as its
  first guess, skipping re-discovery.

### `pod_agent_memory` schema

```json
{
  "_id": ObjectId,
  "connectionId": ObjectId,
  "kind": "session_summary" | "learned_rule" | "anomaly",
  "content": "2026-04-15 08:00–11:30 — resumed session after restart, scraped 23 chats (3 direct unreads), joined scheduled meeting via VLM click (Teams A/B test on join UI).",
  "compressedFromRange": {
    "start": ISODate,
    "end": ISODate,
    "messageCount": 47
  },
  "createdAt": ISODate
}
```

- `session_summary` — one doc per cleanup pass, 2–3 sentences.
- `learned_rule` — durable generalizations ("MFA code rotates every 30 s
  on this tenant", "Outlook web UI breaks scoped DOM 2× per week, needs
  VLM").
- `anomaly` — unresolved stuck events for later human review.

### Cleanup triggers

Two signals, both run inside the pod (no external scheduler):

1. **Size-triggered:** message count > `O365_POOL_CONTEXT_MAX_MSGS`
   (default 100) **or** estimated tokens > `O365_POOL_CONTEXT_MAX_TOKENS`
   (default 40k). Runs before the next agent tick if exceeded.
2. **Nightly:** at 02:00 Europe/Prague if the pod is idle (no agent ticks
   in the last 10 min). Disabled during AWAITING_MFA / active meeting
   recording.

### Cleanup algorithm

Runs inside `runner.py` as an async task, single LLM pass via router
(`capability="chat"`, short context budget):

1. **Freeze** the agent (async lock against the outer LangGraph loop).
2. **Partition** messages:
   - KEEP: last 20 messages, all open `tool_call_id` pairs, anything newer
     than 4 h, anything tied to active PodState transition.
   - SUMMARIZE: everything else.
3. **Extract patterns** from the SUMMARIZE block. For every selector that
   appears in ≥ 3 successful `ToolMessage` results across the history:
   upsert into `pod_agent_patterns` with role inferred from adjacent tool
   calls (`join_button`, `mic_toggle`, …). Bump `observedCount` /
   `successCount`.
4. **Distill summary** via single LLM call: input = SUMMARIZE block +
   existing session memory (last 5 docs); output = 2–3 sentence narrative
   plus a short list of any new `learned_rule` / `anomaly` items.
5. **Write** summary + rules to `pod_agent_memory`.
6. **Replace** the LangGraph state: KEEP messages plus one new
   `SystemMessage("Prior session summary: <distilled>")` inserted before
   the KEEP window.
7. **Unlock** the agent.

### Rules — what to compress, what to keep

**Never drop / never compress:**
- Open `tool_call_id` pairs (LangGraph would break).
- `pending_mfa_code`, `AWAITING_MFA` state, any `interrupt()` marker.
- Last 20 messages (rolling).
- Any message within the current PodState transition chain that has not
  yet reached `ACTIVE`.
- Messages tied to an active meeting recording (until
  `stop_meeting_recording` completes).

**Compress into summary:**
- Closed scrape cycles (`done(summary)` reached and all downstream
  messages drained).
- Successful login flows (STARTING → ACTIVE transition chain).
- Heartbeat `look_at_screen` observations older than 1 h.
- Old `inspect_dom` observations where the selector has already been
  promoted to a pattern.

**Extract into patterns:**
- A selector used successfully in ≥ 3 distinct tool calls → promoted to
  `workingSelectors[role]`.
- A repeated `app_state` → `[action, action, …]` sequence → promoted to
  `actionTemplate`.
- A tenant-specific quirk confirmed across ≥ 2 sessions → new
  `learned_rule`.

**Drop entirely:**
- Raw DOM `matches` arrays older than 1 h (the extracted pattern
  survives).
- VLM bounding-box and screenshot detail older than 1 h (the summary
  sentence survives).
- Failed tool-call retries that were later superseded by a successful
  attempt at the same step.

### Cold-start integration

On pod start, before the first agent LLM call, `runner.py` composes the
`SystemMessage` as:

```
<static system prompt §3 + §15 + §17 + §18 rules>

Previous-session summary:
<pod_agent_memory where kind='session_summary' order by createdAt desc limit 1>

Learned patterns for this connection (top 10 by lastUsedAt):
- teams.microsoft.com/v2/*/prejoin: join_button=[data-tid='prejoin-join-button'], mic_toggle=[data-tid='toggle-mute']
- login.microsoftonline.com/*/mfa: sign_in_number=[data-display-sign-in-code]
- teams.microsoft.com/v2/chat: chat_list_item=[data-tid='chat-list-item']
…

Learned rules (kind='learned_rule', most recent 5):
- "MFA code rotates every 30 s on tenant <id> — retry notify_user on each new code read."
- "Outlook web UI requires VLM during first 10 s after navigate — DOM selectors load late."
```

The agent tries patterns first; fresh selectors are discovered only when
the URL pattern does not match a known entry. Patterns that fail twice in
a row are demoted (bump `failureCount`; once `failureCount >= 3` the
entry is dropped from the cold-start injection).

### No cross-connection sharing

Patterns and memory are scoped to a single `connectionId`. Two connections
to the same tenant do not share learned selectors — each pod learns
independently. Tenant-wide sharing is an anti-pattern: different accounts
can hit different A/B UI variants, and leaking patterns would mix them.

---

## 21. Open items

1. **Router policy for pod traffic:** model selection is the router's concern
   (`capability="chat"` for tool-calling, `capability="vision"` for VLM). Pod
   code never names a model.
2. **Urgent notify burst damping:** server-side window configured in
   `configmap.yaml` (`O365_URGENT_DEDUP_WINDOW_S=60`).
3. **MFA code UI surface:** confirm that the mobile push payload includes
   `mfa_code` so the user sees the number without opening the app (Android
   notification body + iOS notification subtitle).
4. **Relogin approval via chat:** orchestrator agent tool
   `connection_approve_relogin(connection_id)` — out of scope for the pod, but
   needed so users can say "ano přihlas to znova" in chat off-hours.
5. **Meeting recording + indexation + view** — canonical design is in §10a
   (recording pipeline, `MeetingDocument` lifecycle, indexation) and §20
   (context persistence). Remaining implementation items:
   - Server-side `MeetingRecordingMonitor` job (stuck detector + hard
     ceiling `scheduledEnd+30min`).
   - `MeetingRecordingIndexer` job (Whisper + pyannote + scene detection
     + VLM frame descriptions → `timeline[]`).
   - Nightly retention job (drop WebM after `videoRetentionUntil`).
   - UI `MeetingScreen`: video player, timeline strip with scene
     thumbnails, synced transcript panel.
   - Orchestrator MCP tools `meeting_alone_leave` /
     `meeting_alone_stay` (new) + existing `meeting_attend_*`.
6. **Router vision routing fix:** `route-decision` for `capability=vision`
   currently returns `qwen3-coder-tool:30b` — must return a VLM
   (`qwen3-vl-tool` or cloud equivalent). Prerequisite for any VLM work.
   Tracked in `project-next-session-todos.md` item 6.
