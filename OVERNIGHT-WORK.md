# Noční práce 2026-04-13 → 2026-04-14

**Schváleno uživatelem:** ano
**Pořadí:** 1 → 2 → 3 → 4 → 5 → 6

---

## 1. Fix "Hotovo" — task se neoznačí jako DONE na serveru
- [x] Trace celý flow: `ChatViewModel.markActiveTaskDone()` → RPC `pendingTaskService.markDone()` → `PendingTaskService.markDone()` → MongoDB save
- [x] Ověřit přes `mongo_query` jestli se stav reálně mění po volání — **ANO, funguje** (bazén task DONE v DB)
- [x] Najít root cause — **markDone na serveru funguje, root cause je v UI:**
  - `switchToMainChat()` místo navigace na další task
  - Sidebar se správně přenačte (query filtruje active states), ale SSE TASK_LIST_CHANGED může přijít dřív než DB commit → stale data se na moment zobrazí
- [x] Opravit root cause:
  - `markActiveTaskDone()` → `findNextActiveTask()` + `switchToTaskConversation(nextTask)` místo `switchToMainChat()`
  - `sidebarRemovedTaskIds: StateFlow<Set<String>>` — synchronní filtr v sidebaru (ne async Flow)
  - Sidebar `visibleTasks` filtruje `removedTaskIds` v `remember(tasks, removedTaskIds)`
- [x] Uklidit mrtvý kód — odstraněn SharedFlow, recentlyRemovedIds, async collector
- [x] Kompilace ověřena — `:apps:desktop:classes` BUILD SUCCESSFUL, žádné errory
- [ ] Po opravě ověřit na desktopu (ráno s uživatelem)
- [ ] Commit

**Poznámky:**
- `PendingTaskService.markDone()` správně mění state na DONE, vymazává pendingUserQuestion, emituje TASK_LIST_CHANGED
- Sidebar `loadActive()` query: USER_TASK, PROCESSING, QUEUED, BLOCKED, INDEXING, NEW, ERROR — DONE vyloučeno
- `sidebarRemovedTaskIds` zajišťuje, že i při stale server response task neprokmitne zpět


---

## 2. Fix ollama-router — vision routing + 503
- [x] Přečíst `router_core.py` — `decide_route()` řádek 157-253, `_find_local_model_for_capability()` řádek 352-378
- [x] Přečíst `models.py` — `LOCAL_MODEL_CAPABILITIES` řádek 69-74
- [x] Opravit vision routing — **root cause: capability name mismatch** `"vision"` vs `"visual"`
  - `vlm_client.py` posílá `capability="vision"`, `models.py` měl pouze `"visual"`
  - `_find_local_model_for_capability("vision")` → None → fallback `orchestrator_model` (30b)
  - Fix: přidáno `"vision"` do capabilities pro `qwen3-vl-tool:latest`
- [x] Přečíst `proxy.py` a `request_queue.py` — GPU error handling
  - `proxy.py:170-190` — vrací 503/error přímo klientovi, NO RETRY
  - `request_queue.py:394-418` — vrací 503 pro gpu_unavailable a model_load_failed
- [x] Opravit 503 — `_run_on_backend` nyní retryuje: 5s, 15s, 30s, 60s backoff
  - model_load_failed → mark unhealthy, start recovery, retry
  - gpu_unavailable → retry, pak fallback na jiný healthy GPU
  - connect_error → GPU marked unhealthy (existing), nyní retry
- [x] Deploy: `k8s/build_ollama_router.sh` — úspěšně
- [x] Otestovat přes `/route-decision`:
  - `capability="vision"` → `{"target":"local","model":"qwen3-vl-tool:latest"}` ✓
  - `capability="visual"` → `{"target":"local","model":"qwen3-vl-tool:latest"}` ✓ (backward compat)
- [x] Commit: `79368d71`

**Poznámky:**
- `decide_route()` řádek 192: `local_model or settings.orchestrator_model` — None fallback na 30b byl root cause
- Router docs (`structures.md:29`): "Router ALWAYS accepts requests. Never returns 503/reject." — teď splněno s retry


