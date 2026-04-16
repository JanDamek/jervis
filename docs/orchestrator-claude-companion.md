# Orchestrator-Claude Companion — SSOT

**Datum:** 2026-04-15
**Status:** První implementace (test: 2026-04-16)
**Supersedes:** orchestrator-plan-v4/v5/v6 (v rámci companion role)

## 1. Role a princip

**Claude CLI companion** je paralelní K8s Job agent, nikoli LLM provider. Běží vedle orchestrátoru, který mu deleguje **komplexní logické úlohy**, zatímco sám řeší:

- routing, qualification, KB graf, thought map
- běžné odpovědi, recall, memory, status, CRUD task
- jednoduché summarizace (přes OpenRouter / Ollama)

**Companion řeší:**

- deep analýzy (rozpočty, matematika, vícekriteriální plánování)
- research napříč KB + web + O365 + dokumenty
- analýzu obrázků (čte soubor přímo, VLM se NEvolá)
- meeting asistenta (persistentní session po dobu meetingu)
- přípravu zadání pro coding pipeline (plán, dekompozice)
- audit / review rozsáhlého kontextu

## 2. Dva režimy

### 2.1 Ad-hoc task companion

- Fire-and-forget K8s Job per těžký úkol
- Workspace: `brief.md` + prefetch KB + `attachments/` (obrázky čte přímo)
- Job skončí po dodání výsledku → orchestrátor pushne uživateli / uloží do KB
- Latence: nevadí (long-running analýza)

### 2.2 Assistant session (persistent)

- **1 Job na asistent-session** (meeting nebo delší chat), žije po dobu session
- **Bootstrap brief** na začátku: projekt, klient, role, kontext meetingu/agenda, účastníci, stručná historie chatu
- **Continuous input**: orchestrátor appenduje eventy do `inbox/events.jsonl` (SSE scrape chunks, user prompty, system hints)
- **Continuous output**: Claude zapisuje odpovědi do `outbox/events.jsonl` → orchestrátor streamuje uživateli
- **Shutdown**: `.jervis/END` marker nebo hard timeout 8h safety

## 3. Workspace layout (PVC)

```
/opt/jervis/data/<session>/
  .jervis/
    brief.md              # role, cíl, očekávaný výstup, omezení
    context.json          # KB prefetch, memory snapshot, task graph refs
    inbox/events.jsonl    # orchestrátor appenduje eventy (session mode)
    outbox/events.jsonl   # Claude appenduje odpovědi
    attachments/          # obrázky, PDF, transkripty, logy
    result.json           # ad-hoc final result
    END                   # marker pro shutdown session
  .claude/mcp.json        # MCP config
  CLAUDE.md               # system prompt / role
```

## 4. Protokol eventů (session)

**Inbox event** (JSON řádek):
```json
{"ts":"2026-04-15T12:00:00Z","type":"user|meeting|system","content":"…","meta":{…}}
```

**Outbox event**:
```json
{"ts":"2026-04-15T12:00:02Z","type":"answer|suggestion|note","content":"…","final":true}
```

- `type=user` — přímý prompt od uživatele přes chat
- `type=meeting` — SSE scrape chunk (speaker + text)
- `type=system` — orchestrátor hint (např. "nová osoba připojena")
- `type=answer` — odpověď směrovaná uživateli (jde do asistent bubliny)
- `type=suggestion` — proaktivní návrh (UI ukáže jako hint card)
- `type=note` — interní poznámka (uloží se, uživateli se nepushuje)

## 5. MCP tools (přes jervis-mcp HTTP)

Companion má přístup k: `kb_search`, `kb_store`, `kb_traverse`, `web_search`, `o365_*`, `get_task`, `ask_jervis`, `mongo_query`, `kb_document_upload`, `get_meeting_transcript`. Agent si sám rozhoduje, co volat.

## 6. Output format

**Volný text** (ne JSON) — výstup je surový markdown/plain text.

**Jazyk**: určuje orchestrátor v `brief.md`:
- asistent uživateli → CS
- klientské materiály, matematika, analytika → EN (volba agenta dle briefu)

## 7. Budget control

`settings` (app/config.py):
- `companion_max_concurrent_sessions` = 3
- `companion_max_adhoc_per_hour` = 10 (soft cap)
- `companion_daily_token_budget` = cca dle MAX planu
- `companion_soft_warning_pct` = 70 / 90 / 100 (push do chatu)

Budget tracker: MongoDB `orchestrator_companion_usage` (counter per day). Orchestrátor před dispatchem zvalidate limity; při překročení → push warning, ale **NEblokuje** — user rozhodne.

