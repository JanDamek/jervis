# Qualifier → Claude Session — Eskalační Design (Fáze B)

**Status:** návrh (2026-04-24). Ještě neimplementováno. SSOT pro následující iteraci po pilotu Fáze A.

## 1. Motivace

Po přepnutí chat cesty na per-klient Claude session (Fáze A, `docs/claude-session-pilot.md`) zbývá rozhodnout, co se stane s **backgroundem** — příchozí maily, Teams zprávy, kalendářové události, meeting transcripts. Vize: **Claude NEřeší drobnosti**. Spam, ACK maily, automatické odpovědi, "OK" zprávy v Teams — všechno autonomně zpracuje qualifier dříve, než se dostane do Claude budgetu.

## 2. Rozhodovací vrstvy

```
Incoming event (mail / teams msg / calendar event / meeting transcript chunk)
         │
         ▼
┌────────────── Qualifier (Python, deterministic + local LLM) ──────────────┐
│  Rule-based fast path:                                                    │
│    - spam/phishing patterns (regex, known senders, unsubscribe headers)   │
│    - auto-reply / notification headers (List-Unsubscribe, Auto-Submitted) │
│    - trivial ACKs ("ok", "thanks", "dík", "díky", emoji-only)             │
│    - calendar ICS notifications (use calendar_*.processed flag)           │
│  → if matched → AUTO_HANDLE (archive / mark seen / quiet ack, no user)    │
│                                                                           │
│  Remaining items → local LLM triage (qwen3-coder-tool:30b, cheap):        │
│    - classify: AUTO_HANDLE | ESCALATE_TO_CLAUDE | USER_TASK               │
│    - extract: client_id (via sender mapping), urgency, topic              │
│    - confidence score 0..1                                                │
│  → ESCALATE_TO_CLAUDE: post to `client_session.inbox` as                  │
│    type=system, content=<summarized hook>, meta.kind=escalation,          │
│    meta.source_urn=<origin>                                               │
│  → USER_TASK: existing path (red-dot / K reakci list in UI)               │
└───────────────────────────────────────────────────────────────────────────┘
         │                                                  │
         ▼ AUTO_HANDLE                                       ▼ ESCALATE
┌──── Qualifier autonomous actions ────┐    ┌── ClientSessionManager.inject_event() ──┐
│ - archive email                      │    │ Appends to .jervis/inbox/events.jsonl   │
│ - mark message seen                  │    │ Lazy spin-up per client session if none  │
│ - record decision in action_log      │    │ Session wakes, processes event, drafts  │
│ - skip notification                  │    │ a reply or takes action                 │
└──────────────────────────────────────┘    └─────────────────────────────────────────┘
```

## 3. Classification rules (draft)

**AUTO_HANDLE** triggers (any one sufficient):

- Mail headers: `Precedence: bulk`, `Auto-Submitted: auto-generated`, `X-Autoreply`, List-Unsubscribe on campaign senders
- Subject matches spam regex (`\b(viagra|invest|crypto|winner)\b/i`) OR sender on known-spam list
- Body ≤ 3 words and matches ACK pattern (`^(ok|díky|thanks|ty|+1|👍)$/i`)
- Calendar notification from `noreply@` domain referencing an already-processed invite
- Teams "Added you to channel X" / "User Y joined" / reaction-only message
- Reply in a thread where the last outbound was `type=auto_ack` from Jervis itself

**ESCALATE_TO_CLAUDE** triggers:

- Any non-auto content that mentions a known client (sender domain → ClientDocument resolution)
- Meeting transcript final for an attended meeting
- User-initiated replies in ongoing threads where Claude has scratchpad items with `namespace=pending_reply`

**USER_TASK** triggers (unchanged):

- High-risk actions Claude can't decide alone: financial > threshold, legal docs
- Authentication / relogin requests
- Explicit Jan-only topics (preferences in `claude_scratchpad[scope=user:jan, namespace=always_ask]`)

## 4. Contract s Claude session (inbox framing)

Qualifier → session event:

