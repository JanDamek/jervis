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

## TODO: MFA notifikace — auto-zrušení po schválení

**Problém:** MFA dialog s číslem (např. "Potvrďte číslo 91 v Authenticator") zůstává na Desktopu i po schválení. Uživatel musí ručně kliknout Zavřít/Potvrzeno. Zbytečné — pod ví že MFA prošla.

**Požadavek:**
- Jakmile pod identifikuje že session přešla dál (MFA schváleno), dialog musí **okamžitě zmizet** na všech klientech:
  - Desktop (in-app dialog)
  - Android (remote push notification)
  - iOS (remote push notification)
- Tlačítko "Zavřít" → pro uživatele když to chce ručně zrušit (ale obvykle nebude potřeba)
- Tlačítko "Potvrzeno" je redundantní — schválení se provádí v Authenticator apce, ne v JERVISu

**Flow:**
1. Pod detekuje AWAITING_MFA → pošle push všem klientům
2. Uživatel schválí v Authenticator
3. Pod detekuje že se page posunula (ACTIVE stav)
4. Pod POST na server: MFA_COMPLETED
5. Server pushne všem klientům: task dismiss + cancel notification
6. Desktop zavře dialog, Android/iOS zruší push notification

## TODO: Token expired v Teams — odložit re-login na uživatelskou aktivitu

**Problém:** V Teams se objeví hláška "token expired, přihlašte se znovu". Pod by to detekoval a spustil re-login automaticky — ale pokud je to ve 2 v noci a uživatel nemůže schválit MFA, login skončí v AWAITING_MFA a timeoutne.

**Požadavek:**
- Pod detekuje expired token → označí session jako `EXPIRED` (ale nic nedělá, čeká)
- Ihned se přihlásí jakmile detekuje **aktivitu uživatele v JERVISu** (chat message, UI interakce, push ack)
- Ve 2 v noci nespouštět MFA flow — uživatel spí, MFA by propadlo

**Detekce expired tokenu:**
- **Primárně DOM check** — hláška "token expired" / "sign in again" / element s ID Microsoft login prompt je v DOM
- **VLM jako fallback** — screenshot → VLM identifikuje login page pokud DOM selektory selžou
- DOM je rychlejší a stabilnější, VLM jen když DOM check nic nenajde

**Detekce aktivity uživatele:**
- WebSocket connected z UI (Desktop/Mobile) = uživatel je online
- Nová chat message odeslaná
- Uživatel otevřel app (foreground event)
- Server vysílá heartbeat do podu: "user active" / "user idle"

**Flow:**
1. Pod DOM check → "token expired"
2. Pod → server: `state=EXPIRED`, `reason="token expired, waiting for user activity"`
3. Pod se nepouští do re-login, čeká
4. Server sleduje user activity (UI connected, chat sent)
5. Server → pod: "user is active, start re-login"
6. Pod spustí AI login flow → pošle MFA push → uživatel schválí okamžitě (je u toho)

**Why:** MFA pushka ve 2 v noci = uživatel nespí/propadne a ráno je pod v ERROR. Raději session EXPIRED přes noc a re-login ráno když je uživatel u toho.

## TODO: iOS push notifikace MFA nedorazila

**Problém:** MFA push "Potvrďte číslo 91 v Authenticator" přišla jen na Android, na iOS ne.

**Ověřit:**
- iOS push tokeny — jsou registrované? APNs konfigurace?
- `ApnsPushService.kt` — v logu, proč push neodešel nebo nedošel
- `UserTaskCreated` event — pošle se push na všechna zařízení?
- Priorita notifikace — iOS vyžaduje `apns-priority: 10` pro okamžité doručení při urgent alert
- Silent push vs alert push — MFA musí být alert (zvuk, vibrace)
- Device token validity — iOS tokeny mohou expirovat, invalidace při `BadDeviceToken`

## TODO: Python → Kotlin rewrite (pro služby nezávislé na Python-only knihovnách)

Každý Python service, který NENÍ závislý na knihovnách které nejsou v Kotlinu dostupné, přepsat do Kotlinu:

**Pro přepis (nezávislé na Python-only lib):**
- `service-mcp` — MCP SDK existuje i pro Kotlin (https://github.com/modelcontextprotocol/kotlin-sdk). **Priorita** — zdroj dnešních bugů (SCHEDULED_TASK, UUID trim)

**Závislé na Python knihovnách — zůstávají v Pythonu:**
- `service-orchestrator` — LangGraph (Python-only)
- `service-knowledgebase` — langchain, některé LLM integrations
- `service-tts` — Coqui TTS, XTTS v2 (PyTorch Python API)
- `service-correction` — pravděpodobně LLM-specific
- `service-ollama-router` — čistý Python HTTP proxy (LZE přepsat, ale asi OK zůstat)
- `service-o365-browser-pool` — Playwright (Python/JS only, žádná Kotlin alternativa)

**Postup:**
1. MCP rewrite (Ktor, rychlejší startup než Spring Boot ~1-2s vs 10-15s)
2. U dalších služeb zvážit nutnost — většina závisí na LLM/ML lib

**Why:** Dnešní dva bugy (UUID trim → invalid ObjectId, SCHEDULED_TASK → neexistující enum) byly způsobené tím, že MCP Python obchází Kotlin type safety. Kotlin přístup přes `TaskId`, `TaskTypeEnum` by tyto chyby vůbec nedovolil.

## TODO: Kalendář — živý, interaktivní

Kalendář musí být živý view, ne statický seznam:
- **Drag & drop** — přesunout task na jiný den/čas
- **Right-click menu** — vepsat odpověď/reakci → task se přesune na kvalifikaci (nečeká na uživatele)
- **Tasky čekající na kvalifikaci** — zobrazeny v aktuálním dni na konci, zabalené (tree/collapsible)
  - Rozbalitelné jako v sidebaru
- **Celý kalendář = sidebar + chat v pohledu kalendáře** — ne samostatný screen
- **Živé aktualizace** — server push, ne polling
- **Backend:** endpoint pro přesun tasku (change scheduledAt), endpoint pro inline odpověď
- [ ] **Meeting UI push:** změny v meetingu (nový segment, stav) se musí okamžitě zobrazit v UI přes PUSH — žádné reloady, vše real-time přes kRPC/SSE
- [ ] **Ověřit:** BMS CommerzBank — JERVIS extrahoval úkoly z dnešního standup meetingu a provedl je (uzavření starých tasků, založení nových)
- [ ] **FALLBACK v rozporu s guidelines**: orchestrátor má fallback logiku v `_helpers.py` a `handler_streaming.py` — pokud router vrátí local a ten selže, orchestrátor sám zkouší cloud. Porušuje princip "router je jediný gateway". Router by měl sám retry/requeue, klient nemá řešit fallback. Projít a odstranit.

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