Pre-filtr (kdy vůbec volat companion):
- `complexity ≥ COMPLEX` nebo
- explicit `/deep` prefix nebo
- meeting assistant (vždy) nebo
- uživatel volá přes tool `dispatch_claude_companion`

## 8. K8s Job

**Image**: `jervis-claude:latest` (reuse, ne nový)
**Mode override**: env `COMPANION_MODE=session|adhoc`
**Entrypoint**: `/opt/jervis/entrypoint-companion.sh` (nový)
**Runner**: `companion_sdk_runner.py` (nový, používá `claude-agent-sdk` s `ClaudeSDKClient` pro streaming input)
**Labels**: `app=jervis-companion`, `companion-mode={session|adhoc}`, `session-id=<id>`
**Resources**: requests 256Mi/250m, limits 2Gi/2000m
**Timeout**: session 8h, adhoc 30m

## 9. Orchestrator integrace

### 9.1 Dispatch (ad-hoc)
`CompanionRunner.dispatch_adhoc(task_id, brief, attachments, client_id, project_id)` → vytvoří K8s Job, vrátí `{job_name, workspace_path}`. Non-blocking. Watcher čte `result.json`.

### 9.2 Session lifecycle
- `start_session(session_id, brief, context)` → vytvoří Job, čeká na `READY` marker
- `send_event(session_id, event)` → append do `inbox/events.jsonl`
- `stream_outbox(session_id)` → async generator z `outbox/events.jsonl` (tail -f)
- `stop_session(session_id)` → vytvoří `END` marker + čeká na graceful shutdown + delete Job

### 9.3 Plan node integrace
Nový `StepType.COMPANION` nebo rozhodnutí v `plan` node: když task vyžaduje deep analysis → step dispatch Companion místo RESPOND. Výstup (volný text) → přímo do `final_result`.

### 9.4 Chat integrace
- Běžný chat: orchestrátor může vytvořit **chat-scoped session** při prvním `/deep` nebo když detekuje complex follow-up. Session se udržuje po dobu aktivního chatu (TTL 30 min inactivity).
- Meeting: companion session startuje při prvním zapnutí asistenta v meetingu, běží do konce meetingu.

## 10. Budget & fallback

- MAX plan primárně (OAuth token)
- API key fallback při překročení (dokup výjimečně)
- Žádný fallback na Ollama pro companion úkoly (orchestrátor pre-filtruje — pokud není vhodné, nevolá companion)

## 11. Zítřejší milestone (testování + autonomní coding)

Po základním testu companion:
- **Asistent**: meeting session mode
- **Autonomní coding pipeline**: orchestrátor + companion vyrobí **celkový plán** (např. BMS feature), pak tento plán postupně servíruje coding agent Jobs (stávající `jervis-claude` v coding módu) s jasným zadáním per krok. Companion drží "meta" pohled, coding agent implementuje.

## 12. Co companion NEdělá

- Git operace (ty jsou výhradně coding agent pipeline s `ALLOW_GIT=true`)
- Modifikace repo kódu (read-only na workspace, zápis jen do `outbox/` a přes `kb_store`)
- Rozhodnutí o routingu (to je vždy orchestrátor)
- Klasická chat odpověď, co zvládne OpenRouter/Ollama

## 12b. Fail-fast & anti-polling pravidla (2026-04-16)

Companion se řídí projektovým pravidlem **fail-fast, žádné hard timeouty, push/stream místo pollu**:

- **Adhoc**: orchestrátor **NEpolluje** `result.json`. `CompanionRunner.wait_for_result()` blokuje přes K8s `watch.Watch()` na `metadata.name=<job>` a vrací hned po změně stavu. Jediný cap je `active_deadline_seconds` na samotném Jobu (K8s-side). Při `failed` / prázdném `summary` → `RuntimeError` — **žádný fallback** na inline LLM (jinak by se maskovaly auth/budget problémy a odpověď by byla označená jako deep, ale tichem by byla lokální).
- **Session**: žádné čekání na READY marker na straně orchestrátoru. Job se vytvoří a konzument ihned otevírá SSE `GET /companion/session/{id}/stream`. Runner po inicializaci SDK emituje `type=note, content="Session ready."` jako první outbox event — konzument to vidí in-band.
- **Stop**: `POST /companion/session/{id}/stop` zapíše `END` marker + pošle `delete_namespaced_job`. Žádné orchestrátor-side sleep; K8s doručí SIGTERM a runner graceful-exituje přes signal handler.
- **Session runner**: při SDK chybě (rate limit, auth, infra) **žádný retry** — zapíše error note do outboxu a propaguje; Job padne, K8s status to zvenku signalizuje. Předchozí tichá "error continue" politika byla odstraněna.

### Stale-event TTL (asistent do ucha)

