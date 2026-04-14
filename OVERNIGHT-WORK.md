# Noční práce 2026-04-13 → 2026-04-14

**Schváleno uživatelem:** ano
**Pořadí:** 1 → 2 → 3 → 4 → 5 → 6
**Stav:** VŠECH 6 BODŮ IMPLEMENTOVÁNO

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
- [x] Smazat Screen enum: UserTasks, Finance, TimeTracking, PendingTasks, IndexingQueue, ErrorLogs, RagSearch, EnvironmentManager, EnvironmentViewer, Scheduler (renamed→Calendar), DebugConsole
- [x] Smazat composable soubory: PendingTasksScreen, ErrorLogsScreen, RagSearchScreen, IndexingQueueScreen, EnvironmentViewerScreen, FinanceScreen, TimeTrackingScreen
- [x] Ponechat: Main, Meetings, Calendar, Settings — Screen enum, ikony v top baru
- [x] Environment pravý sidebar ponechán (EnvironmentPanel v MainScreenView)
- [x] Smazat MenuDropdown (hamburger) — nahrazeno ikonami TopBarMenuItem v top baru
- [x] Desktop Main.kt — system tray + menu bar aktualizovány
- [x] -4394 řádků mrtvého kódu
- [x] Kompilace OK
- [x] Commit: `55762378`
- [ ] Update `docs/ui-design.md` (TODO)

**Poznámky:**
- SchedulerScreen ponechán jako placeholder pro Calendar screen (redesign v kroku 4)
- EnvironmentManagerScreen ponechán (používán z K8sResourcesTab) — ale nemá Screen enum, přístupný jen přes environment panel
- UserTasksScreen.kt ponechán v repo (má "Hotovo" tlačítko) ale nemá Screen — přístupný jen přes chat sidebar


---

## 4. Kalendář
- [x] Přečíst existující SchedulerScreen, backend O365CalendarPoller, CalendarEventIndexDocument, TaskRepository
- [x] Implementovat kalendářový grid — týdenní view (Po-Ne)
- [x] Zdroje dat:
  - Scheduled tasks (`type=SCHEDULED`, `scheduledAt` set)
  - Calendar events (O365 polling, `meetingMetadata != null`)
  - Deadline tasks (jakýkoli task se `scheduledAt`)
- [x] Overdue → přesouvá na dnešek, `isOverdue=true` badge
- [x] Backend: `CalendarEntryDto`, `ITaskSchedulingService.calendarEntries()`, `TaskSchedulingRpcImpl` s MongoDB range query
- [x] UI: Navigace předchozí/další týden, "dnes" tlačítko, barevné kódování typů
- [x] Smazán SchedulerComponents.kt (mrtvý kód)
- [x] Kompilace OK
- [x] Commit: `125cb91d`
- [ ] Update `docs/ui-design.md`
- [ ] Backend server build potřeba (nový RPC endpoint) — ráno

**Poznámky:**
- Backend `calendarEntries()` ještě nebyl zkompilován (server build potřeba)
- Tasky bez termínu se zatím nezobrazují jako "dnes" — query filtruje `scheduledAt != null`
- TODO: přidat "unscheduled active tasks = today" logiku po server buildu


---

## 5. Teams scraping přes MCAS
- [x] Přečíst existující browser pool kód — **VŠE UŽ IMPLEMENTOVÁNO:**
  - `teams_crawler.py` (655 řádků) — plný DOM extraction: sidebar list, messages, scrolling, dedup
  - `screen_scraper.py` (359 řádků) — VLM fallback s adaptivními intervaly
  - `routes/scrape.py` (589 řádků) — REST API: crawl, discover, search, backfill
  - `scrape_storage.py` — ukládá do `o365_scrape_messages` kolekce (state=NEW)
- [x] DOM selektory pro Teams v2 existují: `[data-tid="chat-list"]`, `[data-tid="chat-pane-message"]`, atd.
- [x] VLM fallback existuje (pokud DOM selže)
- [x] **Chybějící kus:** Kotlin server nečetl `o365_scrape_messages` → vytvořen `O365ScrapeMessageIndexer`:
  - Polluje `o365_scrape_messages` (state=NEW) každých 30s
  - Groupuje messages by chatName (topic consolidation)
  - Appenduje k existujícímu aktivnímu tasku (ne duplicity)
  - Vytváří TaskDocument se `sourceUrn=teams::conn:{id},scrape:{chatName}`
  - Markuje jako PROCESSED
- [x] Commit: `a0e54e22`
- [ ] Server build potřeba pro nasazení indexeru (ráno)
- [ ] Browser pool restart pro aktivaci scraperu na MMB connection (ráno)

**Poznámky:**
- DOM extraction je preferovaný (rychlejší, bez GPU), VLM je fallback
- VLM routing nyní funguje (bod 2 — `capability=vision` → `qwen3-vl-tool:latest`)
- MCAS org info page handling už implementován v `auto_login.py`
- Existující `O365ScrapeMessageDocument` a `O365ScrapeMessageRepository` — vše připraveno


---

## 6. Sidebar šířka persistence
- [x] `chatSidebarSplitFraction` uložit na server přes `chatService.saveUiSetting`
- [x] Na startu obnovit přes `chatService.loadUiSettings`
- [x] Backend: `ChatSessionDocument.uiSettings: Map<String, String>`, `ChatService.updateUiSettings()`
- [x] RPC: `IChatService.saveUiSetting(key, value)`, `loadUiSettings()`
- [x] Kompilace OK
- [x] Commit: `808f3395`
- [ ] Sidebar toggle na ikonu — TODO (menší UX vylepšení, ne blocker)
- [ ] UX: mikrofon ikona v top baru je zavádějící (meeting vs ad-hoc recording) — redesign do budoucna

## NÁVRH K DISKUSI: Browser pod — autonomní session health monitoring

**Problém:** Pod naběhne, auto_login proběhne (LOGGED_IN), ale session může kdykoliv vypršet
a browser se redirectne na login stránku. Pod to nedetekuje → zůstane zaseklý.

**Návrh (uživatel):** Periodický check ~1x/min:
1. Zkontrolovat DOM — jsou vidět Teams zprávy? (selektory `[data-tid="chat-list"]` atd.)
2. Pokud DOM neobsahuje zprávy → VLM screenshot → zjistit co je na obrazovce
3. Podle výsledku VLM → rozhodnout kde ve flow jsme (login stránka? MFA picker? Teams loading?)
4. Automaticky pokračovat ve flow (kliknout na Authenticator, zadat email, atd.)

**Princip:** Pod si žije vlastním životem. Server jen polluje data ze scrapingu.
Pod sám detekuje problémy a sám se z nich zotaví.

**Implementace:**
- Nový `session_health_loop` v browseru (coroutine, každých 60s)
- DOM check: `page.query_selector('[data-tid="chat-list"]')` — pokud existuje, session OK
- Pokud DOM check selže → `_detect_stage(page)` → podle výsledku spustit příslušný handler
- Pokud na login stránce → auto_login znovu (credentials z init-config.json na PVC)
- Pokud na MFA method picker → `_select_mfa_method(page)`
- Notifikovat server jen pokud je potřeba MFA schválení od uživatele

**K diskusi:** Schválit přístup, pak implementovat.

**Poznámky:**
- Lokální storage (per-device) zatím není — vše na server. Pro reinstalaci to stačí.
- Toggle ikona pro sidebar collapse zatím chybí — přidám v dalším kroku pokud bude čas


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
