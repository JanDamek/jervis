# WhatsApp Connection — Design Document

**Status:** Phase 1 (read-only) implemented
**Last updated:** 2026-04-16
**Approach:** Browser pod + DOM-first scraping (VLM fallback for attachments/sanity)
**Phase 1:** `CHAT_READ` only — no outbound messages

---

## 1. Purpose

One pod per WhatsApp Connection runs a persistent Playwright/Chromium session
on `https://web.whatsapp.com`. The pod is autonomous, self-heals, and mirrors
chats into MongoDB. The Kotlin server indexes deltas into the knowledge base.

### Phase 1 scope
- Watch all chats (direct + groups) via the sidebar
- Extract message content, sender, timestamps via DOM (primary)
- Use VLM only for image/video/sticker descriptions and periodic sanity scans
- Push **direct messages as urgent** to the Kotlin server
- Propagate discovered chats/groups to the server for project assignment

### Phase 1 non-goals
- No `CHAT_SEND` — the pod never types into the input box
- No auto-join of group invitations
- Never close + reopen tabs; one tab, persistent session

---

## 2. Why browser + DOM, not an API

- WhatsApp has no public API for personal accounts (Business API needs approval)
- WhatsApp Web renders in Chromium — DOM scraping is stable enough
- KB note `agent://claude-code/whatsapp-dom-scraping-2026-04-11` validated the
  DOM-first approach: real sender names, message bodies, hash-diffing

### DOM-first vs VLM

| Observation | How | When VLM is used |
|-------------|-----|------------------|
| Chat list (sidebar) | DOM walk + shadow pierce | never |
| Conversation messages | DOM walk + hash-diff | never |
| Attachment descriptions (image/video/sticker) | not possible via DOM | VLM on the attachment region |
| QR code state | DOM label probe | VLM as fallback (localised pages) |
| 5-min silence sanity scan | — | VLM "what's on screen?" |

### Shadow DOM pierce (read-only)

Parts of WhatsApp Web render inside shadow roots. The probe walks
`element.shadowRoot` recursively during read-only observation. No writes.

---

## 3. Pod ↔ server comms

Parallel to Teams. Endpoints:

| Direction | Endpoint | Purpose |
|-----------|----------|---------|
| Pod → Server | `POST /internal/whatsapp/session-event` | QR pending / ACTIVE / EXPIRED |
| Pod → Server | `POST /internal/whatsapp/notify` | Kind-aware push (same shape as O365 notify) |
| Pod → Server | `POST /internal/whatsapp/capabilities-discovered` | CHAT_READ only for now |
| Pod → Mongo | direct write | `whatsapp_scrape_messages`, `whatsapp_discovered_resources`, `whatsapp_message_ledger` |
| Server → UI | `GET /internal/whatsapp/discovered-resources?connectionId=X` | List groups/direct chats for project assignment |
| UI → Server | `PUT /project/{projectId}/resources` | Attach a discovered chat to a project |

### Direct messages are urgent

Any new message in a 1:1 chat (`isGroup=false`) → `kind=urgent_message`,
USER_TASK `priorityScore=95`, `alwaysPush=true`. Server deduplicates per
`(connectionId, chatId)` in a 60s window.

Group messages are indexed but do **not** fire push notifications unless the
pod detects `@mention` or a keyword from the per-project `ResourceFilter`.

---

## 4. Message ledger

Collection `whatsapp_message_ledger`, one row per `(connectionId, chatId)`:

```json
{
  "connectionId": "…",
  "clientId": "…",
  "chatId": "wa_chat_<slug>",
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

Invariants match Teams (see `docs/teams-pod-agent.md` §6).

---

## 5. Scraping loop (DOM-first)

```python
async def scrape_loop(self):
    while self.running:
        sidebar = await dom_probe_sidebar(self.page)   # DOM
        await ledger.upsert_rows(sidebar.chats)

        for chat in sidebar.chats:
            if chat.unread == 0:
                continue
            await self.click_chat(chat.name)
            messages = await dom_probe_conversation(self.page)  # DOM
            for m in messages:
                if m.attachment_kind in ("image", "video", "sticker"):
                    m.attachment_description = await describe_via_vlm(self.page, m.bbox)
                await storage.store_message(connection_id, chat, m)
                if chat.is_direct:
                    await notify_server_urgent(chat, m)
            await ledger.mark_seen(chat.id)

        # 5-min silent sanity: if DOM diff shows nothing for 5 min, ask VLM
        if time.time() - self.last_dom_delta > 300:
            await look_at_screen(reason="heartbeat")

        await asyncio.sleep(adaptive_sleep(sidebar))
```

**Adaptive sleep:**
- Unread visible → 15s
- Idle, anything on sidebar → 60s
- Idle, long (>5 min no delta) → 120s + VLM heartbeat

---

## 6. Discovered groups → project assignment

`whatsapp_discovered_resources` is written per chat/group the pod sees. The
server exposes `GET /internal/whatsapp/discovered-resources` for Settings UI.
The user manually binds each resource to a `Project` via `ProjectResource`.
Indexing uses the bind list via `ResourceFilter` in the polling handler — the
pod scrapes everything, projects get only what they asked for.

---

## 7. QR login flow (DOM first, VLM fallback)

1. Playwright opens `https://web.whatsapp.com`
2. DOM probe: look for `canvas[aria-label*="QR" i]` or a known data-testid
3. If DOM says QR visible → `report_state(PENDING_LOGIN)` + emit `auth_request`
   kind notify so the UI can render the noVNC link
4. If DOM ambiguous → `look_at_screen(reason="login")` — VLM returns
   `{state: "qr_visible" | "logged_in" | "phone_disconnected" | "error"}`
5. Loop every 5s until DOM shows the chat list → `report_state(ACTIVE)`
6. On `EXPIRED`: pod self-heals silently (no user notification) unless a new QR
   scan is required; then `auth_request` again

---

## 8. Router-first LLM/VLM

All VLM calls (attachments, QR fallback, heartbeat) go through
`jervis-ollama-router` via `/route-decision`. No direct provider calls.

---

## 9. K8s

- Dynamic Deployment per Connection (`jervis-whatsapp-browser-<connId>`)
- Persistent browser profile on PVC (`/data/whatsapp/<connId>/`)
- noVNC for manual QR scan; session survives pod restart
- Resources: `256Mi / 500m` request, `1Gi / 1 CPU` limit

---

## 10. Phase 2 (future) — send

Deferred. Will require:
- Playwright click + type into `[contenteditable][data-tab]`
- Orchestrator approval flow (`CommunicationAgent`)
- Audit trail in Mongo (`whatsapp_sent_messages`)

Not in Phase 1.

---

## 11. MCP tools (Phase 1 read-only)

```python
@mcp.tool() async def whatsapp_list_chats() -> list[dict]: ...
@mcp.tool() async def whatsapp_read_chat(chat_name: str, limit: int = 20) -> dict: ...
# whatsapp_send_message deferred to Phase 2
```

---

## 12. Parity with Teams

Where possible, endpoints, collection shape, ledger fields, and kind-aware
notifications mirror Teams (`docs/teams-pod-agent.md`). This keeps the
Kotlin-side indexer and UI code uniform across providers.
