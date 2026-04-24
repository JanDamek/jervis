# Claude Session — Fáze A Pilot

**Stav:** pilot (2026-04-24, revised — in-process SDK)
**Feature flag:** `USE_CLAUDE_CLIENT_SESSION` (default OFF)
**Supersedes:** nothing — legacy LangGraph chat path remains the fallback

## 1. Cíl

Nahradit LangGraph 14-nodový chat handler per-klient **in-process Claude SDK session** pro chatové zprávy, které mají nastavený `active_client_id`. Cíl je, aby klíčová logika (qualifier, triage, odpověď, KB interakce) běžela na Claude CLI přes MCP nástroje, ne přes orchestratorův vlastní LLM chain.

Pilot testuje jediný slice na konci end-to-end chain: **UI chat → Kotlin → Python Chat handler → ClaudeSDKClient přímo v orchestrator podu → odpověď zpět do UI**.

## 2. Architektura pilotu

```
┌─────────────────────────── UI ─────────────────────────────┐
│ User selects a client in the chat scope dropdown,          │
│ types a message, hits send.                                │
└────────────────────────┬───────────────────────────────────┘
                         │ kRPC ChatRpcImpl.sendMessage
                         ▼
┌─────────────── Kotlin server ──────────────────────────────┐
│ PythonChatClient.chat(session_id, message, activeClientId) │
└────────────────────────┬───────────────────────────────────┘
                         │ gRPC OrchestratorChatService.Chat
                         ▼
┌────────────── Python orchestrator ─────────────────────────┐
│ OrchestratorChatServicer.Chat:                             │
│   if use_claude_client_session and active_client_id:       │
│       → ClientSessionManager.chat(client_id, project_id,   │
│                                    message)                │
│   else:                                                    │
│       → handle_chat_sse() (legacy LangGraph path)          │
└────────────────────────┬───────────────────────────────────┘
                         │ lazy get-or-create
                         ▼
┌────────── ClientSessionManager (in-memory, orchestrator pod) ──┐
│ sessions[client_id] = ClientSession(                           │
│   session_id, sdk=ClaudeSDKClient(...),                        │
│   last_activity_monotonic, lock, stop_flag)                    │
│ TTL 30 min idle → watchdog sends COMPACT_AND_EXIT              │
│ SDK subprocess (claude CLI, Node.js) stays warm between turns  │
└────────────────────────┬───────────────────────────────────────┘
                         │ sdk.query(message) + sdk.receive_response()
                         ▼
┌────────── claude-agent-sdk subprocess (inside orchestrator pod) ┐
│ @anthropic-ai/claude-code Node.js CLI                          │
│   - receives system_prompt built from brief.md + CLAUDE.md     │
│   - MCP config points at jervis-mcp HTTP server                │
│   - streams AssistantMessage/ResultMessage over stdin/stdout   │
└────────────────────────────────────────────────────────────────┘
```

**Proč in-process a ne K8s Job**: Job per chat session má cold start 60-90 s
(Docker image pull + Node.js + Claude CLI init). In-process SDK má cold
start ~5 s (jen subprocess spawn), subsequent messages ~1-3 s. Companion
K8s Job infrastruktura zůstává pro adhoc deep analysis + meeting
assistant, kde izolace + dlouhodobý běh dávají smysl.

## 3. Klíčové chování

- **Lazy startup** — session se vytvoří až první zprávou pro daný `clientId`. Cold start ~5 s (SDK subprocess spawn + Claude CLI init + MCP HTTP connect).
- **Sessions žijí po celý život podu.** Žádné idle-TTL, žádný watchdog. S MAX plánem je idle session zdarma (subprocess drží ~100 MB RAM, ale žádné tokeny mezi turny). Kontinuita napříč celým dnem.
- **Stop triggers:**
  - Pod shutdown → `shutdown()` → compact + stop všech sessions
  - Noční maintenance (Fáze B) → batch-compact + clean slate před ránem
  - Claude interní `/compact` (SDK to dělá sám při naběhnutí kontextu)
  - Explicitní `stop_session(client_id)` přes budoucí UI "reset" tlačítko
- **Compact protocol** — při stopu orchestrátor pošle `COMPACT_AND_EXIT` system prompt, Claude vrátí markdown shrnutí, uloží se do `compact_snapshots` v MongoDB (klíč `scope="client:<id>"`).
- **Bootstrap** — při příští session brief obsahuje sekci *"Previous compact"* s obsahem posledního snapshotu.
- **Serializace** — jedna zpráva na klienta zároveň (per-client `asyncio.Lock`). Stejný Jan nepošle dvě souběžně; multi-user by vyžadoval další fronty.
- **Allowlist** — env `CLAUDE_CLIENT_SESSION_ALLOWED_IDS` (comma-separated) omezí flag jen na vybrané klienty (postupný rollout). Prázdná = všichni.

## 4. Soubory

### Nové (Python, orchestrator)

| Soubor | Účel |
|--------|------|
| `app/sessions/__init__.py` | Package doc |
| `app/sessions/compact_store.py` | Motor CRUD pro `compact_snapshots` |
| `app/sessions/client_brief_builder.py` | Staví `brief.md` + `CLAUDE.md` per-klient |
| `app/sessions/client_session_manager.py` | Lifecycle, watchdog, chat dispatch |

### Upravené (Python, orchestrator)

