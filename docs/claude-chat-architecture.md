# Claude Chat Architecture — SSOT

**Stav:** finální design (2026-04-24)
**Scope:** foreground chat (UI → orchestrator → Claude → UI)
**Supersedes:** `docs/graph-agent-architecture.md` Phase 4 chat flow + LangGraph 14-node chat path (removed)

## 1. Princip

Každý foreground chat běží přes jednu z per-klient **in-process Claude SDK sessions**. Orchestrator vůbec nedělá LLM rozhodnutí sám — chat se klasifikuje, triaguje a odpovídá uvnitř Claude session přes MCP tooly. Žádný feature flag, žádný fallback na starý LangGraph path, žádný allowlist — toto je jediná cesta.

Scope chat message:

- `active_client_id` prázdný → error (UI musí vybrat klienta)
- `active_client_id` set → dispatch do `ClientSessionManager.chat(client_id, project_id, message)`
- `project_id` je metadata signál pro Claude; nepoužívá se k rozlišení session (scope je per-client)

## 2. End-to-end flow

```
┌─────────────────────────── UI ─────────────────────────────┐
│ User selects a client in the chat scope dropdown,          │
│ types a message, hits send.                                │
└────────────────────────┬───────────────────────────────────┘
                         │ kRPC ChatRpcImpl.sendMessage
                         ▼
┌─────────────── Kotlin server ──────────────────────────────┐
│ PythonChatClient.chat(session_id, message, activeClientId) │
│ subscribeToChatEvents filters out events whose              │
│ metadata.clientId ≠ current session.lastClientId            │
│ (prevents Client A's tokens from leaking into Client B's    │
│ chat view when two sessions stream in parallel)             │
└────────────────────────┬───────────────────────────────────┘
                         │ gRPC OrchestratorChatService.Chat
                         ▼
┌────────────── Python orchestrator ─────────────────────────┐
│ OrchestratorChatServicer.Chat:                             │
│   → ClientSessionManager.chat(client_id, project_id, msg)  │
│   (no branching, no fallback)                              │
└────────────────────────┬───────────────────────────────────┘
                         │ lazy get-or-create per client_id
                         ▼
┌────────── ClientSessionManager (in-memory, orchestrator pod) ──┐
│ sessions[client_id] = ClientSession(                           │
│   session_id = c<uuid4hex>,                                    │
│   task = asyncio.create_task(_session_task),                   │
│   in_queue, out_queue,                                         │
│   turn_lock, stop_flag)                                        │
│ Sessions stay alive for pod lifetime; explicit stop + nightly  │
│ maintenance compact them into compact_snapshots.               │
└────────────────────────┬───────────────────────────────────────┘
                         │ prompt → in_queue
                         │ SDK msg ← out_queue
                         ▼
┌──── _session_task (async with ClaudeSDKClient) ────────────────┐
│ @anthropic-ai/claude-code Node.js CLI (subprocess)              │
│   - system_prompt = brief.claude_md + brief.brief_md            │
│   - MCP config = jervis MCP over HTTP (bearer auth)             │
│   - per-turn: sdk.query → sdk.receive_response until ResultMsg  │
└────────────────────────────────────────────────────────────────┘
```

## 3. Lifecycle

- **Lazy startup** — session se vytvoří až první zprávou pro daný `client_id`. Cold start ~5 s (SDK subprocess spawn + Claude CLI init + MCP handshake).
- **Sessions žijí po celý život podu.** MAX plan = idle session netikne tokeny; subprocess drží ~100 MB RAM. Session kontinuita napříč celým dnem.
- **Stop triggers:**
  - Pod shutdown → `shutdown()` compactuje + stop všech sessions
  - Nightly maintenance (qualifier layer) → batch-compact + clean slate
  - Claude interní `/compact` (SDK při naběhnutí kontextu)
  - Explicitní `stop_session(client_id)` — UI "reset" tlačítko (API ready, UI není napojen)