Asistent je hlasový kanál — stará odpověď je horší než žádná. Outbox stream proto **zahazuje staré eventy**:

- `settings.companion_assistant_event_ttl_seconds = 45` (default)
- `GET /companion/session/{id}/stream?max_age_seconds=<N>` — override per-request (0 = vypnout TTL, užitečné jen pro debug/log consumery)
- Pro UI chat (text, uživatel si může odscrollovat) poslat `max_age_seconds=0`
- Runner v Jobu symetricky zahazuje staré **inbox** eventy (`COMPANION_INBOX_MAX_AGE_SECONDS=30`) — hlas/SSE chunks mají smysl jen dokud jsou čerstvé

## 13. Dopojení do orchestrátoru (2026-04-15 / upraveno 2026-04-16)

### Execute node — auto-dispatch na COMPLEX RESPOND
`app/graph/nodes/execute.py::_execute_respond_step` volá helper `_should_use_companion(state, task, step)`. Trigger:
- `complexity ∈ {complex, critical}`, nebo
- prompt obsahuje `/deep`, nebo
- `state["companion_attachments"]` není prázdné

Při triggeru → `_run_companion_adhoc(...)` staví brief (goal + project_context + KB evidence + očekávaný výstup), dispatchne adhoc Job a blokuje přes `companion_runner.wait_for_result()` (K8s watch, žádný poll). `summary` → `final_result`. **Žádný fallback** — při chybě step propaguje výjimku (fail-fast).

### HTTP endpointy (FastAPI)
Registrováno v `app/main.py` přes `app/companion/routes.py`:
- `POST /companion/adhoc` — dispatch adhoc
- `GET  /companion/adhoc/{task_id}?workspace_path=…` — poll result
- `POST /companion/session` — start persistent session (vrátí `session_id`, `workspace_path`)
- `POST /companion/session/{id}/event` — append do inboxu (`type=user|meeting|system`)
- `GET  /companion/session/{id}/stream` — SSE outbox stream
- `POST /companion/session/{id}/stop` — END marker + Job delete, bez sleepu
- `GET  /companion/session/{id}/stream?max_age_seconds=<N>` — stale-event TTL (default ze `companion_assistant_event_ttl_seconds=45`, 0 = vypnout)

### Pipeline pro Kotlin server
- Běžný chat `/deep`: server zašle `POST /companion/adhoc` → polluje `adhoc/{task_id}` → pushne `summary` jako assistant bubbu.
- Meeting asistent zapnut: server zašle `POST /companion/session` s meeting briefem, otevře SSE `GET /companion/session/{id}/stream` → outbox eventy rovnou do chatu. SSE scrape z meetingu → `POST /companion/session/{id}/event` s `type=meeting`.
- Uživatelské prompty během meetingu → `POST /companion/session/{id}/event` s `type=user`.
- Ukončení meetingu → `POST /companion/session/{id}/stop`.

## 13b. Kotlin server integrace (2026-04-16)

**Nové soubory v `backend/server/src/main/kotlin/com/jervis/meeting/`:**
- `OrchestratorCompanionClient.kt` — Ktor klient proti `/companion/*` (startSession, sendEvent, stopSession, streamOutbox Flow)
- `MeetingCompanionAssistant.kt` — session lifecycle per meetingId, outbox consumer → `HelperMessageDto(SUGGESTION)` → `MeetingHelperService.pushMessage` → RPC event stream

**Hooks:**
- `MeetingHelperService.startHelper/stopHelper` — lifecycle companion session (ObjectProvider kvůli circular dep)
- `MeetingLiveUrgencyProbe.probeOne` — každý transcribnutý segment paralelně jde do `urgencyDetector` (regex) i do `companionAssistant.forwardSegment` (Claude)

**Lifecycle koncový uživatel:**
1. iOS/Desktop MeetingViewModel: start recording s `helperDeviceId=<Desktop>`
2. Kotlin `MeetingHelperService.startHelper` → orchestrator companion session → outbox consumer
3. Whisper live probe (~45s tail) → segmenty → Claude
4. Claude emituje `suggestion|answer` → HelperMessageDto → Desktop/iOS přes existující RPC event stream (žádné UI změny)
5. Stop recording → `stopHelper` → END marker + Job delete

**Fallback tail:** `scripts/companion_tail.sh <session-id>` pro debug/meeting fallback (tail SSE přímo přes kubectl exec).

## 14. Odkazy

- Infrastruktura: `app/agents/job_runner.py`, `app/agents/workspace_manager.py`, `app/agents/companion_runner.py`
- Runner: `backend/service-claude/companion_sdk_runner.py`
- Entrypoint: `backend/shared-entrypoints/entrypoint-companion.sh`
- K8s: reuse `jervis-claude` image, nový label set