| Soubor | Změna |
|--------|-------|
| `app/config.py` | Dva nové flagy: `use_claude_client_session`, `claude_client_session_allowed_ids` |
| `app/grpc_server.py` | Chat handler: větev přes `ClientSessionManager` před legacy `handle_chat_sse` |
| `app/main.py` | Lifespan: init compact indexy, start watchdog (pokud flag ON), shutdown sessions při exit |

### Upravené (MCP)

| Soubor | Změna |
|--------|-------|
| `service-mcp/app/main.py` | Dva nové tooly: `memory_graph_save_snapshot(scope, content, ...)` a `memory_graph_load_snapshot(scope)` |

## 5. MongoDB kolekce

```
compact_snapshots
  _id: ObjectId
  scope: "client:<id>" | "global" | "project:<id>"  (index asc + created_at desc)
  content: string (markdown)
  client_id: string | null                         (index)
  project_id: string | null
  session_id: string | null
  created_at: datetime (UTC)
  token_estimate: int
```

Indexy vytvořené při startu: `(scope, created_at desc)` a `client_id`. Data jsou append-only; prune volitelně přes `compact_store.prune_old(scope, keep_latest=10)` (zatím bez scheduleru).

## 6. Zapnutí pilotu

1. **Deploy** — `k8s/build_orchestrator.sh` + `k8s/build_mcp.sh` (v pořadí; MCP musí mít snapshot tooly dřív než orchestrátor na ně odkazuje v briefu).
2. **Configmap** — v `k8s/configmap.yaml` v sekci `service-orchestrator` nastavit:
   ```yaml
   USE_CLAUDE_CLIENT_SESSION: "true"
   # Volitelně: postupný rollout — jen Jervis self-klient
   CLAUDE_CLIENT_SESSION_ALLOWED_IDS: "68a332361b04695a243e5ae8"
   ```
3. **Rollout** — `kubectl rollout restart deployment/jervis-orchestrator -n jervis`.
4. **Test v UI** — vyberte klienta v chat scope, pošlete zprávu. Log orchestrátoru by měl obsahovat `CHAT_CLIENT_SESSION_ROUTE`.

## 7. Co **ne**dělá (pilot scope)

- **Nemění Kotlin kód** — žádná proto změna, žádná nová RPC. Stávající stream formát (token/done) je zachován.
- **Nemění UI** — uživatel nepozná rozdíl, jen kvalitnější odpovědi (teoreticky).
- **Nezapojuje globální session** ani per-task Jobs (Fáze B/C).
- **Nerozdělil MCP** — používá stávající monolitický `jervis-mcp` (rozdělení ve Fázi B).
- **Neřeší Memory Graph migraci** — Memory Graph zůstává spravovaný Pythonem; Claude může číst/psát jen přes MCP tooly.
- **Neobsahuje qualifier → Claude eskalaci** — chat je user-initiated, tam qualifier nefiguruje. Pro background (mail/Teams/meeting) patří eskalace do Fáze B, viz `docs/qualifier-claude-escalation.md`.

## 7b. Scratchpad — Claude's structured notebook

Součástí pilotu jsou 4 nové MCP tooly nad kolekcí `claude_scratchpad`:

- `scratchpad_put(scope, namespace, key, data_json, tags, ttl_days, ...)` — upsert
- `scratchpad_get(scope, namespace, key)` — exact fetch
- `scratchpad_query(scope, namespace?, tag?, limit)` — list
- `scratchpad_delete(scope, namespace, key)` — remove

Claude je má přes jervis MCP dostupné. Doporučené rozdělení v briefu:

- **scratchpad** — přesné klíče, strukturovaný JSON, vlastní poznámky (pending replies, dnešní rozhodnutí, follow-upy). Rychlé, scoped, TTL.
- **kb_search/kb_store** — fuzzy/semantic search přes durable znalost. `kb_store` jen pro netriviální, trvalé poznatky.
- **mongo_query** — čtení cizích kolekcí (tasks, meetings, clients). NE jako scratchpad.

Indexy na `claude_scratchpad`:
- `(scope, namespace, key)` unique — primární klíč
- `(scope, updated_at desc)` — list per scope
- `tags` — coarse filter

## 8. Rollback

Flag OFF → watchdog se nevzbudí, `Chat` handler předá request rovnou do LangGraph. Běžící sessions se zastaví při `shutdown()` lifespan. Žádná destruktivní akce potřebná.

## 9. Ověření (smoke test)

1. Po deployi: `GET /health` (gRPC) — `active_tasks` + logy bez exception.
2. V UI: "napiš něco klientovi X".
3. V log orchestrátoru hledat:
   - `CHAT_CLIENT_SESSION_ROUTE | session=... client=...`
   - `started client session | client=...`
   - K8s: `kubectl get jobs -n jervis -l app=jervis-companion,companion-mode=session`
4. Po 30 min idle (nebo pod manuálně smazán):
   - `kubectl logs` ukáže `Session ended.`
   - Mongo: `db.compact_snapshots.find({scope: "client:<id>"}).sort({created_at:-1}).limit(1)`

## 10. Odkazy

- SSOT starého companion: `docs/orchestrator-claude-companion.md`
- Companion runner: `backend/service-claude/companion_sdk_runner.py`
- Companion dispatch: `backend/service-orchestrator/app/agents/companion_runner.py`
- Workspace layout: viz `docs/orchestrator-claude-companion.md` §3