---

## 3. Zjednodušení menu
- [ ] Smazat Screen enum hodnoty: UserTasks, Finance, Capacity, PendingTasks, IndexingQueue, ErrorLogs, RagSearch, EnvironmentManager, EnvironmentViewer
- [ ] Smazat odpovídající composables a soubory
- [ ] Ponechat: Meetings, Kalendář, Nastavení — ikony v top baru
- [ ] Ponechat environment pravý sidebar = live náhled do K8s namespace (pody, ingressy, configmapy)
- [ ] Settings: environment = přiřazení namespace → projekt
- [ ] Smazat drawer menu
- [ ] Smazat mrtvé soubory — žádný mrtvý kód nikde
- [ ] Update `docs/ui-design.md`
- [ ] Commit

**Poznámky:**


---

## 4. Kalendář
- [ ] Přečíst existující `CalendarScreen.kt`, backend `O365CalendarPoller`, `CalendarEventIndexDocument`
- [ ] Implementovat kalendářový grid (den/týden view)
- [ ] Zdroje dat: O365 kalendářní eventy, tasky s termínem (`scheduledAt`), tasky bez termínu = dnes
- [ ] Co se nestihlo → přesouvá na další den
- [ ] Nic nezapadne — vše má místo v kalendáři
- [ ] Kompletní implementace
- [ ] Update `docs/ui-design.md`
- [ ] Commit

**Poznámky:**


---

## 5. Teams scraping přes MCAS
- [ ] Přečíst existující browser pool kód: `tab_manager.py`, `screen_scraper.py`, `routes/scrape.py`
- [ ] Přečíst `vlm_client.py` — volá router přes `_get_route_decision(capability="vision")`
- [ ] Zjistit DOM strukturu Teams chatů na `.mcas.ms` doméně — Playwright selectory:
  - [ ] Navigace na Teams chat list
  - [ ] Extrakce seznamu chatů (jméno, poslední zpráva, timestamp)
  - [ ] Kliknutí na chat → extrakce zpráv (odesílatel, text, čas)
  - [ ] Detekce nových zpráv od posledního scrape
- [ ] Pokud DOM selektory fungují → implementovat DOM scraping (rychlejší, spolehlivější, bez GPU)
- [ ] Pokud DOM nefunguje (Shadow DOM, MCAS obfuskace) → VLM screenshot přes router (bod 2 musí být hotový)
- [ ] Napojit na Kotlin server:
  - [ ] Vytvořit `/internal/o365-scrape` endpoint v `InternalO365SessionRouting.kt`
  - [ ] Browser pool POST-ne scrape výsledky na server (push-based, ne polling)
  - [ ] Server uloží jako TaskDocument se `sourceUrn=teams://{clientId}/{chatId}`
- [ ] Kód připravit v repo, restart podů ráno
- [ ] Commit

**Poznámky:**


---

## 6. Sidebar šířka persistence
- [ ] `chatSidebarSplitFraction` uložit lokálně na zařízení (platform-specific storage)
- [ ] Sync na server přes preferences API (pro reinstalaci)
- [ ] Sidebar schovávací na ikonu — toggle tlačítko
- [ ] Commit

**Poznámky:**


---

## Pravidla (z CLAUDE.md + memory)
- KB first PŘED každou prací
- Docs first — přečíst relevantní docs/ před změnou
- NIKDY quick-fix — vždy celkový design
- NIKDY spouštět desktop app
- NIKDY restartovat pody (MFA ban risk) — restart ráno
- NIKDY mrtvý kód — uklízet pořádně
- Router = jediný gateway na GPU, žádné timeouty, vždy čekat
- Environment = obálka pro K8s namespace, zdroj pravdy = K8s, coding agent dostane namespace
- Po každé změně kódu aktualizovat relevantní docs/
- Commit a push přímo na master, NIKDY Co-Authored-By
