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
- [ ] Přečíst `router_core.py` — `decide_route()` a jak mapuje `capability=vision` na model
- [ ] Přečíst `models.py` — `LOCAL_MODEL_CAPABILITIES` mapování
- [ ] Opravit `decide_route()` aby `capability=vision` → `qwen3-vl-tool:latest` na p40-2
- [ ] Přečíst `proxy.py` a `request_queue.py` — zajistit, že router NIKDY nevrací 503, vždy requeue
- [ ] Router je jediný gateway na GPU, drží frontu, ví co kde běží, žádné timeouty — vždy se čeká na odpověď
- [ ] Otestovat přes `/route-decision` endpoint
- [ ] Deploy: `k8s/build_ollama_router.sh`
- [ ] Commit

**Poznámky:**


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