- **Compact protokol** — při stopu orchestrator pošle `COMPACT_AND_EXIT` system prompt, Claude vrátí markdown shrnutí, uloží se do `compact_snapshots` (scope `client:<id>`).
- **Bootstrap** — při příští session brief obsahuje sekci *"Previous compact"* s posledním snapshotem.
- **Serializace** — jedna zpráva na klienta zároveň (per-session `asyncio.Lock`).

## 4. Event typy směrem k UI

`ClientSessionManager.chat` je async generator vracející události. Každá nese `metadata.clientId` / `metadata.projectId` / `metadata.sessionId` pro UI filtraci.

| type | význam |
|------|--------|
| `thinking` | keepalive / tool-use hint ("Volám nástroj: kb_search") |
| `token` | kus odpovědi (textový blok z AssistantMessage) |
| `done` | turn skončil |
| `error` | session/SDK chyba; turn končí, ne celá session |

## 5. Soubory

### Python orchestrator

| Soubor | Účel |
|--------|------|
| `app/sessions/__init__.py` | Package doc |
| `app/sessions/compact_store.py` | Motor CRUD pro `compact_snapshots` (+ scratchpad indexy) |
| `app/sessions/client_brief_builder.py` | Staví `brief.md` + `CLAUDE.md` per-klient |
| `app/sessions/client_session_manager.py` | Lifecycle, per-client task, turn queue |
| `app/grpc_server.py` | `OrchestratorChatServicer.Chat` — jediná cesta přes ClientSessionManager |
| `app/main.py` | Lifespan: `ensure_indexes` na startu, `shutdown()` na stopu |
| `Dockerfile` | Node.js 20 + `@anthropic-ai/claude-code` + `claude-agent-sdk` |

### MCP

| Soubor | Změna |
|--------|-------|
| `service-mcp/app/main.py` | Dva nové tool families: `memory_graph_save/load_snapshot`, `scratchpad_put/get/query/delete` |
| `service-mcp/Dockerfile` | Drop monorepo constraints (fastmcp 3.x ≠ constraints.txt FastAPI pin) |

### Kotlin server

| Soubor | Změna |
|--------|-------|
| `ChatRpcImpl.subscribeToChatEvents` | Filtruje emissions s `metadata.clientId` ≠ current session scope |

## 6. MongoDB kolekce

### `compact_snapshots`

```
_id, scope, content, client_id, project_id, session_id, created_at, token_estimate
```

- `(scope, created_at desc)` index — fetch latest per scope
- `client_id` index — fast client filter

### `claude_scratchpad`

```
_id, scope, namespace, key, data, tags, created_at, updated_at, expires_at, session_id, client_id, project_id
```

- unique `(scope, namespace, key)` — primární klíč pro `scratchpad_put` upsert
- `(scope, updated_at desc)` — list per scope
- `tags` — coarse filter

**Why separate from KB**: Scratchpad je pro **exact-key retrieval** (pending replies, follow-upy, rozhodnutí). KB je pro **semantic/fuzzy search**. Claude ve briefu dostane pravidlo kdy co použít.

## 7. Scratchpad tooly

Přes `jervis` MCP server je Claude má dostupné:

- `scratchpad_put(scope, namespace, key, data_json, tags, ttl_days, ...)` — upsert
- `scratchpad_get(scope, namespace, key)` — exact fetch
- `scratchpad_query(scope, namespace?, tag?, limit)` — list
- `scratchpad_delete(scope, namespace, key)` — remove

Default scope v briefu: `client:<clientId>`.

## 8. Co NEfunguje (vědomě vynecháno)

- **UI "Stop" button na chat turn** — Python `Stop` RPC dnes no-op, abort pending prompt bude přes `turn_lock` cancel v další iteraci.
- **Multi-user** — manager je process-local, jedna instance orchestrátoru. Scale-out vyžaduje Mongo-backed session registry.
- **Cross-session scratchpad sharing** — session vidí jen vlastní scope (client / global / project). Záměr.

## 9. Odkazy

- Companion K8s Job (adhoc + meeting assistant): `docs/orchestrator-claude-companion.md`
- Plánovaná qualifier eskalace: `docs/qualifier-claude-escalation.md`
- Memory Graph (kontext pro chat turn přes MCP tools): `docs/graph-agent-architecture.md`