```jsonl
{
  "ts": "2026-04-24T14:00:00Z",
  "type": "system",
  "content": "Escalated: <one-line summary>",
  "meta": {
    "kind": "escalation",
    "source_urn": "email:<message_id>",
    "client_id": "68a332...",
    "urgency": "medium",
    "hint": "User is asking for delivery schedule; we have related pending_reply in scratchpad.",
    "artifact_ref": "mongo:mails:<id>"
  }
}
```

Claude v session vidí `type=system, meta.kind=escalation` a dle CLAUDE.md má instrukce:

1. Přečíst `content` + `meta.hint`
2. V případě potřeby detailu: `mongo_get_document("mails", meta.artifact_ref)` nebo `get_meeting_transcript(...)`
3. Rozhodnout: odpovědět/draftovat, vytvořit task, uložit follow-up do scratchpadu, nebo eskalovat dál na USER_TASK
4. Výstup: standardní `type=answer` do outboxu (orchestrator ho forwarduje do appropriate kanálu)

## 5. Moduly pro implementaci

Nové v Pythonu:

```
app/qualifier/
  __init__.py
  rules.py              # deterministic spam/ACK filters
  triage_llm.py         # local LLM classifier (qwen3-coder-tool)
  classifier.py         # rules first, then LLM; returns QualifierDecision
  auto_actions.py       # AUTO_HANDLE executors (archive, mark seen, ack)
  escalator.py          # ESCALATE → ClientSessionManager.inject_event
  models.py             # QualifierDecision, QualifierInput
```

Nové v `ClientSessionManager`:

```python
async def inject_event(
    self,
    *,
    client_id: str,
    event_type: str,    # "system" / "user"
    content: str,
    meta: dict | None = None,
) -> None:
    """Append a non-chat event (escalation, system hint) to a client
    session's inbox. Starts the session lazily if not running."""
```

Nové v Kotlinu:

- `QualifierEntrypoint.kt` — centrální vstup pro všechny incoming events. Stávající handlers (email ingest, Teams webhook, meeting finalizer) ho volají místo přímé dispatch.
- Nové `sourceUrn` přípony: `qualifier:auto-handled:<type>:<id>` pro audit auto-actions.

## 6. Metriky (aby bylo vidět jestli to funguje)

Mongo kolekce `qualifier_metrics`:

```
{
  date: "2026-04-24",        # daily roll-up
  client_id: "68a332...",
  auto_handled: 147,
  escalated: 12,
  user_task: 3,
  auto_handled_by_rule: {spam: 80, ack: 23, calendar_noise: 44},
  escalated_topics: ["delivery", "invoice", "change-request"]
}
```

Cíl Fáze B: **auto_handled / (auto_handled + escalated) > 0.9** přes týdenní okno. Claude se tedy probudí jen pro tu 1/10 incoming eventů, co genuinely potřebuje úsudek.

## 7. Bezpečnost

- **Auto-actions jsou reverzibilní** — archive ne mazání, mark seen se dá vrátit.
- **Pro každou auto-action record do `action_log`** — když user nesouhlasí, vidí v UI "Jervis archivoval 23 spamů dnes" a může rollback.
- **Confidence threshold 0.8** — pod ním se NIKDY auto-handle nepoužije, padá to na ESCALATE_TO_CLAUDE.
- **Per-klient kill switch** — `ClientDocument.qualifierAutoHandleEnabled: bool` (default true). Pokud off, vše jde na Claude.

## 8. Co NEsmíme udělat

- Poslat auto-reply na spam (trigger spam-fight loop).
- Archivovat mail s attachmentem bez VLM scan (může tam být smlouva).
- Auto-handle events z nově přidaných klientů prvních 7 dní (nemáme baseline).
- Claude dotazovat LLM routem na triage — drazí a pomalejší než lokální qwen3.

## 9. Odkazy

- Fáze A pilot: `docs/claude-session-pilot.md`
- Current ClientSessionManager: `backend/service-orchestrator/app/sessions/client_session_manager.py`
- Current Companion SSOT: `docs/orchestrator-claude-companion.md`
- Memory Graph principles: `docs/graph-agent-architecture.md`
